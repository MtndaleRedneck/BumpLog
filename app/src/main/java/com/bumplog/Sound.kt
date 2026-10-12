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
            if (ttsReady) { tts?.language = Locale.getDefault(); tts?.setAudioAttributes(attrs) }
            main.post { voicesVersion++ }
        }
    }

    /** Installed voices for the picker as (name, label). */
    fun voices(): List<Pair<String, String>> {
        val vs: Set<Voice> = try { tts?.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        val lang = vs.filter { it.locale.language == Locale.getDefault().language }
        val usable = lang.filter { !it.isNetworkConnectionRequired }.ifEmpty { lang }
        return usable.sortedBy { it.name }.mapIndexed { i, v ->
            v.name to "Voice ${i + 1} (${v.locale.displayCountry.ifBlank { v.locale.language }})"
        }
    }

    /** Speaks a sample in one voice ("" = phone default), whatever the Sound setting is. */
    fun previewVoice(ctx: Context, voiceName: String) {
        init(ctx)
        val t = tts ?: return
        if (!ttsReady) return
        applyVoice(t, voiceName)
        t.speak("Road defect ahead", TextToSpeech.QUEUE_FLUSH, null, "bumplog-preview")
    }

    /** Plays the selected tone once, whatever the Sound setting is. */
    fun previewTone(ctx: Context) = tone(ctx, true)

    private fun applyVoice(t: TextToSpeech, name: String) {
        if (name.isNotBlank()) t.voices?.firstOrNull { it.name == name }?.let { t.voice = it }
        else t.defaultVoice?.let { t.voice = it }
    }

    fun play(ctx: Context, phrase: String, urgent: Boolean) {
        val mode = Prefs.audioMode
        if (mode == "OFF") return
        if (mode == "BOTH" || mode == "TONE") tone(ctx, urgent)
        if (mode == "BOTH" || mode == "VOICE") {
            init(ctx)
            main.postDelayed({ speak(phrase) }, if (mode == "BOTH") 700 else 0)
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
