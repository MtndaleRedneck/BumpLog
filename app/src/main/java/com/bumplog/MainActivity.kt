package com.bumplog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class PendingLink {
    data class Setup(val assistant: String, val email: String) : PendingLink()
}

sealed class UpdateUi {
    data class Offer(val info: UpdateInfo) : UpdateUi()
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUi()
    data class Failed(val message: String) : UpdateUi()
}

sealed class Screen {
    object Setup : Screen()
    object DamageQueue : Screen()
    object Voice : Screen()
    object Recordings : Screen()
    object BugReport : Screen()
    data class Alert(val id: Long) : Screen()
    data class Spot(val id: Long) : Screen()
    data class Damage(val spotId: Long) : Screen()
}

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf("home")
    private var stack by mutableStateOf(listOf<Screen>())
    private var pendingLink by mutableStateOf<PendingLink?>(null)
    private var pendingTrip by mutableStateOf(0L)
    private var guideOffer by mutableStateOf(false)
    private var confirmFixed by mutableStateOf<Long?>(null)
    private var askSent by mutableStateOf<List<Long>?>(null)
    private var updateUi by mutableStateOf<UpdateUi?>(null)
    private var updateReady: java.io.File? = null
    private var updateJob: kotlinx.coroutines.Job? = null
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var permsOk by mutableStateOf(false)

    private val askBg = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms() }
    private val askMain = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPerms()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            askBg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun refreshPerms() { permsOk = permsGranted(this) }

    private fun askPerms() {
        askMain.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Notifier.channels(this)
        Sound.init(this)
        Live.update { it.copy(paused = Prefs.passenger) }
        refreshPerms()
        if (!Prefs.setupDone) stack = listOf(Screen.Setup)
        handle(intent)
        // One-time offer after an upgrade that changed the report email or the guide.
        if (savedInstanceState == null && Prefs.setupDone && Prefs.guideSeen < Reports.GUIDE_VERSION) guideOffer = true
        if (savedInstanceState == null) checkForUpdate(if (BuildConfig.DEBUG) intent.getStringExtra("updatecheck") else null)
        setContent {
            val live by Live.state.collectAsState()
            var themeKey by remember { mutableStateOf(0) }
            // theme/accent changes bump the tick, which re-reads prefs here
            val theme = remember(live.tick) { Prefs.theme }
            val accent = remember(live.tick) { Prefs.accent }
            BumpLogTheme(theme, accent) { Box(Modifier.fillMaxSize()) { Root(live); CelebrationOverlay(); ConfirmationOverlay() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() { Sound.stopRecording(save = false); Sound.onRecordingEnded?.invoke(); super.onStop() }
    override fun onResume() {
        super.onResume(); refreshPerms(); Live.bump()
        // Power on means logging is running. Bring it back if Android or the user ended it.
        if (permsOk && Prefs.detectionEnabled && !Live.state.value.running) try { DriveService.start(this) } catch (e: Exception) { }
        // Back from the "Install unknown apps" screen with the file already downloaded and checked: carry on to the installer.
        updateReady?.let { f -> if (packageManager.canRequestPackageInstalls()) { updateReady = null; launchInstaller(f) } }
        // Back from the mail app: ask once whether the handed-off email really went.
        if (askSent == null && Prefs.askSentFor.isNotBlank())
            askSent = Prefs.askSentFor.split(",").mapNotNull { it.toLongOrNull() }.takeIf { it.isNotEmpty() }
    }
    override fun onDestroy() { updateJob?.cancel(); super.onDestroy() }

    /** Always on: once per cold open, plus any manual tap of Check for updates in Settings.
     * A manual tap always runs and always says what happened, even during a drive -
     * the offer stays cancelable. Only the automatic check stays quiet during a drive.
     * A skipped version stays skipped on the automatic check. */
    private fun checkForUpdate(fakeCurrent: String?, manual: Boolean = false) {
        val current = fakeCurrent ?: BuildConfig.VERSION_NAME
        Updater.clean(this)
        ioScope.launch {
            val info = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.checkOrThrow(current) }
            } catch (e: Exception) {
                if (manual) toast(this@MainActivity, "Couldn't check for updates right now.")
                return@launch
            }
            val st = Live.state.value
            if (st.running && st.gps && st.speedMph >= Prefs.minSpeedMph && !manual) return@launch
            if (info == null) { if (manual) toast(this@MainActivity, "You're up to date."); return@launch }
            if (!manual && info.versionName == Prefs.skippedUpdate) return@launch
            updateUi = UpdateUi.Offer(info)
        }
    }

    private fun startUpdate(info: UpdateInfo) {
        updateUi = UpdateUi.Downloading(info, 0f)
        updateJob = ioScope.launch {
            try {
                val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val file = Updater.download(this@MainActivity, info, { pr -> updateUi = UpdateUi.Downloading(info, pr.coerceIn(0f, 1f)) }, { !isActive })
                    try { if (!(BuildConfig.DEBUG && intent.getBooleanExtra("skipverify", false))) Updater.verify(this@MainActivity, file) }
                    catch (e: Updater.Failure) { file.delete(); throw e }
                    file
                }
                updateUi = null
                if (packageManager.canRequestPackageInstalls()) launchInstaller(f)
                else {
                    updateReady = f
                    toast(this@MainActivity, "Allow BumpLog to install updates, then come back.")
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            } catch (e: Updater.Failure) {
                updateUi = if (e.msg == "cancelled") null else UpdateUi.Failed(e.msg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Updater.clean(this@MainActivity); updateUi = null
            } catch (e: Exception) {
                Updater.clean(this@MainActivity); updateUi = UpdateUi.Failed("The update could not be installed.")
            }
        }
    }

    private fun launchInstaller(f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { updateUi = UpdateUi.Failed("Could not open the installer.") }
    }

    private fun handle(i: Intent?) {
        i ?: return
        val spot = i.getLongExtra("spot", 0)
        when (i.getStringExtra("screen")) {
            "review" -> { tab = "review"; stack = emptyList() }
            "reported" -> { tab = "reported"; stack = emptyList() }
            "settings" -> { tab = "settings"; stack = emptyList() }
            "home" -> { tab = "home"; stack = emptyList() }
            "setup" -> stack = listOf(Screen.Setup)
            "alert" -> stack = listOf(Screen.Alert(spot))
            "score", "stats" -> { tab = "stats"; stack = emptyList() }
            "damagequeue" -> stack = listOf(Screen.DamageQueue)
            "damage" -> stack = listOf(Screen.Damage(0))
            "spot" -> stack = listOf(Screen.Spot(spot))
            "markfixed" -> {
                // Opened from the "may be fixed" prompt, but any app can send this, so it only asks. Nothing changes until the user confirms in the app.
                val s = Db.get(this).spot(spot)
                if (s != null && s.review != "FIXED") confirmFixed = spot
                stack = emptyList()
            }
        }
        if (i.hasExtra("sendtrip")) {
            val driveId = i.getLongExtra("sendtrip", 0)
            if (!Prefs.setupDone) {
                // The assistant setup has to happen once before the first report goes out. The trip waits for it.
                pendingTrip = driveId; stack = listOf(Screen.Setup)
            } else sendTrip(driveId)
            i.removeExtra("sendtrip")
        }
        if (spot != 0L && i.getStringExtra("screen") == null) {
            val s = Db.get(this).spot(spot)
            stack = listOf(if (s != null && s.severe && s.review == "PENDING") Screen.Alert(spot) else Screen.Spot(spot))
        }
        if (i.getBooleanExtra("start", false)) { Prefs.detectionEnabled = true; startDetection() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("demo", false)) { Demo.seed(this); Live.bump() }
        // Debug builds only, for the CI screenshot run: pick the voice source and trigger the celebration.
        if (BuildConfig.DEBUG && i.hasExtra("setvoice")) { Prefs.voice = i.getStringExtra("setvoice").orEmpty(); Live.bump() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("celebrate", false)) Celebrate.trigger()
        if (BuildConfig.DEBUG && i.getBooleanExtra("confirmnotice", false)) ConfirmationNotice.show()
        // Debug builds only, for the CI screenshot run: open the voice screens directly and put a few clips in place so every button shows.
        if (BuildConfig.DEBUG) {
            when (i.getStringExtra("screen")) {
                "voice" -> stack = listOf(Screen.Voice)
                "recordings" -> stack = listOf(Screen.Voice, Screen.Recordings)
                "bugreport" -> stack = listOf(Screen.BugReport)
            }
            if (i.getBooleanExtra("seedclips", false)) { Sound.clipPhrases.take(2).forEach { Sound.clipFile(this, it.first).writeBytes(ByteArray(2000)) }; Live.bump() }
        }
        if (BuildConfig.DEBUG && i.getBooleanExtra("mockdrive", false)) {
            val live = i.getBooleanExtra("mocklive", true)
            val w = FloatArray(120) { k -> if (!live) 0f else (0.2f + 0.35f * kotlin.math.abs(kotlin.math.sin(k * 0.7f)) + (if (k in 84..100) 1.1f * (1f - kotlin.math.abs(k - 92) / 8f).coerceAtLeast(0f) else 0f)).coerceAtLeast(0f) }
            Live.update { it.copy(running = live, gps = live, speedMph = if (live) 38f else 0f, hardestHitG = 1.42f, lastG = if (live) 0.34f else 0f, wave = w) }
        }
        if (BuildConfig.DEBUG && i.hasExtra("theme")) { Prefs.theme = i.getStringExtra("theme")!!; Live.bump() }
        if (BuildConfig.DEBUG && i.hasExtra("drv")) { Prefs.passenger = false; Live.update { it.copy(paused = false) }; Live.bump() }
        if (BuildConfig.DEBUG && i.hasExtra("pass")) { Prefs.passenger = true; Live.update { it.copy(paused = true) }; Live.bump() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("lastdemo", false)) { Db.get(this).hits(1).firstOrNull()?.let { Prefs.lastDriveId = it.driveId }; Live.bump() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("emptylast", false)) { Prefs.lastDriveId = System.currentTimeMillis(); Live.bump() }
        if (BuildConfig.DEBUG && i.hasExtra("detoff")) { Prefs.detectionEnabled = !i.getBooleanExtra("detoff", false); Live.bump() }
        // bumplog://setup?assistant=instinct&email=...
        // Links never change state on their own. They open a confirmation the user must accept.
        i.data?.let { u ->
            if (u.scheme == "bumplog") when (u.host) {
                "setup" -> {
                    val a = (u.getQueryParameter("assistant") ?: "custom").uppercase()
                    val asst = if (a in listOf("INSTINCT", "TAB")) a else "CUSTOM"
                    val email = Prefs.cleanEmail(u.getQueryParameter("email").orEmpty())
                    pendingLink = if (Prefs.isValidEmail(email)) PendingLink.Setup(asst, email) else null
                    if (email.isNotEmpty() && pendingLink == null) toast(this, "Setup link ignored: it needs exactly one valid email address.")
                    stack = listOf(Screen.Setup)
                }
            }
        }
    }

    private fun startDetection() {
        if (!Prefs.detectionEnabled) return
        if (!permsOk) { askPerms(); return }
        try { DriveService.start(this) } catch (e: Exception) { toast(this, "That didn't work. Try again.") }
    }

    /** Hand one trip's confirmed spots to the mail app and mark them Sent. */
    private fun sendTrip(driveId: Long) {
        val list = Db.get(this).tripSpots(driveId)
        if (list.isEmpty()) toast(this, "No confirmed reports left to send.")
        else if (handOffMail(this, Reports.reportsMail(this, list))) {
            Prefs.askSentFor = list.joinToString(",") { it.id.toString() }; Prefs.askSentKind = "trip"
        }
    }

    /** Setup screen closed. A trip that waited for it goes out only if setup actually completed; otherwise it stays queued. */
    private fun finishSetup() {
        stack = emptyList()
        val driveId = pendingTrip
        pendingTrip = 0
        if (driveId == 0L) return
        if (Prefs.setupDone) sendTrip(driveId)
        else toast(this, "The trip's reports are still waiting. Finish the assistant setup to send them.")
    }

    @Composable
    private fun Root(live: LiveState) {
        val p = LocalPal.current
        val top = stack.lastOrNull()
        BackHandler(top != null) { stack = stack.dropLast(1) }
        confirmFixed?.let { id ->
            val sp = remember(id) { Db.get(this@MainActivity).spot(id) }
            if (sp == null || sp.review == "FIXED") confirmFixed = null
            else AlertDialog(
                onDismissRequest = { confirmFixed = null },
                title = { Text("Mark as fixed?") },
                text = { Text("${sp.title}. No impact was detected there on recent passes. Marking it fixed stops warnings for it. You can restore it any time from its screen.") },
                confirmButton = { TextButton({ Db.get(this@MainActivity).markFixed(id, System.currentTimeMillis()); Notifier.cancel(this@MainActivity, "fixed", id.toInt()); confirmFixed = null; Celebrate.trigger(); Live.bump() }) { Text("Mark fixed") } },
                dismissButton = { TextButton({ confirmFixed = null }) { Text("Not yet") } },
            )
        }
        askSent?.let { ids ->
            AlertDialog(
                onDismissRequest = { askSent = null },
                title = { Text("Did the email go out?") },
                text = { Text(if (ids.size == 1) "The report went to your mail app. If it wasn't sent, it stays in the queue for the next try." else "The ${ids.size} reports went to your mail app. If they weren't sent, they stay in the queue for the next try.") },
                confirmButton = { TextButton({
                    val now = System.currentTimeMillis(); val db = Db.get(this@MainActivity); ids.forEach { db.markSent(it, now) }
                    if (Prefs.askSentKind == "trip") ioScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Backup.autoSave(this@MainActivity) } }
                    Prefs.askSentFor = ""; Prefs.askSentKind = ""; Prefs.askAgain = ""; askSent = null; Live.bump()
                }) { Text("Yes, sent") } },
                dismissButton = { TextButton({
                    if (Prefs.askSentKind == "again") Prefs.askAgain.split("|").let { p ->
                        if (p.size == 6) Db.get(this@MainActivity).unReportAgain(p[0].toLong(), p[1].toInt(), p[2].toLong(), p[3].toLong(), p[4], p[5].toLong())
                    }
                    Prefs.askSentFor = ""; Prefs.askSentKind = ""; Prefs.askAgain = ""; askSent = null; Live.bump()
                }) { Text("Not yet") } },
            )
        }
        updateUi?.let { u ->
            when (u) {
                is UpdateUi.Offer -> AlertDialog(
                    onDismissRequest = { updateUi = null },
                    title = { Text("BumpLog ${u.info.versionName} is available") },
                    text = {
                        Column {
                            Text("You have ${BuildConfig.VERSION_NAME}.")
                            if (u.info.notes.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                // Long notes scroll inside this box, so the buttons below never get pushed off the dialog.
                                val maxNotes = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.38f).dp
                                Column(Modifier.heightIn(max = maxNotes).verticalScroll(rememberScrollState())) {
                                    u.info.notes.forEach {
                                        if (it.startsWith("## ")) { Spacer(Modifier.height(4.dp)); Text(it.drop(3), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                                        else Text("\u2022 $it")
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Android will ask you to confirm the install.")
                            Spacer(Modifier.height(4.dp))
                            TextButton({ Prefs.skippedUpdate = u.info.versionName; updateUi = null }) { Text("Skip this version") }
                        }
                    },
                    confirmButton = { TextButton({ startUpdate(u.info) }) { Text("Update") } },
                    dismissButton = { TextButton({ updateUi = null }) { Text("Later") } },
                )
                is UpdateUi.Downloading -> AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Downloading ${u.info.versionName}") },
                    text = { androidx.compose.material3.LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth()) },
                    confirmButton = { TextButton({ updateJob?.cancel() }) { Text("Cancel") } },
                )
                is UpdateUi.Failed -> AlertDialog(
                    onDismissRequest = { updateUi = null },
                    title = { Text("Update not installed") },
                    text = { Text(u.message) },
                    confirmButton = { TextButton({ updateUi = null }) { Text("OK") } },
                )
            }
        }
        pendingLink?.let { pl ->
            val ctx = this@MainActivity
            when (pl) {
                is PendingLink.Setup -> AlertDialog(
                    onDismissRequest = { pendingLink = null },
                    title = { Text("Use this address?") },
                    text = { Text("A link wants to set your reports address to ${pl.email} (${pl.assistant.lowercase()}). Reports will be emailed there. Only accept if you started this setup.") },
                    confirmButton = { TextButton({ Prefs.assistant = pl.assistant; Prefs.activeAddress = pl.email; pendingLink = null; Live.bump() }) { Text("Use address") } },
                    dismissButton = { TextButton({ pendingLink = null }) { Text("Cancel") } },
                )
            }
        }
        if (guideOffer) AlertDialog(
            onDismissRequest = { guideOffer = false },
            title = { Text("The report email changed") },
            text = { Text("BumpLog has new instructions for your assistant. Send it the new setup email? It is the assistant guide.") },
            confirmButton = { TextButton({ Prefs.guideSeen = Reports.GUIDE_VERSION; guideOffer = false; if (sendSetupMail(this@MainActivity)) stack = listOf(Screen.Setup) }) { Text("Send") } },
            dismissButton = { TextButton({ Prefs.guideSeen = Reports.GUIDE_VERSION; guideOffer = false }) { Text("Not now") } },
        )
        val settingsScroll = androidx.compose.foundation.rememberScrollState()
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (top) {
                    is Screen.Setup -> SetupScreen { finishSetup() }
                    is Screen.Alert -> AlertScreen(top.id, live.tick, { stack = emptyList() }) { tab = "review"; stack = emptyList() }
                    is Screen.Spot -> SpotScreen(top.id, live.tick, { stack = stack.dropLast(1) }) { stack = stack + Screen.Damage(it) }
                    is Screen.Damage -> DamageScreen(top.spotId, live.tick, { stack = stack.dropLast(1) }) { stack = listOf(Screen.DamageQueue) }
                    is Screen.DamageQueue -> DamageQueueScreen(live.tick) { stack = emptyList() }
                    is Screen.Voice -> VoiceScreen({ stack = stack + Screen.Recordings }) { tab = "settings"; stack = emptyList() }
                    is Screen.Recordings -> RecordingsScreen { stack = stack.dropLast(1) }
                    is Screen.BugReport -> BugReportScreen { tab = "settings"; stack = emptyList() }
                    null -> when (tab) {
                        "review" -> ReviewScreen(live, { stack = listOf(Screen.Spot(it)) }) { stack = listOf(Screen.Setup) }
                        "reported" -> ReportedScreen(live.tick) { stack = listOf(Screen.Spot(it)) }
                        "stats" -> StatsScreen(live.tick) { stack = listOf(Screen.Spot(it)) }
                        "settings" -> SettingsScreen(live.tick, settingsScroll, { askPerms() }, { stack = listOf(Screen.Voice) }, { stack = listOf(Screen.BugReport) }, { stack = listOf(Screen.Setup) }, { checkForUpdate(null, manual = true) })
                        else -> HomeScreen(live, { stack = listOf(Screen.Spot(it)) }, { stack = listOf(Screen.Damage(0)) }, permsOk, { askPerms() },
                            {
                                Prefs.passenger = !Prefs.passenger
                                Live.update { it.copy(paused = Prefs.passenger) }
                            })
                    }
                }
            }
            if (top == null || top is Screen.Spot) Row(Modifier.fillMaxWidth().background(p.nav).navigationBarsPadding().padding(vertical = 8.dp)) {
                NavItem("Home", Icons.Default.Home, tab == "home" && top == null, Modifier.weight(1f)) { tab = "home"; stack = emptyList() }
                NavItem("Review", Icons.Default.CheckCircle, tab == "review" && top == null, Modifier.weight(1f)) { tab = "review"; stack = emptyList() }
                NavItem("Reported", StatsIcons.plane, tab == "reported" && top == null, Modifier.weight(1f)) { tab = "reported"; stack = emptyList() }
                NavItem("Stats", StatsIcons.bar, tab == "stats" && top == null, Modifier.weight(1f)) { tab = "stats"; stack = emptyList() }
                NavItem("Settings", Icons.Default.Settings, tab == "settings" && top == null, Modifier.weight(1f)) { tab = "settings"; stack = emptyList() }
            }
        }
    }

    @Composable
    private fun NavItem(label: String, icon: ImageVector, sel: Boolean, m: Modifier, onClick: () -> Unit) {
        val p = LocalPal.current
        Column(m.clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (sel) p.accent else p.muted, modifier = Modifier.size(26.dp))
            Txt(label, 12, FontWeight.Medium, if (sel) p.accent else p.muted)
        }
    }
}
