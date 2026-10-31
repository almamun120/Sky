package com.skychat.app.net

/** Which server addresses the app accepts. wss:// always; ws:// (no encryption) only in the debug build. */
object ServerUrl {
    /** Null if [url] is fine, otherwise a short reason for the user. */
    fun problem(url: String, debuggable: Boolean): String? {
        val u = url.trim().lowercase()
        return when {
            u.startsWith("wss://") && u.length > 6 -> null
            u.startsWith("ws://") && u.length > 5 ->
                if (debuggable) null else "ws:// (not encrypted) works only in the test version of the app. Use wss://"
            else -> "The address must start with wss:// (or ws:// for testing on your own network)."
        }
    }

    fun isSecure(url: String) = url.trim().lowercase().startsWith("wss://")
}
