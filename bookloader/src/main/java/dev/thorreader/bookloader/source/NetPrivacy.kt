package dev.thorreader.bookloader.source

import java.net.InetSocketAddress
import java.net.Proxy

/** How Bookloader reaches the internet. */
enum class NetMode(val label: String) {
    DIRECT("Direct"),
    /** Tor through the Orbot app's SOCKS port on this device. */
    TOR("Tor (Orbot)"),
    PROXY("Your proxy"),
}

enum class ProxyKind { SOCKS, HTTP }

/**
 * The chosen route. Everything Bookloader fetches (searches, covers, downloads) uses it, and
 * when it is not [NetMode.DIRECT] nothing is ever sent directly: if the route is down, the
 * request fails. Through SOCKS the proxy resolves host names, so no DNS lookups leak either.
 */
data class NetConfig(
    val mode: NetMode = NetMode.DIRECT,
    val kind: ProxyKind = ProxyKind.SOCKS,
    val host: String = "",
    val port: Int = 0,
) {
    /** Null = the route is usable; otherwise why not, in plain words. */
    fun problem(): String? = when (mode) {
        NetMode.DIRECT, NetMode.TOR -> null
        NetMode.PROXY -> when {
            host.isBlank() -> "Enter the proxy's address"
            host.any { it.isWhitespace() || it == '/' || it == '@' } -> "The proxy address should be just a host name or IP"
            port !in 1..65535 -> "Enter the proxy's port (1-65535)"
            else -> null
        }
    }

    /** The java.net proxy to use; null = direct. Throws if the configuration isn't usable. */
    fun proxy(): Proxy? = when (mode) {
        NetMode.DIRECT -> null
        NetMode.TOR -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(ORBOT_HOST, ORBOT_SOCKS_PORT))
        NetMode.PROXY -> {
            problem()?.let { throw IllegalStateException(it) }
            Proxy(if (kind == ProxyKind.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(host.trim(), port))
        }
    }

    /** For the status badge: "Tor", "Proxy", or null when direct. */
    val badge: String? get() = when (mode) {
        NetMode.DIRECT -> null
        NetMode.TOR -> "Tor"
        NetMode.PROXY -> "Proxy"
    }

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val ORBOT_HOST = "127.0.0.1"
        const val ORBOT_SOCKS_PORT = 9050
        /** Asked only when the user taps "Test connection". */
        const val CHECK_URL = "https://check.torproject.org/api/ip"
    }
}
