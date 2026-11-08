package com.skychat.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.skychat.app.net.ServerUrl

/** Null if the address is usable, otherwise why not. */
fun serverProblem(url: String): String? = ServerUrl.problem(url)

/** One line under the server address field: encrypted (wss) or not (ws, fallback only) or not usable. */
@Composable
fun ServerNote(url: String) {
    val problem = serverProblem(url)
    val (text, color) = when {
        problem != null -> problem to Color(0xFFD32F2F)
        ServerUrl.isSecure(url) -> t("🔒 Encrypted connection (wss). ws:// is used only if wss:// cannot connect.") to Accent
        else -> "⚠ ws:// is not encrypted. Prefer wss://" to Color(0xFFF5B301)
    }
    Text(text, color = color, fontSize = 12.sp)
}
