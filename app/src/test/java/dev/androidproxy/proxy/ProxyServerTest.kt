package dev.androidproxy.proxy

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ProxyServerTest {
    private lateinit var origin: OriginServer
    private lateinit var echo: EchoServer
    private val proxies = mutableListOf<ProxyServer>()

    @Before
    fun setUp() {
        origin = OriginServer()
        echo = EchoServer()
    }

    @After
    fun tearDown() {
        proxies.forEach { it.stop() }
        origin.close()
        echo.close()
    }

    private fun startProxy(http: Boolean = true, socks: Boolean = true): ProxyServer {
        val proxy = ProxyServer(
            ProxyConfig(port = 0, httpEnabled = http, socksEnabled = socks, bindAddress = InetAddress.getLoopbackAddress()),
        )
        proxy.start()
        proxies += proxy
        return proxy
    }

    private fun connect(proxy: ProxyServer) = Socket("127.0.0.1", proxy.localPort).apply { soTimeout = 5_000 }

    @Test
    fun httpGetIsForwardedInOriginForm() {
        val proxy = startProxy()
        connect(proxy).use { s ->
            s.getOutputStream().write(
                ("GET http://127.0.0.1:${origin.port}/hello?x=1 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${origin.port}\r\n" +
                    "Proxy-Connection: keep-alive\r\n" +
                    "User-Agent: test\r\n\r\n").toByteArray(),
            )
            val response = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response, response.endsWith("hello from origin"))
        }
        val request = origin.requests.single()
        assertTrue(request, request.startsWith("GET /hello?x=1 HTTP/1.1\r\n"))
        assertFalse(request, request.contains("Proxy-Connection", ignoreCase = true))
        assertTrue(request, request.contains("Connection: close"))
        assertTrue(request, request.contains("User-Agent: test"))
    }

    @Test
    fun httpPostBodyIsForwarded() {
        val proxy = startProxy()
        val body = "a".repeat(100_000)
        connect(proxy).use { s ->
            s.getOutputStream().write(
                ("POST http://127.0.0.1:${origin.port}/upload HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${origin.port}\r\n" +
                    "Content-Length: ${body.length}\r\n\r\n$body").toByteArray(),
            )
            val response = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(response, response.endsWith("received ${body.length}"))
        }
    }

    @Test
    fun connectTunnelRelaysBothWays() {
        val proxy = startProxy()
        connect(proxy).use { s ->
            val out = s.getOutputStream()
            val input = s.getInputStream()
            out.write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\nHost: 127.0.0.1:${echo.port}\r\n\r\n".toByteArray())
            val head = readHead(input)
            assertTrue(head, head.startsWith("HTTP/1.1 200"))
            val payload = ByteArray(50_000) { (it % 251).toByte() }
            out.write(payload)
            assertArrayEquals(payload, readExactly(input, payload.size))
        }
    }

    @Test
    fun connectToClosedPortGives502() {
        val proxy = startProxy()
        val closedPort = ServerSocket(0).use { it.localPort }
        connect(proxy).use { s ->
            s.getOutputStream().write("CONNECT 127.0.0.1:$closedPort HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(readHead(s.getInputStream()).startsWith("HTTP/1.1 502"))
        }
    }

    @Test
    fun socks5ConnectWithDomain() {
        val proxy = startProxy()
        connect(proxy).use { s ->
            val name = "localhost".toByteArray()
            socksHandshake(s, byteArrayOf(0x03, name.size.toByte()) + name, echo.port)
            echoRoundTrip(s)
        }
    }

    @Test
    fun socks5ConnectWithIpv4() {
        val proxy = startProxy()
        connect(proxy).use { s ->
            socksHandshake(s, byteArrayOf(0x01, 127, 0, 0, 1), echo.port)
            echoRoundTrip(s)
        }
    }

    @Test
    fun socks5RejectsUnsupportedCommand() {
        val proxy = startProxy()
        connect(proxy).use { s ->
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            assertArrayEquals(byteArrayOf(0x05, 0x00), readExactly(input, 2))
            // BIND(0x02)
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 127, 0, 0, 1, 0, 80))
            assertEquals(0x07, readExactly(input, 10)[1].toInt())
        }
    }

    @Test
    fun disabledHttpIsClosed() {
        val proxy = startProxy(http = false)
        connect(proxy).use { s ->
            s.getOutputStream().write("GET http://127.0.0.1:${origin.port}/ HTTP/1.1\r\n\r\n".toByteArray())
            assertEquals(-1, s.getInputStream().read())
        }
        assertTrue(origin.requests.isEmpty())
        // SOCKS5 는 그대로 된다.
        connect(proxy).use { s ->
            socksHandshake(s, byteArrayOf(0x01, 127, 0, 0, 1), echo.port)
            echoRoundTrip(s)
        }
    }

    @Test
    fun disabledSocksIsClosed() {
        val proxy = startProxy(socks = false)
        connect(proxy).use { s ->
            s.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            assertEquals(-1, s.getInputStream().read())
        }
        // HTTP 는 그대로 된다.
        connect(proxy).use { s ->
            s.getOutputStream().write("GET http://127.0.0.1:${origin.port}/ HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(s.getInputStream().readBytes().toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 200"))
        }
    }

    @Test
    fun countersMatchTunnelBytes() {
        val proxy = startProxy()
        connect(proxy).use { s ->
            s.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
            readHead(s.getInputStream())
            val payload = ByteArray(123_456) { it.toByte() }
            s.getOutputStream().write(payload)
            readExactly(s.getInputStream(), payload.size)
        }
        waitUntil { proxy.counter.txBytes == 123_456L && proxy.counter.rxBytes == 123_456L }
        assertEquals(123_456L, proxy.counter.txBytes)
        assertEquals(123_456L, proxy.counter.rxBytes)
    }

    @Test
    fun countersIncludeHttpRequestAndResponse() {
        val proxy = startProxy()
        var responseSize: Int
        connect(proxy).use { s ->
            s.getOutputStream().write("GET http://127.0.0.1:${origin.port}/ HTTP/1.1\r\n\r\n".toByteArray())
            responseSize = s.getInputStream().readBytes().size
        }
        val sentToOrigin = origin.requests.single().toByteArray(Charsets.ISO_8859_1).size
        waitUntil { proxy.counter.rxBytes == responseSize.toLong() }
        assertEquals(sentToOrigin.toLong(), proxy.counter.txBytes)
        assertEquals(responseSize.toLong(), proxy.counter.rxBytes)
    }

    @Test
    fun stopClosesListenerAndConnections() {
        val proxy = startProxy()
        val port = proxy.localPort
        val s = connect(proxy)
        s.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
        readHead(s.getInputStream())
        proxy.stop()
        assertEquals(-1, s.getInputStream().read())
        s.close()
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }

    @Test
    fun manyConcurrentConnections() {
        val proxy = startProxy()
        val failures = CopyOnWriteArrayList<Throwable>()
        val threads = (1..50).map {
            thread {
                try {
                    connect(proxy).use { s ->
                        socksHandshake(s, byteArrayOf(0x01, 127, 0, 0, 1), echo.port)
                        echoRoundTrip(s)
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        threads.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test
    fun clientFilterAllowsOnlyPrivateAddresses() {
        fun ip(text: String) = InetAddress.getByName(text)
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.43.10", "169.254.1.1", "::1", "fd00::1", "fe80::1")
            .forEach { assertTrue(it, ClientFilter.isAllowed(ip(it))) }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "1.1.1.1", "2001:4860:4860::8888")
            .forEach { assertFalse(it, ClientFilter.isAllowed(ip(it))) }
    }

    @Test
    fun counterRateIsBytesPerSecond() {
        val counter = TrafficCounter()
        counter.sample(1_000_000_000L)
        counter.addTx(2_000)
        counter.addRx(500)
        val snapshot = counter.sample(1_500_000_000L)
        assertEquals(4_000L, snapshot.txRate)
        assertEquals(1_000L, snapshot.rxRate)
        assertEquals(2_000L, snapshot.txBytes)
    }

    @Test
    fun parseHostPortForms() {
        assertEquals("example.com" to 443, ProxyServer.parseHostPort("example.com:443", 80))
        assertEquals("example.com" to 80, ProxyServer.parseHostPort("example.com", 80))
        assertEquals("::1" to 8443, ProxyServer.parseHostPort("[::1]:8443", 80))
        assertEquals(null, ProxyServer.parseHostPort("example.com:abc", 80))
    }

    // ---------------------------------------------------------------- 도우미

    private fun socksHandshake(s: Socket, address: ByteArray, port: Int) {
        val out = s.getOutputStream()
        val input = s.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        assertArrayEquals(byteArrayOf(0x05, 0x00), readExactly(input, 2))
        out.write(byteArrayOf(0x05, 0x01, 0x00) + address + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val reply = readExactly(input, 4)
        assertEquals(0x00, reply[1].toInt())
        val addressLength = if (reply[3].toInt() == 0x01) 4 else 16
        readExactly(input, addressLength + 2)
    }

    private fun echoRoundTrip(s: Socket) {
        val payload = "ping-${System.nanoTime()}".toByteArray()
        s.getOutputStream().write(payload)
        assertArrayEquals(payload, readExactly(s.getInputStream(), payload.size))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    companion object {
        fun readExactly(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(bytes, offset, length - offset)
                check(n >= 0) { "EOF after $offset bytes" }
                offset += n
            }
            return bytes
        }

        fun readHead(input: InputStream): String {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) break
                buffer.write(b)
                if (buffer.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) break
            }
            return buffer.toString(Charsets.ISO_8859_1.name())
        }
    }
}

/** 요청 머리를 적어 두고 짧은 응답을 내는 HTTP 서버. POST 면 받은 body 길이를 돌려준다. */
private class OriginServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort
    val requests = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true) {
                    s.use {
                        val head = ProxyServerTest.readHead(it.getInputStream())
                        requests += head
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                            .find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = if (length > 0) {
                            ProxyServerTest.readExactly(it.getInputStream(), length)
                            "received $length"
                        } else {
                            "hello from origin"
                        }
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

/** 받은 byte 를 그대로 돌려주는 서버. */
private class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 100, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true) {
                    s.use { it.getInputStream().copyTo(it.getOutputStream()) }
                }
            }
        }
    }

    override fun close() = server.close()
}
