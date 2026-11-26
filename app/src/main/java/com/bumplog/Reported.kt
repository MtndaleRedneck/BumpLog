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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/** The Reported tab: only bumps whose report went to the mail app, with what the city did about it. */

private fun sentDay(ts: Long): String = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date(ts))
private fun shortDay(ts: Long): String = SimpleDateFormat("M/d", Locale.US).format(Date(ts))

@Composable
fun ReportedScreen(tick: Int, openSpot: (Long) -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val first = remember { firstFrame(TabSnap.reported, tick) { ReportedSnap(Db.get(ctx).reportedSpots()) } }
    var reported by remember { mutableStateOf(first?.spots ?: emptyList()) }
    var loaded by remember { mutableStateOf(first != null) }   // false = nothing read yet: a blank pane, not the "nothing reported" text
    val reportedLoad = LoadingSpots(tick) { db -> TabSnap.reported.warm(Stamp.now(tick))?.let { reported = it.spots; loaded = true; return@LoadingSpots }; val st = Stamp.now(tick); val l = withContext(Dispatchers.IO) { db.reportedSpots() }; reported = l; loaded = true; TabSnap.reported.put(st, ReportedSnap(l)) }
    if (!loaded) { NotLoadedPane("Reported", reportedLoad); return }
    var markFor by remember { mutableStateOf<Spot?>(null) }

    markFor?.let { s ->
        AlertDialog(
            onDismissRequest = { markFor = null },
            title = { M3Text(s.title) },
            text = {
                Column {
                    M3Text((if (s.reportNo > 0) "Report #${s.reportNo}" else s.ref()) + (if (s.sentTs > 0) " - sent ${sentDay(s.sentTs)}" else ""))
                    Spacer(Modifier.height(10.dp))
                    M3Text("How did it turn out?")
                    Spacer(Modifier.height(14.dp))
                    val fixedGreen = androidx.compose.ui.graphics.Color(0xFF3CC878)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            // Back to waiting: clears an earlier Fixed / Unclear response / Won't fix mark.
                            onClick = { markFor = null; DbQ.run(ctx, work = { it.markOutcome(s.id, "NONE", 0) }) { Live.bump() } },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, p.text),
                        ) { M3Text("Waiting on the city", color = p.text) }
                        OutlinedButton(
                            onClick = { markFor = null; DbQ.run(ctx, work = { it.markOutcome(s.id, "FIXED", System.currentTimeMillis()) }) { Live.bump() } },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, fixedGreen),
                        ) { M3Text("Fixed", color = fixedGreen) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { markFor = null; DbQ.run(ctx, work = { it.markOutcome(s.id, "UNCLEAR", System.currentTimeMillis()) }) { Live.bump() } },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, p.yellow),
                        ) { M3Text("Unclear response", color = p.yellow) }
                        OutlinedButton(
                            onClick = { markFor = null; DbQ.run(ctx, work = { it.markOutcome(s.id, "WONTFIX", System.currentTimeMillis()) }) { Live.bump() } },
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
        else -> "Waiting on the city" to p.accentText(p.card2)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).clickable(onClick = onMark).padding(14.dp)) {
        Txt(s.title, 17, FontWeight.Bold, modifier = Modifier.clickable(onClick = onOpen))
        Muted((if (s.reportNo > 0) "Report #${s.reportNo}" else s.ref()) + (if (s.sentTs > 0) " - sent ${sentDay(s.sentTs)}" else ""))
        Spacer(Modifier.height(4.dp))
        Txt(status, 15, FontWeight.Medium, color)
        if (s.reportOutcome == "WONTFIX" || (s.reportOutcome != "NONE" && hitAgain)) {
            Spacer(Modifier.height(10.dp))
            // A fresh report with a fresh number; the count is what shows the city this keeps happening.
            var againBusy by remember { mutableStateOf(false) }   // a second tap while the number is being drawn must not burn another report number
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accent).clickable(enabled = !againBusy) {
                againBusy = true
                val now = System.currentTimeMillis()
                // The number is drawn once, together with the change, and the mail carries that stored number. The pending question with its rollback snapshot is
                // saved FIRST: a process death after it and before the change leaves a harmless question over an unchanged row (a rollback of it is a no-op); a
                // death after the change still has the snapshot to roll back. A failed change clears only its own question; a refused handoff rolls back at once.
                var tok = -1   // written on the queue thread, read in done: the generation of this question
                val old = listOf(s.id, s.reportNo, s.reportTs, s.sentTs, s.reportOutcome, s.outcomeTs).joinToString("|")
                DbQ.run(ctx, fail = { PendingAsk.clearIf(tok); againBusy = false; toast(ctx, "That didn't work. Try again.") }, work = { db ->
                    tok = PendingAsk.set(s.id.toString(), "again", old, durable = true)
                    db.reportAgain(s.id, now)
                    db.spot(s.id)?.reportNo ?: error("spot is gone")
                }) { no ->
                    if (handOffMail(ctx, Reports.reportsMail(ctx, listOf(s.copy(reportNo = no, reportTs = now))))) { againBusy = false; Live.bump() }
                    else DbQ.run(ctx, fail = { againBusy = false; Live.bump(); toast(ctx, "That didn't work. Try again.") },
                        work = { it.unReportAgain(s.id, s.reportNo, s.reportTs, s.sentTs, s.reportOutcome, s.outcomeTs); PendingAsk.clearIf(tok) }) { againBusy = false; Live.bump() }
                }
            }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Txt("Report again", 16, FontWeight.Bold, p.onAccent)
            }
            Spacer(Modifier.height(6.dp))
            Muted("Each new report shows the city the count.", 13)
        }
    }
    // The row's tap marks the outcome; the spot itself opens from its title, keeping one gesture per job.
}
