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
    val bad = value.isNotBlank() && !Prefs.isValidEmail(value.trim())
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
            Muted("Tap a voice to pick it. Tap Play to hear it.", 12)
            Spacer(Modifier.height(6.dp))
            (listOf("" to "Phone default") + voices).forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (voice == k) p.accent else p.card2)
                    .clickable { voice = k; Prefs.voice = k; Sound.previewVoice(ctx, k) }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(label, 16, FontWeight.Medium, if (voice == k) p.onAccent else p.text, Modifier.weight(1f))
                    Icon(Icons.Default.PlayArrow, "Play", tint = if (voice == k) p.onAccent else p.accent,
                        modifier = Modifier.size(28.dp).clickable { Sound.previewVoice(ctx, k) })
                }
            }
            if (voices.isEmpty()) Muted("No extra voices found on this phone. Install more in your phone's text-to-speech settings.", 12)
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
        Card(Modifier.clickable { ctx.startActivity(Reports.csv(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Share, null, tint = p.muted); Spacer(Modifier.width(12.dp)); Txt("Export CSV backup", 18, FontWeight.Bold) }
        }
        val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val txt = try {
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        val b = ins.readNBytes(1_000_001)
                        if (b.size > 1_000_000) "TOO_BIG" else String(b)
                    }
                } catch (e: Exception) { null }
                val msg = if (txt == "TOO_BIG") "That file is over 1 MB, so it was not imported."
                else {
                    val r = if (txt == null) null else Db.get(ctx).importCsv(txt)
                    if (r == null) "That file is not a BumpLog CSV export."
                    else "Imported ${r.imported} spot(s)." + (if (r.skipped > 0) " Skipped ${r.skipped} (already here or unreadable)." else "")
                }
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                Live.bump()
            }
        }
        Card(Modifier.clickable { pick.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }, pad = 16.dp) {
            Column { Txt("Import CSV backup", 18, FontWeight.Bold); Muted("Restores spots from a BumpLog export. Spots already here are skipped.", 12) }
        }
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
            title = { Text("Delete all data?") }, text = { Text("Every logged hit, spot, report, damage photo, draft, saved reference and exported file on this phone will be erased.") })
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
        SmallButton("Send test report", Modifier.fillMaxWidth()) { if (Prefs.isValidEmail(address.trim())) handOffMail(ctx, Reports.testMail()) else toast(ctx, "Enter one valid email address first.") }
        Muted("The test report is labeled TEST so nothing is treated as a real defect.", 12)
        BigButton("Done", Icons.Default.Check) { Prefs.setupDone = true; done() }
        TextButton({ Prefs.setupDone = true; done() }) { Txt("Skip for now", 15, color = p.muted) }
    }
}
