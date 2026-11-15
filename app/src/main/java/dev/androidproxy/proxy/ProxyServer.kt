package dev.androidproxy.proxy

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** proxy 설정. 포트 하나로 HTTP 와 SOCKS5 를 함께 받는다. */
data class ProxyConfig(
    val port: Int = DEFAULT_PORT,
    val httpEnabled: Boolean = true,
    val socksEnabled: Boolean = true,
    /** null 이면 모든 interface 에서 받는다. */
    val bindAddress: InetAddress? = null,
    val connectTimeoutMs: Int = 15_000,
    /** 첫 요청(HTTP 머리, SOCKS5 협상)을 기다리는 시간. */
    val handshakeTimeoutMs: Int = 30_000,
    /** 한쪽이 끝난 뒤 다른 쪽이 끝나기를 기다리는 시간. */
    val lingerMs: Long = 30_000,
    val maxConnections: Int = 1024,
    val clientFilter: (InetAddress) -> Boolean = ClientFilter::isAllowed,
) {
    companion object {
        const val DEFAULT_PORT = 8080
    }
}

/**
 * HTTP proxy(절대 URI 요청과 CONNECT)와 SOCKS5 proxy(인증 없음, CONNECT)를 한 포트에서 돌린다.
 * 첫 byte 가 `0x05` 면 SOCKS5, 아니면 HTTP 로 본다.
 * Android 에 기대지 않는 순수 JVM code 라 단위 테스트로 확인한다.
 */
class ProxyServer(
    val config: ProxyConfig,
    val counter: TrafficCounter = TrafficCounter(),
) {
    private val threadIds = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool(
        ThreadFactory { r -> Thread(r, "proxy-${threadIds.incrementAndGet()}").apply { isDaemon = true } },
    )
    private val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val activeClients = AtomicInteger()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 실제로 받는 포트. 설정 포트가 0 이면 시스템이 고른 포트다. */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    /** 지금 열린 client 연결 수. */
    val connectionCount: Int get() = activeClients.get()

    @Throws(IOException::class)
    fun start() {
        check(serverSocket == null) { "already started" }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(config.bindAddress, config.port), 128)
        serverSocket = server
        isRunning = true
        executor.execute { acceptLoop(server) }
    }

    fun stop() {
        isRunning = false
        closeQuietly(serverSocket)
        sockets.forEach { closeQuietly(it) }
        sockets.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (isRunning) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!isRunning) return
                continue
            }
            if (!config.clientFilter(client.inetAddress) || activeClients.get() >= config.maxConnections) {
                closeQuietly(client)
                continue
            }
            sockets += client
            activeClients.incrementAndGet()
            try {
                executor.execute {
                    try {
                        handleClient(client)
                    } finally {
                        activeClients.decrementAndGet()
                        release(client)
                    }
                }
            } catch (e: Exception) {
                activeClients.decrementAndGet()
                release(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = config.handshakeTimeoutMs
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
            val output = client.getOutputStream()
            input.mark(1)
            val first = input.read()
            if (first < 0) return
            input.reset()
            if (first == SOCKS_VERSION) {
                if (config.socksEnabled) handleSocks(client, input, output)
            } else {
                if (config.httpEnabled) handleHttp(client, input, output)
            }
        } catch (_: Exception) {
            // 연결이 끊겼거나 시간이 지났거나 proxy 가 멈췄다. 그 연결만 닫는다.
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun handleHttp(client: Socket, input: InputStream, output: OutputStream) {
        val head = readHttpHead(input) ?: return
        val lines = head.split("\r\n", "\n").toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        val requestLine = lines.firstOrNull() ?: return
        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            writeHttpError(output, 400, "Bad Request")
            return
        }
        val (method, target, version) = parts

        if (method.equals("CONNECT", ignoreCase = true)) {
            val (host, port) = parseHostPort(target, 443) ?: run {
                writeHttpError(output, 400, "Bad Request")
                return
            }
            val remote = connectRemote(host, port) ?: run {
                writeHttpError(output, 502, "Bad Gateway")
                return
            }
            output.write("$version 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            relay(client, input, remote)
            return
        }

        if (!target.regionMatches(0, "http://", 0, 7, ignoreCase = true)) {
            // https:// 절대 URI 나 origin-form 은 proxy 가 다룰 수 없다.
            writeHttpError(output, 400, "Bad Request")
            return
        }
        val rest = target.substring(7)
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' }
        val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
        var path = if (pathStart < 0) "/" else rest.substring(pathStart)
        if (path.startsWith("?")) path = "/$path"
        val hostPart = authority.substringAfterLast('@')
        val (host, port) = parseHostPort(hostPart, 80) ?: run {
            writeHttpError(output, 400, "Bad Request")
            return
        }

        val out = StringBuilder()
        out.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
        var hasHost = false
        for (line in lines.drop(1)) {
            val name = line.substringBefore(':').trim().lowercase()
            if (name in HOP_BY_HOP_HEADERS) continue
            if (name == "host") hasHost = true
            out.append(line).append("\r\n")
        }
        if (!hasHost) out.append("Host: ").append(hostPart).append("\r\n")
        // 요청 하나를 보내고 끊는다. 다음 요청은 다른 서버로 갈 수 있어서다.
        out.append("Connection: close\r\n\r\n")

        val remote = connectRemote(host, port) ?: run {
            writeHttpError(output, 502, "Bad Gateway")
            return
        }
        try {
            val bytes = out.toString().toByteArray(Charsets.ISO_8859_1)
            remote.getOutputStream().write(bytes)
            counter.addTx(bytes.size)
        } catch (e: IOException) {
            release(remote)
            throw e
        }
        relay(client, input, remote)
    }

    /** 빈 줄까지 읽어 머리를 낸다. 너무 길거나 빈 줄 전에 끝나면 null. */
    private fun readHttpHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var state = 0 // 지금까지 본 줄 끝 글자의 상태
        while (buffer.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            state = when {
                b == '\n'.code && (state == 1 || state == 3) -> state + 1 // \r\n 또는 \r\n\r\n
                b == '\n'.code && state == 2 -> 4 // \n\n 만 쓴 client
                b == '\n'.code -> 2
                b == '\r'.code && state == 2 -> 3
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) return buffer.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun writeHttpError(output: OutputStream, code: Int, reason: String) {
        try {
            output.write(
                "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1),
            )
            output.flush()
        } catch (_: IOException) {
        }
    }

    // ---------------------------------------------------------------- SOCKS5

    private fun handleSocks(client: Socket, input: InputStream, output: OutputStream) {
        val greeting = readFully(input, 2)
        if (greeting[0].toInt() != SOCKS_VERSION) return
        val methods = readFully(input, greeting[1].toInt() and 0xFF)
        if (methods.none { it.toInt() == 0x00 }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xFF.toByte()))
            return
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x00))

        val request = readFully(input, 4)
        if (request[0].toInt() != SOCKS_VERSION) return
        val command = request[1].toInt()
        val host: String = when (request[3].toInt()) {
            0x01 -> InetAddress.getByAddress(readFully(input, 4)).hostAddress!!
            0x03 -> String(readFully(input, input.read().also { if (it < 0) throw EOFException() }), Charsets.ISO_8859_1)
            0x04 -> InetAddress.getByAddress(readFully(input, 16)).hostAddress!!
            else -> {
                writeSocksReply(output, 0x08, null)
                return
            }
        }
        val portBytes = readFully(input, 2)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        if (command != 0x01) {
            writeSocksReply(output, 0x07, null)
            return
        }

        val remote = try {
            openSocket(host, port)
        } catch (e: IOException) {
            writeSocksReply(output, socksErrorCode(e), null)
            return
        }
        try {
            writeSocksReply(output, 0x00, remote)
        } catch (e: IOException) {
            release(remote)
            throw e
        }
        relay(client, input, remote)
    }

    private fun socksErrorCode(e: IOException): Int = when (e) {
        is UnknownHostException -> 0x04
        is NoRouteToHostException -> 0x04
        is ConnectException -> 0x05
        is SocketTimeoutException -> 0x06
        else -> 0x01
    }

    private fun writeSocksReply(output: OutputStream, code: Int, remote: Socket?) {
        val bound = remote?.localAddress
        val addressBytes = bound?.address ?: ByteArray(4)
        val type = if (bound == null || bound is Inet4Address) 0x01 else 0x04
        val port = remote?.localPort ?: 0
        val reply = ByteArrayOutputStream()
        reply.write(byteArrayOf(SOCKS_VERSION.toByte(), code.toByte(), 0x00, type.toByte()))
        reply.write(addressBytes)
        reply.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        output.write(reply.toByteArray())
        output.flush()
    }

    // ---------------------------------------------------------------- 공통

    private fun connectRemote(host: String, port: Int): Socket? = try {
        openSocket(host, port)
    } catch (_: IOException) {
        null
    }

    private fun openSocket(host: String, port: Int): Socket {
        val socket = Socket()
        sockets += socket
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port).also {
                if (it.isUnresolved) throw UnknownHostException(host)
            }, config.connectTimeoutMs)
            if (!isRunning) throw SocketException("proxy stopped")
            return socket
        } catch (e: IOException) {
            release(socket)
            throw e
        }
    }

    /**
     * 두 방향을 잇는다. client→remote 는 TX, remote→client 는 RX 로 센다.
     * 이 thread 는 remote→client 를, 다른 thread 는 client→remote 를 맡는다.
     */
    private fun relay(client: Socket, clientIn: InputStream, remote: Socket) {
        try {
            client.soTimeout = 0
            remote.soTimeout = 0
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()
            val clientOut = client.getOutputStream()
            val upstream = executor.submit {
                try {
                    pump(clientIn, remoteOut) { counter.addTx(it) }
                    try {
                        remote.shutdownOutput()
                    } catch (_: IOException) {
                    }
                } catch (_: IOException) {
                    // client 쪽이 끊겼다: 다른 방향도 끝낸다.
                    closeQuietly(remote)
                    closeQuietly(client)
                }
            }
            try {
                pump(remoteIn, clientOut) { counter.addRx(it) }
                try {
                    client.shutdownOutput()
                } catch (_: IOException) {
                }
                try {
                    upstream.get(config.lingerMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                } catch (_: Exception) {
                }
            } catch (_: IOException) {
            } finally {
                upstream.cancel(true)
            }
        } finally {
            release(remote)
            closeQuietly(client)
        }
    }

    private inline fun pump(input: InputStream, output: OutputStream, count: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            output.write(buffer, 0, n)
            count(n)
        }
    }

    private fun release(socket: Socket) {
        sockets -= socket
        closeQuietly(socket)
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val SOCKS_VERSION = 0x05
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private val HOP_BY_HOP_HEADERS = setOf("proxy-connection", "proxy-authorization", "connection", "keep-alive")

        private fun readFully(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(bytes, offset, length - offset)
                if (n < 0) throw EOFException()
                offset += n
            }
            return bytes
        }

        /** `host:port`, `[v6]:port`, `host` 를 나눈다. */
        internal fun parseHostPort(value: String, defaultPort: Int): Pair<String, Int>? {
            if (value.isEmpty()) return null
            val host: String
            val portText: String?
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end < 0) return null
                host = value.substring(1, end)
                portText = value.substring(end + 1).removePrefix(":").ifEmpty { null }
            } else if (value.count { it == ':' } == 1) {
                host = value.substringBefore(':')
                portText = value.substringAfter(':')
            } else {
                host = value
                portText = null
            }
            val port = portText?.toIntOrNull() ?: if (portText == null) defaultPort else return null
            if (host.isEmpty() || port !in 1..65535) return null
            return host to port
        }
    }
}
