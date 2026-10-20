package com.bumplog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

object ConfirmationNotice {
    val shown = MutableStateFlow(0L)
    fun show() { shown.value = System.nanoTime() }
    fun message(fileTrip: Boolean = true) = if (fileTrip)
        "Confirmed. Nothing sends yet. It goes in one email with the rest of your drive, at the end of the trip."
    else "Confirmed. Saved on this phone. Not included in the trip email."
}

/** Visual only: no click target, modal window, or input blocker. */
@Composable fun ConfirmationOverlay() {
    val shown by ConfirmationNotice.shown.collectAsState()
    if (shown == 0L) return
    LaunchedEffect(shown) { delay(2500); if (ConfirmationNotice.shown.value == shown) ConfirmationNotice.shown.value = 0L }
    val p = LocalPal.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(26.dp)).background(p.card).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("✓", 28, FontWeight.Bold, p.accent)
            Txt("Confirmed", 20, FontWeight.Bold)
            Txt("Nothing sends yet. It goes in one email with the rest of your drive, at the end of the trip.", 14,
                color = p.muted, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
