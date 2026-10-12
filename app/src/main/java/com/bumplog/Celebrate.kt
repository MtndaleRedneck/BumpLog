package com.bumplog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** A short balloons-and-streamers celebration over the whole app, for when a reported spot is marked fixed. */
object Celebrate {
    var run by mutableIntStateOf(0)
        private set
    fun trigger() { run++ }
}

private val party = listOf(0xFFFF5228, 0xFFFFB020, 0xFF3CC878, 0xFF3C9BFF, 0xFFA064FF, 0xFFFF4D8D, 0xFF20C9C9).map { Color(it) }

private class Balloon(val fx: Float, val size: Float, val delay: Float, val phase: Float, val color: Color)
private class Bit(val fx: Float, val delay: Float, val phase: Float, val rot: Float, val spin: Float, val len: Float, val color: Color, val streamer: Boolean)

@Composable
fun CelebrationOverlay() {
    val n = Celebrate.run
    if (n == 0) return
    val prog = remember(n) { Animatable(0f) }
    var done by remember(n) { mutableStateOf(false) }
    LaunchedEffect(n) { prog.animateTo(1f, tween(4200, easing = LinearEasing)); done = true }
    if (done) return
    val dp = LocalDensity.current.density
    val rnd = remember(n) { Random(n * 7919L + 17) }
    val balloons = remember(n) { List(14) { Balloon(0.05f + rnd.nextFloat() * 0.9f, 34f + rnd.nextFloat() * 28f, rnd.nextFloat() * 0.35f, rnd.nextFloat() * 6f, party[rnd.nextInt(party.size)]) } }
    val bits = remember(n) { List(90) { Bit(rnd.nextFloat(), rnd.nextFloat() * 0.45f, rnd.nextFloat() * 6f, rnd.nextFloat() * 360f, 180f + rnd.nextFloat() * 540f, 10f + rnd.nextFloat() * 14f, party[rnd.nextInt(party.size)], it % 6 == 0) } }
    val p = LocalPal.current
    val t = prog.value
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (b in bits) {
                val tc = ((t - b.delay) / (1f - b.delay)).coerceIn(0f, 1f)
                if (tc <= 0f || tc >= 1f) continue
                val x = b.fx * w + sin(tc * 9f + b.phase) * 26f * dp
                val y = -40f * dp + (h + 80f * dp) * tc
                if (b.streamer) {
                    val path = Path().apply {
                        moveTo(x, y)
                        for (k in 1..6) lineTo(x + sin(tc * 12f + b.phase + k) * 10f * dp, y + k * 9f * dp)
                    }
                    drawPath(path, b.color, style = Stroke(width = 4f * dp))
                } else {
                    rotate(b.rot + b.spin * tc, Offset(x, y)) {
                        drawRect(b.color, Offset(x, y), Size(6f * dp, b.len * dp))
                    }
                }
            }
            for (b in balloons) {
                val tb = ((t - b.delay) / (1f - b.delay)).coerceIn(0f, 1f)
                if (tb <= 0f || tb >= 1f) continue
                val sz = b.size * dp
                val x = b.fx * w + sin(tb * 5f + b.phase) * 18f * dp
                val y = h + sz - (h + sz * 3.2f) * tb
                drawLine(Color(0x99FFFFFF), Offset(x + sz / 2, y + sz * 1.2f), Offset(x + sz / 2 + sin(tb * 8f + b.phase) * 6f * dp, y + sz * 1.2f + 40f * dp), strokeWidth = 1.5f * dp)
                drawOval(b.color, Offset(x, y), Size(sz, sz * 1.2f))
                drawOval(Color(0x55FFFFFF), Offset(x + sz * 0.2f, y + sz * 0.15f), Size(sz * 0.22f, sz * 0.3f))
            }
        }
        val a = sin(PI.toFloat() * t).coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.Center).alpha(a).background(p.card, RoundedCornerShape(20.dp)).padding(horizontal = 28.dp, vertical = 16.dp)) {
            Text("Marked fixed", color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}
