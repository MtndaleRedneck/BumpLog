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
    private const val MAX_VOICE = 5_000_000
    private const val MAX_ENTRIES = 5_000
    /** No leading dot, so neither "." nor ".." can pass as a name. */
    private val safeName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}")

    /** Suffix of a restore's staging copies; never a real photo, so it is filtered and cleaned up. */
    internal const val RESTORE_TMP = ".restore-tmp"

    /**
     * The one budget every archive is checked against before anything is decompressed: entry count
     * and the sum of every entry's declared size - including entries restore will never open, like
     * the spreadsheet export or anything unrecognized. Pure, unit-tested.
     */
    internal fun budgetRefusal(sizes: List<Long>): String? {
        if (sizes.size > MAX_ENTRIES) return "it has more than 5,000 entries"
        var total = 0L
        for (n in sizes) {
            total += n
            if (total < 0 || total > MAX_TOTAL) return "it unpacks to more than 1.5 GB"
        }
        return null
    }

    fun fileName(ext: String): String = "BumpLog-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + "." + ext

    private fun realSpots() = "report_id NOT LIKE 'TEST-%'"

    private fun dump(db: SQLiteDatabase, table: String, where: String?): JSONArray {
        val arr = JSONArray()
        db.query(table, null, where, null, null, null, if (table == "meta") "k ASC" else "id ASC").use { c ->
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

    /** Bigger than a restore could ever take back: the write is refused, not half-saved. */
    class BackupTooLarge(val reason: String) : Exception(reason)

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
            // The writer holds itself to the exact limits the restore side enforces, so a
            // backup this app writes is always one it could restore back - and an oversized
            // one is refused loudly here instead of being labeled "saved and checked" and
            // then failing a restore. No media is ever dropped to make it fit.
            var entries = 0
            var total = 0L
            fun put(name: String, size: Long, maxEntry: Long?, reason: String, write: () -> Unit) {
                if (maxEntry != null && size > maxEntry) throw BackupTooLarge(reason)
                if (++entries > MAX_ENTRIES) throw BackupTooLarge("there are more than 5,000 files to back up")
                total += size
                if (total > MAX_TOTAL) throw BackupTooLarge("everything together is over 1.5 GB, too big to restore later")
                z.putNextEntry(ZipEntry(name)); write(); z.closeEntry()
            }
            val jsonBytes = json.toString().toByteArray()
            put("backup.json", jsonBytes.size.toLong(), MAX_JSON.toLong(), "the report data alone is over 30 MB") { z.write(jsonBytes) }
            val csvBytes = Reports.csvText(ctx).toByteArray()
            put("BumpLog-export.csv", csvBytes.size.toLong(), null, "") { z.write(csvBytes) }
            for (d in db.damages()) {
                val dir = DamagePhotos.dir(ctx, d.reportId)
                for (f in DamagePhotos.files(ctx, d.reportId)) {
                    put("photos/${dir.name}/${f.name}", f.length(), MAX_ENTRY, "a damage photo is over 60 MB, too big to restore later") {
                        f.inputStream().use { it.copyTo(z) }
                    }
                }
            }
            for ((key, _) in Sound.clipPhrases) if (Sound.hasClip(ctx, key)) {
                val f = Sound.clipFile(ctx, key)
                put("voice/$key.clip", f.length(), MAX_VOICE.toLong(), "a voice recording is over 5 MB, too big to restore later") {
                    f.inputStream().use { it.copyTo(z) }
                }
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
        } catch (e: BackupTooLarge) {
            // The typed refusal travels so every save path can say why in his words.
            try { delete(created) } catch (x: Exception) { }
            throw e
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

    /** What a save came back with, so the manual, share and weekly paths all say the same thing. */
    sealed class SaveResult {
        object Saved : SaveResult()
        object NoFolder : SaveResult()
        /** Bigger than a restore could ever take back; retrying the same save won't change that. */
        class TooLarge(val reason: String) : SaveResult()
        /** An ordinary failure (folder gone, write error) - worth another try later. */
        class Failed(val reason: String) : SaveResult()
    }

    private class Attempt(val uri: android.net.Uri?, val failure: SaveResult?)

    /** Writes the full backup zip into the auto-backup folder the owner picked. False when no folder is set or the write fails. */
    fun autoSave(ctx: Context): Boolean = autoSaveResult(ctx) is SaveResult.Saved

    /** The one place backup outcomes reach the Settings failure line: every failure names itself,
     *  and every success - manual or weekly - clears it, so a stale "Backup failed" never survives
     *  a later good save. No folder picked is not a failure and clears a stale line too. */
    internal fun noteOutcome(r: SaveResult): SaveResult {
        when (r) {
            is SaveResult.Failed -> Prefs.lastBackupError = r.reason
            is SaveResult.TooLarge -> Prefs.lastBackupError = r.reason
            else -> Prefs.lastBackupError = ""
        }
        return r
    }

    /** The typed form of [autoSave]: prune runs only after the new backup wrote cleanly (item 6). */
    fun autoSaveResult(ctx: Context): SaveResult {
        val a = autoSaveAttempt(ctx)
        a.failure?.let { return noteOutcome(it) }
        Prefs.lastBackupTs = System.currentTimeMillis()
        prune(ctx, android.net.Uri.parse(Prefs.backupFolder))
        return noteOutcome(SaveResult.Saved)
    }

    private fun autoSaveAttempt(ctx: Context): Attempt {
        val tree = Prefs.backupFolder
        if (tree.isBlank()) return Attempt(null, SaveResult.NoFolder)
        return try {
            val treeUri = android.net.Uri.parse(tree)
            val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri))
            val created = writeOrDelete(
                create = { android.provider.DocumentsContract.createDocument(ctx.contentResolver, docUri, "application/zip", fileName("zip")) },
                openOut = { ctx.contentResolver.openOutputStream(it, "wt") },
                write = { writeZip(ctx, it) },
                // Only this just-created file is removed, never anything already in the folder.
                delete = { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, it) })
            Attempt(created, if (created == null) SaveResult.Failed("the file would not write") else null)
        } catch (e: BackupTooLarge) { Attempt(null, SaveResult.TooLarge(e.reason)) }
        catch (e: Exception) { Attempt(null, SaveResult.Failed("the file would not write")) }
    }

    /**
     * Saves a fresh backup into the picked folder and reads it all the way back: every entry decompressed, the zip's
     * end record checked by ZipFile. Only then is it offered as the safety copy before a delete (item 11).
     * False when no folder is set, the write fails, or the read-back fails.
     */
    fun autoSaveVerified(ctx: Context): Boolean = autoSaveVerifiedResult(ctx) is SaveResult.Saved

    /**
     * The typed form of [autoSaveVerified]. The size limits now live in [writeZip] itself, so a backup
     * that a restore would reject can never be written, labeled "saved and checked", and then used to
     * gate a delete: the delete flow hears the refusal instead. The read-back below still proves the
     * saved bytes are complete.
     */
    fun autoSaveVerifiedResult(ctx: Context): SaveResult {
        val a = autoSaveAttempt(ctx)
        a.failure?.let { return noteOutcome(it) }
        val created = a.uri ?: return noteOutcome(SaveResult.Failed("the file would not write"))
        val tmp = File(ctx.cacheDir, "verify-backup.zip")
        // The file only counts (timestamp, prune, delete-gate) once it read back complete;
        // a file that fails the read-back is removed again, not left looking like a backup.
        fun bad(): SaveResult {
            try { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, created) } catch (e: Exception) { }
            return noteOutcome(SaveResult.Failed("the saved file would not read back"))
        }
        return try {
            ctx.contentResolver.openInputStream(created)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                ?: return bad()
            java.util.zip.ZipFile(tmp).use { z ->
                val names = z.entries().toList().map { it.name }
                if ("backup.json" !in names) return bad()
                val buf = ByteArray(65536)
                for (e in z.entries()) { z.getInputStream(e).use { while (it.read(buf) >= 0) { } } }
            }
            Prefs.lastBackupTs = System.currentTimeMillis()
            prune(ctx, android.net.Uri.parse(Prefs.backupFolder)) // only a backup that read back complete may push older ones out
            noteOutcome(SaveResult.Saved)
        } catch (e: Exception) { bad() } finally { tmp.delete() }
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
        try {
            f.outputStream().use { writeZip(ctx, it) }
        } catch (e: Exception) {
            f.delete() // a refused or interrupted write never leaves a partial zip to share
            throw e
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share backup")
    }

    class Restored(val spots: Int, val hits: Int, val damages: Int, val photos: Int, val settings: Int, val skipped: Int, val incomplete: Boolean = false)

    /**
     * Entry limits on the restore side: a real backup has a handful of entries and stays well under
     * the byte cap, so an archive past these is not a BumpLog backup. Counts every entry, adds json,
     * photo and voice bytes up against the one cumulative cap, and allows each voice clip name once.
     * Pure, unit-tested.
     */
    internal class RestoreGate(private val limit: Long = MAX_TOTAL) {
        private var entries = 0
        var total = 0L; private set
        private val voiceNames = HashSet<String>()
        fun countEntry(): Boolean = ++entries <= MAX_ENTRIES
        fun addBytes(n: Long): Boolean { total += n; return total <= limit }
        fun admitVoice(name: String, size: Int): Boolean = size <= MAX_VOICE && voiceNames.add(name)
    }

    /** Test seams: exercise the raw-copy cap and the aggregate gate without gigabytes on disk. */
    internal var rawCapForTests: Long? = null
    internal var gateLimitForTests: Long? = null

    /** One post-commit restore phase: a failure is flagged, not thrown, and never skips the other phases. */
    internal fun runPhase(phase: () -> Unit): Boolean = try { phase(); true } catch (e: Exception) { false }

    /** Test seam: the copy used by the restore's post-commit phases. Production never overrides this. */
    internal var copyFileForRestore: (File, File) -> Unit = { src, dst -> src.copyTo(dst, overwrite = true) }

    /** Test seam: the content hash the reconcile reads for journaled files. Production never overrides this. */
    internal var hashForReconcile: (File) -> String = { sha256Hex(it) }

    /** The restore photo phase's positive-provenance journal: one dir<TAB>name<TAB>sha256 line
     *  per photo the restore sets out to write, recorded before the copy. It lives directly under the
     *  damage root - never inside a report folder - so no photo listing, backup or staging
     *  cleanup can mistake it for a photo or a partial copy. */
    private fun restoreJournal(ctx: Context) = File(File(ctx.filesDir, "damage"), ".restore-journal")

    /** Hex SHA-256 of a file's bytes, streamed so big photos do not sit in memory twice. */
    internal fun sha256Hex(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** What a restore attempt came back with. */
    sealed class RestoreResult {
        /** The restore ran; check [Restored.incomplete] for post-commit copy trouble. */
        class Done(val r: Restored) : RestoreResult()
        /** Not a file this app wrote. */
        object NotABackup : RestoreResult()
        /** A real-looking backup refused by an entry or size limit; [reason] says which, in his words. */
        class Refused(val reason: String) : RestoreResult()
    }

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

    /**
     * Copies through a staging file in the destination folder, then renames: an interrupted copy
     * never leaves a partial file that a re-run would mistake for the real thing and skip.
     */
    internal fun stageCopy(src: File, dest: File) {
        val staging = File(dest.parentFile, dest.name + RESTORE_TMP)
        try {
            copyFileForRestore(src, staging)
            if (!staging.renameTo(dest)) throw java.io.IOException("could not place " + dest.name)
        } finally { staging.delete() }
    }

    /**
     * Restores a zip made by [writeZip]. Anything already on the phone (same report ID) is left alone.
     * NotABackup when it is not a BumpLog backup, Refused when a limit says it cannot be one this app
     * wrote, otherwise Done - and Done.incomplete tells him to run it again when a copy failed.
     */
    fun restoreZip(ctx: Context, ins: InputStream): RestoreResult {
        val db = Db.get(ctx)
        var json: JSONObject? = null
        var photos = 0
        val gate = RestoreGate(gateLimitForTests ?: MAX_TOTAL)
        val seenPhotoNames = HashSet<String>()
        val photoFiles = ArrayList<Triple<String, String, File>>()
        val voiceFiles = ArrayList<Pair<String, File>>()
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
        // A restore killed mid-copy on an earlier run can leave staging files behind in the
        // damage folders; they are never real photos, so drop them before doing anything.
        File(ctx.filesDir, "damage").listFiles()?.forEach { d ->
            d.listFiles()?.forEach { if (it.name.endsWith(RESTORE_TMP)) it.delete() }
        }
        // Copy the archive to a temp file and read it by entry name with ZipFile: every entry's
        // declared size counts toward the one budget up front (spreadsheet export and anything
        // unrecognized included), and entries restore doesn't know are never opened, so their
        // bytes are never decompressed at all. The copy itself is plain bounded file I/O.
        // The raw archive copy is capped too: bigger than any backup this app can produce
        // (the budget plus zip overhead) is refused before it fills the disk.
        val zipCopy = File(tmp, "in.zip")
        run {
            var raw = 0L
            ins.buffered().use { i ->
                zipCopy.outputStream().use { o ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val r = i.read(buf); if (r < 0) break
                        raw += r
                        if (raw > (rawCapForTests ?: (MAX_TOTAL + 8_000_000L))) return RestoreResult.Refused("the file itself is too big")
                        o.write(buf, 0, r)
                    }
                }
            }
        }
        java.util.zip.ZipFile(zipCopy).use { z ->
            val entries = z.entries().toList().filter { !it.isDirectory }
            // Declared sizes bound everything, opened or not; the reads below additionally
            // count real bytes, so a header that lies about its size cannot slip past.
            budgetRefusal(entries.map { e -> if (e.size >= 0) e.size else e.compressedSize.coerceAtLeast(0) })
                ?.let { return RestoreResult.Refused(it) }
            for (e in entries) {
                val parts = e.name.split("/")
                when {
                    e.name == "backup.json" -> {
                        val b = z.getInputStream(e).use { it.readNBytes(MAX_JSON + 1) }
                        if (b.size > MAX_JSON) return RestoreResult.Refused("its report data is over 30 MB")
                        if (!gate.addBytes(b.size.toLong())) return RestoreResult.Refused("it unpacks to more than 1.5 GB")
                        json = try { JSONObject(String(b)) } catch (x: Exception) { return RestoreResult.NotABackup }
                    }
                    parts.size == 3 && parts[0] == "photos" && safeName.matches(parts[1]) && safeName.matches(parts[2]) -> {
                        // Same deterministic rule as voice clips: a name appearing twice is
                        // refused, never a silent first-wins pick.
                        if (!seenPhotoNames.add(e.name)) return RestoreResult.Refused("a photo appears twice")
                        val f = File(tmp, "p${photoFiles.size}")
                        z.getInputStream(e).use { es ->
                            var n = 0L
                            f.outputStream().use { o -> val buf = ByteArray(65536); while (true) { val r = es.read(buf); if (r < 0) break; n += r; if (n > MAX_ENTRY) return RestoreResult.Refused("a photo is over 60 MB"); if (!gate.addBytes(r.toLong())) return RestoreResult.Refused("it unpacks to more than 1.5 GB"); o.write(buf, 0, r) } }
                        }
                        photoFiles.add(Triple(parts[1], parts[2], f))
                    }
                    parts.size == 2 && parts[0] == "voice" && parts[1].endsWith(".clip") && Sound.clipPhrases.any { it.first + ".clip" == parts[1] } -> {
                        val b = z.getInputStream(e).use { it.readNBytes(MAX_VOICE + 1) }
                        if (b.isNotEmpty()) {
                            if (b.size > MAX_VOICE) return RestoreResult.Refused("a voice clip is over 5 MB")
                            if (!gate.admitVoice(parts[1], b.size)) return RestoreResult.Refused("a voice clip appears twice")
                            if (!gate.addBytes(b.size.toLong())) return RestoreResult.Refused("it unpacks to more than 1.5 GB")
                            val f = File(tmp, "v-" + parts[1]); f.writeBytes(b); voiceFiles.add(parts[1].removeSuffix(".clip") to f)
                        }
                    }
                    // else: an entry restore doesn't know - never opened, already counted above.
                }
            }
        }
        zipCopy.delete()
        val j = json ?: return RestoreResult.NotABackup
        if (j.optString("format") != "BumpLog backup") return RestoreResult.NotABackup
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
        Db.noteWrite() // restored spots must reach the drive service's warning cache
        // Photos only go into the folder of a damage report that is in this backup, and never outside the damage folder.
        val damageRoot = File(ctx.filesDir, "damage").canonicalFile
        val allowed = HashSet<String>()
        val dj = j.optJSONArray("damage") ?: JSONArray()
        for (i in 0 until dj.length()) dj.optJSONObject(i)?.optString("report_id")?.takeIf { okId(it) }?.let { allowed.add(DamagePhotos.dir(ctx, it).name) }
        // The DB is already committed, so a failure below must not look like a clean restore:
        // each phase is wrapped, the result is flagged, and a re-run of the same file finishes it.
        // One bad file never stops the rest of the phase: it is flagged and the re-run finishes it.
        var complete = true
        complete = runPhase {
            for ((dir, name, f) in photoFiles) {
                if (dir !in allowed) continue
                val folder = File(damageRoot, dir)
                val dest = File(folder, name)
                if (dest.canonicalFile.parentFile != folder.canonicalFile || folder.canonicalFile.parentFile != damageRoot) continue
                folder.mkdirs()
                if (!dest.exists()) {
                    // Positive provenance is journaled BEFORE the copy: a failure later in the
                    // phase or a process death can never lose the membership of a file already
                    // written. The reconcile trusts a journal line only for a file that is
                    // actually on disk, so a line whose copy never landed is inert.
                    restoreJournal(ctx).appendText(dir + "\t" + name + "\t" + sha256Hex(f) + "\n")
                    try { stageCopy(f, dest); photos++ } catch (e: Exception) { complete = false }
                }
            }
        } && complete
        // A photo this restore positively wrote into a folder whose manifest predates it is
        // invisible to the report: the manifest decides the attachment set. Reconcile only the
        // journaled files - never other disk files, which include deliberately removed picks
        // syncDraft keeps on disk - and only after a clean photo phase. The journal carries
        // membership across an interrupted run: the heal re-run skips files already on disk,
        // so without it a photo copied before the interruption would stay invisible forever.
        // A consumed journal is deleted; a failed reconcile keeps it for the next run.
        if (complete) complete = runPhase {
            val jf = restoreJournal(ctx)
            if (jf.isFile) {
                // A journaled name is trusted only when the file on disk is the exact content
                // this restore journaled: a stale line whose same-named file now holds different
                // bytes (an unrelated later write reusing the name) must not claim membership.
                // A hash READ failure is not a mismatch: the bytes may be the restore's own, so
                // the entry keeps its provenance - the reconcile fails, the journal survives,
                // and a later run retries (reruns skip existing files, so discarding the line
                // here would lose that membership forever).
                var hashReadFailed = false
                val byFolder = HashMap<String, MutableList<String>>()
                for (line in jf.readLines()) {
                    val parts = line.split('\t')
                    if (parts.size != 3) continue
                    val (dirName, name, hash) = parts
                    val folder = File(damageRoot, dirName)
                    if (folder.canonicalFile.parentFile != damageRoot) continue
                    val onDisk = File(folder, name)
                    if (onDisk.canonicalFile.parentFile != folder.canonicalFile) continue
                    if (!onDisk.isFile) continue
                    val onDiskHash = try { hashForReconcile(onDisk) } catch (e: Exception) { hashReadFailed = true; continue }
                    if (onDiskHash != hash) continue
                    byFolder.getOrPut(dirName) { ArrayList() }.add(name)
                }
                for ((dirName, names) in byFolder) {
                    val folder = File(damageRoot, dirName)
                    if (folder.canonicalFile.parentFile != damageRoot) continue
                    DamagePhotos.addRestoredToManifest(folder, names)
                }
                if (hashReadFailed) throw java.io.IOException("a journaled photo could not be hashed; keeping the journal for the next run")
                jf.delete()
            }
        } && complete
        // Voice clips only fill gaps, so a restore never overwrites recordings made since.
        complete = runPhase {
            for ((key, f) in voiceFiles) if (!Sound.hasClip(ctx, key)) {
                try { stageCopy(f, Sound.clipFile(ctx, key)) } catch (e: Exception) { complete = false }
            }
        } && complete
        var settings = 0
        complete = runPhase { settings = j.optJSONObject("prefs")?.let { Prefs.restoreAll(it) } ?: 0 } && complete
        return RestoreResult.Done(Restored(nSpots, nHits, nDmg, photos, settings, skipped, incomplete = !complete))
        } finally { tmp.deleteRecursively() }
    }
}
