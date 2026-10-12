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
    var accent: Int get() = i("accent", 0); set(v) = sp.edit().putInt("accent", v).apply()

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

    /** Every setting as typed JSON, for the backup file. Drafts are left out. */
    fun dumpAll(): org.json.JSONObject {
        val o = org.json.JSONObject()
        for ((k, v) in sp.all) {
            if (k.startsWith("logdraft_")) continue
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue }
            o.put(k, org.json.JSONObject().put("t", t).put("v", if (v is Float) v.toDouble() else v))
        }
        return o
    }

    /** Applies settings from [dumpAll]. Unknown or malformed entries are skipped. Returns how many were applied. */
    fun restoreAll(o: org.json.JSONObject): Int {
        val e = sp.edit(); var n = 0
        for (k in o.keys()) {
            try {
                if (k.startsWith("logdraft_")) continue
                val x = o.getJSONObject(k)
                when (x.getString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v")); "i" -> e.putInt(k, x.getInt("v")); "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat()); "s" -> e.putString(k, x.getString("v")); else -> continue
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
