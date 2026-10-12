package com.bumplog

import android.content.Context
import android.content.SharedPreferences

/** All settings live in one SharedPreferences file on the phone. Nothing is synced anywhere. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("spotter", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d

    /**
     * Cleans pasted or typed addresses: strips ordinary and non-breaking spaces, tabs, newlines and invisible
     * zero-width characters from the ends. Spaces inside an address are left alone, so it stays invalid.
     */
    fun cleanEmail(t: String): String = t.trim { it.isWhitespace() || it in "\u200B\u200C\u200D\u2060\uFEFF\u00A0" }

    var detectionEnabled: Boolean get() = b("detection", true); set(v) = sp.edit().putBoolean("detection", v).apply()
    var cutoffG: Float get() = f("cutoff", 0.80f); set(v) = sp.edit().putFloat("cutoff", v).apply()
    var severeG: Float get() = f("severe", 1.30f); set(v) = sp.edit().putFloat("severe", v).apply()
    var minSpeedMph: Int get() = i("minspeed", 15); set(v) = sp.edit().putInt("minspeed", v).apply()
    var theme: String get() = s("theme", "DARK"); set(v) = sp.edit().putString("theme", v).apply()
    var accent: Int get() = i("accent", Swatches.lastIndex); set(v) = sp.edit().putInt("accent", v).apply()

    /** Draft of the log form: "TYPE|confirmed|SEVERITY|fileTrip", kept so leaving the screen does not lose it. */
    fun logDraft(spotId: Long): String = s("logdraft_$spotId", "")
    fun setLogDraft(spotId: Long, v: String) = sp.edit().apply { if (v.isEmpty()) remove("logdraft_$spotId") else putString("logdraft_$spotId", v) }.apply()
    fun setFiledRef(reportId: String, ref: String) = sp.edit().putString("filedref_$reportId", ref).apply()
    fun filedRef(reportId: String): String = s("filedref_$reportId", "")
    var assistant: String get() = s("assistant", "INSTINCT"); set(v) = sp.edit().putString("assistant", v).apply()
    var addrInstinct: String get() = s("addr_instinct", ""); set(v) = sp.edit().putString("addr_instinct", cleanEmail(v)).apply()
    var addrTab: String get() = s("addr_tab", ""); set(v) = sp.edit().putString("addr_tab", cleanEmail(v)).apply()
    var addrCustom: String get() = s("addr_custom", ""); set(v) = sp.edit().putString("addr_custom", cleanEmail(v)).apply()
    var setupDone: Boolean get() = b("setup_done", false); set(v) = sp.edit().putBoolean("setup_done", v).apply()

    /** The address for whichever assistant is selected. Empty until the user sets it up. */
    private fun rawAddress() = cleanEmail(when (assistant) { "TAB" -> addrTab; "CUSTOM" -> addrCustom; else -> addrInstinct })
    /** What the user typed, even if not valid yet. Mail only ever goes to a valid single address (activeAddress). */
    val rawActiveAddress: String get() = rawAddress()
    /** Exactly one plain email address: no spaces, commas, semicolons, angle brackets or quotes. */
    fun isValidEmail(raw: String): Boolean { val s = cleanEmail(raw); return s.length in 5..254 && Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$").matches(s) }
    /** Clears local-only extras on "Delete all data": drafts, saved filed references and temp exports/photos. */
    fun wipeLocalExtras(ctx: Context) {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("logdraft_") || it.startsWith("filedref_") }.forEach { e.remove(it) }
        e.apply()
        java.io.File(ctx.cacheDir, "exports").deleteRecursively()
        java.io.File(ctx.filesDir, "voice").deleteRecursively()
    }

    /** Every restorable setting as typed JSON, for the backup file. Drafts, ride-only state and permission-bound switches are left out. */
    fun dumpAll(): org.json.JSONObject {
        val o = org.json.JSONObject()
        for ((k, v) in sp.all) {
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue }
            if (k in SETTING_KEYS || (k.startsWith("filedref_") && v is String)) o.put(k, org.json.JSONObject().put("t", t).put("v", if (v is Float) v.toDouble() else v))
        }
        return o
    }

    private val SETTING_KEYS = setOf("detection", "cutoff", "severe", "minspeed", "theme", "accent", "assistant", "addr_instinct", "addr_tab", "addr_custom",
        "setup_done", "autostart", "car_addr", "car_name", "warn_far", "warn_near", "audio_mode", "tone", "voice", "voice_style", "hit_timeout", "lock_buttons")

    /** Checks one restored value against the type and range its setting expects. Returns the clean value or null to skip it. */
    private fun checked(k: String, t: String, x: org.json.JSONObject): Any? {
        fun str(max: Int = 200): String? = if (t == "s") x.getString("v").takeIf { it.length <= max && it.none { c -> c.isISOControl() } } else null
        fun int(lo: Int, hi: Int): Int? = if (t == "i") x.getInt("v").takeIf { it in lo..hi } else null
        fun flt(lo: Float, hi: Float): Float? = if (t == "f") x.getDouble("v").toFloat().takeIf { it.isFinite() && it in lo..hi } else null
        return when (k) {
            "detection", "setup_done", "autostart", "lock_buttons" -> if (t == "b") x.getBoolean("v") else null
            "cutoff" -> flt(0.1f, 5f); "severe" -> flt(0.1f, 10f)
            "minspeed" -> int(0, 100); "accent" -> int(0, Swatches.lastIndex)
            "warn_far" -> int(100, 10560); "warn_near" -> int(50, 5280)
            "hit_timeout" -> int(0, 30)?.takeIf { it in listOf(0, 10, 20, 30) }
            "theme" -> str(10)?.takeIf { it in listOf("DARK", "LIGHT", "SYSTEM") }
            "assistant" -> str(10)?.takeIf { it in listOf("INSTINCT", "TAB", "CUSTOM") }
            "audio_mode" -> str(10)?.takeIf { it in listOf("BOTH", "TONE", "VOICE", "OFF") }
            "tone" -> str(10)?.takeIf { it in listOf("CHIME", "ALARM", "BEEP", "DOUBLE") }
            "voice_style" -> str(10)?.takeIf { it in listOf("PLAIN", "MAPS", "WAZE") }
            "voice" -> str(200)
            "car_addr", "car_name" -> str(64)
            "addr_instinct", "addr_tab", "addr_custom" -> str(254)?.let { cleanEmail(it) }?.takeIf { it.isEmpty() || isValidEmail(it) }
            else -> null
        }
    }

    /**
     * Applies settings from [dumpAll]. Only known settings with the right type and a sane value are applied; anything else
     * is skipped, so a damaged or hand-edited file cannot break the app. A saved filed reference is only restored where
     * none exists yet. Returns how many were applied.
     */
    fun restoreAll(o: org.json.JSONObject): Int {
        val e = sp.edit(); var n = 0
        for (k in o.keys()) {
            try {
                val x = o.getJSONObject(k); val t = x.getString("t")
                if (k.startsWith("filedref_")) {
                    val id = k.removePrefix("filedref_")
                    val v = if (t == "s") x.getString("v") else continue
                    if (id.length in 4..40 && id.all { it.isLetterOrDigit() || it == '-' } && v.length <= 64 && v.none { it.isISOControl() } && !sp.contains(k)) { e.putString(k, v); n++ }
                    continue
                }
                if (k !in SETTING_KEYS) continue
                when (val v = checked(k, t, x) ?: continue) {
                    is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Float -> e.putFloat(k, v); is String -> e.putString(k, v)
                }
                n++
            } catch (ex: Exception) { }
        }
        e.apply(); return n
    }

    var activeAddress: String
        get() = rawAddress().let { if (isValidEmail(it)) it else "" }
        set(v) = when (assistant) { "TAB" -> addrTab = v; "CUSTOM" -> addrCustom = v; else -> addrInstinct = v }

    var autoStart: Boolean get() = b("autostart", true); set(v) = sp.edit().putBoolean("autostart", v).apply()
    var carAddress: String get() = s("car_addr", ""); set(v) = sp.edit().putString("car_addr", v).apply()
    var carName: String get() = s("car_name", ""); set(v) = sp.edit().putString("car_name", v).apply()
    var passenger: Boolean get() = b("passenger", false); set(v) = sp.edit().putBoolean("passenger", v).apply()

    var warnFarFt: Int get() = i("warn_far", 1320); set(v) = sp.edit().putInt("warn_far", v).apply()
    var warnNearFt: Int get() = i("warn_near", 600); set(v) = sp.edit().putInt("warn_near", v).apply()
    var audioMode: String get() = s("audio_mode", "BOTH"); set(v) = sp.edit().putString("audio_mode", v).apply()
    var tone: String get() = s("tone", "CHIME"); set(v) = sp.edit().putString("tone", v).apply()
    /** How spoken alerts are phrased: PLAIN ("Major pothole ahead"), MAPS (distance first) or WAZE (short and casual). */
    var voiceStyle: String get() = s("voice_style", "PLAIN"); set(v) = sp.edit().putString("voice_style", v).apply()
    var voice: String get() = s("voice", ""); set(v) = sp.edit().putString("voice", v).apply()
    var overlay: Boolean get() = b("overlay", false); set(v) = sp.edit().putBoolean("overlay", v).apply()
    /** Seconds before a major-hit alert dismisses itself: 10, 20, 30, or 0 for never. Default 20. */
    private fun b2(k: String, d: Int) = sp.getInt(k, d)
    var hitTimeoutSec: Int
        get() = b2("hit_timeout", 20).let { if (it in listOf(0, 10, 20, 30)) it else 20 }
        set(v) = sp.edit().putInt("hit_timeout", v).apply()
    var lockButtons: Boolean get() = b("lock_buttons", false); set(v) = sp.edit().putBoolean("lock_buttons", v).apply()
}
