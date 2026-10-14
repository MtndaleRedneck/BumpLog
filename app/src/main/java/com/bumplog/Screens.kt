package com.bumplog

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

@Composable
private fun LoadingSpots(tick: Int, load: (Db) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(tick) { load(Db.get(ctx)) }
}

fun spotIcon(s: Spot) = if (s.review == "DISMISSED") Icons.Default.Close else if (s.reportStatus == "FILED") Icons.Default.CheckCircle else if (s.severe) Icons.Default.Warning else Icons.Default.Place
@Composable fun spotColor(s: Spot): Color { val p = LocalPal.current; return if (s.reportStatus != "FILED" && s.severe) p.yellow else p.text }

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
        !live.running -> "Not driving" to p.muted
        live.paused -> "Paused - passenger" to p.muted
        !live.gps -> "Waiting for GPS" to p.muted
        else -> "Detecting" to p.text
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("BumpLog")
        val detOn = Prefs.detectionEnabled
        val togglePower = {
            Prefs.detectionEnabled = !detOn
            // Off is a full stop: end an active drive so GPS, sensors and the status notification shut down too.
            if (detOn && live.running) ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_STOP))
            Live.bump()
        }
        val isPassenger = Prefs.passenger || live.paused
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val seg = listOf(false to "DRIVER" to "Logging hits", true to "PASSENGER" to "Not logging")
            @Composable fun Seg(k: Pair<Pair<Boolean, String>, String>, mod: Modifier) {
                val (mode, title) = k.first
                val on = isPassenger == mode
                Column(
                    mod.fillMaxHeight().alpha(if (detOn) 1f else 0.35f).clip(RoundedCornerShape(20.dp))
                        .background(if (on) p.accent else p.card)
                        .clickable(enabled = detOn) { if (!on) togglePassenger() },
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
        val dimA = Modifier.alpha(if (detOn) 1f else 0.35f)
        val speedTxt = "${live.speedMph.toInt()}"
        Card(dimA, pad = 14.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Muted("LIVE DATA", 13)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(verticalAlignment = Alignment.Bottom) { Txt(speedTxt, 44, FontWeight.Bold); Spacer(Modifier.width(4.dp)); Txt("mph", 16, FontWeight.Normal, p.muted, Modifier.padding(bottom = 7.dp)) } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(verticalAlignment = Alignment.Bottom) { Txt("%.2f".format(live.lastG), 44, FontWeight.Bold); Spacer(Modifier.width(4.dp)); Txt("g", 16, FontWeight.Normal, p.muted, Modifier.padding(bottom = 7.dp)) } }
                }
                Wave(live.wave, Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp), color = p.accent)
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
        BigButton("Report last hit", Icons.Default.Warning) {
            val s = recent.firstOrNull(); if (s == null) toast(ctx, "No hits logged yet.") else openSpot(s.id)
        }
        SmallButton("File a damage report", Modifier.fillMaxWidth(), onClick = openDamage)
        Txt("Recent", 20, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        if (recent.isEmpty()) Muted("Nothing yet. Start a drive and hits show up here.", 14, Modifier.padding(start = 4.dp))
        recent.forEach { SpotRow(it) { openSpot(it.id) } }
        Spacer(Modifier.height(8.dp))
    }
}

val PowerOrange = Color(0xFFFF8A1F)

// ---------------------------------------------------------------- Severe hit alert (opens from the notification)

@Composable
fun AlertScreen(spotId: Long, tick: Int, onDone: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<Spot?>(null) }
    LoadingSpots(tick) { db -> s = db.spot(spotId) }
    val spot = s ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        BigButton("Yes", Icons.Default.Check) { val d = Db.get(ctx); d.setReview(spot.id, "CONFIRMED"); d.setWarn(spot.id, true); Live.bump(); onDone() }
        Spacer(Modifier.height(12.dp))
        BigButton("No", Icons.Default.Close, filled = false) { val d = Db.get(ctx); d.setReview(spot.id, "DISMISSED"); d.setWarn(spot.id, false); Live.bump(); onDone() }
        Spacer(Modifier.height(12.dp))
        BigButton("Warn me here forever", Icons.Default.Place, filled = false) { Db.get(ctx).setWarn(spot.id, true); Live.bump(); onDone() }
        Spacer(Modifier.height(16.dp))
        Muted("Hit saved automatically", 15)
    }
}

// ---------------------------------------------------------------- Review at destination

@Composable
fun ReviewScreen(live: LiveState, openSpot: (Long) -> Unit, openSetup: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf(listOf<Spot>()) }
    var ready by remember { mutableStateOf(listOf<Spot>()) }
    var sent by remember { mutableStateOf(listOf<Spot>()) }
    var saved by remember { mutableStateOf(0) }
    LoadingSpots(live.tick) { db ->
        pending = db.pendingSpots(); ready = db.readyToSend()
        sent = db.allSpots().filter { it.reportStatus != "NONE" }.take(10)
        saved = db.hitsSince(Db.startOfToday())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Drive complete")
        Card(pad = 20.dp) {
            Muted("Today", 15, Modifier.fillMaxWidth(), TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Txt("$saved", 54, FontWeight.Bold); Muted("hits saved", 16) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Txt("${pending.size}", 54, FontWeight.Bold, if (pending.isEmpty()) p.text else p.accentText(p.card)); Muted("to review", 16) }
            }
        }
        BigButton("Review", Icons.Default.CheckCircle) { pending.firstOrNull()?.let { openSpot(it.id) } ?: toast(ctx, "Nothing left to review.") }
        BigButton("Send saved (${ready.size})", Icons.Default.Send, filled = false, enabled = ready.isNotEmpty()) {
            if (Prefs.activeAddress.isBlank()) { toast(ctx, "Set your assistant address first."); openSetup() }
            else if (handOffMail(ctx, Reports.reportsMail(ctx, ready))) {
                val d = Db.get(ctx); val now = System.currentTimeMillis(); ready.forEach { d.markSent(it.id, now) }; Live.bump()
            }
        }
        Muted("Sent means handed to your mail app. A report is Filed only after your assistant confirms it.", 12, Modifier.padding(horizontal = 4.dp))
        if (pending.isNotEmpty()) Txt("To review", 20, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        pending.forEach { SpotRow(it, tag = it.type.label.substringBefore(" (")) { openSpot(it.id) } }
        if (ready.isNotEmpty()) Txt("Ready to send", 20, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        ready.forEach { SpotRow(it, tag = "Confirmed") { openSpot(it.id) } }
        if (sent.isNotEmpty()) Txt("Sent reports", 20, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        sent.forEach { SpotRow(it, tag = Reports.statusLabel(it)) { openSpot(it.id) } }
        Card(color = p.card, pad = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Email, null, tint = p.muted, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
                val a = Prefs.activeAddress
                Muted(if (a.isBlank()) "No assistant address set yet" else "Sends to $a", 15)
            }
        }
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
    var confirmEnd by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle(spot.title)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("%.2fg".format(spot.maxG), 40, FontWeight.Bold, spotColor(spot)); Spacer(Modifier.weight(1f)); Chip(Reports.statusLabel(spot))
            }
            Muted("${spot.hitCount} hit(s)  -  last ${dayTime(spot.lastTs)}  -  ${spot.speedMph.toInt()} mph")
            Muted("ID ${spot.reportId}  -  %.5f, %.5f".format(spot.lat, spot.lon))
            if (spot.cleanPasses >= 2 && spot.review != "FIXED") { Spacer(Modifier.height(6.dp)); Txt("Drove past ${spot.cleanPasses} times without a hit. Fixed?", 14, color = p.muted) }
        }
        Dropdown(if (spot.typeUser) "Defect type" else "Defect type (guess - pick to confirm)", spot.type.name, DefectType.values().map { it.name to it.label }) { db.setType(spot.id, DefectType.parse(it)); Live.bump() }
        Toggle("Warn me here", spot.warn, "Heads-up before this spot on future drives") { db.setWarn(spot.id, it); Live.bump() }
        BigButton("Confirm as real defect", Icons.Default.Check, enabled = spot.review != "CONFIRMED" || spot.reportStatus == "NONE") { db.setReview(spot.id, "CONFIRMED"); Live.bump() }
        if (spot.review == "CONFIRMED" && spot.reportStatus == "NONE")
            BigButton("Send this report", Icons.Default.Send, filled = false) {
                if (handOffMail(ctx, Reports.reportsMail(ctx, listOf(spot)))) { db.markSent(spot.id, System.currentTimeMillis()); Live.bump() }
            }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallButton("Dismiss", Modifier.weight(1f)) { confirmEnd = "DISMISSED" }
            SmallButton("Mark fixed", Modifier.weight(1f)) { confirmEnd = "FIXED" }
        }
        if (spot.review == "DISMISSED" || spot.review == "FIXED")
            BigButton("Restore this spot", Icons.Default.Check, filled = false) { db.setReview(spot.id, "PENDING"); db.setWarn(spot.id, spot.severe); db.update(spot.id, "fixed_after_report" to false, "fixed_ts" to 0L); Live.bump() }
        confirmEnd?.let { what ->
            AlertDialog(onDismissRequest = { confirmEnd = null },
                title = { Text(if (what == "FIXED") "Mark as fixed?" else "Dismiss this spot?") },
                text = { Text("It stops warning you. You can restore it any time from this screen.") },
                confirmButton = { TextButton({ if (what == "FIXED") { db.markFixed(spot.id, System.currentTimeMillis()); Celebrate.trigger() } else { db.setReview(spot.id, what); db.setWarn(spot.id, false) }; confirmEnd = null; Live.bump(); back() }) { Text(if (what == "FIXED") "Mark fixed" else "Dismiss") } },
                dismissButton = { TextButton({ confirmEnd = null }) { Text("Cancel") } })
        }
        SmallButton("This one got me: damage report", Modifier.fillMaxWidth()) { openDamage(spot.id) }
        if (spot.reportStatus == "FILED") Muted("Filed" + Prefs.filedRef(spot.reportId).let { if (it.isNotEmpty()) ", reference $it" else "" } + ".", 12)
        if (spot.reportStatus == "SENT") Muted("Sent ${dayTime(spot.sentTs)} means handed to your mail app. It becomes Filed when your assistant confirms.", 12)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("File damage report")
        Muted("Pick the impact that hurt your car. Your assistant works out where the claim should go.", 14)
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
            if (handOffMail(ctx, Reports.damageMail(ctx, d, h, db.spot(d.spotId)))) { db.markDamageSent(d.id); toast(ctx, "Handed to your mail app. Press send there."); back() }
        }
        BigButton("Send at end of drive", Icons.Default.Check, filled = false, enabled = picked != 0L) {
            val d = build() ?: return@BigButton
            Db.get(ctx).setDamageStatus(d.id, "QUEUED"); Live.bump()
            toast(ctx, "Saved. You'll get a reminder when the drive ends to add photos and send."); back()
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Damage reports waiting")
        if (list.isEmpty()) Muted("Nothing waiting.")
        list.forEach { d ->
            Card {
                Txt("${d.category} - ${dayTime(d.ts)}", 17, FontWeight.Bold)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Stats")
        Card(pad = 20.dp) {
            Muted("TIMES AVOIDED", 13)
            Txt("${s.saves}", 56, FontWeight.Bold)
            Muted("Counts each time a warned spot's heads-up alert fired and you passed it again.", 13)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) { Muted("REPORTS LOGGED", 12); Txt("${s.logged}", 34, FontWeight.Bold) }
            Card(Modifier.weight(1f)) { Muted("REPORTS FILED", 12); Txt("${s.filed}", 34, FontWeight.Bold) }
        }
        Card(pad = 16.dp) {
            Muted("FIXED AFTER YOU REPORTED", 12)
            Txt("${s.fixedAfterReport}", 34, FontWeight.Bold)
            Muted("Spots you reported that were later marked fixed.", 12)
        }
        big?.let { (h, sp) ->
            val what = if (!sp.typeUser) "road defect" else sp.type.spoken
            Card(Modifier.clickable { openSpot(sp.id) }, pad = 16.dp) {
                Muted("HARDEST HIT", 12)
                Txt("%.2fg".format(h.peakG), 34, FontWeight.Bold)
                Muted("${dayTime(h.ts)} - $what at %d mph. Tap to open the spot.".format(h.speedMph.toInt()), 12)
            }
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
        Muted("Counts cover everything on this phone since you installed BumpLog or last deleted your data.", 12)
    }
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
