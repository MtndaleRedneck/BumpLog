package com.bumplog

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlin.math.sin
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

    // A short duck asks music to dip while an alert plays, like navigation guidance.
    private val focus by lazy { AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build() }
    private fun duck(ctx: Context) = ctx.getSystemService(AudioManager::class.java).requestAudioFocus(focus)
    private fun unduck(ctx: Context) = ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus)

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

    /** Speaker key of the phone's default voice, or null when it can't be read. Used to hide a "Phone default" row that would duplicate a listed voice. */
    fun defaultSpeakerKey(): String? = try { tts?.defaultVoice?.name?.let { speakerKey(it) } } catch (e: Exception) { null }

    /** Speaks a sample in one voice ("" = phone default), whatever the Sound setting is. */
    fun previewVoice(ctx: Context, voiceName: String) {
        if (isNatural(voiceName)) { if (!playNatural(ctx, voiceName, "generic")) { init(ctx); tts?.takeIf { ttsReady }?.let { applyVoice(it, ""); it.speak(phraseFor(null, 0, true), TextToSpeech.QUEUE_FLUSH, null, "bumplog-preview") } }; return }
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

    /** Built-in natural voices: pre-recorded clips shipped inside the app. The saved voice value is "natural:<id>". */
    const val NATURAL_PREFIX = "natural:"
    val naturalVoices: List<Pair<String, String>> = listOf(
        "natural:am_adam" to "Adam", "natural:am_michael" to "Michael", "natural:af" to "Default",
        "natural:af_bella" to "Bella", "natural:af_sarah" to "Sarah", "natural:af_sky" to "Sky",
    )

    fun isNatural(voice: String) = naturalVoices.any { it.first == voice }

    /** Plays the built-in clip for [key] in a natural voice. Returns false when it can't, so the caller falls back to speech. */
    fun playNatural(ctx: Context, voice: String, key: String, onDone: () -> Unit = {}): Boolean {
        if (!isNatural(voice)) return false
        var mp: android.media.MediaPlayer? = null
        return try {
            val afd = ctx.assets.openFd("natural/" + voice.removePrefix(NATURAL_PREFIX) + "/" + key + ".m4a")
            val p = android.media.MediaPlayer()
            mp = p
            p.setAudioAttributes(attrs)
            afd.use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.prepare()
            stopClip()
            p.setOnCompletionListener { it.release(); if (clipPlayer === it) clipPlayer = null; onDone() }
            p.start(); clipPlayer = p
            android.util.Log.d("BumpLog", "natural clip played: $voice $key ${p.duration}ms")
            true
        } catch (e: Exception) {
            try { mp?.release() } catch (x: Exception) { }
            android.util.Log.d("BumpLog", "natural clip failed: $voice $key ${e.message}")
            false
        }
    }

    /** Stops and releases the clip that is playing, and forgets it. */
    private fun stopClip() {
        val old = clipPlayer
        clipPlayer = null
        try { old?.release() } catch (e: Exception) { }
    }

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

    /** Words for a heads-up. A spot whose type is only a guess stays generic. */
    fun phraseFor(s: Spot?, feet: Int, final: Boolean): String {
        val name = if (s == null) "Road defect ahead" else Notifier.aheadName(s)
        return "$name."
    }

    private var clipPlayer: android.media.MediaPlayer? = null

    /** Plays the user's own recording for [key]. Returns false when there is none, so the caller can fall back to speech. */
    fun playClip(ctx: Context, key: String, onDone: () -> Unit = {}): Boolean {
        if (!hasClip(ctx, key)) return false
        var mp: android.media.MediaPlayer? = null
        return try {
            val p = android.media.MediaPlayer()
            mp = p
            p.setAudioAttributes(attrs)
            p.setDataSource(clipFile(ctx, key).absolutePath)
            p.prepare()
            stopClip()
            p.setOnCompletionListener { it.release(); if (clipPlayer === it) clipPlayer = null; onDone() }
            p.start(); clipPlayer = p; true
        } catch (e: Exception) { try { mp?.release() } catch (x: Exception) { }; false }
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
            r.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) android.os.Handler(android.os.Looper.getMainLooper()).post { stopRecording(true); onRecordingEnded?.invoke() }
            }
            // Records to a temp file; the old clip is only replaced once a new recording finished properly.
            val tmp = File(clipFile(ctx, key).path + ".tmp")
            tmp.delete()
            r.setOutputFile(tmp.absolutePath)
            r.prepare(); r.start(); recorder = r; recordingKey = key; recordingCtx = ctx.applicationContext; true
        } catch (e: Exception) { try { File(clipFile(ctx, key).path + ".tmp").delete() } catch (x: Exception) { }; recorder = null; recordingKey = null; false }
    }

    private var recordingCtx: Context? = null

    /** Called on the main thread when a recording ends by itself (the 8 second limit). */
    var onRecordingEnded: (() -> Unit)? = null

    /** Stops the recorder and releases the mic. [save] false throws the take away (screen left, app backgrounded). */
    fun stopRecording(save: Boolean = true) {
        val r = recorder ?: return
        val key = recordingKey; val ctx = recordingCtx
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }
        try { r.release() } catch (e: Exception) { }
        recorder = null; recordingKey = null
        if (key != null && ctx != null) {
            val tmp = File(clipFile(ctx, key).path + ".tmp")
            if (save && tmp.length() > (if (ok) 0 else 2000)) tmp.copyTo(clipFile(ctx, key), overwrite = true)
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
        duck(ctx)
        var done = false
        val finish = { if (!done) { done = true; unduck(ctx) } }
        if (mode == "BOTH" || mode == "TONE") tone(ctx, urgent)
        if (mode == "BOTH" || mode == "VOICE") {
            init(ctx)
            // The voice starts once the tone has had its moment: Chime and Alarm ring 2.5 s, the beeps are short.
            val delay = if (mode != "BOTH") 0L else if (Prefs.tone == "CHIME" || Prefs.tone == "ALARM") 1500L else 700L
            main.postDelayed({
                val played = (Prefs.voice == CUSTOM && playClip(ctx, key, finish)) || playNatural(ctx, Prefs.voice, key, finish)
                if (!played && !speak(phrase, finish)) finish()
            }, delay)
        } else main.postDelayed({ finish() }, 3000) // tone only: hold the duck until it has played out
    }

    private fun speak(phrase: String, onDone: () -> Unit = {}): Boolean {
        val t = tts ?: return false
        if (!ttsReady) return false
        applyVoice(t, Prefs.voice)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { onDone() }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone() }
            // A settings preview flushes an alert mid-sentence: that fires onStop, not onDone, and the duck would stick.
            override fun onStop(id: String?, interrupted: Boolean) { onDone() }
        })
        return t.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "bumplog") == TextToSpeech.SUCCESS
    }

    private fun tone(ctx: Context, urgent: Boolean) {
        when (Prefs.tone) {
            "BEEP" -> beep(400)
            "DOUBLE" -> beep(200, second = true)
            "ALARM" -> ring(ctx, RingtoneManager.TYPE_ALARM)
            else -> ring(ctx, RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    /** One or two short sine beeps through the same navigation-guidance channel as the other alerts. */
    private fun beep(ms: Int, second: Boolean = false) {
        try {
            val rate = 22050
            val gapMs = if (second) 100 else 0
            val total = ms + gapMs + (if (second) ms else 0)
            val n = rate * total / 1000
            val buf = ShortArray(n)
            for (i in 0 until n) {
                val tMs = i * 1000L / rate
                val on = tMs < ms || (second && tMs >= ms + gapMs)
                buf[i] = if (on) (sin(2 * Math.PI * 880.0 * i / rate) * Short.MAX_VALUE * 0.6).toInt().toShort() else 0
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, n)
            track.play()
            main.postDelayed({ try { track.stop(); track.release() } catch (e: Exception) { } }, total + 300L)
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

/**
 * Voice names look like "en-us-x-tpd-local" or "en-us-x-sfg#female_1-network". The speaker is the part before any "#" variant
 * and before the local/network/embedded suffix, so all the entries for one speaker share a key.
 */
fun speakerKey(name: String) = name.lowercase().replace(Regex("[-_](local|network|embedded)(-.*)?$"), "")
