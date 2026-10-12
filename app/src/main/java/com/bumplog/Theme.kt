package com.bumplog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class Pal(
    val bg: Color, val card: Color, val card2: Color, val nav: Color, val text: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val green: Color, val yellow: Color, val dark: Boolean,
)

val Swatches = listOf(
    0xFFFF5228, 0xFFFFB020, 0xFF3CC878, 0xFF3C9BFF, 0xFFA064FF,
    0xFFFF4D8D, 0xFF20C9C9, 0xFFC8E63C, 0xFFFF8C28, 0xFFE0E0E8,
    0xFFCC5500, // burnt orange
    // Muted tones from the notification shade photo: sampled blue and mint, glow colors lifted to be readable.
    0xFF7BAFDB, 0xFF83A2A4, 0xFFB8735C, 0xFF6F9C7E,
).map { Color(it) }

fun palette(dark: Boolean, accentIdx: Int): Pal {
    val ac = Swatches[accentIdx.coerceIn(0, Swatches.lastIndex)]
    val lum = 0.299f * ac.red + 0.587f * ac.green + 0.114f * ac.blue
    val onAc = if (lum > 0.62f) Color(0xFF0E0E10) else Color.White
    return if (dark) Pal(
        bg = Color(0xFF0E0E10), card = Color(0xFF1C1C20), card2 = Color(0xFF26262C), nav = Color(0xFF141418),
        text = Color(0xFFF5F5F7), muted = Color(0xFF9696A0), accent = ac, onAccent = onAc,
        green = Color(0xFF3CC878), yellow = Color(0xFFFFBE3C), dark = true,
    ) else Pal(
        bg = Color(0xFFF4F4F6), card = Color.White, card2 = Color(0xFFE9E9EE), nav = Color.White,
        text = Color(0xFF111114), muted = Color(0xFF6A6A74), accent = ac, onAccent = onAc,
        green = Color(0xFF1FA35C), yellow = Color(0xFFD99A00), dark = false,
    )
}

val LocalPal = staticCompositionLocalOf { palette(true, 0) }

@Composable
fun BumpLogTheme(theme: String, accent: Int, content: @Composable () -> Unit) {
    val dark = when (theme) { "LIGHT" -> false; "SYSTEM" -> isSystemInDarkTheme(); else -> true }
    val p = palette(dark, accent)
    val scheme = if (dark) darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.card,
        onBackground = p.text, onSurface = p.text, surfaceVariant = p.card2)
    else lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.card,
        onBackground = p.text, onSurface = p.text, surfaceVariant = p.card2)
    CompositionLocalProvider(LocalPal provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}
