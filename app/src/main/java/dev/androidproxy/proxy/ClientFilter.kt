package dev.androidproxy.proxy

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 받을 연결인지 가린다: 사설 주소(RFC 1918), loopback, link-local, IPv6 ULA(fc00::/7)만 받는다.
 * loopback 은 `adb forward` 로 들어온 연결이다.
 */
object ClientFilter {
    fun isAllowed(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        return when (address) {
            is Inet4Address -> address.isSiteLocalAddress
            is Inet6Address -> {
                val bytes = address.address
                val mapped = bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
                    bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
                if (mapped) {
                    isAllowed(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
                } else {
                    (bytes[0].toInt() and 0xFE) == 0xFC
                }
            }
            else -> false
        }
    }
}
