package com.bumplog

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

const val REPO_ISSUES = "https://github.com/MtndaleRedneck/BumpLog/issues"

fun batteryDone(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) = Card(content = content)

@Composable
fun AddressField(label: String, value: String, onChange: (String) -> Unit) {
    val p = LocalPal.current
    val bad = value.isNotBlank() && !Prefs.isValidEmail(value)
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, isError = bad,
        supportingText = { if (bad) Text("Enter exactly one email address, like name@example.com. Nothing is sent until it is valid.") }, modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.card2, focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent))
}

@SuppressLint("MissingPermission")
fun pairedDevices(ctx: Context): List<Pair<String, String>> {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return try {
        ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.map { it.address to (it.name ?: it.address) } ?: emptyList()
    } catch (e: Exception) { emptyList() }
}

@Composable
fun SettingsScreen(tick: Int, askPerms: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    // Local copies so the screen redraws as values change.
    var detection by remember { mutableStateOf(Prefs.detectionEnabled) }
    var cutoff by remember { mutableStateOf(Prefs.cutoffG) }
    var severe by remember { mutableStateOf(Prefs.severeG) }
    var minSpeed by remember { mutableStateOf(Prefs.minSpeedMph) }
    var theme by remember { mutableStateOf(Prefs.theme) }
    var accent by remember { mutableStateOf(Prefs.accent) }
    var assistant by remember { mutableStateOf(Prefs.assistant) }
    var address by remember { mutableStateOf(Prefs.rawActiveAddress) }
    var auto by remember { mutableStateOf(Prefs.autoStart) }
    var far by remember { mutableStateOf(Prefs.warnFarFt) }
    var near by remember { mutableStateOf(Prefs.warnNearFt) }
    var audio by remember { mutableStateOf(Prefs.audioMode) }
    var tone by remember { mutableStateOf(Prefs.tone) }
    var voice by remember { mutableStateOf(Prefs.voice) }
    var style by remember { mutableStateOf(Prefs.voiceStyle) }
    var overlay by remember { mutableStateOf(Prefs.overlay) }
    var lock by remember { mutableStateOf(Prefs.lockButtons) }
    var hitTimeout by remember { mutableStateOf(Prefs.hitTimeoutSec.toString()) }
    var car by remember { mutableStateOf(Prefs.carAddress) }
    var batt by remember { mutableStateOf(batteryDone(ctx)) }
    var confirmWipe by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { batt = batteryDone(ctx) }
    val voices = remember(Sound.voicesVersion) { Sound.init(ctx); Sound.voices() }
    val cars = remember { pairedDevices(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Settings")

        SettingsCard {
            Txt("Appearance", 18, FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(p.bg).padding(4.dp)) {
                listOf("DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "System").forEach { (k, v) ->
                    Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(26.dp)).background(if (theme == k) p.accent else p.bg)
                        .clickable { theme = k; Prefs.theme = k; Live.bump() }, contentAlignment = Alignment.Center) {
                        Txt(v, 16, FontWeight.Bold, if (theme == k) p.onAccent else p.muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Txt("Accent", 16, color = p.muted)
            Spacer(Modifier.height(8.dp))
            val rows = Swatches.chunked(5)
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEachIndexed { c, col ->
                        val idx = r * 5 + c
                        Box(Modifier.size(46.dp).clip(CircleShape).background(col)
                            .let { if (accent == idx) it.border(3.dp, p.text, CircleShape) else it }
                            .clickable { accent = idx; Prefs.accent = idx; Live.bump() })
                    }
                }
            }
        }


        Toggle("Detection", detection, "Master switch for logging hits") { detection = it; Prefs.detectionEnabled = it }

        SettingsCard {
            Row { Txt("Sensitivity", 18, FontWeight.Bold, modifier = Modifier.weight(1f)); Txt("%.2fg".format(cutoff), 18, FontWeight.Bold, p.accent) }
            Slider(cutoff, { cutoff = Math.round(it * 20) / 20f; Prefs.cutoffG = cutoff }, valueRange = 0.3f..1.6f,
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.card2))
            Row(Modifier.fillMaxWidth()) { Muted("0.3 more alerts", 12, Modifier.weight(1f)); Muted("0.80 default", 12); Muted("  1.6 fewer", 12) }
            Spacer(Modifier.height(12.dp))
            Row { Txt("Severe hit level", 16, FontWeight.Bold, modifier = Modifier.weight(1f)); Txt("%.2fg".format(severe), 16, FontWeight.Bold, p.accent) }
            Slider(severe, { severe = Math.round(it * 20) / 20f; Prefs.severeG = severe }, valueRange = 0.8f..2.5f,
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.card2))
            Muted("Hits above this pop an alert. 1.30 default.", 12)
        }
        Stepper("Minimum speed", "$minSpeed mph", { minSpeed = (minSpeed - 5).coerceAtLeast(5); Prefs.minSpeedMph = minSpeed }, { minSpeed = (minSpeed + 5).coerceAtMost(60); Prefs.minSpeedMph = minSpeed })

        SettingsCard {
            Txt("Warnings before a saved spot", 18, FontWeight.Bold)
            Muted("Two stages: a heads-up, then a final chime.", 12)
        }
        Stepper("Heads-up distance", "$far ft", { far = (far - 220).coerceAtLeast(660); Prefs.warnFarFt = far }, { far = (far + 220).coerceAtMost(2640); Prefs.warnFarFt = far })
        Stepper("Final warning", "$near ft", { near = (near - 100).coerceAtLeast(200); Prefs.warnNearFt = near }, { near = (near + 100).coerceAtMost(1000); Prefs.warnNearFt = near })

        SettingsCard {
            Txt("Alerts", 18, FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Dropdown("Sound", audio, Sound.modes) { audio = it; Prefs.audioMode = it }
            Spacer(Modifier.height(8.dp))
            Dropdown("Tone", tone, Sound.tones) { tone = it; Prefs.tone = it }
            Spacer(Modifier.height(6.dp))
            SmallButton("Play tone", Modifier.fillMaxWidth()) { Sound.previewTone(ctx) }
            Spacer(Modifier.height(12.dp))
            Txt("Voice", 16, FontWeight.Bold)
            Muted("US English voices only. Tap a voice to pick it. Tap Play to hear it.", 12)
            Spacer(Modifier.height(6.dp))
            Dropdown("Style", style, listOf("PLAIN" to "Plain: \"Major pothole ahead\"", "MAPS" to "Maps-style: distance first", "WAZE" to "Waze-style: short and casual")) { style = it; Prefs.voiceStyle = it; Sound.previewVoice(ctx, voice) }
            Spacer(Modifier.height(6.dp))
            (listOf("" to "Phone default (US)") + voices + listOf(Sound.CUSTOM to "My own recordings")).forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (voice == k) p.accent else p.card2)
                    .clickable { voice = k; Prefs.voice = k; Sound.previewVoice(ctx, k) }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(label, 16, FontWeight.Medium, if (voice == k) p.onAccent else p.text, Modifier.weight(1f))
                    Icon(Icons.Default.PlayArrow, "Play", tint = if (voice == k) p.onAccent else p.accent,
                        modifier = Modifier.size(28.dp).clickable { Sound.previewVoice(ctx, k) })
                }
            }
            if (voices.isEmpty()) Muted("No extra US English voices found on this phone. Install more in your phone's text-to-speech settings.", 12)
            if (voice == Sound.CUSTOM) { Spacer(Modifier.height(10.dp)); VoiceRecorder() }
            Spacer(Modifier.height(8.dp))
            Muted("Alerts also pop up as a heads-up notification with Yes / No / Warn buttons.", 12)
        }
        Dropdown("Major-hit alert dismisses after", hitTimeout, listOf("10" to "10 seconds", "20" to "20 seconds", "30" to "30 seconds", "0" to "Never dismiss")) { hitTimeout = it; Prefs.hitTimeoutSec = it.toInt() }
        Toggle("Lock buttons while moving", lock, "Alerts show no buttons above the minimum speed") { lock = it; Prefs.lockButtons = it }
        Toggle("Overlay pop-up (experimental)", overlay, "Draws over other apps. Needs 'Appear on top'. Off by default.") {
            overlay = it; Prefs.overlay = it
            if (it && !Settings.canDrawOverlays(ctx)) ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        SmallButton("Preview overlay pop-up", Modifier.fillMaxWidth()) {
            if (!Notifier.previewOverlay(ctx)) {
                toast(ctx, "Allow 'Appear on top' for BumpLog, then tap Preview again.")
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Email, null, tint = p.accent); Spacer(Modifier.width(10.dp)); Txt("Assistant", 18, FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            Dropdown("Send reports to", assistant, listOf("INSTINCT" to "Instinct", "TAB" to "Tab", "CUSTOM" to "Custom")) {
                assistant = it; Prefs.assistant = it; address = Prefs.rawActiveAddress
            }
            Spacer(Modifier.height(8.dp))
            AddressField(if (assistant == "CUSTOM") "Email address" else "Your ${if (assistant == "TAB") "Tab" else "Instinct"} address", address) { address = it; Prefs.activeAddress = it }
            Muted("Saved on this phone only. Pick a preset and it fills in the address you saved for it.", 12)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallButton("Test alert", Modifier.weight(1f)) { Notifier.channels(ctx); Notifier.testAlert(ctx) }
            SmallButton("Test email", Modifier.weight(1f)) { handOffMail(ctx, Reports.testMail()) }
        }
        Muted("The test email is labeled TEST.", 12, Modifier.fillMaxWidth(), androidx.compose.ui.text.style.TextAlign.Center)

        Toggle("Auto-start when driving", auto, "Starts when your phone connects to the car's Bluetooth") { auto = it; Prefs.autoStart = it }
        if (auto && cars.isNotEmpty()) Card {
            Dropdown("Car Bluetooth", car, listOf("" to "Any car audio device") + cars) { car = it; Prefs.carAddress = it; Prefs.carName = cars.firstOrNull { c -> c.first == it }?.second ?: "" }
        }
        Card(Modifier.clickable { askPerms() }, pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Permissions", 18, FontWeight.Bold, modifier = Modifier.weight(1f))
                Txt(if (permsGranted(ctx)) "Done" else "Set up", 17, FontWeight.Bold, if (permsGranted(ctx)) p.green else p.accent)
            }
        }
        Card(Modifier.clickable { batterySetup(ctx) }, pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Txt("Battery setup", 18, FontWeight.Bold); if (!batt) Muted("Samsung can stop BumpLog mid-drive. Tap to fix.", 12) }
                Txt(if (batt) "Done" else "Set up", 17, FontWeight.Bold, if (batt) p.green else p.accent)
            }
        }
        BackupCards()
        Card(Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_ISSUES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Txt("Feedback", 18, FontWeight.Bold); Muted("Opens the GitHub issues page. Nothing is attached.", 12) }
                Icon(Icons.Default.Info, null, tint = p.muted)
            }
        }
        Card(pad = 16.dp) {
            Txt("Testing", 18, FontWeight.Bold)
            Muted("Fires the real alerts and screens without driving. Test spots are flagged TEST and never count toward your Scorecard or stats.", 12)
            Spacer(Modifier.height(8.dp))
            SmallButton("Simulate a major hit", Modifier.fillMaxWidth()) { Testing.majorHit(ctx); Live.bump() }
            Spacer(Modifier.height(6.dp))
            SmallButton("Simulate a warn-ahead alert", Modifier.fillMaxWidth()) { Testing.warnAhead(ctx); Live.bump() }
            Spacer(Modifier.height(6.dp))
            SmallButton("Simulate end of trip", Modifier.fillMaxWidth()) { Testing.endOfTrip(ctx); Live.bump() }
            Spacer(Modifier.height(6.dp))
            SmallButton("Simulate car connected", Modifier.fillMaxWidth()) { Notifier.carConnected(ctx) }
            Spacer(Modifier.height(6.dp))
            SmallButton("Clear test data", Modifier.fillMaxWidth()) {
                val n = Db.get(ctx).clearTestData(); Live.bump(); toast(ctx, if (n == 0) "No test data." else "Cleared $n test spot(s).")
            }
        }
        Card(Modifier.clickable { confirmWipe = true }, pad = 16.dp) { Txt("Delete all logged data", 16, color = p.muted) }
        Muted("BumpLog ${BuildConfig.VERSION_NAME}  -  everything stays on this phone", 12, Modifier.fillMaxWidth(), androidx.compose.ui.text.style.TextAlign.Center)
        if (confirmWipe) AlertDialog(onDismissRequest = { confirmWipe = false },
            confirmButton = { TextButton({ Db.get(ctx).wipeAll(); DamagePhotos.wipeAll(ctx); Prefs.wipeLocalExtras(ctx); Live.bump(); confirmWipe = false }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmWipe = false }) { Text("Cancel") } },
            title = { Text("Delete all data?") }, text = { Text("Every logged hit, spot, report, damage photo, voice recording, draft, saved reference and exported file on this phone will be erased.") })
    }
}

fun permsGranted(ctx: Context): Boolean {
    fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return ok(Manifest.permission.ACCESS_FINE_LOCATION) && ok(Manifest.permission.POST_NOTIFICATIONS) && ok(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}

@SuppressLint("BatteryLife")
fun batterySetup(ctx: Context) {
    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    toast(ctx, "Allow it. Also on Samsung: Settings > Battery > Background usage limits > Never sleeping apps > add BumpLog.")
}

// ---------------------------------------------------------------- First-run setup

@Composable
fun SetupScreen(done: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var assistant by remember { mutableStateOf(Prefs.assistant) }
    var address by remember { mutableStateOf(Prefs.rawActiveAddress) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Set up your assistant")
        Muted("BumpLog emails confirmed reports to an AI assistant that works out who owns the road and prepares the repair request. Nothing is filed without your approval. The address stays on this phone.", 14)
        Dropdown("Assistant", assistant, listOf("INSTINCT" to "Instinct", "TAB" to "Tab", "CUSTOM" to "Custom")) { assistant = it; Prefs.assistant = it; address = Prefs.rawActiveAddress }
        AddressField("Its email address", address) { address = it; Prefs.activeAddress = it }
        SmallButton("Send test report", Modifier.fillMaxWidth()) { if (Prefs.isValidEmail(address)) handOffMail(ctx, Reports.testMail()) else toast(ctx, "Enter one valid email address first.") }
        Muted("The test report is labeled TEST so nothing is treated as a real defect.", 12)
        BigButton("Done", Icons.Default.Check) { Prefs.setupDone = true; done() }
        TextButton({ Prefs.setupDone = true; done() }) { Txt("Skip for now", 15, color = p.muted) }
    }
}


private fun runIo(ctx: Context, work: () -> String) {
    val h = android.os.Handler(android.os.Looper.getMainLooper())
    Thread {
        val msg = try { work() } catch (e: Exception) { "That did not work: ${e.message ?: "unknown error"}" }
        h.post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show(); Live.bump() }
    }.start()
}

/** Save to device, share, and restore. The zip is the full backup; the CSV is for spreadsheets. */
@Composable
private fun BackupCards() {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val saveZip = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runIo(ctx) {
            ctx.contentResolver.openOutputStream(uri)?.use { Backup.writeZip(ctx, it) } ?: error("could not open the file")
            "Backup saved."
        }
    }
    val saveCsv = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runIo(ctx) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(Reports.csvText(ctx).toByteArray()) } ?: error("could not open the file")
            "CSV saved."
        }
    }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runIo(ctx) {
            val ins = ctx.contentResolver.openInputStream(uri)?.buffered() ?: error("could not open the file")
            ins.use { i ->
                i.mark(8)
                val head = ByteArray(4); val n = i.read(head); i.reset()
                if (n == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
                    val r = Backup.restoreZip(ctx, i)
                    if (r == null) "That file is not a BumpLog backup."
                    else "Restored ${r.spots} spot(s), ${r.hits} hit(s), ${r.damages} damage report(s), ${r.photos} photo(s) and ${r.settings} setting(s)." +
                        (if (r.skipped > 0) " Skipped ${r.skipped} already here or unreadable." else "") + " Reopen BumpLog if the look or sound settings seem unchanged."
                } else {
                    val b = i.readNBytes(1_000_001)
                    if (b.size > 1_000_000) "That file is over 1 MB, so it was not imported."
                    else {
                        val r = Db.get(ctx).importCsv(String(b))
                        if (r == null) "That file is not a BumpLog backup or CSV export."
                        else "Imported ${r.imported} spot(s)" + (if (r.damages > 0) " and ${r.damages} damage report(s) (CSV has no photos or settings)" else "") +
                            "." + (if (r.skipped > 0) " Skipped ${r.skipped} (already here or unreadable)." else "")
                    }
                }
            }
        }
    }
    Card(pad = 16.dp) {
        Txt("Backup", 18, FontWeight.Bold)
        Muted("One file with your reports, damage records and photos, voice recordings and settings. Restore it after a reinstall to get everything back.", 12)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Save to device", Modifier.weight(1f)) { saveZip.launch(Backup.fileName("zip")) }
            SmallButton("Share", Modifier.weight(1f)) { runIo(ctx) { ctx.startActivity(Backup.shareIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "" } }
        }
        Spacer(Modifier.height(8.dp))
        SmallButton("Restore from backup", Modifier.fillMaxWidth()) { restore.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/*", "application/octet-stream")) }
    }
    Card(pad = 16.dp) {
        Txt("Spreadsheet CSV", 18, FontWeight.Bold)
        Muted("Hits, spots and damage records for a spreadsheet. Photo file names are listed, the photos are in the backup file. Restore reads this too.", 12)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Save to device", Modifier.weight(1f)) { saveCsv.launch("BumpLog-export.csv") }
            SmallButton("Share", Modifier.weight(1f)) { ctx.startActivity(Reports.csv(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/** Record or import your own clip for each alert. Missing clips fall back to the phone's voice. */
@Composable
private fun VoiceRecorder() {
    val ctx = LocalContext.current
    val p = LocalPal.current
    var rec by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableStateOf(0) }
    var wantKey by remember { mutableStateOf<String?>(null) }
    var importKey by remember { mutableStateOf<String?>(null) }
    fun start(key: String) {
        if (Sound.startRecording(ctx, key)) { rec = key; android.widget.Toast.makeText(ctx, "Recording up to 8 seconds. Tap Stop when done.", android.widget.Toast.LENGTH_SHORT).show() }
        else android.widget.Toast.makeText(ctx, "Could not start recording.", android.widget.Toast.LENGTH_SHORT).show()
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        val k = wantKey; wantKey = null
        if (ok && k != null) start(k) else if (!ok) android.widget.Toast.makeText(ctx, "Microphone permission is needed to record.", android.widget.Toast.LENGTH_SHORT).show()
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val k = importKey; importKey = null
        if (uri != null && k != null) runIo(ctx) {
            val f = Sound.clipFile(ctx, k)
            val n = ctx.contentResolver.openInputStream(uri)?.use { i ->
                val b = i.readNBytes(5_000_001)
                if (b.size > 5_000_000) -1 else { f.writeBytes(b); b.size }
            } ?: -1
            if (n <= 0) { f.delete(); "That audio file is empty or over 5 MB." } else "Imported."
        }
    }
    Muted("Record your own voice for each alert, or import an audio file. Anything you leave empty uses the phone voice. Recordings stay on this phone.", 12)
    Spacer(Modifier.height(6.dp))
    key(bump) {
        Sound.clipPhrases.forEach { (key, text) ->
            val has = Sound.hasClip(ctx, key)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Txt(text, 15, FontWeight.Medium); Muted(if (rec == key) "Recording..." else if (has) "Recorded" else "Not set", 11) }
                if (rec == key) {
                    SmallButton("Stop") { Sound.stopRecording(); rec = null; bump++ }
                } else {
                    SmallButton(if (has) "Redo" else "Record") {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) start(key)
                        else { wantKey = key; ask.launch(android.Manifest.permission.RECORD_AUDIO) }
                    }
                    Spacer(Modifier.width(6.dp))
                    SmallButton("Import") { importKey = key; pick.launch(arrayOf("audio/*")) }
                    if (has) { Spacer(Modifier.width(6.dp)); SmallButton("Play") { Sound.playClip(ctx, key) }; Spacer(Modifier.width(6.dp)); SmallButton("Delete") { Sound.clipFile(ctx, key).delete(); bump++ } }
                }
            }
        }
    }
    SmallButton("Refresh list", Modifier.fillMaxWidth()) { bump++ }
}
