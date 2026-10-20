package com.bumplog

import androidx.compose.ui.unit.Dp
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun clock(ts: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ts))
fun dayTime(ts: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ts))

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

/** Opens the mail app with the prepared message. Returns true when the handoff worked. */
fun handOffMail(ctx: Context, i: Intent): Boolean {
    if (Prefs.activeAddress.isBlank()) { toast(ctx, "Set your assistant address first (Settings > Assistant)."); return false }
    return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    catch (e: Exception) { toast(ctx, "No mail app found."); false }
}

/** Hands the setup email to the mail app and remembers that it went out, so the setup screen picks up at the check-with-you step even if the app was killed in between. */
fun sendSetupMail(ctx: android.content.Context): Boolean {
    val ok = handOffMail(ctx, Reports.setupMail(ctx))
    if (ok) Prefs.setupEmailSent = true
    return ok
}

@Composable
private fun LoadingSpots(tick: Int, load: (Db) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(tick) { load(Db.get(ctx)) }
}

fun spotIcon(s: Spot) = if (s.review == "DISMISSED") Icons.Default.Close else if (s.reportStatus == "SENT") Icons.Default.CheckCircle else if (s.severe) Icons.Default.Warning else Icons.Default.Place
@Composable fun spotColor(s: Spot): Color { val p = LocalPal.current; return if (s.reportStatus != "SENT" && s.severe) p.yellow else p.text }

@Composable
fun SpotRow(s: Spot, tag: String? = null, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(p.card2), contentAlignment = Alignment.Center) {
            Icon(spotIcon(s), null, tint = spotColor(s), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(s.title, 17, FontWeight.Bold)
            Muted("${clock(s.lastTs)}  -  ${s.speedMph.toInt()} mph" + if (s.hitCount > 1) "  -  ${s.hitCount} hits" else "")
        }
        if (tag != null) Chip(tag) else Txt("%.2fg".format(s.maxG), 17, FontWeight.Bold, spotColor(s))
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(live: LiveState, openSpot: (Long) -> Unit, openDamage: () -> Unit, permsOk: Boolean, askPerms: () -> Unit, togglePassenger: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var recent by remember { mutableStateOf(listOf<Spot>()) }
    var today by remember { mutableStateOf(0) }
    LoadingSpots(live.tick) { db -> recent = db.allSpots().take(6); val id = homeDriveId(live.running, live.driveId, Prefs.lastDriveId); today = if (id > 0) db.hitsInDrive(id) else 0 }
    val status = when {
        !Prefs.detectionEnabled -> "Detection off" to p.muted
        !permsOk -> "Needs permission" to p.muted
        !live.running -> "Starting..." to p.muted
        live.paused -> { val m = (Prefs.passengerLeftMs() + 59_999) / 60_000; "Paused - passenger, ${m / 60}h ${m % 60}m left" to p.muted }
        !live.gps -> "Waiting for GPS" to p.muted
        else -> "Detecting" to p.text
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val viewH = maxHeight
    val dens = androidx.compose.ui.platform.LocalDensity.current
    // Slight fixed padding top and bottom; the gaps between the blocks flex with the screen height (blocks never change size).
    var gap by remember { mutableStateOf(10.dp) }
    var extraAct by remember { mutableStateOf(0.dp) }   // extra space above the action buttons
    var extraTop by remember { mutableStateOf(0.dp) }   // extra space above everything
    val nGaps = if (permsOk) 3 else 4
    Column(Modifier.fillMaxSize().pane("Home").verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 24.dp + extraTop, bottom = 6.dp)) {
      Column(Modifier.onSizeChanged {
          val h = with(dens) { it.height.toDp() }
          val fixed = h - gap * nGaps - extraAct
          val free = viewH - 30.dp - fixed
          var g: Dp; var ea = 0.dp; var et = 0.dp
          g = (free / nGaps).coerceIn(4.dp, 16.dp); ea = (free - g * nGaps).coerceIn(0.dp, 32.dp)
          if (kotlin.math.abs((g - gap).value) > 0.5f) gap = g
          if (kotlin.math.abs((ea - extraAct).value) > 0.5f) extraAct = ea
          if (kotlin.math.abs((et - extraTop).value) > 0.5f) extraTop = et
      }, verticalArrangement = Arrangement.spacedBy(gap)) {
        val detOn = Prefs.detectionEnabled
        val togglePower = {
            Prefs.detectionEnabled = !detOn
            if (detOn) Prefs.passenger = false // Power off always returns to Driver
            // Off is a full stop: end an active drive so GPS, sensors and the status notification shut down too.
            if (detOn && live.running) ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_STOP))
            // On is the master switch: logging starts right away and keeps running until Power is turned off.
            if (!detOn) try { DriveService.start(ctx) } catch (e: Exception) { }
            Live.bump()
        }
        val isPassenger = Prefs.passenger || live.paused
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val seg = listOf(false to "DRIVER" to "Logging hits", true to "PASSENGER" to "Not logging")
            @Composable fun Seg(k: Pair<Pair<Boolean, String>, String>, mod: Modifier) {
                val (mode, title) = k.first
                val on = isPassenger == mode
                val segBg by androidx.compose.animation.animateColorAsState(if (on) p.accent else p.card, androidx.compose.animation.core.tween(160), label = "seg")
                Column(
                    mod.fillMaxHeight().alpha(if (detOn) 1f else 0.35f).clip(RoundedCornerShape(20.dp))
                        .background(segBg)
                        .clickable(enabled = detOn, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { if (!on) togglePassenger() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Txt(title, 20, FontWeight.Bold, if (on) p.onAccent else p.text)
                    Txt(k.second, 13, FontWeight.Normal, if (on) p.onAccent else p.muted)
                }
            }
            Seg(seg[0], Modifier.weight(1f))
            Seg(seg[1], Modifier.weight(1f))
            PowerButton(detOn, togglePower)
        }
        if (!permsOk) Card(color = p.card2) {
            Txt("Allow location and notifications", 17, FontWeight.Bold)
            Muted("BumpLog needs these to log hits and warn you. Location stays on this phone.")
            Spacer(Modifier.height(10.dp)); SmallButton("Allow", Modifier.fillMaxWidth(), outline = false, onClick = askPerms)
        }
        val liveOn = detOn && !isPassenger // Power off and Passenger both freeze Live Data at dimmed zeros
        val dimA = Modifier.alpha(if (liveOn) 1f else 0.35f)
        val speedTxt = if (liveOn) "${live.speedMph.toInt()}" else "0"
        Card(dimA, pad = 14.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Muted("LIVE DATA", 13, Modifier.asHeading())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(verticalAlignment = Alignment.Bottom) { Txt(speedTxt, 44, FontWeight.Bold); Spacer(Modifier.width(4.dp)); Txt("mph", 16, FontWeight.Normal, p.muted, Modifier.padding(bottom = 7.dp)) } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(verticalAlignment = Alignment.Bottom) { Txt("%.1f".format(if (liveOn) live.lastG else 0f), 44, FontWeight.Bold); Spacer(Modifier.width(4.dp)); Txt("g", 16, FontWeight.Normal, p.muted, Modifier.padding(bottom = 7.dp)) } }
                }
                Wave(if (liveOn) live.wave else FloatArray(0), Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp), color = p.accent)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Dot(status.second); Spacer(Modifier.width(8.dp)); Txt(status.first, 15, FontWeight.Medium, status.second) }
            }
        }
        @Composable fun Tile(label: String, value: String, unit: String? = null, mod: Modifier) = Card(mod.fillMaxHeight(), pad = 10.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) { Txt(label, 11, FontWeight.Normal, p.muted, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                Row(verticalAlignment = Alignment.Bottom) { Txt(value, 30, FontWeight.Bold); if (unit != null) { Spacer(Modifier.width(3.dp)); Txt(unit, 14, FontWeight.Normal, p.muted, Modifier.padding(bottom = 4.dp)) } }
            }
        }
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(homeDriveLabel(live.running), "$today", null, Modifier.weight(1f))
            Tile(homeHardestHitLabel(live.running), "%.2f".format(live.hardestHitG), "g", Modifier.weight(1f))
        }
        Box(Modifier.padding(top = extraAct)) { ActionButtonPair("Report last hit", Icons.Default.Warning, {
            val s = recent.firstOrNull(); if (s == null) toast(ctx, "No hits logged yet.") else openSpot(s.id)
        }, "File a damage report", openDamage) }
      }
    }
    }
}

val PowerOrange = Color(0xFFFF8A1F)

// ---------------------------------------------------------------- Severe hit alert (opens from the notification)

@Composable
fun AlertScreen(spotId: Long, tick: Int, onDone: () -> Unit, onConfirmed: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<Spot?>(null) }
    LoadingSpots(tick) { db -> s = db.spot(spotId) }
    val spot = s ?: return
    Column(Modifier.fillMaxSize().pane("Heads-up").verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        val alertColor = if (spot.severe) p.yellow else p.accent
        Txt("BUMP DETECTED", 22, FontWeight.Bold, alertColor)
        Spacer(Modifier.height(12.dp))
        Icon(Icons.Default.Warning, null, tint = alertColor, modifier = Modifier.size(110.dp))
        Txt("%.2fg".format(spot.maxG), 76, FontWeight.Bold)
        Muted("${spot.title}  -  ${spot.speedMph.toInt()} mph", 17)
        Spacer(Modifier.height(14.dp))
        Chip("Audio alert played", p.muted)
        Spacer(Modifier.height(20.dp))
        Txt("Real road defect?", 20, FontWeight.Bold, p.muted)
        Spacer(Modifier.height(14.dp))
        Dropdown("Defect type", spot.type.name, DefectType.values().map { it.name to it.label }, Modifier.fillMaxWidth()) { Db.get(ctx).setType(spot.id, DefectType.parse(it)); Live.bump() }
        Spacer(Modifier.height(14.dp))
        BigButton("Yes", Icons.Default.Check) { val d = Db.get(ctx); d.confirmForTrip(spot.id); ConfirmationNotice.show(d.spot(spot.id)?.ref() ?: "", true); Live.bump(); onConfirmed() }
        Spacer(Modifier.height(12.dp))
        BigButton("No", Icons.Default.Close, filled = false) { val d = Db.get(ctx); d.setReview(spot.id, "DISMISSED"); d.setWarn(spot.id, false); Live.bump(); onDone() }
        Spacer(Modifier.height(12.dp))
        BigButton("Warn me here forever", Icons.Default.Place, filled = false) { Db.get(ctx).setWarn(spot.id, true); Live.bump(); onDone() }
        Spacer(Modifier.height(16.dp))
        Muted("Hit saved automatically", 15)
    }
}

// ---------------------------------------------------------------- Review at destination


/** Time for a hit row: clock if it happened today, weekday within a week, date after that. */
fun hitWhen(ts: Long): String {
    val now = java.util.Calendar.getInstance(); val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> clock(ts)
        now.timeInMillis - ts <= 7 * 24 * 3_600_000L -> SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(ts))
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ts))
    }
}

/** A hit counts as reported when its spot was sent and the hit happened at or before the send (sentTs 0 = restored from a backup, all of its hits count). */
fun hitReported(h: Hit, s: Spot) = s.reportStatus != "NONE" && (s.sentTs == 0L || h.ts <= s.sentTs)

/** One recent hit. Hits and spots are separate: a spot is a place, a hit is one pass over it. */
@Composable
fun HitRow(h: Hit, s: Spot, gray: Boolean = false, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().alpha(if (gray) 0.45f else 1f).clip(RoundedCornerShape(18.dp)).background(p.card).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(s.title, 17, FontWeight.Bold)
            Muted("${hitWhen(h.ts)}  -  ${h.speedMph.toInt()} mph  -  ${Reports.statusLabel(s)}")
            // The public ref once the spot is confirmed (old reports show their old code). Wraps at big fonts, never ellipsizes.
            if (s.review == "CONFIRMED" || s.reportStatus != "NONE") Txt(s.ref(), 12, FontWeight.Normal, p.muted, maxLines = 2)
        }
        Txt("%.2fg".format(h.peakG), 17, FontWeight.Bold, if (h.peakG >= Prefs.severeG && s.reportStatus != "SENT") p.yellow else p.text)
    }
}

@Composable
fun ReviewScreen(live: LiveState, openSpot: (Long) -> Unit, openSetup: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var pendingCount by remember { mutableStateOf(0) }
    var saved by remember { mutableStateOf(0) }
    var allHits by remember { mutableStateOf(listOf<Pair<Hit, Spot>>()) }
    LoadingSpots(live.tick) { db ->
        // Hits are listed one by one (never merged by spot). Hits logged after their spot was reported are kept in the data but not listed.
        val shown = db.hits(200).mapNotNull { h -> db.spot(h.spotId)?.takeIf { !it.reportId.startsWith("TEST-") && (it.reportStatus == "NONE" || hitReported(h, it)) }?.let { h to it } }
        // A spot still waiting on you must always be reachable, however old its hits are: add its latest hit if it fell outside the recent window.
        val missing = (db.pendingSpots() + db.readyToSend()).distinctBy { it.id }
            .filter { sp -> !sp.reportId.startsWith("TEST-") && shown.none { it.second.id == sp.id } }
            .mapNotNull { sp -> db.lastHitOf(sp.id)?.takeIf { sp.reportStatus == "NONE" }?.let { it to sp } }
        allHits = (shown + missing).sortedByDescending { it.first.ts }
        pendingCount = db.pendingSpots().size
        saved = db.hitsSince(Db.startOfToday())
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().pane("Review").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(pad = 20.dp) {
            Muted("Today", 15, Modifier.fillMaxWidth().asHeading(), TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Txt("$saved", 54, FontWeight.Bold); Muted("hits saved", 16) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Txt("$pendingCount", 54, FontWeight.Bold, if (pendingCount == 0) p.text else p.accentText(p.card)); Muted("to review", 16) }
            }
        }
        // The drive being shown is the live drive while running, otherwise the last drive that started, even if it logged no hits.
        val lastDrive = if (live.running) live.driveId else Prefs.lastDriveId
        val (cur, older) = allHits.partition { it.first.driveId == lastDrive }
        if (live.running || lastDrive > 0) {
            Muted(if (live.running) "THIS DRIVE" else "LAST DRIVE", 14, Modifier.padding(start = 4.dp, top = 6.dp).asHeading())
            if (cur.isEmpty()) Muted(if (live.running) "No hits yet. Start a drive and they show up here." else "No hits in your last drive.", 14, Modifier.padding(start = 4.dp))
        }
        cur.forEach { (h, sp) -> ReviewHitRow(h, sp, snack) { openSpot(sp.id) } }
        if (older.isNotEmpty()) Muted("OLDER HITS", 14, Modifier.padding(start = 4.dp, top = 6.dp).asHeading())
        older.forEach { (h, sp) -> ReviewHitRow(h, sp, snack) { openSpot(sp.id) } }
        Card(color = p.card, pad = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Email, null, tint = p.muted, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
                val a = Prefs.activeAddress
                Muted(if (a.isBlank()) "No assistant address set yet" else "Sends to $a", 15)
            }
        }
    }
    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

/** One Review row. A "To review" card can be swiped away to dismiss it, with Undo; anything confirmed stays put. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReviewHitRow(h: Hit, sp: Spot, snack: SnackbarHostState, openSpot: (Long) -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val canDismiss = sp.review == "PENDING"
    androidx.compose.runtime.key(sp.id) {
        val st = rememberSwipeToDismissBoxState(confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart && canDismiss })
        LaunchedEffect(st.currentValue) {
            if (st.currentValue == SwipeToDismissBoxValue.EndToStart) {
                val wasWarn = sp.warn
                val db = Db.get(ctx)
                db.setReview(sp.id, "DISMISSED"); db.setWarn(sp.id, false); Live.bump()
                st.reset()
                val r = snack.showSnackbar("Spot dismissed.", actionLabel = "Undo")
                if (r == SnackbarResult.ActionPerformed) { db.setReview(sp.id, "PENDING"); db.setWarn(sp.id, wasWarn); Live.bump() }
            }
        }
        SwipeToDismissBox(
            state = st,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = canDismiss,
            backgroundContent = {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(p.card2).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    Txt("Dismiss", 16, FontWeight.Bold, p.muted)
                }
            },
        ) { HitRow(h, sp, gray = hitReported(h, sp)) { openSpot(sp.id) } }
    }
}

// ---------------------------------------------------------------- One spot

@Composable
fun SpotScreen(spotId: Long, tick: Int, back: () -> Unit, openDamage: (Long) -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<Spot?>(null) }
    LoadingSpots(tick) { db -> s = db.spot(spotId) }
    val spot = s ?: return
    val db = Db.get(ctx)
    var confirmFixed by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().pane("Spot").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth()) {
            BackRow(spot.title, back)
            // Mark fixed lives in this menu, and only for a confirmed spot.
            if (spot.review == "CONFIRMED") Box(Modifier.align(Alignment.CenterEnd)) {
                IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "More actions", tint = p.muted) }
                DropdownMenu(menuOpen, { menuOpen = false }, modifier = Modifier.background(p.card2)) {
                    DropdownMenuItem(text = { Txt("Mark fixed", 16) }, onClick = { menuOpen = false; confirmFixed = true })
                }
            }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("%.2fg".format(spot.maxG), 40, FontWeight.Bold, spotColor(spot)); Spacer(Modifier.weight(1f)); Chip(Reports.statusLabel(spot))
            }
            Muted("${spot.hitCount} hit(s)  -  last ${dayTime(spot.lastTs)}  -  ${spot.speedMph.toInt()} mph")
            // The public ref shows once the spot is confirmed: Report # for new reports, the old code for old ones. Raw coordinates stay either way.
            Muted((if (spot.review == "CONFIRMED" || spot.reportStatus != "NONE") spot.ref() + "  -  " else "") + "%.5f, %.5f".format(spot.lat, spot.lon))
            if (spot.cleanPasses >= 2 && spot.review != "FIXED") { Spacer(Modifier.height(6.dp)); Txt("Drove past ${spot.cleanPasses} times without a hit. Fixed?", 14, color = p.muted) }
        }
        Dropdown(if (spot.typeUser) "Defect type" else "Defect type (guess - pick to confirm)", spot.type.name, DefectType.values().map { it.name to it.label }) { db.setType(spot.id, DefectType.parse(it)); Live.bump() }
        Toggle("Warn me here", spot.warn, "Heads-up before this spot on future drives") { db.setWarn(spot.id, it); Live.bump() }
        BigButton("Confirm as real defect", Icons.Default.Check, enabled = spot.review != "CONFIRMED") {
            db.confirmForTrip(spot.id)
            ConfirmationNotice.show(db.spot(spot.id)?.ref() ?: "", true)
            Live.bump()
            back()
        }
        if (TripReportPolicy.canSendNow(spot, Live.state.value.running, Live.state.value.driveId))
            BigButton("Send this report", Icons.Default.Send, filled = false) {
                if (handOffMail(ctx, Reports.reportsMail(ctx, listOf(spot)))) { db.markSent(spot.id, System.currentTimeMillis()); Live.bump() }
            }
        if (spot.review == "DISMISSED" || spot.review == "FIXED")
            BigButton("Restore this spot", Icons.Default.Check, filled = false) { db.setReview(spot.id, "PENDING"); db.setWarn(spot.id, spot.severe); db.update(spot.id, "fixed_after_report" to false, "fixed_ts" to 0L); Live.bump() }
        if (confirmFixed)
            AlertDialog(onDismissRequest = { confirmFixed = false },
                title = { Text("Mark as fixed?") },
                text = { Text("It stops warning you. You can restore it any time from this screen.") },
                confirmButton = { TextButton({ db.markFixed(spot.id, System.currentTimeMillis()); Celebrate.trigger(); confirmFixed = false; Live.bump(); back() }) { Text("Mark fixed") } },
                dismissButton = { TextButton({ confirmFixed = false }) { Text("Cancel") } })
        SmallButton("This one got me: damage report", Modifier.fillMaxWidth()) { openDamage(spot.id) }
        if (spot.reportStatus == "SENT") Muted("Sent ${dayTime(spot.sentTs)} - handed to your mail app. Press send there.", 12)
    }
}

// ---------------------------------------------------------------- Damage report

@Composable
private fun PhotoButtons(onPhotos: (List<android.net.Uri>) -> Unit) {
    val ctx = LocalContext.current
    var camUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val gallery = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { if (it.isNotEmpty()) onPhotos(it) }
    val cam = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok -> if (ok) camUri?.let { onPhotos(listOf(it)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallButton("Take photo", Modifier.weight(1f)) {
            val f = java.io.File(java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }, "cam-${System.currentTimeMillis()}.jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            camUri = u
            try { cam.launch(u) } catch (e: Exception) { toast(ctx, "No camera app found.") }
        }
        SmallButton("From gallery", Modifier.weight(1f)) { gallery.launch("image/*") }
    }
}

@Composable
fun DamageScreen(preSpot: Long, tick: Int, back: () -> Unit, openQueue: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var queued by remember { mutableStateOf(0) }
    LoadingSpots(tick) { db -> hits = db.hits(60); queued = db.queuedDamages().size }
    var picked by remember { mutableStateOf(0L) }
    var cat by remember { mutableStateOf("Tire") }
    var note by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(listOf<android.net.Uri>()) }
    LaunchedEffect(hits, preSpot) { if (picked == 0L) picked = hits.firstOrNull { it.spotId == preSpot }?.id ?: 0L }
    val recentCut = System.currentTimeMillis() - 20 * 60_000L
    val recent = hits.filter { it.ts >= recentCut }
    val earlier = hits.filter { it.ts < recentCut }.take(10)
    @Composable fun hitRow(h: Hit) {
        val sel = h.id == picked
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (sel) p.card2 else p.card)
            .let { if (sel) it.border(2.dp, p.accent, RoundedCornerShape(16.dp)) else it }
            .clickable { picked = h.id }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Txt(dayTime(h.ts), 16, FontWeight.Bold); Muted("${h.speedMph.toInt()} mph  -  ${h.type.label}") }
            Txt("%.2fg".format(h.peakG), 17, FontWeight.Bold, if (h.peakG >= Prefs.severeG) p.yellow else p.text)
        }
    }
    fun build(): Damage? {
        val h = hits.firstOrNull { it.id == picked } ?: return null
        val d = Db.get(ctx).addDamage(h.id, h.spotId, cat, note, System.currentTimeMillis())
        if (photos.isNotEmpty()) DamagePhotos.add(ctx, d.reportId, photos)
        return d
    }
    Column(Modifier.fillMaxSize().pane("File damage report").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted("Pick the impact that hurt your car. Your assistant works out where the claim should go.", 14, Modifier.asHeading())
        if (hits.isEmpty()) Muted("No impacts logged yet.")
        if (recent.isNotEmpty()) { Txt("Last 20 minutes", 16, FontWeight.Bold); recent.forEach { hitRow(it) } }
        else if (hits.isNotEmpty()) Muted("Nothing in the last 20 minutes. Earlier impacts:", 13)
        if (earlier.isNotEmpty() && (recent.isEmpty() || true)) { if (recent.isNotEmpty()) Txt("Earlier", 16, FontWeight.Bold); earlier.forEach { hitRow(it) } }
        Dropdown("What was damaged", cat, listOf("Tire", "Wheel or rim", "Suspension", "Alignment", "Body or undercarriage", "Other").map { it to it }) { cat = it }
        OutlinedTextField(note, { note = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.card2, focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent))
        Txt("Photos", 16, FontWeight.Bold)
        Muted(if (photos.isEmpty()) "Optional. Add them now, or choose to send at the end of the drive and add them then." else "${photos.size} photo(s) added.", 13)
        PhotoButtons { photos = photos + it }
        BigButton("Send now", Icons.Default.Send, enabled = picked != 0L) {
            val d = build() ?: return@BigButton
            val db = Db.get(ctx)
            val h = hits.firstOrNull { it.id == picked }
            if (handOffMail(ctx, Reports.damageMail(ctx, d, h, db.spot(d.spotId)))) { db.markDamageSent(d.id); toast(ctx, "Confirmed. ${d.ref(h)}. Handed to your mail app - press send there."); back() }
        }
        BigButton("Send at end of drive", Icons.Default.Check, filled = false, enabled = picked != 0L) {
            val d = build() ?: return@BigButton
            Db.get(ctx).setDamageStatus(d.id, "QUEUED"); Live.bump()
            toast(ctx, "Confirmed. ${d.ref(hits.firstOrNull { it.id == picked })}. You'll get a reminder when the drive ends to add photos and send."); back()
        }
        if (queued > 0) SmallButton("Waiting to send ($queued)", Modifier.fillMaxWidth(), onClick = openQueue)
        Muted("Damage reports go to your assistant, not to the road authority, and are kept separate from road defect reports. If you already reported this spot, that is included.", 12)
    }
}

@Composable
fun DamageQueueScreen(tick: Int, back: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(listOf<Damage>()) }
    var n by remember { mutableStateOf(0) }
    LoadingSpots(tick + n) { db -> list = db.queuedDamages() }
    Column(Modifier.fillMaxSize().pane("Damage reports waiting").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackRow("Damage reports waiting", back)
        if (list.isEmpty()) Muted("Nothing waiting.")
        list.forEach { d ->
            Card {
                Txt("${d.category} - ${dayTime(d.ts)}", 17, FontWeight.Bold)
                Muted(d.ref(Db.get(ctx).hit(d.hitId)), 13)
                if (d.note.isNotBlank()) Muted(d.note, 13)
                Muted("${DamagePhotos.files(ctx, d.reportId).size} photo(s) attached", 13)
                Spacer(Modifier.height(8.dp))
                PhotoButtons { DamagePhotos.add(ctx, d.reportId, it); n++ }
                Spacer(Modifier.height(8.dp))
                BigButton("Send", Icons.Default.Send) {
                    val db = Db.get(ctx)
                    val h = db.hit(d.hitId)
                    if (handOffMail(ctx, Reports.damageMail(ctx, d, h, db.spot(d.spotId)))) { db.markDamageSent(d.id); n++; Live.bump() }
                }
            }
        }
        SmallButton("Back", Modifier.fillMaxWidth(), onClick = back)
    }
}

// ---------------------------------------------------------------- Stats

@Composable
fun StatsScreen(tick: Int, openSpot: (Long) -> Unit) {
    val p = LocalPal.current
    var sc by remember { mutableStateOf<Db.Score?>(null) }
    var top by remember { mutableStateOf(listOf<Spot>()) }
    var big by remember { mutableStateOf<Pair<Hit, Spot>?>(null) }
    var byType by remember { mutableStateOf(listOf<Pair<DefectType?, Int>>()) }
    LoadingSpots(tick) { db -> sc = db.score(); top = db.topSaves(); byType = db.savesByType(); big = db.largestImpact() }
    val s = sc ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val viewH = maxHeight
    val dens = androidx.compose.ui.platform.LocalDensity.current
    // Slight fixed padding top and bottom; the gaps between the summary cards flex with the screen height (cards and text never change size).
    var gap by remember { mutableStateOf(10.dp) }
    Column(Modifier.fillMaxSize().pane("Stats").verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = gap, bottom = gap), verticalArrangement = Arrangement.spacedBy(gap)) {
      Column(Modifier.onSizeChanged {
          val h = with(dens) { it.height.toDp() }
          val cards = h - gap * 3
          // One spacing S: above the first card, between cards, and below the last card (above the nav bar).
          val want = ((viewH - cards) / 5).coerceIn(8.dp, 16.dp)
          if (kotlin.math.abs((want - gap).value) > 0.5f) gap = want
      }, verticalArrangement = Arrangement.spacedBy(gap)) {
        Card(pad = 20.dp) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Muted("TIMES AVOIDED", 13, Modifier.asHeading())
            Txt("${s.saves}", 56, FontWeight.Bold)
            Muted("Counts each time a warned spot's heads-up alert fired and you passed it again.", 13, align = TextAlign.Center)
        } }
        Card { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Muted("REPORTS SENT", 12); Txt("${s.sent}", 34, FontWeight.Bold) } }
        Card(pad = 16.dp) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Muted("FIXED AFTER YOU REPORTED", 12)
            Txt("${s.fixedAfterReport}", 34, FontWeight.Bold)
            Muted("Spots you reported that were later marked fixed.", 12, align = TextAlign.Center)
        } }
        val bigHit = big
        Card(if (bigHit != null) Modifier.clickable { openSpot(bigHit.second.id) } else Modifier, pad = 16.dp) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Muted("HARDEST HIT", 12)
            Txt("%.2fg".format(bigHit?.first?.peakG ?: 0f), 34, FontWeight.Bold)
            if (bigHit != null) {
                val what = if (!bigHit.second.typeUser) "road defect" else bigHit.second.type.spoken
                Muted("${dayTime(bigHit.first.ts)} - $what at %d mph. Tap to open the spot.".format(bigHit.first.speedMph.toInt()), 12, align = TextAlign.Center)
            } else Muted("No hits yet.", 12, align = TextAlign.Center)
        } }
      }
        if (byType.isNotEmpty()) Card {
            Txt("Avoided", 18, FontWeight.Bold)
            byType.sortedByDescending { it.second }.forEach { (t, n) ->
                val name = t?.avoidedName ?: "Other (type not confirmed)"
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) { Txt(name, 16, modifier = Modifier.weight(1f)); Txt("$n", 16, FontWeight.Bold) }
            }
        }
        if (top.isNotEmpty()) {
            Txt("Most avoided", 20, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            top.forEach { sp ->
                val what = if (!sp.typeUser) "road defect" else sp.type.spoken
                Card(pad = 14.dp) { Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Most avoided $what", 16, modifier = Modifier.weight(1f))
                    Txt(if (sp.saves == 1) "1 time" else "${sp.saves} times", 16, FontWeight.Bold)
                } }
            }
        }
    }    }
}

@Composable
fun PowerButton(detOn: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(20.dp)
    val reduce = remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val sunk = detOn || pressed
    val depth by androidx.compose.animation.core.animateDpAsState(if (sunk) 0.dp else 8.dp, androidx.compose.animation.core.tween(if (reduce) 0 else 90), label = "depth")
    // Ring is darkened in light mode so it keeps 3:1 against the light background.
    val ringColor = if (p.dark) PowerOrange else Color(0xFFC45E00)
    val raisedTop = if (p.dark) Color(0xFF5E636D) else Color(0xFFFFFFFF)
    val raisedBot = if (p.dark) Color(0xFF3B3F47) else Color(0xFFE2E2E8)
    val insetTop = if (p.dark) Color(0xFF111316) else Color(0xFFCFCFD6)
    val insetBot = if (p.dark) Color(0xFF26292F) else Color(0xFFE6E6EB)
    val edge = if (p.dark) listOf(Color(0xFF8A8F9A), Color(0xFF1B1D22)) else listOf(Color(0xFFFFFFFF), Color(0xFFB4B4BE))
    val m = Modifier.size(76.dp)
        .shadow(depth, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(if (sunk) listOf(insetTop, insetBot) else listOf(raisedTop, raisedBot)))
        .border(if (detOn) 3.dp else 2.dp,
            androidx.compose.ui.graphics.Brush.verticalGradient(if (detOn) listOf(ringColor, ringColor) else edge), shape)
    val c = p.text
    Box(m.clickable(interactionSource = src, indication = null, role = androidx.compose.ui.semantics.Role.Switch, onClick = onClick)
        .semantics { contentDescription = "Bump detection"; stateDescription = if (detOn) "On" else "Off" }, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(40.dp).offset(y = if (sunk) 2.dp else 0.dp)) {
            val w = size.width * 0.09f
            val pad = size.width * 0.16f
            val d = size.width - 2 * pad
            drawArc(c, -60f, 300f, false, androidx.compose.ui.geometry.Offset(pad, pad + size.width * 0.04f), androidx.compose.ui.geometry.Size(d, d), style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width / 2, size.width * 0.08f), androidx.compose.ui.geometry.Offset(size.width / 2, size.width * 0.5f), w, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
