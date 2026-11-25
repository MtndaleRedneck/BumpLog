package com.bumplog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class Pal(
    val bg: Color, val card: Color, val card2: Color, val nav: Color, val text: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val yellow: Color, val danger: Color, val dark: Boolean,
    /** Accent lightened toward white: the top of accent gradients on filled buttons and selected tiles. */
    val accentHi: Color,
    /** Raised tile top, one step above card2: the top of unselected tile gradients. */
    val tile: Color,
)

/** Muted sage, the default accent. It leads the picker. */
const val SAGE_ARGB: Long = 0xFF6F9C7E

val Swatches = listOf(
    SAGE_ARGB,
    0xFFCC5500, // burnt orange
    0xFF7BAFDB, // dusty blue
    0xFF83A2A4, // gray-green
    0xFFB8735C, // terracotta
    0xFFB3838F, // mauve
    0xFF8C8FC0, // indigo
    0xFFA89A8C, // taupe
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

    /** The colour to use. [rgb] is the stored colour, [legacyIndex] an old position-based choice from before colours were stored. A colour the picker no longer offers falls back to sage. */
    fun resolve(rgb: Int?, legacyIndex: Int?): Int {
        val candidate = when {
            rgb != null -> rgb
            legacyIndex != null && legacyIndex in LegacySwatchArgb.indices -> LegacySwatchArgb[legacyIndex].toInt()
            else -> null
        }
        return if (candidate != null && candidate in current) candidate else SAGE_ARGB.toInt()
    }

    /** Position of [argb] in the picker. */
    fun indexOf(argb: Int): Int = Swatches.indexOfFirst { it.toArgb() == argb }.coerceAtLeast(0)
}

private fun lighten(c: Color, f: Float) = Color(c.red + (1f - c.red) * f, c.green + (1f - c.green) * f, c.blue + (1f - c.blue) * f)

/** Label color on accent fills. Verified: this dark reads at least 4.5:1 on all eight swatches, flat and
 * lightened gradient end (worst case burnt orange flat, about 4.53:1); white fails every swatch. */
fun onAccentArgb(accent: Int): Int = 0xFF0C0C0E.toInt()

fun palette(dark: Boolean, accentIdx: Int): Pal {
    val ac = Swatches[accentIdx.coerceIn(0, Swatches.lastIndex)]
    val onAc = Color(onAccentArgb(ac.toArgb()))
    return if (dark) Pal(
        // Surfaces match BingeMote's grays (owner decision, Nov 2026): one rung lighter than the old ladder.
        bg = Color(0xFF1B1B1E), card = Color(0xFF26262D), card2 = Color(0xFF2E2E36), nav = Color(0xFF141418),
        // Muted sits one step brighter than BingeMote's so small tile labels stay at 4.5:1 on the tile top.
        text = Color(0xFFF5F5F7), muted = Color(0xFFA0A0AA), accent = ac, onAccent = onAc,
        yellow = Color(0xFFFFBE3C), danger = Color(0xFFE57373), dark = true,
        accentHi = lighten(ac, 0.30f),
        // Same step above card2 that Listen Later's tile top (2E2E36) keeps over its card2 (26262C).
        tile = Color(0xFF363640),
    ) else Pal(
        bg = Color(0xFFF4F4F6), card = Color.White, card2 = Color(0xFFE9E9EE), nav = Color.White,
        text = Color(0xFF111114), muted = Color(0xFF6A6A74), accent = ac, onAccent = onAc,
        yellow = Color(0xFF946400), danger = Color(0xFFC62828), dark = false,
        accentHi = lighten(ac, 0.30f), tile = Color.White,
    )
}

/** Card fill: card2 at the top easing to card at the bottom. */
val Pal.cardGradient: Brush get() = Brush.verticalGradient(listOf(card2, card))
/** Filled buttons and selected tiles: lightened accent over the accent. */
val Pal.accentGradient: Brush get() = Brush.verticalGradient(listOf(accentHi, accent))
/** Unselected raised tiles: one step above card2 easing to card2. */
val Pal.tileGradient: Brush get() = Brush.verticalGradient(listOf(tile, card2))

val LocalPal = staticCompositionLocalOf { palette(true, 0) }

@Composable
fun BumpLogTheme(theme: String, accent: Int, content: @Composable () -> Unit) {
    // Dark only (owner decision, Oct 2026): the Light/System choices are gone, the app always uses the dark palette.
    val p = palette(true, accent)
    val scheme = darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.card,
        onBackground = p.text, onSurface = p.text, surfaceVariant = p.card2)
    CompositionLocalProvider(LocalPal provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}

private fun chan(x: Float): Float = if (x <= 0.03928f) x / 12.92f else Math.pow(((x + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
private fun lum(c: Color): Float = 0.2126f * chan(c.red) + 0.7152f * chan(c.green) + 0.0722f * chan(c.blue)
fun contrastRatio(a: Color, b: Color): Float { val x = maxOf(lum(a), lum(b)); val y = minOf(lum(a), lum(b)); return (x + 0.05f) / (y + 0.05f) }
/** The accent as a text color: itself when it already reads at 4.5:1 on the surface, otherwise lightened
 * in small steps until it does (hue kept), falling back to plain text only past a 48% lift. The 48% cap
 * leaves room for burnt orange, the darkest swatch. */
fun Pal.accentText(bg: Color): Color {
    if (contrastRatio(accent, bg) >= 4.5f) return accent
    var f = 0.04f
    while (f <= 0.48f) {
        val c = lighten(accent, f)
        if (contrastRatio(c, bg) >= 4.5f) return c
        f += 0.04f
    }
    return text
}
