package com.bumplog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class Pal(
    val bg: Color, val card: Color, val card2: Color, val nav: Color, val text: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val yellow: Color, val danger: Color, val dark: Boolean,
)

/** Muted sage, the default accent. It leads the last row of the picker. */
const val SAGE_ARGB: Long = 0xFF6F9C7E

val Swatches = listOf(
    0xFFFF5228, 0xFFFFB020, 0xFF3CC878, 0xFF3C9BFF, 0xFFA064FF,
    0xFFFF4D8D, 0xFF20C9C9, 0xFFC8E63C, 0xFFFF8C28, 0xFFE0E0E8,
    SAGE_ARGB,
    0xFFCC5500, // burnt orange
    // Muted tones from the notification shade photo: sampled blue and mint, glow colors lifted to be readable.
    0xFF7BAFDB, 0xFF83A2A4, 0xFFB8735C,
).map { Color(it) }

/** The accent list in the order it had when accents were saved by position. Frozen: it only exists to read old saved choices. */
val LegacySwatchArgb: List<Long> = listOf(
    0xFFFF5228, 0xFFFFB020, 0xFF3CC878, 0xFF3C9BFF, 0xFFA064FF,
    0xFFFF4D8D, 0xFF20C9C9, 0xFFC8E63C, 0xFFFF8C28, 0xFFE0E0E8,
    0xFFCC5500, 0xFF7BAFDB, 0xFF83A2A4, 0xFFB8735C, 0xFF6F9C7E,
)

/** Accent choices are stored as the colour itself, so reordering the picker can never change what someone chose. */
object AccentStore {
    private val current: Set<Int> = Swatches.map { it.toArgb() }.toSet()

    /** The colour to use. [rgb] is the stored colour, [legacyIndex] an old position-based choice from before colours were stored. */
    fun resolve(rgb: Int?, legacyIndex: Int?): Int = when {
        rgb != null && rgb in current -> rgb
        rgb == null && legacyIndex != null && legacyIndex in LegacySwatchArgb.indices -> LegacySwatchArgb[legacyIndex].toInt()
        else -> SAGE_ARGB.toInt()
    }

    /** Position of [argb] in the picker. */
    fun indexOf(argb: Int): Int = Swatches.indexOfFirst { it.toArgb() == argb }.coerceAtLeast(0)
}

fun palette(dark: Boolean, accentIdx: Int): Pal {
    val ac = Swatches[accentIdx.coerceIn(0, Swatches.lastIndex)]
    val lum = 0.299f * ac.red + 0.587f * ac.green + 0.114f * ac.blue
    val onAc = if (lum > 0.62f) Color(0xFF0E0E10) else Color.White
    return if (dark) Pal(
        bg = Color(0xFF0E0E10), card = Color(0xFF1C1C20), card2 = Color(0xFF26262C), nav = Color(0xFF141418),
        text = Color(0xFFF5F5F7), muted = Color(0xFF9696A0), accent = ac, onAccent = onAc,
        yellow = Color(0xFFFFBE3C), danger = Color(0xFFE57373), dark = true,
    ) else Pal(
        bg = Color(0xFFF4F4F6), card = Color.White, card2 = Color(0xFFE9E9EE), nav = Color.White,
        text = Color(0xFF111114), muted = Color(0xFF6A6A74), accent = ac, onAccent = onAc,
        yellow = Color(0xFF946400), danger = Color(0xFFC62828), dark = false,
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

private fun chan(x: Float): Float = if (x <= 0.03928f) x / 12.92f else Math.pow(((x + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
private fun lum(c: Color): Float = 0.2126f * chan(c.red) + 0.7152f * chan(c.green) + 0.0722f * chan(c.blue)
fun contrastRatio(a: Color, b: Color): Float { val x = maxOf(lum(a), lum(b)); val y = minOf(lum(a), lum(b)); return (x + 0.05f) / (y + 0.05f) }
/** The accent as a text color, but only when it reads at 4.5:1 on the given background. Otherwise plain text color. */
fun Pal.accentText(bg: Color): Color = if (contrastRatio(accent, bg) >= 4.5f) accent else text
