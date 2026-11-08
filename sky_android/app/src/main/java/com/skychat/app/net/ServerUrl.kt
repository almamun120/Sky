package com.skychat.app.net

/**
 * Server addresses. Debug and release builds behave the same.
 * The normal way is wss:// (TLS, port 443). ws:// (port 80) is only the fallback: when wss:// cannot connect at all,
 * the app tries the same host with ws:// once, and goes back to wss:// at the next connection.
 */
object ServerUrl {
    /** What the person typed -> a full address: "chat.example.com" becomes "wss://chat.example.com/ws"; https/http become wss/ws. */
    fun normalize(input: String): String {
        var u = input.trim()
        if (u.isEmpty()) return u
        val l = u.lowercase()
        u = when {
            l.startsWith("wss://") || l.startsWith("ws://") -> u
            l.startsWith("https://") -> "wss://" + u.substring(8)
            l.startsWith("http://") -> "ws://" + u.substring(7)
            else -> "wss://$u"
        }
        val rest = u.substringAfter("://")
        return if (rest.contains('/')) u else "$u/ws"
    }

    /** Null if [url] is usable, otherwise a short reason for the user. */
    fun problem(url: String): String? {
        val u = normalize(url).lowercase()
        val host = u.substringAfter("://", "").substringBefore('/')
        return when {
            host.isEmpty() || host.startsWith(":") -> "Enter the server address, e.g. wss://chat.example.com/ws"
            u.startsWith("wss://") || u.startsWith("ws://") -> null
            else -> "The address must start with wss://"
        }
    }

    fun isSecure(url: String) = normalize(url).lowercase().startsWith("wss://")

    /** The ws:// fallback for a wss:// address (same host and path, default port 80); null if [url] is not wss:// . */
    fun fallback(url: String): String? {
        val u = normalize(url)
        if (!u.lowercase().startsWith("wss://")) return null
        val rest = u.substring(6)
        val hostPort = rest.substringBefore('/')
        val path = rest.substring(hostPort.length)
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return "ws://$host$path"
    }
}
