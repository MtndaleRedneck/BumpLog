package com.bumplog

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale

/** Audio alerts: a tone, a spoken phrase, or both, depending on settings. Plays as navigation guidance so it sits over music. */
object Sound {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Bumps when the speech engine finishes loading, so the voice list can redraw. */
    var voicesVersion by mutableStateOf(0)
        private set
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    val tones = listOf("CHIME" to "Chime", "ALARM" to "Alarm", "BEEP" to "Beep", "DOUBLE" to "Double beep")
    val modes = listOf("BOTH" to "Tone and voice", "TONE" to "Tone only", "VOICE" to "Voice only", "OFF" to "Off")

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) { tts?.language = Locale.US; tts?.setAudioAttributes(attrs) }
            main.post { voicesVersion++ }
        }
    }

    private fun isUs(v: Voice) = v.locale.language == "en" && v.locale.country == "US"

    /** Installed US English voices for the picker as (name, label). Offline voices come first when there are any. */
    fun voices(): List<Pair<String, String>> {
        val vs: Set<Voice> = try { tts?.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        val us = vs.filter { isUs(it) }
        val usable = us.filter { !it.isNetworkConnectionRequired }.ifEmpty { us }
        // The same speaker can be listed more than once (for example a local and a network entry, or a quality variant). Keep one per speaker.
        val one = usable.sortedBy { it.name }.distinctBy { speakerKey(it.name) }
        return one.mapIndexed { i, v -> v.name to "Voice ${i + 1}" }
    }

    /** Speaks a sample in one voice ("" = phone default), whatever the Sound setting is. */
    fun previewVoice(ctx: Context, voiceName: String) {
        if (voiceName == CUSTOM) { if (!playClip(ctx, "generic")) { init(ctx); tts?.takeIf { ttsReady }?.let { applyVoice(it, ""); it.speak(phraseFor(null, 0, true), TextToSpeech.QUEUE_FLUSH, null, "bumplog-preview") } }; return }
        init(ctx)
        val t = tts ?: return
        if (!ttsReady) return
        applyVoice(t, voiceName)
        t.speak(phraseFor(null, 0, true), TextToSpeech.QUEUE_FLUSH, null, "bumplog-preview")
    }

    /** Plays the selected tone once, whatever the Sound setting is. */
    fun previewTone(ctx: Context) = tone(ctx, true)

    private fun applyVoice(t: TextToSpeech, name: String) {
        val picked = if (name.isNotBlank() && name != CUSTOM) t.voices?.firstOrNull { it.name == name && isUs(it) } else null
        if (picked != null) t.voice = picked
        else { t.language = Locale.US; t.defaultVoice?.takeIf { isUs(it) }?.let { t.voice = it } }
    }

    const val CUSTOM = "CUSTOM"

    /** Alerts that can be recorded: (key, spoken text). The key names the clip file. */
    val clipPhrases: List<Pair<String, String>> = listOf(
        "generic" to "Road defect ahead",
        "major_pothole" to "Major pothole ahead", "minor_pothole" to "Minor pothole ahead",
        "major_bump" to "Major bump ahead", "minor_bump" to "Minor bump ahead",
        "major_rough" to "Major rough road ahead", "minor_rough" to "Minor rough road ahead",
        "hit" to "Bump detected",
    )

    fun clipKey(s: Spot?): String {
        if (s == null || !s.typeUser) return "generic"
        val t = when (s.type) { DefectType.POTHOLE -> "pothole"; DefectType.FAULTING -> "bump"; DefectType.RUMBLE -> "rough" }
        return (if (s.severity == "MINOR") "minor_" else "major_") + t
    }

    fun clipFile(ctx: Context, key: String) = java.io.File(java.io.File(ctx.filesDir, "voice").apply { mkdirs() }, "$key.clip")
    fun hasClip(ctx: Context, key: String) = clipFile(ctx, key).let { it.isFile && it.length() > 0 }

    /** Words for a heads-up, in the chosen style. A spot whose type is only a guess stays generic. */
    fun phraseFor(s: Spot?, feet: Int, final: Boolean): String {
        val name = if (s == null) "Road defect ahead" else Notifier.aheadName(s)
        val low = name.replaceFirstChar { it.lowercase() }
        val dist = if (feet >= 1000) "in a quarter mile" else "in ${feet / 50 * 50} feet"
        return when (Prefs.voiceStyle) {
            "MAPS" -> if (final || feet <= 0) "$name." else "${dist.replaceFirstChar { it.uppercase() }}, $low."
            "WAZE" -> if (final || feet <= 0) "$name, right ahead." else "Heads up, $low."
            else -> "$name."
        }
    }

    private var clipPlayer: android.media.MediaPlayer? = null

    /** Plays the user's own recording for [key]. Returns false when there is none, so the caller can fall back to speech. */
    fun playClip(ctx: Context, key: String): Boolean {
        if (!hasClip(ctx, key)) return false
        return try {
            clipPlayer?.release()
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(clipFile(ctx, key).absolutePath)
            mp.setOnCompletionListener { it.release(); if (clipPlayer === it) clipPlayer = null }
            mp.prepare(); mp.start(); clipPlayer = mp; true
        } catch (e: Exception) { false }
    }

    private var recorder: android.media.MediaRecorder? = null
    private var recordingKey: String? = null
    val recordingNow: String? get() = recordingKey

    /** Starts recording a clip for [key]. Needs the microphone permission already granted. */
    fun startRecording(ctx: Context, key: String): Boolean {
        stopRecording()
        return try {
            val r = android.media.MediaRecorder(ctx.applicationContext)
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000); r.setAudioSamplingRate(44100)
            r.setMaxDuration(8000)
            // Records to a temp file; the old clip is only replaced once a new recording finished properly.
            val tmp = File(clipFile(ctx, key).path + ".tmp")
            tmp.delete()
            r.setOutputFile(tmp.absolutePath)
            r.prepare(); r.start(); recorder = r; recordingKey = key; recordingCtx = ctx.applicationContext; true
        } catch (e: Exception) { try { File(clipFile(ctx, key).path + ".tmp").delete() } catch (x: Exception) { }; recorder = null; recordingKey = null; false }
    }

    private var recordingCtx: Context? = null

    fun stopRecording() {
        val r = recorder ?: return
        val key = recordingKey; val ctx = recordingCtx
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }
        try { r.release() } catch (e: Exception) { }
        recorder = null; recordingKey = null
        if (key != null && ctx != null) {
            val tmp = File(clipFile(ctx, key).path + ".tmp")
            if (tmp.length() > (if (ok) 0 else 2000)) tmp.copyTo(clipFile(ctx, key), overwrite = true)
            tmp.delete()
        }
    }

    /** Speaks or plays the heads-up for [s] (null = the generic one) per the Sound setting and chosen voice. */
    fun playAhead(ctx: Context, s: Spot?, feet: Int, final: Boolean, urgent: Boolean) {
        playPhrase(ctx, phraseFor(s, feet, final), urgent, clipKey(s))
    }

    fun play(ctx: Context, phrase: String, urgent: Boolean, key: String = "generic") = playPhrase(ctx, phrase, urgent, key)

    private fun playPhrase(ctx: Context, phrase: String, urgent: Boolean, key: String) {
        val mode = Prefs.audioMode
        if (mode == "OFF") return
        if (mode == "BOTH" || mode == "TONE") tone(ctx, urgent)
        if (mode == "BOTH" || mode == "VOICE") {
            init(ctx)
            main.postDelayed({ if (!(Prefs.voice == CUSTOM && playClip(ctx, key))) speak(phrase) }, if (mode == "BOTH") 700 else 0)
        }
    }

    private fun speak(phrase: String) {
        val t = tts ?: return
        if (!ttsReady) return
        applyVoice(t, Prefs.voice)
        t.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "bumplog")
    }

    private fun tone(ctx: Context, urgent: Boolean) {
        when (Prefs.tone) {
            "BEEP" -> beep(ToneGenerator.TONE_PROP_BEEP2, 400)
            "DOUBLE" -> beep(ToneGenerator.TONE_PROP_PROMPT, 500)
            "ALARM" -> ring(ctx, RingtoneManager.TYPE_ALARM)
            else -> ring(ctx, RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    private fun beep(tone: Int, ms: Int) {
        try {
            val g = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            g.startTone(tone, ms)
            main.postDelayed({ g.release() }, ms + 300L)
        } catch (e: Exception) { }
    }

    private fun ring(ctx: Context, type: Int) {
        try {
            val uri = RingtoneManager.getDefaultUri(type)
            val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
            r.audioAttributes = attrs
            r.play()
            main.postDelayed({ try { r.stop() } catch (e: Exception) { } }, 2500)
        } catch (e: Exception) { }
    }
}

/** Voice names look like "en-us-x-tpd-local"; the speaker is everything before the local/network/embedded suffix. */
fun speakerKey(name: String) = name.lowercase().replace(Regex("[-_](local|network|embedded)(-.*)?$"), "")
