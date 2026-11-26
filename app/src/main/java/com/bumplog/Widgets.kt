package com.bumplog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.focusable
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = LocalPal.current.card, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().shadow(4.dp, shape).clip(shape)
        .then(if (color == p.card) Modifier.background(p.cardGradient) else Modifier.background(color))
        .padding(pad), content = content)
}

@Composable
fun Txt(s: String, size: Int = 16, weight: FontWeight = FontWeight.Normal, color: Color = LocalPal.current.text,
        modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) =
    Text(s, fontSize = size.sp, fontWeight = weight, color = color, modifier = modifier, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Visible)

@Composable
fun Muted(s: String, size: Int = 13, modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) =
    Txt(s, size, color = LocalPal.current.muted, modifier = modifier, align = align, maxLines = maxLines)

/**
 * BingeMote's press feel, shared by every button: the button sinks to 94% and dims about 15% while a finger is down
 * (90 ms in, 120 ms out), with a light tick on touch-down when Vibrate on press is on. The tap fires on release.
 * Put it before clip and background so the whole button shrinks, not just its label.
 */
fun Modifier.pressFeel(
    enabled: Boolean = true,
    role: androidx.compose.ui.semantics.Role = androidx.compose.ui.semantics.Role.Button,
    selected: Boolean? = null,
    onPress: () -> Unit,
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(if (pressed) 90 else 120), label = "sink")
    val dim by animateFloatAsState(if (pressed) 0.15f else 0f, tween(if (pressed) 90 else 120), label = "dim")
    val view = LocalView.current
    val tap by rememberUpdatedState(onPress)
    // Keyboard and D-pad: the button stays focusable with a visible ring, and Enter fires it on release, but only after
    // its own key-down, so focus moving mid-press can never fire a different button.
    var focused by remember(enabled) { mutableStateOf(false) }
    var keyArmed by remember(enabled) { mutableStateOf(false) }
    val ring = LocalPal.current.text
    this
        .semantics(mergeDescendants = true) {
            this.role = role
            if (selected != null) this.selected = selected
            if (!enabled) disabled()
            onClick { if (enabled) { tap(); true } else false }
        }
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 1f - dim }
        .onFocusChanged { focused = it.isFocused; if (!it.isFocused) keyArmed = false }
        .focusable(enabled)
        .onKeyEvent { e ->
            val k = e.key
            if (!enabled || !(k == Key.Enter || k == Key.NumPadEnter || k == Key.DirectionCenter)) false
            else when (e.type) {
                KeyEventType.KeyDown -> { keyArmed = true; true }
                KeyEventType.KeyUp -> {
                    if (keyArmed) {
                        keyArmed = false
                        if (Prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        tap()
                    }
                    true
                }
                else -> false
            }
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                val r = minOf(size.minDimension / 2f, 32.dp.toPx())
                drawRoundRect(ring, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown()
                if (Prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                pressed = true
                try {
                    val up = waitForUpOrCancellation()
                    if (up != null) tap()
                } finally {
                    // Also runs when the gesture is cancelled (a scroll took over), so the button never stays sunk.
                    pressed = false
                }
            }
        }
}

/** The custom report mark from the app icon. Monochrome takes the button's own ink; full colour is the shipped coral mark. */
@Composable
fun ReportMark(tint: Color, modifier: Modifier = Modifier) {
    val mono = Prefs.reportIcon == "mono"
    val painter = androidx.compose.ui.res.painterResource(if (mono) R.drawable.report_mark_mono else R.drawable.report_mark_color)
    // The mark is about 9:8; this keeps its proportions in a 30dp-tall slot.
    val size = modifier.size(width = 34.dp, height = 30.dp)
    if (mono) androidx.compose.material3.Icon(painter, null, tint = tint, modifier = size)
    else androidx.compose.foundation.Image(painter, null, modifier = size)
}

@Composable
fun BigButton(label: String, icon: ImageVector? = null, filled: Boolean = true, modifier: Modifier = Modifier, enabled: Boolean = true,
              iconContent: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(32.dp)
    val m = modifier.fillMaxWidth().heightIn(min = 60.dp).pressFeel(enabled, onPress = onClick).clip(shape)
        .let { if (filled) { if (enabled) it.background(p.accentGradient) else it.background(p.card2) } else it.background(p.card2).border(BorderStroke(2.dp, p.accent), shape) }
    Row(m, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (iconContent != null) { iconContent(); Spacer(Modifier.width(10.dp)) }
        else if (icon != null) { Icon(icon, null, tint = if (filled) p.onAccent else p.text, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)) }
        Txt(label, 20, FontWeight.Bold, if (filled) p.onAccent else p.text)
    }
}

@Composable
fun SmallButton(label: String, modifier: Modifier = Modifier, outline: Boolean = true, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(26.dp)
    Box(modifier.heightIn(min = 52.dp).pressFeel(onPress = onClick).clip(shape).let { if (outline) it.background(p.card2) else it.background(p.accentGradient) }
        .let { if (outline) it.border(BorderStroke(2.dp, p.accent), shape) else it },
        contentAlignment = Alignment.Center) {
        Txt(label, 16, FontWeight.Bold, if (outline) p.text else p.onAccent)
    }
}

@Composable
fun Chip(label: String, color: Color = LocalPal.current.accent) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(LocalPal.current.card2).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Txt(label, 13, FontWeight.Medium, color)
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    Card(pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Txt(label, 17, FontWeight.Bold); if (sub != null) Muted(sub, 12) }
            Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent,
                uncheckedTrackColor = p.card2, uncheckedThumbColor = p.muted, uncheckedBorderColor = p.muted))
        }
    }
}

/** A labelled dropdown: tap to pick one of [options] (key to label). */
@Composable
fun Dropdown(label: String, selected: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val p = LocalPal.current
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: selected
    Box(modifier) {
        Row(Modifier.fillMaxWidth().pressFeel(onPress = { open = true }).clip(RoundedCornerShape(14.dp)).background(p.card2).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { if (label.isNotBlank()) Muted(label, 11); Txt(shown, 16, FontWeight.Medium) }
            Muted("v", 14)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.background(p.card2)) {
            options.forEach { (k, v) ->
                DropdownMenuItem(text = { Txt(v, 16) }, onClick = { open = false; onPick(k) })
            }
        }
    }
}

/** A label with minus and plus buttons, without a card around it, so several can share one card. */
@Composable
fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 17, FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(Modifier.size(48.dp).pressFeel(onPress = onMinus).clip(RoundedCornerShape(12.dp)).background(p.card2), contentAlignment = Alignment.Center) { Txt("-", 22, FontWeight.Bold, p.accent) }
        Txt(value, 17, FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.size(48.dp).pressFeel(onPress = onPlus).clip(RoundedCornerShape(12.dp)).background(p.card2), contentAlignment = Alignment.Center) { Txt("+", 22, FontWeight.Bold, p.accent) }
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, note: String? = null) {
    Card(pad = 16.dp) { StepperRow(label, value, onMinus, onPlus); if (note != null) { Spacer(Modifier.height(6.dp)); Muted(note, 12) } }
}

/** Gauge ring open at the bottom, like the mockup. value/maxValue fills the accent arc. */
@Composable
fun Gauge(value: Float, maxValue: Float, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Canvas(modifier) {
        val stroke = size.minDimension * 0.07f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        val start = 140f; val sweep = 260f
        drawArc(p.card2, start, sweep, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        val f = (value / maxValue).coerceIn(0.02f, 1f)
        drawArc(p.accent, start, sweep * f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
fun Wave(samples: FloatArray, modifier: Modifier = Modifier, dim: Boolean = false, color: Color? = null) {
    val p = LocalPal.current
    Canvas(modifier) {
        val c = color ?: p.accent
        if (samples.size < 2) { drawLine(c.copy(alpha = 0.35f), Offset(0f, size.height * 0.62f), Offset(size.width, size.height * 0.62f), 3f); return@Canvas }
        val path = Path()
        val mid = size.height * 0.62f
        val scale = size.height * 0.5f / 2f
        samples.forEachIndexed { i, g ->
            val x = size.width * i / (samples.size - 1)
            val y = mid - (g.coerceAtMost(2.2f) * scale) * (if (i % 2 == 0) 1f else -0.6f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.copy(alpha = if (dim) 0.35f else 1f), style = Stroke(4f, join = StrokeJoin.Round))
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun ScreenTitle(s: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(s, 28, FontWeight.Bold, modifier = Modifier.weight(1f)); trailing?.invoke()
    }
}

// ---------------------------------------------------------------- Compact pill buttons (recordings, dialogs)

enum class PillKind { Fill, Outline, Danger }

private fun dangerColor(dark: Boolean) = if (dark) Color(0xFFE57373) else Color(0xFFC62828)

/** A compact pill, at least 48dp tall. The label stays on one line. */
@Composable
fun PillButton(label: String, kind: PillKind, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(24.dp)
    val red = dangerColor(p.dark)
    val m = modifier.heightIn(min = 48.dp).pressFeel(onPress = onClick).clip(shape)
        .let {
            when (kind) {
                PillKind.Fill -> it.background(p.accent)
                PillKind.Outline -> it.background(p.card2).border(BorderStroke(2.dp, p.accent), shape)
                PillKind.Danger -> it.background(p.card2).border(BorderStroke(2.dp, red), shape)
            }
        }
    Box(m, contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            color = when (kind) { PillKind.Fill -> p.onAccent; PillKind.Outline -> p.text; PillKind.Danger -> red },
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/**
 * Pills laid out in equal-width rows. All of them go on one row when the widest label fits at the current font size and
 * screen width. Otherwise they wrap to two per row, or one per row, so nothing is clipped.
 */
@Composable
fun PillRow(buttons: List<Triple<String, PillKind, () -> Unit>>, modifier: Modifier = Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
        val widest = buttons.maxOf { measurer.measure(it.first, style, maxLines = 1, softWrap = false).size.width }
        val perRow = pillsPerRow(buttons.size, widest.toFloat(), constraints.maxWidth.toFloat(), with(density) { 16.dp.toPx() }, with(density) { 6.dp.toPx() })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (l, k, f) -> PillButton(l, k, Modifier.weight(1f), f) }
                    // Keep widths equal when the last row has fewer pills.
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** How many pills fit per row: all of them if the widest label fits, else two, else one. Widths in pixels. */
fun pillsPerRow(count: Int, widestLabel: Float, available: Float, sidePadding: Float, gap: Float): Int {
    fun fits(k: Int) = k * (widestLabel + sidePadding) + (k - 1) * gap <= available
    return listOf(count, 2, 1).filter { it in 1..count }.firstOrNull { fits(it) } ?: 1
}

/** A confirmation dialog in the app's own card style: a title and two pills. */
@Composable
fun ConfirmDialog(title: String, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val p = LocalPal.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.card2).padding(24.dp)) {
            Txt(title, 20, FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("Cancel", PillKind.Outline, Modifier.weight(1f), onCancel)
                PillButton(confirmLabel, PillKind.Danger, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

object StatsIcons {
    private fun build(name: String, d: String): androidx.compose.ui.graphics.vector.ImageVector =
        androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(), fill = androidx.compose.ui.graphics.SolidColor(Color.Black)).build()
    val bar = build("bar", "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z")
    // The report plane: the Material send shape tipped up 45 degrees (locked with the owner).
    val plane = build("plane", "M11.42 23.02L18.42 5.66L0.99 12.57L5.04 16.68L14.9 9.1L7.36 18.96z")
    // Straight up, for the floating back-to-top pill (material-icons-core has no ArrowUpward).
    val up = build("up", "M12 4l-7 7h4v9h6v-9h4z")
}

/** Scroll positions survive tab switches: each screen keeps its own ScrollState by name. */
object ScrollMemory {
    private val states = HashMap<String, androidx.compose.foundation.ScrollState>()
    fun get(key: String): androidx.compose.foundation.ScrollState =
        states.getOrPut(key) { androidx.compose.foundation.ScrollState(0) }
}


// ---------------------------------------------------------------- Screen chrome (titles, back row, accessibility)

/** Announces the screen to TalkBack when it appears. Put it on each screen's root. */
fun Modifier.pane(title: String): Modifier = this.semantics { paneTitle = title }

/** Marks a section label so TalkBack users can jump between sections. */
fun Modifier.asHeading(): Modifier = this.semantics { heading() }

/** The small "< Title" row at the top of deeper pages. The whole row is a Back button, 48dp tall. */
@Composable
fun BackRow(title: String, back: () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().height(48.dp)
        .pressFeel(onPress = back)
        .semantics { contentDescription = "Back" },
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(Icons.Default.ArrowBack, null, tint = p.text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Txt(title, 18, FontWeight.Bold, modifier = Modifier.asHeading())
    }
}

/** Two equal buttons side by side, with the icon above the label. Falls back to stacked full-width buttons on narrow screens or large text. */
@Composable
fun ActionButtonPair(primaryLabel: String, primaryIcon: @Composable () -> Unit, onPrimary: () -> Unit, secondaryLabel: String, onSecondary: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    // The rule is about the screen width, not the content width inside the page padding.
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (!sideBySide(screenWidthDp.toFloat(), fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(primaryLabel, iconContent = primaryIcon, onClick = onPrimary)
                SmallButton(secondaryLabel, Modifier.fillMaxWidth(), onClick = onSecondary)
            }
        } else {
            val p = LocalPal.current
            val shape = RoundedCornerShape(32.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp).pressFeel(onPress = onPrimary).clip(shape).background(p.accentGradient)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    primaryIcon()
                    Txt(primaryLabel, 17, FontWeight.Bold, p.onAccent, align = TextAlign.Center)
                }
                Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp).pressFeel(onPress = onSecondary).clip(shape).background(p.card2)
                    .border(BorderStroke(2.dp, p.accent), shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Txt(secondaryLabel, 17, FontWeight.Bold, p.text, align = TextAlign.Center)
                }
            }
        }
    }
}

const val SIDE_BY_SIDE_MIN_DP = 360
const val SIDE_BY_SIDE_MAX_FONT = 1.5f

/** Pure rule behind [ActionButtonPair], kept testable. */
fun sideBySide(widthDp: Float, fontScale: Float) = widthDp >= SIDE_BY_SIDE_MIN_DP && fontScale < SIDE_BY_SIDE_MAX_FONT


/** One tab's last load, stamped with the Live tick it was read at. Immutable, so a reader never sees half of an update. */
class SnapSlot<T> {
    private class Entry<T>(val stamp: Stamp, val value: T)
    @Volatile private var e: Entry<T>? = null
    /** The snapshot only if its stamp equals the current one: any later database write moves the stamp and makes it cold. */
    fun warm(stamp: Stamp): T? = e?.takeIf { it.stamp == stamp }?.value
    /** A late, older read never replaces a snapshot taken at a newer stamp. */
    @Synchronized fun put(stamp: Stamp, v: T) { val c = e; if (c != null && (c.stamp.writes > stamp.writes || c.stamp.tick > stamp.tick)) return; e = Entry(stamp, v) }
    fun clear() { e = null }
}

/** Live tick plus the database's own write counter, read BEFORE a load starts. */
data class Stamp(val tick: Int, val writes: Long, val day: Long = 0L) { companion object { fun now(tick: Int) = Stamp(tick, Db.writes, Db.startOfToday()) } }   // the day is in the stamp so "today" counts refresh after midnight

class HomeSnap(val recent: List<Spot>, val today: Int, val driveKey: Long)
class ReviewSnap(val pending: Int, val saved: Int, val hits: List<Pair<Hit, Spot>>, val partial: Boolean = false)
class StatsSnap(val score: Db.Score, val big: Pair<Hit, Spot>?, val byType: List<Pair<DefectType?, Int>>)
class ReportedSnap(val spots: List<Spot>)
/** Measured spacing and the exact geometry it was measured for; reused only when the geometry matches. */
data class GeoKey(val viewH: Float, val count: Int, val density: Float, val fontScale: Float)
class GeoSnap(val key: GeoKey, val gap: androidx.compose.ui.unit.Dp, val extra: androidx.compose.ui.unit.Dp)

/**
 * What each tab last showed, so a tab that is rebuilt on every switch can draw real content on its first frame instead of an
 * empty state. A snapshot counts only if it was read at the CURRENT Live tick (every database change bumps it), so after a new
 * hit, report, delete or review action the tab starts blank and fills in, it never shows old numbers. The stamp is the tick the
 * load started at, so a load that finishes after a later write is already stale. Wipe, CSV import and restore also clear
 * everything after they finish. A cold process has nothing, so tabs draw a blank pane until their first read.
 */
/**
 * First-frame data for a tab: the warm snapshot if its stamp is current, otherwise one small read right now (the data is a handful of
 * rows), stamped before the read, so the first frame already shows final content: never stale, never blank. The read runs on a helper
 * thread and the main thread waits at most [FIRST_FRAME_WAIT_MS] for it: every Db read takes the database monitor, and an import,
 * restore or wipe holds that monitor for seconds, so an unbounded wait here froze the whole window (measured on the emulator: a 7 s
 * frame and an input-dispatch ANR during a 5000-row import). On timeout this returns null, the tab shows its own empty state, the effect
 * loads as before, and the helper still stores its result under the stamp it began with, so a late read can only warm the slot, never
 * show stale data. Null also if the read itself failed (logged).
 */
const val FIRST_FRAME_WAIT_MS = 40L
/** At most 2 helper threads and 4 waiting reads: during a long import every read blocks on the monitor, so extra tab switches must not pile up threads. */
internal val firstFramePool = java.util.concurrent.ThreadPoolExecutor(2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>(4),
    { r -> Thread(r, "bump-first-frame").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
fun <T : Any> firstFrame(slot: SnapSlot<T>, tick: Int, usable: (T) -> Boolean = { true }, read: () -> T): T? {
    val st = Stamp.now(tick)
    slot.warm(st)?.takeIf(usable)?.let { return it }
    val job = try { firstFramePool.submit(java.util.concurrent.Callable { read().also { slot.put(st, it) } }) }
              catch (e: java.util.concurrent.RejectedExecutionException) { return null }   // pool and queue full: every helper is stuck behind the monitor
    return try { job.get(FIRST_FRAME_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
    catch (e: java.util.concurrent.TimeoutException) { job.cancel(false); firstFramePool.remove(job as Runnable); null }   // a queued read never starts; a running one finishes and only warms the slot under its stamp
    catch (e: Exception) { android.util.Log.w("BumpLog", "first-frame read failed", e); null }
}

/**
 * Every Db write or read a tap needs runs here, one at a time in the order it was queued, never on the main thread: an import or restore holds
 * the Db monitor for seconds and a tap that read the Db inline would freeze the UI. This orders only the work that goes through here: imports,
 * restores, the detection service and the first-frame reads run on their own threads and meet it at the Db monitor, so a queued tap can wait
 * behind them (it never blocks the main thread). [run] hands the result to [done] on the main thread;
 * [call] suspends for it. A failure reaches [fail] (default: a toast) and never [done].
 */
object DbQ {
    private val ex = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "bump-db-queue").apply { isDaemon = true } }
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** Where [done] and [fail] run. The main thread; tests replace it. */
    internal var toMain: (Runnable) -> Unit = { mainHandler.post(it) }
    internal fun resetToMain() { toMain = { mainHandler.post(it) } }

    /** The queue itself, with no Android types: [work] runs in order on the queue thread, then exactly one of [done] (result) or [fail] (exception) runs via [toMain]. */
    internal fun <T> submit(work: () -> T, fail: (Exception) -> Unit, done: (T) -> Unit) {
        ex.execute {
            // Throwable on purpose: an Error in the work must still clear a caller's busy flag through [fail], never leave it set.
            val r = try { Result.success(work()) } catch (e: Throwable) { android.util.Log.w("BumpLog", "queued db work failed", e); Result.failure<T>(if (e is Exception) e else RuntimeException(e)) }
            toMain(Runnable { r.fold({ done(it) }, { e -> fail(e as Exception) }) })
        }
    }

    fun <T> run(ctx: android.content.Context, fail: ((Exception) -> Unit)? = null, work: (Db) -> T, done: (T) -> Unit = {}) {
        val app = ctx.applicationContext
        submit({ work(Db.get(app)) }, { e -> if (fail != null) fail(e) else toast(app, "That didn't work. Try again.") }, done)
    }

    /** One tap at a time: ignored while [busy] is set. [busy] stays set until [done] clears it (leaving the screen needs no clear); a failure clears it. */
    fun <T> runOnce(ctx: android.content.Context, busy: androidx.compose.runtime.MutableState<Boolean>, fail: ((Exception) -> Unit)? = null, work: (Db) -> T, done: (T) -> Unit) {
        if (busy.value) return
        busy.value = true
        run(ctx, fail = { e -> busy.value = false; if (fail != null) fail(e) else toast(ctx.applicationContext, "That didn't work. Try again.") }, work = work, done = done)
    }

    suspend fun <T> call(ctx: android.content.Context, work: (Db) -> T): T {
        val app = ctx.applicationContext
        val d = kotlinx.coroutines.CompletableDeferred<T>()
        ex.execute { try { d.complete(work(Db.get(app))) } catch (e: Throwable) { d.completeExceptionally(e) } }
        return d.await()
    }
}

/**
 * The "did the email go out?" pending question: {generation, sentFor, kind, again-snapshot}. It is published and read as one unit under one lock,
 * and stored with a single Prefs edit, so a reader never sees a half-written question (e.g. kind "again" without its rollback snapshot).
 * A dialog captures the whole [Question] when it is shown; its answer clears the state only if that same generation is still current ([clearIf]),
 * so a late answer can never erase, or roll back for, a newer mail hand-off. Every writer goes through [set].
 */
sealed class RollbackPlan { object None : RollbackPlan(); class Undo(val r: Rollback) : RollbackPlan(); object CannotUndo : RollbackPlan() }

/** A Report again's rollback snapshot: id|reportNo|reportTs|sentTs|outcome|outcomeTs|newReportTs. newReportTs is what the re-report wrote; the rollback applies only to that. */
class Rollback(val id: Long, val reportNo: Int, val reportTs: Long, val sentTs: Long, val outcome: String, val outcomeTs: Long, val expectedTs: Long) {
    fun encode() = listOf(id, reportNo, reportTs, sentTs, outcome, outcomeTs, expectedTs).joinToString("|")
    companion object {
        /** Null for anything that is not a complete 7-field snapshot: no rollback is guessed from a partial or old one. */
        /** What "Not yet" must do for a stored question: nothing to undo, an exact rollback, or an honest "cannot undo" (an unparseable "again" snapshot,
         *  e.g. one stored by an older build). The last case is never treated as rolled back. */
        fun plan(kind: String, again: String): RollbackPlan = if (kind != "again") RollbackPlan.None else parse(again)?.let { RollbackPlan.Undo(it) } ?: RollbackPlan.CannotUndo
        fun parse(s: String): Rollback? {
            val p = s.split("|"); if (p.size != 7) return null
            return try { Rollback(p[0].toLong(), p[1].toInt(), p[2].toLong(), p[3].toLong(), p[4], p[5].toLong(), p[6].toLong()) } catch (e: NumberFormatException) { null }
        }
    }
}

object PendingAsk {
    class Question(val gen: Int, val sentFor: String, val kind: String, val again: String)
    interface Store { fun read(): Triple<String, String, String>; fun write(sentFor: String, kind: String, again: String); fun sync(): Boolean = true }
    private val prefsStore = object : Store {
        override fun read() = Triple(Prefs.askSentFor, Prefs.askSentKind, Prefs.askAgain)
        override fun write(sentFor: String, kind: String, again: String) = Prefs.setAsk(sentFor, kind, again)
        override fun sync() = Prefs.syncAsk()
    }
    /** Tests replace this (and restore [prefsStore]'s default with resetStore). */
    internal var store: Store = prefsStore
    internal fun resetStore() { store = prefsStore }
    private var gen = 0
    @Synchronized fun current(): Question { val (f, k, a) = store.read(); return Question(gen, f, k, a) }
    @Synchronized fun token(): Int = gen
    /** A new question replaces any older one entirely (an old rollback snapshot never outlives its question). */
    fun set(sentFor: String, kind: String, again: String = "", durable: Boolean = false): Int {
        var prev = Triple("", "", "")
        val g = synchronized(this) { prev = store.read(); gen++; store.write(sentFor, kind, again); gen }
        // The in-memory update is under the lock; the disk commit is OUTSIDE it, so a main-thread reader or setter never waits for the disk.
        // durable is for queue-thread callers that must have the question on disk before a DB change. A failed commit removes only this question.
        if (durable && !store.sync()) {
            // Put back the older unanswered question (and its snapshot) unless something newer has already replaced ours.
            synchronized(this) { if (gen == g) { gen++; store.write(prev.first, prev.second, prev.third) } }
            throw java.io.IOException("pending question not saved")
        }
        return g
    }
    @Synchronized fun clearIf(token: Int): Boolean { if (gen != token) return false; gen++; store.write("", "", ""); return true }
    @Synchronized fun clearAll() { gen++; store.write("", "", "") }
}

/** Damage reports already handed to the mail app whose "sent" mark did not land (or has not yet). Kept on disk so they are retried, never re-sent. */
object DamageMarks {
    interface MarkStore { fun read(): String; fun write(v: String); fun sync(): Boolean }
    private val prefsStore = object : MarkStore {
        override fun read() = Prefs.damageMarks
        override fun write(v: String) { Prefs.damageMarks = v }
        override fun sync() = Prefs.syncDamageMarks()
    }
    internal var store: MarkStore = prefsStore
    internal fun resetStore() { store = prefsStore }
    // Entries: "12" = the hand-off returned true (HANDED); "12?" = about to hand off (HANDING). A HANDING entry that survives means the app died or errored
    // inside the hand-off: nobody knows whether a mail exists, so it is never auto-marked; the queue row asks the user.
    private fun entries() = store.read().split(",").filter { it.isNotBlank() }
    @Synchronized fun pending(): Set<Long> = entries().filter { !it.endsWith("?") }.mapNotNull { it.toLongOrNull() }.toSet()
    @Synchronized fun handing(): Set<Long> = entries().filter { it.endsWith("?") }.mapNotNull { it.dropLast(1).toLongOrNull() }.toSet()
    private fun without(id: Long) = entries().filter { it != "$id" && it != "$id?" }
    /** HANDING, committed to disk (call on the Db queue thread, before the hand-off): false when it could not be saved, and then nothing is handed off. */
    fun beginHandOffDurable(id: Long): Boolean {
        // The in-memory edit is under the lock (cheap); the disk commit is OUTSIDE it, so a main-thread pending()/handing() never waits for the disk.
        synchronized(this) { store.write((without(id) + "$id?").joinToString(",")) }
        if (store.sync()) return true
        synchronized(this) { if (entries().contains("$id?")) store.write(without(id).joinToString(",")) }   // nothing is handed off: take our own mark back
        return false
    }
    @Synchronized fun handedOff(id: Long) { store.write((without(id) + "$id").joinToString(",")) }
    @Synchronized fun remove(id: Long) { store.write(without(id).joinToString(",")) }
    @Synchronized fun clearAll() { store.write("") }
    /** Marks one report sent on the Db queue; keeps it pending when that fails. */
    fun settle(ctx: android.content.Context, id: Long, quiet: Boolean = false, done: () -> Unit = {}) {
        DbQ.run(ctx, fail = { if (!quiet) toast(ctx, "The email is in your mail app, but BumpLog could not mark it sent yet. It will try again when the app opens or the damage list opens; do not send it again.") },
            work = { it.markDamageSent(id); remove(id) }) { done() }
    }
    /** Retries every HANDED mark (never a HANDING one). Called when the app comes to the front. */
    fun retryAll(ctx: android.content.Context) { pending().forEach { settle(ctx, it, quiet = true) } }
}

object TabSnap {
    val home = SnapSlot<HomeSnap>(); val review = SnapSlot<ReviewSnap>(); val stats = SnapSlot<StatsSnap>(); val reported = SnapSlot<ReportedSnap>()
    @Volatile var homeGeo: GeoSnap? = null; @Volatile var statsGeo: GeoSnap? = null
    fun clear() { home.clear(); review.clear(); stats.clear(); reported.clear(); homeGeo = null; statsGeo = null }
}
