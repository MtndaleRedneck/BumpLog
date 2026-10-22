package com.bumplog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Reported tab: only bumps whose report went to the mail app, with what the city did about it. */

private fun sentDay(ts: Long): String = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date(ts))
private fun shortDay(ts: Long): String = SimpleDateFormat("M/d", Locale.US).format(Date(ts))

@Composable
fun ReportedScreen(tick: Int, openSpot: (Long) -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var reported by remember { mutableStateOf(listOf<Spot>()) }
    LaunchedEffect(tick) { reported = Db.get(ctx).reportedSpots() }
    var markFor by remember { mutableStateOf<Spot?>(null) }

    markFor?.let { s ->
        AlertDialog(
            onDismissRequest = { markFor = null },
            title = { M3Text(s.title) },
            text = {
                Column {
                    M3Text("Report #${s.reportNo} - sent ${sentDay(s.sentTs)}")
                    Spacer(Modifier.height(10.dp))
                    M3Text("How did it turn out?")
                    Spacer(Modifier.height(14.dp))
                    val fixedGreen = androidx.compose.ui.graphics.Color(0xFF3CC878)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            // Back to waiting: clears an earlier Fixed / Unclear response / Won't fix mark.
                            onClick = { Db.get(ctx).markOutcome(s.id, "NONE", 0); Live.bump(); markFor = null },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, p.text),
                        ) { M3Text("Waiting on the city", color = p.text) }
                        OutlinedButton(
                            onClick = { Db.get(ctx).markOutcome(s.id, "FIXED", System.currentTimeMillis()); Live.bump(); markFor = null },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, fixedGreen),
                        ) { M3Text("Fixed", color = fixedGreen) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { Db.get(ctx).markOutcome(s.id, "UNCLEAR", System.currentTimeMillis()); Live.bump(); markFor = null },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, p.yellow),
                        ) { M3Text("Unclear response", color = p.yellow) }
                        OutlinedButton(
                            onClick = { Db.get(ctx).markOutcome(s.id, "WONTFIX", System.currentTimeMillis()); Live.bump(); markFor = null },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, p.danger),
                        ) { M3Text("Won't fix", color = p.danger) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Muted("Still broken? You can report it again the next time you hit it.", 14)
                }
            },
            confirmButton = {},
        )
    }

    Column(Modifier.fillMaxSize().pane("Reported").verticalScroll(ScrollMemory.get("Reported")).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt("Reported", 24, FontWeight.Bold, modifier = Modifier.asHeading())
        Muted("Only bumps you reported show up here.", 15)
        if (reported.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Muted("Nothing reported yet. Confirm a bump in Review and send it, and it lands here.", 15, Modifier.fillMaxWidth(), TextAlign.Center)
        }
        reported.forEach { s -> ReportedRow(s, { markFor = s }, { openSpot(s.id) }) }
    }
}

@Composable
private fun ReportedRow(s: Spot, onMark: () -> Unit, onOpen: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val hitAgain = s.outcomeTs > 0 && s.lastTs > s.outcomeTs
    val (status, color) = when (s.reportOutcome) {
        "FIXED" -> "Marked fixed ${shortDay(s.outcomeTs)}" to androidx.compose.ui.graphics.Color(0xFF3CC878)
        "UNCLEAR" -> "Marked unclear response ${shortDay(s.outcomeTs)}" to p.yellow
        "WONTFIX" -> ("Marked won't fix ${shortDay(s.outcomeTs)}" + if (hitAgain) " - hit again" else "") to p.danger
        else -> "Waiting on the city" to p.accentText(p.card)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).clickable(onClick = onMark).padding(14.dp)) {
        Txt(s.title, 17, FontWeight.Bold, modifier = Modifier.clickable(onClick = onOpen))
        Muted("Report #${s.reportNo} - sent ${sentDay(s.sentTs)}")
        Spacer(Modifier.height(4.dp))
        Txt(status, 15, FontWeight.Medium, color)
        if (s.reportOutcome == "WONTFIX" || (s.reportOutcome != "NONE" && hitAgain)) {
            Spacer(Modifier.height(10.dp))
            // A fresh report with a fresh number; the count is what shows the city this keeps happening.
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accent).clickable {
                val now = System.currentTimeMillis()
                val db = Db.get(ctx)
                val next = db.counterNext()
                val draft = s.copy(reportNo = next, reportTs = now)
                if (handOffMail(ctx, Reports.reportsMail(ctx, listOf(draft)))) { db.reportAgain(s.id, now); Live.bump() }
            }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Txt("Report again", 16, FontWeight.Bold, p.onAccent)
            }
            Spacer(Modifier.height(6.dp))
            Muted("Each new report shows the city the count.", 13)
        }
    }
    // The row's tap marks the outcome; the spot itself opens from its title, keeping one gesture per job.
}
