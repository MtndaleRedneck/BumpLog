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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    private var guideOffer by mutableStateOf(false)
    private var confirmFixed by mutableStateOf<Long?>(null)
    private var askSent by mutableStateOf<List<Long>?>(null)
    private var askQuestion: PendingAsk.Question? = null   // the exact question the dialog was built from (main thread only)
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
        BackupWorker.schedule(this)
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
        super.onResume(); refreshPerms(); Live.bump(); if (Prefs.setupDone) DamageMarks.retryAll(this)
        // Power on means logging is running. Bring it back if Android or the user ended it.
        if (permsOk && Prefs.detectionEnabled && !Live.state.value.running) try { DriveService.start(this) } catch (e: Exception) { }
        // Back from the "Install unknown apps" screen with the file already downloaded and checked: carry on to the installer.
        updateReady?.let { f -> if (packageManager.canRequestPackageInstalls()) { updateReady = null; launchInstaller(f) } }
        // Back from the mail app: ask once whether the handed-off email really went.
        if (askSent == null) {
            val q = PendingAsk.current()   // one consistent read of the whole question
            val ids = q.sentFor.split(",").mapNotNull { it.toLongOrNull() }.takeIf { it.isNotEmpty() }
            if (ids != null) { askQuestion = q; askSent = ids }
        }
    }
    override fun onDestroy() { updateJob?.cancel(); super.onDestroy() }

    /** Once per cold open. The manual Check for updates in Settings does its own fetch and hands the
     * result straight to the offer, so it never double-requests. The automatic check stays quiet during
     * a drive, and a skipped version stays skipped. */
    private fun checkForUpdate(fakeCurrent: String?) {
        val current = fakeCurrent ?: BuildConfig.VERSION_NAME
        Updater.clean(this)
        ioScope.launch {
            val info = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.checkOrThrow(current) }
            } catch (e: Exception) {
                return@launch
            }
            val st = Live.state.value
            if (st.running && st.gps && st.speedMph >= Prefs.minSpeedMph) return@launch
            if (info == null) return@launch
            if (info.versionName == Prefs.skippedUpdate) return@launch
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
            "feedback" -> stack = listOf(Screen.BugReport)
            "alert" -> stack = listOf(Screen.Alert(spot))
            "score", "stats" -> { tab = "stats"; stack = emptyList() }
            "damagequeue" -> stack = listOf(Screen.DamageQueue)
            "damage" -> stack = listOf(Screen.Damage(0))
            "spot" -> stack = listOf(Screen.Spot(spot))
            "markfixed" -> {
                // Opened from the "may be fixed" prompt, but any app can send this, so it only asks. Nothing changes until the user confirms in the app.
                stack = emptyList()
                DbQ.run(this, work = { it.spot(spot) }) { s -> if (s != null && s.review != "FIXED") confirmFixed = spot }
            }
        }
        // Trips and restarts arrive only from the non-exported ActionReceiver, which
        // records them in Prefs. Extras on this exported activity never cause side effects:
        // any app can forge them.
        if (Prefs.pendingTrip != 0L && !Prefs.setupDone) stack = listOf(Screen.Setup)
        if (Prefs.restartWanted) { Prefs.restartWanted = false; startDetection() }
        if (spot != 0L && i.getStringExtra("screen") == null) {
            stack = listOf(Screen.Spot(spot))
            // A severe pending spot opens the alert instead, unless the user has already moved on from the spot screen.
            DbQ.run(this, work = { it.spot(spot) }) { s -> if (s != null && s.severe && s.review == "PENDING" && stack == listOf(Screen.Spot(spot))) stack = listOf(Screen.Alert(spot)) }
        }
        if (BuildConfig.DEBUG && i.getBooleanExtra("demo", false)) { Demo.seed(this); Live.bump() }
        // Debug builds only, for the CI frame run: a real CSV import and a wipe on a background thread, so the tab
        // switches that follow run while the database monitor is busy, like a user importing while navigating.
        if (BuildConfig.DEBUG && i.hasExtra("stressimport")) {
            val rows = i.getIntExtra("stressimport", 0).coerceIn(1, 20000); val app = applicationContext
            Thread {
                val db = Db.get(app); val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                val ab = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; val t0 = System.currentTimeMillis()
                repeat(3) { round ->
                    val sb = StringBuilder("report_id,report_no,review,report_status,defect_type,hit_time,lat,lon,heading,speed_mph,peak_g,spot_hit_count,road,town,county,report_ts\n")
                    for (n in 0 until rows) {
                        val id = "BL-20260101-" + ab[(n / 29791) % 31] + ab[(n / 961) % 31] + ab[(n / 31) % 31] + ab[n % 31]
                        sb.append(id).append(",0,PENDING,NONE,POTHOLE,").append(fmt.format(t0 - n * 60000L)).append(",39.5,-98.3,90,35,0.9,1,Stress Rd,Anytown,Example County,\n")
                    }
                    val s = System.currentTimeMillis(); val r = db.importCsv(sb.toString())
                    android.util.Log.i("BumpStress", "round $round import rows=$rows imported=${r?.imported} took=${System.currentTimeMillis() - s}ms")
                    Live.bump(); Thread.sleep(1500)
                    val w = System.currentTimeMillis(); db.wipeAll(); Live.bump()
                    android.util.Log.i("BumpStress", "round $round wipe took=${System.currentTimeMillis() - w}ms"); Thread.sleep(500)
                }
                android.util.Log.i("BumpStress", "done")
            }.start()
        }
        // Debug builds only, for the CI screenshot run: pick the voice source and trigger the celebration.
        if (BuildConfig.DEBUG && i.hasExtra("setvoice")) { Prefs.voice = i.getStringExtra("setvoice").orEmpty(); Live.bump() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("celebrate", false)) Celebrate.trigger()
        if (BuildConfig.DEBUG && i.getBooleanExtra("askmail", false)) { PendingAsk.set("5", "trip") }
        if (BuildConfig.DEBUG && i.getBooleanExtra("logstop", false)) Notifier.loggingStopped(this)
        if (BuildConfig.DEBUG && i.getBooleanExtra("logpaused", false)) Notifier.loggingPaused(this)
        if (BuildConfig.DEBUG && i.getBooleanExtra("showwipe", false)) { Testing.openWipeOnce = true; tab = "settings"; stack = emptyList() }
        // Debug builds only, for the CI screenshot run: the Home screen as it looks after GPS drops mid-drive.
        // Debug builds only, for the CI screenshot run: the backup failure line as it shows after a failed weekly save.
        if (BuildConfig.DEBUG && i.getBooleanExtra("mockbackupfail", false)) {
            if (Prefs.backupFolder.isBlank()) Prefs.backupFolder = "content://demo-backup-folder"
            if (Prefs.lastBackupTs == 0L) Prefs.lastBackupTs = System.currentTimeMillis()
            Prefs.lastBackupError = "the saved file would not read back"
        }
        if (BuildConfig.DEBUG && i.getBooleanExtra("mockgpslost", false)) {
            Live.update { it.copy(running = true, gps = false, gpsLost = true, speedMph = 38f, hardestHitG = 1.42f, lastG = 0.34f) }
        }
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
        // Debug builds only, for the CI screenshot run: pick the bottom bar style and stub an up-to-date result.
        if (BuildConfig.DEBUG && i.hasExtra("accentidx")) { Prefs.accent = i.getIntExtra("accentidx", 0).coerceIn(0, Swatches.lastIndex); Live.bump() }
        if (BuildConfig.DEBUG && i.hasExtra("navstyle")) { Prefs.navStyle = i.getStringExtra("navstyle").orEmpty(); Live.bump() }
        if (BuildConfig.DEBUG && i.hasExtra("reporticon")) { Prefs.reportIcon = i.getStringExtra("reporticon").orEmpty(); Live.bump() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("fakeuptodate", false)) Testing.fakeUpToDateCount = 2
        if (BuildConfig.DEBUG && i.hasExtra("drv")) { Prefs.passenger = false; Live.update { it.copy(paused = false) }; Live.bump() }
        if (BuildConfig.DEBUG && i.hasExtra("pass")) { Prefs.passenger = true; Live.update { it.copy(paused = true) }; Live.bump() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("lastdemo", false)) { DbQ.run(this, work = { it.hits(1).firstOrNull() }) { h -> h?.let { Prefs.lastDriveId = it.driveId }; Live.bump() } }
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
    private var sendingTrip = false   // main thread only: a second tap while the trip is being read does not open the mail app twice
    private fun sendTrip(driveId: Long) {
        if (sendingTrip) return
        sendingTrip = true
        DbQ.run(this, fail = { sendingTrip = false; toast(this, "That didn't work. Try again.") }, work = { it.tripSpots(driveId) }) { list ->
            sendingTrip = false
            if (list.isEmpty()) toast(this, "No confirmed reports left to send.")
            else if (handOffMail(this, Reports.reportsMail(this, list))) {
                PendingAsk.set(list.joinToString(",") { it.id.toString() }, "trip")
            }
        }
    }

    /** Setup screen closed. A trip that waited for it goes out only if setup actually completed; otherwise it stays queued. */
    private fun finishSetup() {
        stack = emptyList()
        val driveId = Prefs.pendingTrip
        Prefs.pendingTrip = 0
        if (driveId == 0L) return
        if (Prefs.setupDone) sendTrip(driveId)
        else toast(this, "The trip's reports are still waiting. Finish the assistant setup to send them.")
    }

    /** "Not yet" or a dismissed dialog: roll a "Report again" back (the Db write is queued, in order) and clear the pending state at once. */
    private fun closeAskSentWithoutSend() {
        val q = askQuestion ?: PendingAsk.current()   // the question the user was shown, not whatever is stored now
        askQuestion = null; askSent = null
        val plan = Rollback.plan(q.kind, q.again)
        val finish = { PendingAsk.clearIf(q.gen); Live.bump() }
        // The pending state is cleared only after the rollback is written, so a process death in between still has it (the dialog just asks again).
        // The user's "not sent" answer is always applied: the rollback is ONE conditional statement that only touches a row that still carries the report time this
        // question's Report again wrote, so a newer re-report (or anything else that changed the row) makes it a no-op and it needs no generation gate. The
        // question is cleared only after the rollback landed, and only if it is still the displayed one (a newer question survives). A kill or error before the clear
        // re-asks, and running the rollback twice is harmless.
        when (plan) {
            is Rollback.Plan.None -> finish()
            is Rollback.Plan.CannotUndo -> {   // an old-build snapshot: nothing is guessed, and the user is told instead of the spot silently staying re-reported
                toast(this, "BumpLog could not undo that Report again automatically (it was started by an older version). The spot still shows as reported again.")
                finish()
            }
            is Rollback.Plan.Undo -> DbQ.run(this, fail = { Live.bump() }, work = { db -> db.unReportAgainIf(plan.r) }) { finish() }
        }
    }

    @Composable
    private fun Root(live: LiveState) {
        val p = LocalPal.current
        val top = stack.lastOrNull()
        BackHandler(top != null) { stack = stack.dropLast(1) }
        confirmFixed?.let { id ->
            val spR by androidx.compose.runtime.produceState<List<com.bumplog.Spot?>?>(null, id) { value = listOf(try { DbQ.call(this@MainActivity) { it.spot(id) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }) }
            val sp = spR?.get(0)
            if (spR == null) Unit   // still reading: show nothing yet, and do not treat "not read" as "no such spot"
            else if (sp == null || sp.review == "FIXED") confirmFixed = null
            else AlertDialog(
                onDismissRequest = { confirmFixed = null },
                title = { Text("Mark as fixed?") },
                text = { Text("${sp.title}. No impact was detected there on recent passes. Marking it fixed stops warnings for it. You can restore it any time from its screen.") },
                confirmButton = { TextButton({ confirmFixed = null; DbQ.run(this@MainActivity, work = { it.markFixed(id, System.currentTimeMillis()) }) { Notifier.cancel(this@MainActivity, "fixed", id.toInt()); Celebrate.trigger(); Live.bump() } }) { Text("Mark fixed") } },
                dismissButton = { TextButton({ confirmFixed = null }) { Text("Not yet") } },
            )
        }
        askSent?.let { ids ->
            AlertDialog(
                onDismissRequest = {
                    // Same as "Not yet": hiding the dialog without a yes rolls a "Report
                    // again" back and clears the pending state, so a later mail handoff
                    // can't silently overwrite the rollback snapshot.
                    closeAskSentWithoutSend()
                },
                title = { Text("Did the email go out?") },
                text = { Text(if (ids.size == 1) "The report went to your mail app. If it wasn't sent, it stays in the queue for the next try." else "The ${ids.size} reports went to your mail app. If they weren't sent, they stay in the queue for the next try.") },
                confirmButton = { TextButton({
                    val now = System.currentTimeMillis(); val q = askQuestion ?: PendingAsk.current(); val trip = q.kind == "trip"; val tok = q.gen
                    askQuestion = null; askSent = null
                    // The sent marks are written on the Db queue, then the pending state is cleared; the backup that follows a trip runs on IO as before.
                    DbQ.run(this@MainActivity, work = { db -> ids.forEach { db.markSent(it, now) } }) {
                        PendingAsk.clearIf(tok)   // only if no newer mail question was set while this write waited
                        Live.bump()
                        if (trip) ioScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            when (val r = Backup.autoSaveResult(this@MainActivity)) {
                                is Backup.SaveResult.TooLarge -> { Prefs.lastBackupError = r.reason; Notifier.backupFailed(this@MainActivity, r.reason) }
                                is Backup.SaveResult.Failed -> { Prefs.lastBackupError = r.reason; Notifier.backupFailed(this@MainActivity, r.reason) }
                                else -> Prefs.lastBackupError = ""
                            }
                        } }
                    }
                }) { Text("Yes, sent") } },
                dismissButton = { TextButton({
                    closeAskSentWithoutSend()
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
        // Owner rule (Nov 2026): returning from a child screen like Feedback keeps the Settings scroll
        // position; leaving Settings entirely (another tab) resets it, so re-entering starts at the top.
        LaunchedEffect(tab) { if (tab != "settings") settingsScroll.scrollTo(0) }
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
                        "settings" -> SettingsScreen(live.tick, settingsScroll, { askPerms() }, { stack = listOf(Screen.Voice) }, { stack = listOf(Screen.BugReport) }, { stack = listOf(Screen.Setup) }, { info -> updateUi = UpdateUi.Offer(info) })
                        else -> HomeScreen(live, { stack = listOf(Screen.Spot(it)) }, { stack = listOf(Screen.Damage(0)) }, permsOk, { askPerms() },
                            {
                                Prefs.passenger = !Prefs.passenger
                                Live.update { it.copy(paused = Prefs.passenger) }
                            })
                    }
                }
            }
            if (top == null || top is Screen.Spot) {
                Row(Modifier.fillMaxWidth().background(p.nav).navigationBarsPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Selected tint: against card2, which is stricter than the solid nav strip.
                    val selTint = p.accentText(p.card2)
                    val pillOn = true   // Compact pill is the only bottom bar (Settings chooser removed, 1.20)
                    NavItem(pillOn, "Home", Icons.Default.Home, tab == "home" && top == null, selTint, Modifier.weight(1f)) { tab = "home"; stack = emptyList() }
                    NavItem(pillOn, "Review", Icons.Default.CheckCircle, tab == "review" && top == null, selTint, Modifier.weight(1f)) { tab = "review"; stack = emptyList() }
                    NavItem(pillOn, "Reported", StatsIcons.plane, tab == "reported" && top == null, selTint, Modifier.weight(1f)) { tab = "reported"; stack = emptyList() }
                    NavItem(pillOn, "Stats", StatsIcons.bar, tab == "stats" && top == null, selTint, Modifier.weight(1f)) { tab = "stats"; stack = emptyList() }
                    NavItem(pillOn, "Settings", Icons.Default.Settings, tab == "settings" && top == null, selTint, Modifier.weight(1f)) { tab = "settings"; stack = emptyList() }
                }
            }
        }
    }

    @Composable
    private fun NavItem(pill: Boolean, label: String, icon: ImageVector, sel: Boolean, selTint: Color, m: Modifier, onClick: () -> Unit) {
        // A tab role and selected state so TalkBack announces what is active; at least 48dp of touch height.
        // The tab sinks and ticks on press like every other button.
        Column(m.pressFeel(role = Role.Tab, selected = sel, onPress = onClick).heightIn(min = 48.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) {
                // Compact pill (Settings > Bottom bar): same bar height as flat, a slim capsule behind the selected icon.
                if (pill && sel) Box(Modifier.size(52.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(LocalPal.current.accent.copy(alpha = 0.17f)))
                Icon(icon, null, tint = if (sel) selTint else LocalPal.current.muted, modifier = Modifier.size(if (pill) 22.dp else 26.dp))
            }
            Txt(label, 12, FontWeight.Medium, if (sel) selTint else LocalPal.current.muted)
        }
    }
}
