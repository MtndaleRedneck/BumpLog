package com.bumplog

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** review: PENDING, CONFIRMED, DISMISSED, FIXED.  reportStatus: NONE, SENT (handed to mail), FILED (assistant confirmed). */
data class Spot(
    val id: Long, val reportId: String, val lat: Double, val lon: Double, val heading: Double,
    val type: DefectType, val typeUser: Boolean, val maxG: Float, val hitCount: Int, val speedMph: Float,
    val firstTs: Long, val lastTs: Long, val driveId: Long, val review: String, val warn: Boolean,
    val reportStatus: String, val sentTs: Long, val address: String, val cleanPasses: Int,
    val severity: String = "MAJOR", val fileTrip: Boolean = false, val saves: Int = 0,
    val fixedAfterReport: Boolean = false, val fixedTs: Long = 0,
) {
    val severe: Boolean get() = maxG >= Prefs.severeG
    val title: String get() = address.ifBlank { "%.5f, %.5f".format(lat, lon) }
}

data class Hit(
    val id: Long, val spotId: Long, val driveId: Long, val ts: Long, val lat: Double, val lon: Double,
    val heading: Double, val speedMph: Float, val peakG: Float, val durationMs: Long, val peaks: Int, val type: DefectType,
)

data class Damage(
    val id: Long, val reportId: String, val hitId: Long, val spotId: Long, val category: String,
    val note: String, val ts: Long, val status: String,
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "spotter.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE spots(id INTEGER PRIMARY KEY AUTOINCREMENT, report_id TEXT, lat REAL, lon REAL, heading REAL,
            type TEXT, type_user INTEGER, max_g REAL, hit_count INTEGER, speed REAL, first_ts INTEGER, last_ts INTEGER,
            drive_id INTEGER, review TEXT, warn INTEGER, report_status TEXT, sent_ts INTEGER, address TEXT, clean_passes INTEGER, severity TEXT DEFAULT 'MAJOR', file_trip INTEGER DEFAULT 0, saves INTEGER DEFAULT 0, fixed_after_report INTEGER DEFAULT 0, fixed_ts INTEGER DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE hits(id INTEGER PRIMARY KEY AUTOINCREMENT, spot_id INTEGER, drive_id INTEGER, ts INTEGER,
            lat REAL, lon REAL, heading REAL, speed REAL, peak_g REAL, duration INTEGER, peaks INTEGER, type TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE damage(id INTEGER PRIMARY KEY AUTOINCREMENT, report_id TEXT, hit_id INTEGER, spot_id INTEGER,
            category TEXT, note TEXT, ts INTEGER, status TEXT)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("ALTER TABLE spots ADD COLUMN fixed_after_report INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE spots ADD COLUMN fixed_ts INTEGER DEFAULT 0")
        }
    }

    // ---- spots ----

    private fun spotOf(c: Cursor) = Spot(
        c.getLong(0), c.getString(1) ?: "", c.getDouble(2), c.getDouble(3), c.getDouble(4),
        DefectType.parse(c.getString(5)), c.getInt(6) == 1, c.getFloat(7), c.getInt(8), c.getFloat(9),
        c.getLong(10), c.getLong(11), c.getLong(12), c.getString(13) ?: "PENDING", c.getInt(14) == 1,
        c.getString(15) ?: "NONE", c.getLong(16), c.getString(17) ?: "", c.getInt(18),
        c.getString(19) ?: "MAJOR", c.getInt(20) == 1, c.getInt(21), c.getInt(22) == 1, c.getLong(23),
    )

    private val spotCols = "id,report_id,lat,lon,heading,type,type_user,max_g,hit_count,speed,first_ts,last_ts,drive_id,review,warn,report_status,sent_ts,address,clean_passes,severity,file_trip,saves,fixed_after_report,fixed_ts"

    /** Test-mode spots have report IDs starting with TEST-. They stay out of every list, count and export unless asked for. */
    private fun spots(where: String?, args: Array<String>?, order: String = "last_ts DESC", limit: String? = null, test: Boolean = false): List<Spot> {
        val out = ArrayList<Spot>()
        val w = listOfNotNull(where, if (test) null else "report_id NOT LIKE 'TEST-%'").joinToString(" AND ").ifEmpty { null }
        readableDatabase.query("spots", spotCols.split(",").toTypedArray(), w, args, null, null, order, limit).use { c ->
            while (c.moveToNext()) out.add(spotOf(c))
        }
        return out
    }

    @Synchronized fun spotByReportId(rid: String): Spot? = spots("report_id=?", arrayOf(rid), test = true).firstOrNull()
    @Synchronized fun spot(id: Long): Spot? = spots("id=?", arrayOf(id.toString()), test = true).firstOrNull()
    @Synchronized fun allSpots(): List<Spot> = spots(null, null)
    @Synchronized fun latestSpot(): Spot? = spots(null, null, limit = "1").firstOrNull()
    @Synchronized fun pendingSpots(): List<Spot> = spots("review='PENDING'", null)
    @Synchronized fun readyToSend(): List<Spot> = spots("review='CONFIRMED' AND report_status='NONE'", null)
    @Synchronized fun warnSpots(): List<Spot> = spots("warn=1 AND review NOT IN ('DISMISSED','FIXED')", null)
    @Synchronized fun driveSpots(driveId: Long): List<Spot> = spots("drive_id=?", arrayOf(driveId.toString()))

    /**
     * Records a hit. A hit within 30 m of an existing spot, travelling the same way, joins that spot
     * and bumps its count. Otherwise it starts a new spot.
     */
    @Synchronized fun recordHit(h: Hit, guess: DefectType): Spot {
        val db = writableDatabase
        val near = spots("review!='FIXED'", null).filter {
            Geo.distance(it.lat, it.lon, h.lat, h.lon) <= CLUSTER_M &&
                (it.heading < 0 || h.heading < 0 || Geo.angleDiff(it.heading, h.heading) <= 50)
        }.minByOrNull { Geo.distance(it.lat, it.lon, h.lat, h.lon) }
        val spotId: Long
        if (near != null) {
            spotId = near.id
            val maxG = maxOf(near.maxG, h.peakG)
            val v = ContentValues().apply {
                put("hit_count", near.hitCount + 1); put("max_g", maxG); put("last_ts", h.ts)
                put("drive_id", h.driveId); put("clean_passes", 0)
                if (!near.typeUser) put("type", guess.name)
                if (maxG >= Prefs.severeG && near.review != "DISMISSED") put("warn", 1)
            }
            db.update("spots", v, "id=?", arrayOf(spotId.toString()))
        } else {
            val v = ContentValues().apply {
                put("report_id", newReportId("BL", h.ts)); put("lat", h.lat); put("lon", h.lon); put("heading", h.heading)
                put("type", guess.name); put("type_user", 0); put("max_g", h.peakG); put("hit_count", 1)
                put("speed", h.speedMph); put("first_ts", h.ts); put("last_ts", h.ts); put("drive_id", h.driveId)
                put("review", "PENDING"); put("warn", if (h.peakG >= Prefs.severeG && guess != DefectType.RUMBLE) 1 else 0)
                put("report_status", "NONE"); put("sent_ts", 0); put("address", ""); put("clean_passes", 0)
            }
            spotId = db.insert("spots", null, v)
        }
        val hv = ContentValues().apply {
            put("spot_id", spotId); put("drive_id", h.driveId); put("ts", h.ts); put("lat", h.lat); put("lon", h.lon)
            put("heading", h.heading); put("speed", h.speedMph); put("peak_g", h.peakG); put("duration", h.durationMs)
            put("peaks", h.peaks); put("type", guess.name)
        }
        db.insert("hits", null, hv)
        return spot(spotId)!!
    }

    @Synchronized fun update(id: Long, vararg kv: Pair<String, Any>) {
        val v = ContentValues()
        for ((k, x) in kv) when (x) {
            is Int -> v.put(k, x); is Long -> v.put(k, x); is Double -> v.put(k, x)
            is Float -> v.put(k, x); is Boolean -> v.put(k, if (x) 1 else 0); else -> v.put(k, x.toString())
        }
        writableDatabase.update("spots", v, "id=?", arrayOf(id.toString()))
    }

    fun setReview(id: Long, review: String) = update(id, "review" to review)
    fun setWarn(id: Long, warn: Boolean) = update(id, "warn" to warn)
    fun setType(id: Long, t: DefectType) = update(id, "type" to t.name, "type_user" to true)
    fun setAddress(id: Long, a: String) = update(id, "address" to a)
    /** The user confirmed what this is during the drive. [fileTrip] = include it in the end-of-trip email. */
    fun logSpot(id: Long, t: DefectType, severity: String, fileTrip: Boolean) {
        update(id, "type" to t.name, "type_user" to true, "severity" to severity, "file_trip" to fileTrip, "review" to "CONFIRMED", "warn" to true)
    }
    /** Exactly the spots marked for end-of-trip filing during this drive that have not been handed to mail yet. */
    @Synchronized fun tripSpots(driveId: Long): List<Spot> = spots("file_trip=1 AND report_status='NONE' AND drive_id=?", arrayOf(driveId.toString()), test = driveId == TEST_DRIVE)
    /** A warned spot's heads-up fired and the driver passed it again (hit or no hit). */
    @Synchronized fun addSave(id: Long) { writableDatabase.execSQL("UPDATE spots SET saves=saves+1 WHERE id=?", arrayOf(id)) }
    /** Lifetime counters, all derived from the logged spots (so they go when the data is deleted). */
    class Score(val logged: Int, val sent: Int, val filed: Int, val saves: Int, val warned: Int, val fixedAfterReport: Int = 0)
    @Synchronized fun score(): Score {
        val c = readableDatabase.rawQuery("SELECT " +
            "COALESCE(SUM(CASE WHEN review IN ('CONFIRMED','FIXED') THEN 1 ELSE 0 END),0)," +
            "COALESCE(SUM(CASE WHEN report_status IN ('SENT','FILED') THEN 1 ELSE 0 END),0)," +
            "COALESCE(SUM(CASE WHEN report_status='FILED' THEN 1 ELSE 0 END),0)," +
            "COALESCE(SUM(saves),0)," +
            "COALESCE(SUM(CASE WHEN warn=1 AND review NOT IN ('DISMISSED','FIXED') THEN 1 ELSE 0 END),0)," +
            "COALESCE(SUM(CASE WHEN review='FIXED' AND fixed_after_report=1 THEN 1 ELSE 0 END),0) FROM spots WHERE report_id NOT LIKE 'TEST-%'", null)
        c.use { it.moveToFirst(); return Score(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getInt(4), it.getInt(5)) }
    }
    /** Saves per defect type. Spots whose type the user never confirmed are reported under null. */
    @Synchronized fun savesByType(): List<Pair<DefectType?, Int>> {
        val out = ArrayList<Pair<DefectType?, Int>>()
        readableDatabase.rawQuery("SELECT CASE WHEN type_user=1 THEN type ELSE NULL END AS t, SUM(saves) FROM spots WHERE saves>0 AND report_id NOT LIKE 'TEST-%' GROUP BY t", null).use {
            while (it.moveToNext()) out.add((if (it.isNull(0)) null else DefectType.parse(it.getString(0))) to it.getInt(1))
        }
        return out
    }
    /** The hardest single hit on record, test data excluded. */
    @Synchronized fun largestImpact(): Pair<Hit, Spot>? {
        val hid = readableDatabase.rawQuery("SELECT h.id FROM hits h JOIN spots s ON s.id=h.spot_id WHERE s.report_id NOT LIKE 'TEST-%' ORDER BY h.peak_g DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getLong(0) else return null }
        val h = hit(hid) ?: return null
        val s = spot(h.spotId) ?: return null
        return h to s
    }
    @Synchronized fun topSaves(n: Int = 5): List<Spot> = spots("saves>0", null, order = "saves DESC", limit = n.toString())
    fun markSent(id: Long, now: Long) = update(id, "report_status" to "SENT", "sent_ts" to now)
    /** Restores spots and hits from a BumpLog CSV export. Report IDs (BL- or legacy PS-) are kept; IDs already here are skipped. */
    class ImportResult(val imported: Int, val skipped: Int, val damages: Int = 0)
    @Synchronized fun importCsv(text: String): ImportResult? {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        val have = allSpots().map { it.reportId }.toMutableSet()
        val db = writableDatabase
        val made = HashMap<String, Long>()
        var n = 0
        val sections = text.trimStart('\uFEFF', '\n', '\r', ' ').split(Regex("\r?\n[ \t]*\r?\n"), limit = 2)
        val damageText = sections.getOrNull(1)?.trim()
        val lines = sections[0].lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.isEmpty() || !lines[0].startsWith("report_id,")) return null
        val skippedIds = HashSet<String>(); var bad = 0
        for (line in lines.drop(1)) {
            val c = line.split(",", limit = 12)
            if (c.size < 12 || c[0].isBlank()) { bad++; continue }
            if (c[0] in have) { skippedIds.add(c[0]); continue }
            if (c[0].length > 40 || !(c[0].startsWith("BL-") || c[0].startsWith("PS-")) || !c[0].all { it.isLetterOrDigit() || it == '-' }) { bad++; continue }
            try {
                val ts = fmt.parse(c[4])!!.time
                val lat = num(c[5]); val lon = num(c[6])
                val hd = num(c[7]); val spd = num(c[8])
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || hd !in 0.0..360.0 || spd !in 0.0..300.0) { bad++; continue }
                val type = DefectType.parse(c[3]) // old JOINT rows read as Rough stretch
                val g = num(c[9]).toFloat(); if (g !in 0f..50f) { bad++; continue };  val rv = if (c[1] in listOf("PENDING", "CONFIRMED", "DISMISSED", "FIXED")) c[1] else "PENDING"
                var sid = made[c[0]]
                if (sid == null) {
                    sid = db.insert("spots", null, ContentValues().apply {
                        put("report_id", c[0]); put("lat", lat); put("lon", lon); put("heading", hd)
                        put("type", type.name); put("type_user", 0); put("max_g", g); put("hit_count", 0)
                        put("speed", spd); put("first_ts", ts); put("last_ts", ts); put("drive_id", 0)
                        put("review", rv); put("warn", if (g >= Prefs.severeG && rv !in listOf("DISMISSED", "FIXED")) 1 else 0)
                        put("report_status", if (c[2] in listOf("NONE", "SENT", "FILED")) c[2] else "NONE"); put("sent_ts", 0)
                        put("address", c[11].trim().trim('"').trim()); put("clean_passes", 0)
                    })
                    made[c[0]] = sid; n++
                }
                db.insert("hits", null, ContentValues().apply {
                    put("spot_id", sid); put("drive_id", 0); put("ts", ts); put("lat", lat); put("lon", lon)
                    put("heading", hd); put("speed", spd); put("peak_g", g)
                    put("duration", 0); put("peaks", 1); put("type", type.name)
                })
                db.execSQL("UPDATE spots SET hit_count=hit_count+1, max_g=MAX(max_g,?), first_ts=MIN(first_ts,?), last_ts=MAX(last_ts,?) WHERE id=?", arrayOf(g, ts, ts, sid))
            } catch (e: Exception) { bad++ }
        }
        var dn = 0
        if (damageText != null && damageText.startsWith("damage_report_id,")) {
            val dhave = damages().map { it.reportId }.toMutableSet()
            for (line in damageText.lines().drop(1).map { it.trimEnd('\r') }.filter { it.isNotBlank() }) {
                try {
                    val f = splitCsv(line)
                    if (f.size < 6) { bad++; continue }
                    val rid = f[0]
                    if (rid in dhave) { skippedIds.add(rid); continue }
                    if (rid.length > 40 || !rid.startsWith("BD-") || !rid.all { it.isLetterOrDigit() || it == '-' }) { bad++; continue }
                    val sid = db.rawQuery("SELECT id FROM spots WHERE report_id=?", arrayOf(f[1])).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    val st = if (f[4] in listOf("NONE", "SENT", "QUEUED", "FILED")) f[4] else "NONE"
                    db.insert("damage", null, ContentValues().apply {
                        put("report_id", rid); put("hit_id", 0); put("spot_id", sid); put("category", f[2]); put("note", f[5])
                        put("ts", fmt.parse(f[3])!!.time); put("status", st)
                    })
                    dhave.add(rid); dn++
                } catch (e: Exception) { bad++ }
            }
        }
        return ImportResult(n, skippedIds.size + bad, dn)
    }

    /** Splits one CSV line, honouring double-quoted fields with doubled quotes. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var inQ = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQ && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQ = !inQ
                c == ',' && !inQ -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString()); return out
    }

    fun markFiledByReportId(reportId: String): Boolean {
        val n = writableDatabase.update("spots", ContentValues().apply { put("report_status", "FILED") }, "report_id=?", arrayOf(reportId))
        return n > 0
    }
    /** Marks a spot fixed. If it had been reported (sent to mail or filed), it also counts as fixed after reporting. */
    @Synchronized fun markFixed(id: Long, now: Long) {
        val s = spot(id) ?: return
        update(id, "review" to "FIXED", "warn" to false, "fixed_ts" to now, "fixed_after_report" to (s.reportStatus != "NONE"))
    }
    /** "Still there": the spot is not fixed, so the clean-pass count starts over. */
    @Synchronized fun resetCleanPasses(id: Long) = update(id, "clean_passes" to 0)
    @Synchronized fun addCleanPass(id: Long) {
        writableDatabase.execSQL("UPDATE spots SET clean_passes=clean_passes+1 WHERE id=?", arrayOf<Any>(id))
    }

    // ---- hits ----

    private fun hitOf(c: Cursor) = Hit(
        c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getDouble(4), c.getDouble(5), c.getDouble(6),
        c.getFloat(7), c.getFloat(8), c.getLong(9), c.getInt(10), DefectType.parse(c.getString(11)),
    )

    private val hitCols = arrayOf("id", "spot_id", "drive_id", "ts", "lat", "lon", "heading", "speed", "peak_g", "duration", "peaks", "type")

    @Synchronized fun hits(limit: Int = 200): List<Hit> {
        val out = ArrayList<Hit>()
        readableDatabase.query("hits", hitCols, null, null, null, null, "ts DESC", limit.toString()).use { c ->
            while (c.moveToNext()) out.add(hitOf(c))
        }
        return out
    }

    @Synchronized fun hit(id: Long): Hit? {
        readableDatabase.query("hits", hitCols, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) hitOf(c) else null
        }
    }

    @Synchronized fun hitsSince(ts: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM hits WHERE ts>=?", arrayOf(ts.toString())).use { c -> c.moveToFirst(); c.getInt(0) }

    @Synchronized fun hitsInDrive(driveId: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM hits WHERE drive_id=?", arrayOf(driveId.toString())).use { c -> c.moveToFirst(); c.getInt(0) }

    // ---- damage ----

    @Synchronized fun addDamage(hitId: Long, spotId: Long, category: String, note: String, now: Long): Damage {
        val rid = newReportId("BD", now)
        val v = ContentValues().apply {
            put("report_id", rid); put("hit_id", hitId); put("spot_id", spotId); put("category", category)
            put("note", note); put("ts", now); put("status", "NONE")
        }
        val id = writableDatabase.insert("damage", null, v)
        return Damage(id, rid, hitId, spotId, category, note, now, "NONE")
    }

    @Synchronized fun markDamageSent(id: Long) {
        writableDatabase.update("damage", ContentValues().apply { put("status", "SENT") }, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun setDamageStatus(id: Long, status: String) {
        writableDatabase.update("damage", ContentValues().apply { put("status", status) }, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun queuedDamages(): List<Damage> = damages().filter { it.status == "QUEUED" }
    @Synchronized fun damages(): List<Damage> {
        val out = ArrayList<Damage>()
        readableDatabase.query("damage", arrayOf("id", "report_id", "hit_id", "spot_id", "category", "note", "ts", "status"),
            null, null, null, null, "ts DESC").use { c ->
            while (c.moveToNext()) out.add(Damage(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4), c.getString(5), c.getLong(6), c.getString(7)))
        }
        return out
    }

    /** Creates a flagged test spot. It has no hits, never warns on the road, and is invisible to lists and the scorecard. */
    @Synchronized fun addTestSpot(type: DefectType, confirmed: Boolean, severity: String, fileTrip: Boolean, maxG: Float): Spot {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("spots", null, ContentValues().apply {
            put("report_id", "TEST-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + "-" + (now % 1000))
            put("lat", 39.5); put("lon", -98.35); put("heading", -1.0); put("type", type.name); put("type_user", if (confirmed) 1 else 0)
            put("max_g", maxG); put("hit_count", 0); put("speed", 40.0); put("first_ts", now); put("last_ts", now); put("drive_id", TEST_DRIVE)
            put("review", if (confirmed) "CONFIRMED" else "PENDING"); put("warn", 0); put("report_status", "NONE"); put("sent_ts", 0)
            put("address", "Test location (not real)"); put("clean_passes", 0); put("severity", severity); put("file_trip", if (fileTrip) 1 else 0); put("saves", 0)
        })
        return spot(id)!!
    }
    @Synchronized fun clearTestData(): Int = writableDatabase.delete("spots", "report_id LIKE 'TEST-%'", null)
    @Synchronized fun testCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM spots WHERE report_id LIKE 'TEST-%'", null).use { it.moveToFirst(); it.getInt(0) }

    /** Parses a number that may use a comma decimal; throws on junk or non-finite values. */
    private fun num(t: String): Double {
        val v = t.trim().replace(',', '.').toDouble()
        if (!v.isFinite()) throw NumberFormatException(t)
        return v
    }

    @Synchronized fun wipeAll() {
        writableDatabase.execSQL("DELETE FROM spots"); writableDatabase.execSQL("DELETE FROM hits"); writableDatabase.execSQL("DELETE FROM damage")
    }

    companion object {
        const val CLUSTER_M = 30.0
        const val TEST_DRIVE = -77L
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        fun newReportId(prefix: String, ts: Long): String {
            val d = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts))
            val r = (1..4).map { "ABCDEFGHJKMNPQRSTUVWXYZ23456789".random() }.joinToString("")
            return "$prefix-$d-$r"
        }

        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
