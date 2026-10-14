package com.bumplog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The voices to list: one per speaker, and no "Phone default" row when it would repeat a listed voice. Pure so it can be tested. */
fun voiceChoices(voices: List<Pair<String, String>>, defaultSpeaker: String?): List<Pair<String, String>> {
    val dup = defaultSpeaker != null && voices.any { speakerKey(it.first) == defaultSpeaker }
    return (if (dup) emptyList() else listOf("" to "Phone default")) + voices
}

/** Which row is highlighted: the saved voice, or the listed voice that is the phone default when "Phone default" was saved. */
fun selectedVoiceKey(saved: String, voices: List<Pair<String, String>>, defaultSpeaker: String?): String {
    if (saved.isNotEmpty() || defaultSpeaker == null) return saved
    return voices.firstOrNull { speakerKey(it.first) == defaultSpeaker }?.first ?: ""
}

@Composable
fun VoiceScreen(openRecordings: () -> Unit, done: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var voice by remember { mutableStateOf(Prefs.voice) }
    val voices = remember(Sound.voicesVersion) { Sound.init(ctx); Sound.voices() }
    val defKey = remember(Sound.voicesVersion) { Sound.defaultSpeakerKey() }
    val natural = Sound.naturalVoices
    val rows = voiceChoices(voices, defKey) + listOf(Sound.CUSTOM to "My own recordings")
    val selected = if (voice == Sound.CUSTOM || Sound.isNatural(voice)) voice else selectedVoiceKey(voice, voices, defKey)
    Column(Modifier.fillMaxSize().pane("Voice").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackRow("Voice", done)
        Card {
            Muted("Tap a voice to pick it. Tap Play to hear it.", 12)
            Spacer(Modifier.height(6.dp))
            @Composable
            fun VoiceRow(k: String, label: String) {
                val on = selected == k
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (on) p.accent else p.card2)
                    .clickable { voice = k; Prefs.voice = k; if (k == Sound.CUSTOM) openRecordings() else Sound.previewVoice(ctx, k) }
                    .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(label, 16, FontWeight.Medium, if (on) p.onAccent else p.text, Modifier.weight(1f))
                    Icon(Icons.Default.PlayArrow, "Play", tint = if (on) p.onAccent else p.accent,
                        modifier = Modifier.size(28.dp).clickable { Sound.previewVoice(ctx, k) })
                }
            }
            Txt("Natural voices", 14, FontWeight.Bold)
            Muted("Built in, work offline. They speak the alert phrases only.", 12)
            natural.forEach { (k, label) -> VoiceRow(k, label) }
            Spacer(Modifier.height(10.dp))
            Txt("Phone voices", 14, FontWeight.Bold)
            rows.forEach { (k, label) -> VoiceRow(k, label) }
            if (voices.isEmpty()) Muted("No extra voices found on this phone. Install more in your phone's text-to-speech settings.", 12)
        }
        SmallButton("Done", Modifier.fillMaxWidth(), onClick = done)
    }
}

/** One card: record or import your own clip for each alert. Phrases without a clip use the phone's voice. */
@Composable
fun RecordingsScreen(done: () -> Unit) {
    val ctx = LocalContext.current
    var rec by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableStateOf(0) }
    var wantKey by remember { mutableStateOf<String?>(null) }
    var importKey by remember { mutableStateOf<String?>(null) }
    var deleteKey by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        Sound.onRecordingEnded = { rec = null; bump++ }
        onDispose { Sound.onRecordingEnded = null; Sound.stopRecording(save = false) }
    }
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
        if (uri != null && k != null) runIo(ctx, after = { bump++ }) {
            val f = Sound.clipFile(ctx, k)
            val tmp = java.io.File(f.path + ".tmp")
            val n = ctx.contentResolver.openInputStream(uri)?.use { i ->
                val b = i.readNBytes(5_000_001)
                if (b.size > 5_000_000) -1 else { tmp.writeBytes(b); b.size }
            } ?: -1
            if (n > 0) tmp.copyTo(f, overwrite = true)
            tmp.delete()
            if (n <= 0) "That audio file is empty or over 5 MB, so your existing recording was kept." else "Imported."
        }
    }
    fun record(key: String) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) start(key)
        else { wantKey = key; ask.launch(android.Manifest.permission.RECORD_AUDIO) }
    }
    deleteKey?.let { k ->
        ConfirmDialog("Delete this recording?", "Delete",
            onConfirm = { Sound.clipFile(ctx, k).delete(); deleteKey = null; bump++ }, onCancel = { deleteKey = null })
    }
    Column(Modifier.fillMaxSize().pane("My own recordings").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackRow("My own recordings", done)
        Card(pad = 12.dp) {
            Muted("Record your own voice for each alert, or import an audio file. Anything you leave empty uses the phone voice. Recordings stay on this phone.", 12, Modifier.padding(horizontal = 4.dp))
            key(bump) {
                Sound.clipPhrases.forEach { (key, text) ->
                    val has = Sound.hasClip(ctx, key)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(text, 16, FontWeight.Bold, modifier = Modifier.weight(1f))
                        Muted(if (rec == key) "Recording..." else if (has) "Recorded" else "Not set", 13)
                    }
                    Spacer(Modifier.height(8.dp))
                    val buttons: List<Triple<String, PillKind, () -> Unit>> = when {
                        rec == key -> listOf(Triple("Stop", PillKind.Fill) { Sound.stopRecording(); rec = null; bump++ })
                        has -> listOf(
                            Triple("Play", PillKind.Fill) { Sound.playClip(ctx, key) },
                            Triple("Redo", PillKind.Outline) { record(key) },
                            Triple("Import", PillKind.Outline) { importKey = key; pick.launch(arrayOf("audio/*")) },
                            Triple("Delete", PillKind.Danger) { deleteKey = key })
                        else -> listOf(
                            Triple("Record", PillKind.Fill) { record(key) },
                            Triple("Import", PillKind.Outline) { importKey = key; pick.launch(arrayOf("audio/*")) })
                    }
                    PillRow(buttons)
                }
            }
        }
        SmallButton("Done", Modifier.fillMaxWidth(), onClick = done)
    }
}
