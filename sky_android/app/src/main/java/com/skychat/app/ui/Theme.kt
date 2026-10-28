package com.skychat.app.ui

import android.text.format.DateFormat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val BarGreen = Color(0xFF0B6B5D)
val Accent = Color(0xFF1FAF5A)
val BubbleOut = Color(0xFFDCF8C6)
val ChatBg = Color(0xFFECE5DD)
val ReadBlue = Color(0xFF34B7F1)

@Composable
fun SkyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = BarGreen, secondary = Accent),
        content = content
    )
}

fun timeText(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
