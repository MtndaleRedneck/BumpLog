package com.bumplog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = LocalPal.current.card, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(pad), content = content)
}

@Composable
fun Txt(s: String, size: Int = 16, weight: FontWeight = FontWeight.Normal, color: Color = LocalPal.current.text,
        modifier: Modifier = Modifier, align: TextAlign? = null) =
    Text(s, fontSize = size.sp, fontWeight = weight, color = color, modifier = modifier, textAlign = align)

@Composable
fun Muted(s: String, size: Int = 13, modifier: Modifier = Modifier, align: TextAlign? = null) =
    Txt(s, size, color = LocalPal.current.muted, modifier = modifier, align = align)

@Composable
fun BigButton(label: String, icon: ImageVector? = null, filled: Boolean = true, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(32.dp)
    val m = modifier.fillMaxWidth().height(60.dp).clip(shape)
        .let { if (filled) it.background(if (enabled) p.accent else p.card2) else it.background(p.card2).border(BorderStroke(2.dp, p.accent), shape) }
        .clickable(enabled = enabled, onClick = onClick)
    Row(m, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (filled) p.onAccent else p.text, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)) }
        Txt(label, 20, FontWeight.Bold, if (filled) p.onAccent else p.text)
    }
}

@Composable
fun SmallButton(label: String, modifier: Modifier = Modifier, outline: Boolean = true, onClick: () -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(26.dp)
    Box(modifier.height(52.dp).clip(shape).background(if (outline) p.card2 else p.accent)
        .let { if (outline) it.border(BorderStroke(2.dp, p.accent), shape) else it }.clickable(onClick = onClick),
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
    Card(pad = 14.dp) {
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
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card2).clickable { open = true }.padding(14.dp),
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
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(p.card2).clickable(onClick = onMinus), contentAlignment = Alignment.Center) { Txt("-", 22, FontWeight.Bold, p.accent) }
        Txt(value, 17, FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(p.card2).clickable(onClick = onPlus), contentAlignment = Alignment.Center) { Txt("+", 22, FontWeight.Bold, p.accent) }
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Card(pad = 14.dp) { StepperRow(label, value, onMinus, onPlus) }
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
    val m = modifier.heightIn(min = 48.dp).clip(shape)
        .let {
            when (kind) {
                PillKind.Fill -> it.background(p.accent)
                PillKind.Outline -> it.background(p.card2).border(BorderStroke(2.dp, p.accent), shape)
                PillKind.Danger -> it.background(p.card2).border(BorderStroke(2.dp, red), shape)
            }
        }.clickable(onClick = onClick)
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
}
