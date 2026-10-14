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
    data class Filed(val reportId: String, val ref: String, val existingRef: String = "") : PendingLink()
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
    private var confirmFixed by mutableStateOf<Long?>(null)
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
        if (savedInstanceState == null) checkForUpdate(if (BuildConfig.DEBUG) intent.getStringExtra("updatecheck") else null)
        setContent {
            val live by Live.state.collectAsState()
            var themeKey by remember { mutableStateOf(0) }
            // theme/accent changes bump the tick, which re-reads prefs here
            val theme = remember(live.tick) { Prefs.theme }
            val accent = remember(live.tick) { Prefs.accent }
            BumpLogTheme(theme, accent) { Box(Modifier.fillMaxSize()) { Root(live); CelebrationOverlay() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() { Sound.stopRecording(save = false); Sound.onRecordingEnded?.invoke(); super.onStop() }
    override fun onResume() {
        super.onResume(); refreshPerms(); Live.bump()
        // Back from the "Install unknown apps" screen with the file already downloaded and checked: carry on to the installer.
        updateReady?.let { f -> if (packageManager.canRequestPackageInstalls()) { updateReady = null; launchInstaller(f) } }
    }
    override fun onDestroy() { updateJob?.cancel(); super.onDestroy() }

    /** Once per cold open, only when the user has the Settings toggle on. Silent on any failure, and never shown during a drive. */
    private fun checkForUpdate(fakeCurrent: String?) {
        if (!Prefs.updateCheck) return
        val current = fakeCurrent ?: BuildConfig.VERSION_NAME
        Updater.clean(this)
        ioScope.launch {
            val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.check(current) } ?: return@launch
            if (info.versionName == Prefs.skippedUpdate || Live.state.value.running) return@launch
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
            val list = Db.get(this).tripSpots(i.getLongExtra("sendtrip", 0))
            if (list.isEmpty()) toast(this, "Nothing left to send for that drive.")
            else if (handOffMail(this, Reports.reportsMail(this, list))) {
                val now = System.currentTimeMillis(); list.forEach { Db.get(this).markSent(it.id, now) }; Live.bump()
            }
            i.removeExtra("sendtrip")
        }
        if (spot != 0L && i.getStringExtra("screen") == null) {
            val s = Db.get(this).spot(spot)
            stack = listOf(if (s != null && s.severe && s.review == "PENDING") Screen.Alert(spot) else Screen.Spot(spot))
        }
        if (i.getBooleanExtra("start", false)) startDetection()
        if (BuildConfig.DEBUG && i.getBooleanExtra("demo", false)) { Demo.seed(this); Live.bump() }
        // Debug builds only, for the CI screenshot run: pick the voice source and trigger the celebration.
        if (BuildConfig.DEBUG && i.hasExtra("setvoice")) { Prefs.voice = i.getStringExtra("setvoice").orEmpty(); Live.bump() }
        if (BuildConfig.DEBUG && i.getBooleanExtra("celebrate", false)) Celebrate.trigger()
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
        if (BuildConfig.DEBUG && i.hasExtra("detoff")) { Prefs.detectionEnabled = !i.getBooleanExtra("detoff", false); Live.bump() }
        // bumplog://setup?assistant=instinct&email=...  and  bumplog://filed?id=BL-...
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
                "filed" -> {
                    val id = u.getQueryParameter("id")?.trim().orEmpty()
                    val ref = u.getQueryParameter("ref")?.trim().orEmpty().filter { !it.isISOControl() }
                    if (ref.length > 64) { toast(this, "Filed link ignored: the reference is too long (64 characters max)."); return@let }
                    val sp = if (id.isEmpty() || id.length > 64) null else Db.get(this).spotByReportId(id)
                    val old = if (sp == null) "" else Prefs.filedRef(sp.reportId)
                    if (id.isEmpty()) toast(this, "Filed link ignored: it needs a report ID.")
                    else if (sp == null) toast(this, "Filed link ignored: no report with ID ${id.take(40)} on this phone.")
                    else if (sp.reportStatus == "FILED" && (ref.isEmpty() || ref == old)) toast(this, "Report ${sp.reportId} is already marked Filed.")
                    else pendingLink = PendingLink.Filed(sp.reportId, ref, old)
                }
            }
        }
    }

    private fun startDetection() {
        if (!Prefs.detectionEnabled) return
        if (!permsOk) { askPerms(); return }
        try { DriveService.start(this) } catch (e: Exception) { toast(this, "Could not start: ${e.message}") }
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
                confirmButton = { TextButton({ Db.get(this@MainActivity).markFixed(id, System.currentTimeMillis()); Notifier.cancel(this@MainActivity, 3000 + id.toInt()); confirmFixed = null; Celebrate.trigger(); Live.bump() }) { Text("Mark fixed") } },
                dismissButton = { TextButton({ confirmFixed = null }) { Text("Not yet") } },
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
                            if (u.info.notes.isNotEmpty()) { Spacer(Modifier.height(10.dp)); u.info.notes.forEach { Text("\u2022 $it"); Spacer(Modifier.height(4.dp)) } }
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
                is PendingLink.Filed -> AlertDialog(
                    onDismissRequest = { pendingLink = null },
                    title = { Text(if (pl.existingRef.isNotEmpty() && pl.ref.isNotEmpty() && pl.existingRef != pl.ref) "Replace the reference?" else "Mark as Filed?") },
                    text = { Text("A link says report ${pl.reportId} was filed" + (if (pl.ref.isNotEmpty()) " with reference ${pl.ref}" else "") + "." +
                        (if (pl.existingRef.isNotEmpty() && pl.ref.isNotEmpty() && pl.existingRef != pl.ref) " It already has reference ${pl.existingRef}, which would be replaced." else "") +
                        " Only accept if your assistant told you this.") },
                    confirmButton = { TextButton({
                        val ok = Db.get(ctx).markFiledByReportId(pl.reportId)
                        if (ok && pl.ref.isNotEmpty()) Prefs.setFiledRef(pl.reportId, pl.ref)
                        toast(ctx, if (ok) "Report ${pl.reportId} marked Filed" + (if (pl.ref.isNotEmpty()) " (ref ${pl.ref})." else ".") else "No report with ID ${pl.reportId} on this phone.")
                        pendingLink = null; Live.bump()
                    }) { Text("Mark Filed") } },
                    dismissButton = { TextButton({ pendingLink = null }) { Text("Cancel") } },
                )
            }
        }
        val settingsScroll = androidx.compose.foundation.rememberScrollState()
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (top) {
                    is Screen.Setup -> SetupScreen { stack = emptyList() }
                    is Screen.Alert -> AlertScreen(top.id, live.tick) { stack = emptyList() }
                    is Screen.Spot -> SpotScreen(top.id, live.tick, { stack = stack.dropLast(1) }) { stack = stack + Screen.Damage(it) }
                    is Screen.Damage -> DamageScreen(top.spotId, live.tick, { stack = stack.dropLast(1) }) { stack = listOf(Screen.DamageQueue) }
                    is Screen.DamageQueue -> DamageQueueScreen(live.tick) { stack = emptyList() }
                    is Screen.Voice -> VoiceScreen({ stack = stack + Screen.Recordings }) { tab = "settings"; stack = emptyList() }
                    is Screen.Recordings -> RecordingsScreen { stack = stack.dropLast(1) }
                    is Screen.BugReport -> BugReportScreen { tab = "settings"; stack = emptyList() }
                    null -> when (tab) {
                        "review" -> ReviewScreen(live, { stack = listOf(Screen.Spot(it)) }) { stack = listOf(Screen.Setup) }
                        "stats" -> StatsScreen(live.tick) { stack = listOf(Screen.Spot(it)) }
                        "settings" -> SettingsScreen(live.tick, settingsScroll, { askPerms() }, { stack = listOf(Screen.Voice) }, { stack = listOf(Screen.BugReport) })
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
