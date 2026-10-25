package com.bumplog

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup and restore. One zip holds every report and hit, damage records, damage photos, your own
 * voice recordings and your settings, plus a spreadsheet CSV of the same data. Restoring it on a fresh
 * install brings everything back. Nothing leaves the phone unless you save or share the file yourself.
 */
object Backup {
    private const val MAX_JSON = 30_000_000
    private const val MAX_ENTRY = 60_000_000L
    private const val MAX_TOTAL = 1_500_000_000L
    /** No leading dot, so neither "." nor ".." can pass as a name. */
    private val safeName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}")

    fun fileName(ext: String): String = "BumpLog-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + "." + ext

    private fun realSpots() = "report_id NOT LIKE 'TEST-%'"

    private fun dump(db: SQLiteDatabase, table: String, where: String?): JSONArray {
        val arr = JSONArray()
        db.query(table, null, where, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    val n = c.getColumnName(i)
                    val key = if (n == "id") "_id" else n
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> o.put(key, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> o.put(key, c.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> o.put(key, c.getString(i))
                        else -> {}
                    }
                }
                arr.put(o)
            }
        }
        return arr
    }

    fun writeZip(ctx: Context, out: OutputStream) {
        val db = Db.get(ctx)
        val h = db.readableDatabase
        val json = synchronized(db) {
            JSONObject().put("format", "BumpLog backup").put("version", 1).put("created", System.currentTimeMillis())
                .put("prefs", Prefs.dumpAll())
                .put("spots", dump(h, "spots", realSpots()))
                .put("hits", dump(h, "hits", "spot_id IN (SELECT id FROM spots WHERE ${realSpots()})"))
                .put("damage", dump(h, "damage", null))
                .put("meta", dump(h, "meta", null))
        }
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("backup.json")); z.write(json.toString().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("BumpLog-export.csv")); z.write(Reports.csvText(ctx).toByteArray()); z.closeEntry()
            for (d in db.damages()) {
                val dir = DamagePhotos.dir(ctx, d.reportId)
                for (f in DamagePhotos.files(ctx, d.reportId)) {
                    z.putNextEntry(ZipEntry("photos/${dir.name}/${f.name}"))
                    f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
            for ((key, _) in Sound.clipPhrases) if (Sound.hasClip(ctx, key)) {
                z.putNextEntry(ZipEntry("voice/$key.clip"))
                Sound.clipFile(ctx, key).inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }


    /**
     * Creates a file, writes the zip, and hands the file back only when the write finished. Any failure - the stream
     * never opening OR the write throwing midway - removes the just-created partial file, so a failed save leaves no
     * half-written zip that prune could mistake for the day's newest backup (item 6). Pure plumbing, unit-tested.
     */
    internal fun <T> writeOrDelete(create: () -> T?, openOut: (T) -> OutputStream?, write: (OutputStream) -> Unit, delete: (T) -> Unit): T? {
        val created = create() ?: return null
        try {
            val ok = openOut(created)?.use { write(it); true } ?: false
            if (ok) return created
        } catch (e: Exception) { }
        try { delete(created) } catch (e: Exception) { }
        return null
    }

    /** The yyyyMMdd day a backup file belongs to. Provider renames like "BumpLog-backup-20261025 (1).zip" keep the date at the front. Null for files this app did not name - those are never pruned. */
    internal fun dayKey(name: String): String? = Regex("^BumpLog-backup-(\\d{8})(?!\\d)").find(name)?.groupValues?.get(1)

    /**
     * Which of [zips] (id, lastModified, displayName) survive pruning, split out pure for tests: the newest file of
     * each of the 8 newest days, never fewer than the newest 2 files. A busy day's second save shares the day's key,
     * so it counts once and the first-in-list (newest) save of the day survives; a day can no longer burn two slots
     * and push an older day out. Files not named by this app are left alone.
     */
    internal fun <T> selectKeep(zips: List<Triple<T, Long, String>>): Set<T> {
        val sorted = zips.sortedByDescending { it.second }
        val keep = HashSet<T>()
        sorted.filter { dayKey(it.third) == null }.forEach { keep.add(it.first) }
        val keyed = sorted.mapNotNull { z -> dayKey(z.third)?.let { z to it } }
        val days = keyed.map { it.second }.distinct().take(8)
        for (d in days) keyed.firstOrNull { it.second == d }?.let { keep.add(it.first.first) }
        sorted.take(2).forEach { keep.add(it.first) } // the floor: pruning never leaves fewer than 2
        return keep
    }

    /** Writes the full backup zip into the auto-backup folder the owner picked. False when no folder is set or the write fails. */
    fun autoSave(ctx: Context): Boolean {
        val ok = autoSaveUri(ctx) != null
        if (ok) prune(ctx, android.net.Uri.parse(Prefs.backupFolder)) // prune only after the new backup wrote cleanly (item 6)
        return ok
    }

    /** Like [autoSave] but returns the new file, so the caller can verify it before trusting it. */
    private fun autoSaveUri(ctx: Context): android.net.Uri? {
        val tree = Prefs.backupFolder
        if (tree.isBlank()) return null
        return try {
            val treeUri = android.net.Uri.parse(tree)
            val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri))
            val created = writeOrDelete(
                create = { android.provider.DocumentsContract.createDocument(ctx.contentResolver, docUri, "application/zip", fileName("zip")) },
                openOut = { ctx.contentResolver.openOutputStream(it, "wt") },
                write = { writeZip(ctx, it) },
                // Only this just-created file is removed, never anything already in the folder.
                delete = { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, it) })
            if (created != null) Prefs.lastBackupTs = System.currentTimeMillis()
            created
        } catch (e: Exception) { null }
    }

    /**
     * Saves a fresh backup into the picked folder and reads it all the way back: every entry decompressed, the zip's
     * end record checked by ZipFile. Only then is it offered as the safety copy before a delete (item 11).
     * False when no folder is set, the write fails, or the read-back fails.
     */
    fun autoSaveVerified(ctx: Context): Boolean {
        val created = autoSaveUri(ctx) ?: return false
        val tmp = File(ctx.cacheDir, "verify-backup.zip")
        return try {
            ctx.contentResolver.openInputStream(created)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } ?: return false
            java.util.zip.ZipFile(tmp).use { z ->
                val names = z.entries().toList().map { it.name }
                if ("backup.json" !in names) return false
                val buf = ByteArray(65536)
                for (e in z.entries()) { z.getInputStream(e).use { while (it.read(buf) >= 0) { } } }
            }
            prune(ctx, android.net.Uri.parse(Prefs.backupFolder)) // only a backup that read back complete may push older ones out
            true
        } catch (e: Exception) { false } finally { tmp.delete() }
    }

    /** Keeps the newest backup of each of the last 8 days, and never fewer than the newest 2 files (item 6). Runs only after a new backup wrote cleanly. */
    private fun prune(ctx: Context, treeUri: android.net.Uri) {
        try {
            val kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri))
            val zips = mutableListOf<Triple<android.net.Uri, Long, String>>()
            ctx.contentResolver.query(kids, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.startsWith("BumpLog-backup-") || !name.endsWith(".zip")) continue
                    zips.add(Triple(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)), c.getLong(2), name))
                }
            }
            val keep = selectKeep(zips)
            for (z in zips) if (z.first !in keep) android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, z.first)
        } catch (e: Exception) { /* a folder that can't be listed keeps its zips */ }
    }

    /** Builds the zip in the cache and returns a share sheet for it. */
    fun shareIntent(ctx: Context): Intent {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, fileName("zip"))
        f.outputStream().use { writeZip(ctx, it) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share backup")
    }

    class Restored(val spots: Int, val hits: Int, val damages: Int, val photos: Int, val settings: Int, val skipped: Int)

    private fun okId(s: String?) = s != null && ReportNumbers.isOldId(s)

    private fun cv(o: JSONObject, cols: Set<String>, skip: Set<String>): ContentValues {
        val v = ContentValues()
        for (k in o.keys()) {
            if (k in skip || k.startsWith("_") || k !in cols) continue
            if (k == "type") { v.put(k, DefectType.parse(o.optString(k)).name); continue }
            when (val x = o.get(k)) {
                is Int -> v.put(k, x); is Long -> v.put(k, x); is Double -> v.put(k, x); is String -> v.put(k, x)
            }
        }
        return v
    }

    private fun cols(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("SELECT * FROM $table LIMIT 0", null).use { it.columnNames.toSet() }

    /** Restores a zip made by [writeZip]. Anything already on the phone (same report ID) is left alone. Returns null if it is not a BumpLog backup. */
    fun restoreZip(ctx: Context, ins: InputStream): Restored? {
        val db = Db.get(ctx)
        var json: JSONObject? = null
        var photos = 0; var total = 0L
        val photoFiles = ArrayList<Triple<String, String, File>>()
        val voiceFiles = ArrayList<Pair<String, File>>()
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
        ZipInputStream(ins.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val parts = e.name.split("/")
                when {
                    e.name == "backup.json" -> {
                        val b = z.readNBytes(MAX_JSON + 1)
                        if (b.size > MAX_JSON) return null
                        json = try { JSONObject(String(b)) } catch (x: Exception) { return null }
                    }
                    parts.size == 3 && parts[0] == "photos" && safeName.matches(parts[1]) && safeName.matches(parts[2]) -> {
                        val f = File(tmp, "p${photoFiles.size}")
                        var n = 0L
                        f.outputStream().use { o -> val buf = ByteArray(65536); while (true) { val r = z.read(buf); if (r < 0) break; n += r; total += r; if (n > MAX_ENTRY || total > MAX_TOTAL) return null; o.write(buf, 0, r) } }
                        photoFiles.add(Triple(parts[1], parts[2], f))
                    }
                    parts.size == 2 && parts[0] == "voice" && parts[1].endsWith(".clip") && Sound.clipPhrases.any { it.first + ".clip" == parts[1] } -> {
                        val b = z.readNBytes(5_000_000)
                        if (b.isNotEmpty()) { val f = File(tmp, "v-" + parts[1]); f.writeBytes(b); voiceFiles.add(parts[1].removeSuffix(".clip") to f) }
                    }
                }
            }
        }
        val j = json ?: return null
        if (j.optString("format") != "BumpLog backup") return null
        var nSpots = 0; var nHits = 0; var nDmg = 0; var skipped = 0
        synchronized(db) {
            val w = db.writableDatabase
            val sc = cols(w, "spots"); val hc = cols(w, "hits"); val dc = cols(w, "damage")
            val have = HashSet<String>()
            w.rawQuery("SELECT report_id FROM spots UNION SELECT report_id FROM damage", null).use { while (it.moveToNext()) have.add(it.getString(0) ?: "") }
            val spotMap = HashMap<Long, Long>(); val newSpots = HashSet<Long>(); val hitMap = HashMap<Long, Long>()
            var maxNo = 0
            w.beginTransaction()
            try {
                val spots = j.optJSONArray("spots") ?: JSONArray()
                for (i in 0 until spots.length()) {
                    val o = spots.optJSONObject(i) ?: continue
                    val rid = o.optString("report_id")
                    val oldId = o.optLong("_id", -1)
                    if (!okId(rid) || rid.startsWith("TEST-")) { skipped++; continue }
                    if (rid in have) {
                        w.rawQuery("SELECT id FROM spots WHERE report_id=?", arrayOf(rid)).use { if (it.moveToFirst()) spotMap[oldId] = it.getLong(0) }
                        skipped++; continue
                    }
                    if (o.optString("report_status") == "FILED") o.put("report_status", "SENT") // pre-1.14 backup
                    val no = o.optInt("report_no", 0); if (no > maxNo) maxNo = no
                    val id = w.insert("spots", null, cv(o, sc, setOf("id")))
                    if (id > 0) { spotMap[oldId] = id; newSpots.add(oldId); have.add(rid); nSpots++ } else skipped++
                }
                val hits = j.optJSONArray("hits") ?: JSONArray()
                for (i in 0 until hits.length()) {
                    val o = hits.optJSONObject(i) ?: continue
                    val oldSpot = o.optLong("spot_id", -1)
                    if (oldSpot !in newSpots) continue
                    val v = cv(o, hc, setOf("id", "spot_id")); v.put("spot_id", spotMap[oldSpot])
                    val id = w.insert("hits", null, v)
                    if (id > 0) { hitMap[o.optLong("_id", -1)] = id; nHits++ }
                }
                val dmg = j.optJSONArray("damage") ?: JSONArray()
                for (i in 0 until dmg.length()) {
                    val o = dmg.optJSONObject(i) ?: continue
                    val rid = o.optString("report_id")
                    if (!okId(rid) || !rid.startsWith("BD-")) { skipped++; continue }
                    if (rid in have) { skipped++; continue }
                    if (o.optString("status") == "FILED") o.put("status", "SENT")
                    val no = o.optInt("report_no", 0); if (no > maxNo) maxNo = no
                    val v = cv(o, dc, setOf("id", "spot_id", "hit_id"))
                    v.put("spot_id", spotMap[o.optLong("spot_id", -1)] ?: 0L); v.put("hit_id", hitMap[o.optLong("hit_id", -1)] ?: 0L)
                    if (w.insert("damage", null, v) > 0) { have.add(rid); nDmg++ }
                }
                w.setTransactionSuccessful()
            } finally { w.endTransaction() }
            val stored = (j.optJSONArray("meta") ?: JSONArray()).let { arr ->
                var c = 0
                for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; if (o.optString("k") == "report_counter") c = o.optString("v").toIntOrNull() ?: 0 }
                c
            }
            // A pre-1.14 backup carries no numbers: continue past the confirmed reports it restored, like the upgrade does.
            val existing = w.rawQuery("SELECT (SELECT COUNT(*) FROM spots WHERE (review='CONFIRMED' OR (review='FIXED' AND report_status<>'NONE')) AND report_id NOT LIKE 'TEST-%') + (SELECT COUNT(*) FROM damage)", null)
                .use { it.moveToFirst(); it.getInt(0) }
            db.bumpCounterTo(ReportNumbers.restoreNext(maxNo, stored, existing))
        }
        // Photos only go into the folder of a damage report that is in this backup, and never outside the damage folder.
        val damageRoot = File(ctx.filesDir, "damage").canonicalFile
        val allowed = HashSet<String>()
        val dj = j.optJSONArray("damage") ?: JSONArray()
        for (i in 0 until dj.length()) dj.optJSONObject(i)?.optString("report_id")?.takeIf { okId(it) }?.let { allowed.add(DamagePhotos.dir(ctx, it).name) }
        for ((dir, name, f) in photoFiles) {
            if (dir !in allowed) continue
            val folder = File(damageRoot, dir)
            val dest = File(folder, name)
            if (dest.canonicalFile.parentFile != folder.canonicalFile || folder.canonicalFile.parentFile != damageRoot) continue
            folder.mkdirs()
            if (!dest.exists()) { f.copyTo(dest); photos++ }
        }
        // Voice clips only fill gaps, so a restore never overwrites recordings made since.
        for ((key, f) in voiceFiles) if (!Sound.hasClip(ctx, key)) f.copyTo(Sound.clipFile(ctx, key), overwrite = true)
        val settings = j.optJSONObject("prefs")?.let { Prefs.restoreAll(it) } ?: 0
        return Restored(nSpots, nHits, nDmg, photos, settings, skipped)
        } finally { tmp.deleteRecursively() }
    }
}
