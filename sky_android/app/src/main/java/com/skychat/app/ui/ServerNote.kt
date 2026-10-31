package com.skychat.app.ui

import android.content.pm.ApplicationInfo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.skychat.app.net.ServerUrl

@Composable
private fun isDebuggable(): Boolean =
    (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** Null if the address is usable, otherwise why not. */
@Composable
fun serverProblem(url: String): String? = ServerUrl.problem(url, isDebuggable())

/** One line under the server address field: encrypted (wss), not encrypted (ws) or not usable. */
@Composable
fun ServerNote(url: String) {
    val problem = serverProblem(url)
    val (text, color) = when {
        problem != null -> problem to Color(0xFFD32F2F)
        ServerUrl.isSecure(url) -> "🔒 Encrypted connection (wss)" to Accent
        else -> "⚠ ws:// is not encrypted. Use it only for testing on your own network." to Color(0xFFF5B301)
    }
    Text(text, color = color, fontSize = 12.sp)
}
