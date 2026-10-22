package com.bumplog

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.toArgb

/** All settings live in one SharedPreferences file on the phone. Nothing is synced anywhere. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("spotter", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun l(k: String, d: Long) = sp.getLong(k, d)
    const val PASSENGER_MS = 3 * 60 * 60 * 1000L
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d

    /**
     * Cleans pasted or typed addresses: strips ordinary and non-breaking spaces, tabs, newlines and invisible
     * zero-width characters from the ends. Spaces inside an address are left alone, so it stays invalid.
     */
    fun cleanEmail(t: String): String = t.trim { it.isWhitespace() || it in "\u200B\u200C\u200D\u2060\uFEFF\u00A0" }

    var detectionEnabled: Boolean get() = b("detection", true); set(v) = sp.edit().putBoolean("detection", v).apply()
    var cutoffG: Float get() = f("cutoff", 0.85f); set(v) = sp.edit().putFloat("cutoff", v).apply()
    var severeG: Float get() = f("severe", 1.30f); set(v) = sp.edit().putFloat("severe", v).apply()
    var minSpeedMph: Int get() = i("minspeed", 15); set(v) = sp.edit().putInt("minspeed", v).apply()
    var theme: String get() = s("theme", "DARK"); set(v) = sp.edit().putString("theme", v).apply()
    /**
     * The accent as a position in [Swatches]. It is saved as the colour (key "accent_rgb"), not the position, so the picker order can change.
     * An old position-based "accent" is converted once, using the frozen older list.
     */
    var accent: Int
        get() {
            if (!sp.contains("accent_rgb") && sp.contains("accent")) {
                sp.edit().putInt("accent_rgb", AccentStore.resolve(null, sp.getInt("accent", -1))).remove("accent").apply()
            }
            return AccentStore.indexOf(AccentStore.resolve(if (sp.contains("accent_rgb")) sp.getInt("accent_rgb", 0) else null, null))
        }
        set(v) = sp.edit().putInt("accent_rgb", Swatches[v.coerceIn(0, Swatches.lastIndex)].toArgb()).remove("accent").apply()

    /** Draft of the log form: "TYPE|confirmed|SEVERITY|fileTrip", kept so leaving the screen does not lose it. */
    fun logDraft(spotId: Long): String = s("logdraft_$spotId", "")
    fun setLogDraft(spotId: Long, v: String) = sp.edit().apply { if (v.isEmpty()) remove("logdraft_$spotId") else putString("logdraft_$spotId", v) }.apply()
    /** Guide version the owner last acknowledged; a newer guide or email format brings the one-time banner back. */
    var guideSeen: Int get() = i("guide_seen", 0); set(v) = sp.edit().putInt("guide_seen", v).apply()
    var assistant: String get() = s("assistant", "INSTINCT"); set(v) = sp.edit().putString("assistant", v).apply()
    var addrInstinct: String get() = s("addr_instinct", ""); set(v) = sp.edit().putString("addr_instinct", cleanEmail(v)).apply()
    var addrTab: String get() = s("addr_tab", ""); set(v) = sp.edit().putString("addr_tab", cleanEmail(v)).apply()
    var addrCustom: String get() = s("addr_custom", ""); set(v) = sp.edit().putString("addr_custom", cleanEmail(v)).apply()
    var setupDone: Boolean get() = b("setup_done", false); set(v) = sp.edit().putBoolean("setup_done", v).apply()
    /** The setup email left for the mail app at least once. Survives a process kill mid-handoff, so the setup screen can continue at the check-with-you step. */
    var setupEmailSent: Boolean get() = b("setup_email_sent", false); set(v) = sp.edit().putBoolean("setup_email_sent", v).apply()

    /** The address for whichever assistant is selected. Empty until the user sets it up. */
    private fun rawAddress() = cleanEmail(when (assistant) { "TAB" -> addrTab; "CUSTOM" -> addrCustom; else -> addrInstinct })
    /** What the user typed, even if not valid yet. Mail only ever goes to a valid single address (activeAddress). */
    val rawActiveAddress: String get() = rawAddress()
    /** Exactly one plain email address: no spaces, commas, semicolons, angle brackets or quotes. */
    fun isValidEmail(raw: String): Boolean { val s = cleanEmail(raw); return s.length in 5..254 && Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$").matches(s) }
    /** Clears local-only extras on "Delete all data": drafts and temp exports/photos. */
    fun wipeLocalExtras(ctx: Context) {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("logdraft_") }.forEach { e.remove(it) }
        e.apply()
        java.io.File(ctx.cacheDir, "exports").deleteRecursively()
        java.io.File(ctx.filesDir, "voice").deleteRecursively()
    }

    /** Every restorable setting as typed JSON, for the backup file. Drafts, ride-only state and permission-bound switches are left out. */
    fun dumpAll(): org.json.JSONObject {
        val o = org.json.JSONObject()
        for ((k, v) in sp.all) {
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue }
            if (k in SETTING_KEYS) o.put(k, org.json.JSONObject().put("t", t).put("v", if (v is Float) v.toDouble() else v))
        }
        return o
    }

    private val SETTING_KEYS = setOf("detection", "cutoff", "severe", "minspeed", "accent_rgb", "accent", "assistant", "addr_instinct", "addr_tab", "addr_custom",
        "setup_done", "setup_email_sent", "guide_seen", "autostart", "car_addr", "car_name", "warn_far", "warn_near", "audio_mode", "tone", "voice", "hit_timeout", "lock_buttons", "autooff")

    /** Checks one restored value against the type and range its setting expects. Returns the clean value or null to skip it. */
    private fun checked(k: String, t: String, x: org.json.JSONObject): Any? {
        fun str(max: Int = 200): String? = if (t == "s") x.getString("v").takeIf { it.length <= max && it.none { c -> c.isISOControl() } } else null
        fun int(lo: Int, hi: Int): Int? = if (t == "i") x.getInt("v").takeIf { it in lo..hi } else null
        fun flt(lo: Float, hi: Float): Float? = if (t == "f") x.getDouble("v").toFloat().takeIf { it.isFinite() && it in lo..hi } else null
        return when (k) {
            "detection", "setup_done", "setup_email_sent", "autostart", "lock_buttons" -> if (t == "b") x.getBoolean("v") else null
            "guide_seen" -> int(0, 1000000)
            "cutoff" -> flt(0.1f, 5f); "severe" -> flt(0.1f, 10f)
            "minspeed" -> int(0, 100)
            "accent_rgb" -> if (t == "i") AccentStore.resolve(x.getInt("v"), null).takeIf { it == x.getInt("v") } else null
            "accent" -> int(0, LegacySwatchArgb.lastIndex)?.let { AccentStore.resolve(null, it) }
            "warn_far" -> int(100, 10560); "warn_near" -> int(50, 5280)
            "hit_timeout" -> int(0, 30)?.takeIf { it in listOf(0, 10, 20, 30) }
            "autooff" -> int(10, 120)
            "assistant" -> str(10)?.takeIf { it in listOf("INSTINCT", "TAB", "CUSTOM") }
            "audio_mode" -> str(10)?.takeIf { it in listOf("BOTH", "TONE", "VOICE", "OFF") }
            "tone" -> str(10)?.takeIf { it in listOf("CHIME", "ALARM", "BEEP", "DOUBLE") }
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
                if (k !in SETTING_KEYS) continue
                if (k == "accent" && o.has("accent_rgb")) continue
                // An old backup holds the accent as a position; it is converted to the colour and saved under the colour key.
                val dest = if (k == "accent") "accent_rgb" else k
                when (val v = checked(k, t, x) ?: continue) {
                    is Boolean -> e.putBoolean(dest, v); is Int -> e.putInt(dest, v); is Float -> e.putFloat(dest, v); is String -> e.putString(dest, v)
                }
                if (dest == "accent_rgb") e.remove("accent")
                n++
            } catch (ex: Exception) { }
        }
        e.apply(); return n
    }

    var activeAddress: String
        get() = rawAddress().let { if (isValidEmail(it)) it else "" }
        set(v) = when (assistant) { "TAB" -> addrTab = v; "CUSTOM" -> addrCustom = v; else -> addrInstinct = v }

    var updateCheck: Boolean get() = b("update_check", true); set(v) = sp.edit().putBoolean("update_check", v).apply()
    /** Version name the user chose to skip in the update dialog. */
    var skippedUpdate: String get() = s("skipped_update", ""); set(v) = sp.edit().putString("skipped_update", v).apply()
    /** Spots handed to the mail app but not confirmed sent: comma-joined ids, or "" when none. Survives the app being killed. */
    var askSentFor: String get() = s("ask_sent_for", ""); set(v) = sp.edit().putString("ask_sent_for", v).apply()
    /** Which handoff waits on the "did the email go out?" answer: "trip", "single", "again", or "". */
    var askSentKind: String get() = s("ask_sent_kind", ""); set(v) = sp.edit().putString("ask_sent_kind", v).apply()
    /** A re-reported spot's pre-send snapshot: id|reportNo|reportTs|sentTs|outcome|outcomeTs, or "" when none. */
    var askAgain: String get() = s("ask_again", ""); set(v) = sp.edit().putString("ask_again", v).apply()
    /** Folder the owner picked for automatic backups (persistable tree URI); "" when auto backup is off. */
    var backupFolder: String get() = s("backup_folder", ""); set(v) = sp.edit().putString("backup_folder", v).apply()
    /** Time of the last automatic backup that wrote, 0 when none. */
    var lastBackupTs: Long get() = l("last_backup", 0L); set(v) = sp.edit().putLong("last_backup", v).apply()
    var autoStart: Boolean get() = b("autostart", false); set(v) = sp.edit().putBoolean("autostart", v).apply()
    var carAddress: String get() = s("car_addr", ""); set(v) = sp.edit().putString("car_addr", v).apply()
    var carName: String get() = s("car_name", ""); set(v) = sp.edit().putString("car_name", v).apply()
    /** Start time (also the id) of the most recent drive, so Home can show its hit count while parked. */
    var lastDriveId: Long get() = sp.getLong("last_drive_id", 0L); set(v) = sp.edit().putLong("last_drive_id", v).apply()
    var lastAliveMs: Long get() = sp.getLong("last_alive_ms", 0L); set(v) = sp.edit().putLong("last_alive_ms", v).apply()
    var passenger: Boolean get() = b("passenger", false)
        set(v) = sp.edit().putBoolean("passenger", v).putLong("pass_until", if (v) System.currentTimeMillis() + PASSENGER_MS else 0L).putBoolean("pass_warned", false).apply()
    /** Passenger mode runs for three hours, then returns to Driver (never while moving, see DriveService). */
    fun passengerLeftMs(): Long = if (!passenger) 0L else (l("pass_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    fun extendPassenger() { sp.edit().putLong("pass_until", l("pass_until", System.currentTimeMillis()).coerceAtLeast(System.currentTimeMillis()) + 3 * 60 * 60_000L).putBoolean("pass_warned", false).apply() }
    var passengerWarned: Boolean get() = b("pass_warned", false); set(v) = sp.edit().putBoolean("pass_warned", v).apply()
    /** Until this time, the Undo button on the "back to Driver" notice puts Passenger mode back. */
    var passengerUndoUntil: Long get() = l("pass_undo", 0L); set(v) = sp.edit().putLong("pass_undo", v).apply()

    var autoOffMin: Int get() = i("autooff", 30).coerceIn(10, 120); set(v) = sp.edit().putInt("autooff", v.coerceIn(10, 120)).apply()
    var warnFarFt: Int get() = i("warn_far", 1320); set(v) = sp.edit().putInt("warn_far", v).apply()
    var warnNearFt: Int get() = i("warn_near", 600); set(v) = sp.edit().putInt("warn_near", v).apply()
    var audioMode: String get() = s("audio_mode", "BOTH"); set(v) = sp.edit().putString("audio_mode", v).apply()
    var tone: String get() = s("tone", "CHIME"); set(v) = sp.edit().putString("tone", v).apply()
    var voice: String get() = s("voice", ""); set(v) = sp.edit().putString("voice", v).apply()
    var overlay: Boolean get() = b("overlay", false); set(v) = sp.edit().putBoolean("overlay", v).apply()
    /** Seconds before a major-hit alert dismisses itself: 10, 20, 30, or 0 for never. Default 20. */
    private fun b2(k: String, d: Int) = sp.getInt(k, d)
    var hitTimeoutSec: Int
        get() = b2("hit_timeout", 20).let { if (it in listOf(0, 10, 20, 30)) it else 20 }
        set(v) = sp.edit().putInt("hit_timeout", v).apply()
    var lockButtons: Boolean get() = b("lock_buttons", false); set(v) = sp.edit().putBoolean("lock_buttons", v).apply()
}
