package com.skychat.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val BarGreen = Color(0xFF0B6B5D)
val Accent = Color(0xFF1FAF5A)
val BubbleOut = Color(0xFFDCF8C6)
val ChatBg = Color(0xFFECE5DD)
val ReadBlue = Color(0xFF34B7F1)

/** Colours that differ between the light and the dark look. Use `Sky.xxx` inside composables. */
class SkyColors(
    val bar: Color, val chatBg: Color, val bubbleOut: Color, val bubbleIn: Color,
    val surface: Color, val surface2: Color, val pill: Color, val divider: Color, val accentText: Color
)

private val LightSky = SkyColors(
    bar = BarGreen, chatBg = ChatBg, bubbleOut = BubbleOut, bubbleIn = Color.White,
    surface = Color.White, surface2 = Color(0xFFEFEFEF), pill = Color(0xFFF0F2F5),
    divider = Color(0x14000000), accentText = BarGreen)

private val DarkSky = SkyColors(
    bar = Color(0xFF1F2C34), chatBg = Color(0xFF0B141A), bubbleOut = Color(0xFF005C4B), bubbleIn = Color(0xFF1F2C34),
    surface = Color(0xFF111B21), surface2 = Color(0xFF1F2C34), pill = Color(0xFF1F2C34),
    divider = Color(0x1FFFFFFF), accentText = Color(0xFF06CF9C))

private val LocalSky = staticCompositionLocalOf { LightSky }

val Sky: SkyColors
    @Composable @ReadOnlyComposable get() = LocalSky.current

/** [mode]: 0 follow the phone, 1 light, 2 dark. */
@Composable
fun SkyTheme(mode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark)
        darkColorScheme(primary = Color(0xFF00A884), secondary = Accent, background = Color(0xFF111B21),
            surface = Color(0xFF111B21), onBackground = Color(0xFFE9EDEF), onSurface = Color(0xFFE9EDEF),
            surfaceVariant = Color(0xFF1F2C34), onSurfaceVariant = Color(0xFF8696A0))
    else lightColorScheme(primary = BarGreen, secondary = Accent)
    CompositionLocalProvider(LocalSky provides (if (dark) DarkSky else LightSky)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

fun timeText(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
