package com.bumplog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real zip-in, restore-out tests of the restore path, with the copy seam used to inject
 * failures after the database commit: the failure must be reported, no partial or staging
 * file may survive, and running the same backup again must finish the job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Db.closeForTests() // each test gets a fresh sandbox: drop any stale open handle
        Prefs.init(ctx)
        Backup.rawCapForTests = null
        Backup.gateLimitForTests = null
        Backup.copyFileForRestore = { src, dst -> src.copyTo(dst, overwrite = true) }
    }

    private fun backupJson(): JSONObject = JSONObject()
        .put("format", "BumpLog backup").put("version", 1).put("created", 1L)
        .put("prefs", JSONObject().put("minspeed", JSONObject().put("t", "i").put("v", 20)))
        .put("spots", JSONArray().put(JSONObject()
            .put("_id", 1).put("report_id", "BL-20261101-ABCD")
            .put("lat", 40.0).put("lon", -74.0).put("type", "POTHOLE")
            .put("report_no", 1)))
        .put("hits", JSONArray())
        .put("damage", JSONArray().put(JSONObject()
            .put("_id", 1).put("report_id", "BD-20261102-EFGH")
            .put("spot_id", 1).put("report_no", 2)))
        .put("meta", JSONArray().put(JSONObject().put("k", "report_counter").put("v", "3")))

    private fun zipWith(j: JSONObject, entries: Map<String, ByteArray>): ByteArrayInputStream =
        zipWith(j, entries.entries.map { it.toPair() })

    // Pair list, not a map: a hostile archive can carry the same name twice.
    private fun zipWith(j: JSONObject, entries: List<Pair<String, ByteArray>>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("backup.json")); z.write(j.toString().toByteArray()); z.closeEntry()
            for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun damageZip(): ByteArrayInputStream {
        val dir = DamagePhotos.dir(ctx, "BD-20261102-EFGH").name
        return zipWith(backupJson(), mapOf(
            "photos/$dir/p1.jpg" to ByteArray(100) { 1 },
            "voice/generic.clip" to ByteArray(10) { 2 }))
    }

    private fun done(o: Backup.RestoreResult): Backup.Restored {
        assertTrue("expected Done, was $o", o is Backup.RestoreResult.Done)
        return (o as Backup.RestoreResult.Done).r
    }

    @Test
    fun restoresReportsPhotosVoiceAndSettings() {
        val r = done(Backup.restoreZip(ctx, damageZip()))
        assertEquals(1, r.spots)
        assertEquals(1, r.damages)
        assertEquals(1, r.photos)
        assertEquals(1, r.settings)
        assertFalse(r.incomplete)
        assertEquals(100, File(DamagePhotos.dir(ctx, "BD-20261102-EFGH"), "p1.jpg").length())
        assertTrue(Sound.hasClip(ctx, "generic"))
        assertEquals(20, Prefs.minSpeedMph)
    }

    @Test
    fun secondRunSkipsExistingAndStaysComplete() {
        done(Backup.restoreZip(ctx, damageZip()))
        val r = done(Backup.restoreZip(ctx, damageZip()))
        assertTrue("same report IDs are skipped, not duplicated", r.skipped >= 2)
        assertEquals(0, r.spots)
        assertEquals(0, r.photos) // already there: nothing copied twice
        assertFalse(r.incomplete)
    }

    @Test
    fun restoreReconcilesOnlyPhotosItActuallyWrote() {
        val dirName = DamagePhotos.dir(ctx, "BD-20261102-EFGH").name
        val folder = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        // Pre-existing folder state: the manifest lists only p1.jpg, p1.jpg is already on
        // disk, and p-stale.jpg is a removed pick whose file syncDraft kept by design.
        folder.mkdirs()
        File(folder, "p1.jpg").writeBytes(ByteArray(100) { 1 })
        File(folder, "p-stale.jpg").writeBytes(ByteArray(50) { 3 })
        File(folder, "selected.txt").writeText("p1.jpg")
        // The backup carries p1.jpg (already on disk: not copied) and p2.jpg (new: copied).
        val zip = zipWith(backupJson(), mapOf(
            "photos/$dirName/p1.jpg" to ByteArray(100) { 1 },
            "photos/$dirName/p2.jpg" to ByteArray(100) { 2 }))
        val r = done(Backup.restoreZip(ctx, zip))
        assertEquals("only the photo restore positively wrote counts", 1, r.photos)
        // p2.jpg joins the manifest in place; the unlisted leftover is NOT resurrected.
        assertEquals(listOf("p1.jpg", "p2.jpg"), File(folder, "selected.txt").readLines())
        assertFalse(r.incomplete)
    }

    @Test
    fun restoreLeavesEmptiedManifestAloneWhenNothingNewWritten() {
        val dirName = DamagePhotos.dir(ctx, "BD-20261102-EFGH").name
        val folder = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        // The user removed every pick: the manifest exists and is empty, while the backup
        // still carries p1.jpg and its file was kept on disk. Restore copies nothing, so
        // the emptied selection must stay empty - an existing file is not a restored file.
        folder.mkdirs()
        File(folder, "p1.jpg").writeBytes(ByteArray(100) { 1 })
        File(folder, "selected.txt").writeText("")
        val zip = zipWith(backupJson(), mapOf("photos/$dirName/p1.jpg" to ByteArray(100) { 1 }))
        val r = done(Backup.restoreZip(ctx, zip))
        assertEquals(0, r.photos)
        assertTrue("the emptied manifest stays in force, unread and unfilled", File(folder, "selected.txt").let { it.exists() && it.readLines().isEmpty() })
        assertFalse(r.incomplete)
    }

    @Test
    fun partialPhotoPhaseKeepsPositiveMembershipAcrossTheHealRerun() {
        val dirName = DamagePhotos.dir(ctx, "BD-20261102-EFGH").name
        val folder = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        // Manifest predates the backup's photos: it lists only p1.jpg, which is on disk.
        folder.mkdirs()
        File(folder, "p1.jpg").writeBytes(ByteArray(100) { 1 })
        File(folder, "selected.txt").writeText("p1.jpg")
        val zipBytes = zipWith(backupJson(), mapOf(
            "photos/$dirName/p1.jpg" to ByteArray(100) { 1 },
            "photos/$dirName/p2.jpg" to ByteArray(100) { 2 },
            "photos/$dirName/p3.jpg" to ByteArray(100) { 3 })).readBytes()
        // Run 1: p2 copies, then p3's copy fails. The phase is incomplete, so nothing
        // reconciles yet - but p2's membership must survive in the journal.
        Backup.copyFileForRestore = { src, dst -> if (dst.name.startsWith("p3")) throw java.io.IOException("injected") else src.copyTo(dst, overwrite = true) }
        val r1 = done(Backup.restoreZip(ctx, ByteArrayInputStream(zipBytes)))
        assertTrue("a failed copy flags the restore incomplete", r1.incomplete)
        assertEquals(1, r1.photos)
        assertEquals(listOf("p1.jpg"), File(folder, "selected.txt").readLines())
        val journal = File(File(ctx.filesDir, "damage"), ".restore-journal")
        assertTrue("the interrupted run journals what it positively wrote", journal.readLines().any { it.endsWith("\tp2.jpg") })
        // Run 2 without the failure: p2 already exists so it is NOT copied again - only the
        // journal still knows it belongs. The heal run must list both p2 and p3, in order.
        Backup.copyFileForRestore = { src, dst -> src.copyTo(dst, overwrite = true) }
        val r2 = done(Backup.restoreZip(ctx, ByteArrayInputStream(zipBytes)))
        assertFalse(r2.incomplete)
        assertEquals(listOf("p1.jpg", "p2.jpg", "p3.jpg"), File(folder, "selected.txt").readLines())
        assertFalse("a consumed journal is deleted", journal.exists())
    }

    @Test
    fun failedCopyIsFlaggedAndRerunHeals() {
        Backup.copyFileForRestore = { _, _ -> throw java.io.IOException("disk full") }
        val r1 = done(Backup.restoreZip(ctx, damageZip()))
        assertTrue("a failed copy must be reported, not look done", r1.incomplete)
        assertEquals(1, r1.spots)     // the database commit stands
        assertEquals(1, r1.settings)  // the settings phase still ran
        assertEquals(0, r1.photos)
        val folder = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        assertFalse(File(folder, "p1.jpg").exists())
        assertFalse("no staging file left behind", folder.listFiles()!!.any { it.name.endsWith(".restore-tmp") })
        assertFalse(Sound.hasClip(ctx, "generic"))

        Backup.copyFileForRestore = { src, dst -> src.copyTo(dst, overwrite = true) }
        val r2 = done(Backup.restoreZip(ctx, damageZip()))
        assertFalse(r2.incomplete)
        assertEquals(1, r2.photos)
        assertTrue(r2.skipped >= 2)
        assertEquals(100, File(folder, "p1.jpg").length())
        assertTrue(Sound.hasClip(ctx, "generic"))
    }

    @Test
    fun interruptedCopyLeavesNoPartialFileAndRerunHeals() {
        Backup.copyFileForRestore = { src, dst ->
            dst.writeBytes(src.readBytes().copyOf(3)) // half-written, then the failure
            throw java.io.IOException("interrupted")
        }
        val r1 = done(Backup.restoreZip(ctx, damageZip()))
        assertTrue(r1.incomplete)
        val folder = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        assertFalse("a partial photo must not survive as if copied", File(folder, "p1.jpg").exists())
        assertFalse("a partial clip must not survive as if copied", Sound.hasClip(ctx, "generic"))

        Backup.copyFileForRestore = { src, dst -> src.copyTo(dst, overwrite = true) }
        val r2 = done(Backup.restoreZip(ctx, damageZip()))
        assertFalse(r2.incomplete)
        assertEquals(100, File(folder, "p1.jpg").length())
        assertTrue(Sound.hasClip(ctx, "generic"))
    }

    @Test
    fun limitRefusalSaysWhyInsteadOfNotABackup() {
        val z = zipWith(backupJson(), mapOf("voice/generic.clip" to ByteArray(5_000_001)))
        val o = Backup.restoreZip(ctx, z)
        assertTrue(o is Backup.RestoreResult.Refused)
        assertTrue((o as Backup.RestoreResult.Refused).reason.isNotEmpty())
    }

    @Test
    fun notABackupStillSaysSo() {
        val z = zipWith(JSONObject().put("format", "something else"), emptyMap())
        assertTrue(Backup.restoreZip(ctx, z) is Backup.RestoreResult.NotABackup)
    }

    @Test
    fun ownWriterOutputPassesTheLimits() {
        val out = ByteArrayOutputStream()
        Backup.writeZip(ctx, out)
        assertTrue(Backup.restoreZip(ctx, ByteArrayInputStream(out.toByteArray())) is Backup.RestoreResult.Done)
    }

    @Test
    fun ownWriterWithNearBoundaryMediaPassesRestore() {
        // Real data at the caps, not an empty database: a photo exactly at 60 MB and a
        // voice clip exactly at 5 MB are written and accepted back by restore's scan.
        done(Backup.restoreZip(ctx, damageZip()))
        val dir = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        File(dir, "p1.jpg").writeBytes(ByteArray(60_000_000))
        Sound.clipFile(ctx, "generic").writeBytes(ByteArray(5_000_000))
        val out = ByteArrayOutputStream()
        Backup.writeZip(ctx, out)
        val r = done(Backup.restoreZip(ctx, ByteArrayInputStream(out.toByteArray())))
        assertFalse(r.incomplete)
    }

    @Test
    fun writerRefusesAVoiceClipRestoreWouldReject() {
        Sound.clipFile(ctx, "generic").writeBytes(ByteArray(5_000_001))
        val e = assertThrows(Backup.BackupTooLarge::class.java) {
            Backup.writeZip(ctx, ByteArrayOutputStream())
        }
        assertTrue(e.reason.isNotEmpty())
    }

    @Test
    fun unknownEntriesAreIgnored() {
        // Entries restore doesn't know don't fail the restore and are never opened.
        val z = zipWith(backupJson(), mapOf(
            "junk.bin" to ByteArray(2_000_000) { 7 },
            "notes/readme.txt" to byteArrayOf(1, 2, 3)))
        val r = done(Backup.restoreZip(ctx, z))
        assertEquals(1, r.spots)
    }

    @Test
    fun duplicatePhotoEntryIsRefusedLikeADuplicateClip() {
        val dir = DamagePhotos.dir(ctx, "BD-20261102-EFGH").name
        // ZipOutputStream itself refuses duplicate names, so build with a distinct name of
        // the SAME length, then rewrite both header copies to the duplicated one.
        val bytes = zipWith(backupJson(), listOf(
            "photos/$dir/p1.jpg" to ByteArray(10) { 1 },
            "photos/$dir/pX.jpg" to ByteArray(10) { 2 })).readBytes()
        val from = "pX.jpg".toByteArray(); val to = "p1.jpg".toByteArray()
        var patched = 0
        for (i in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[i + it] == from[it] }) { to.copyInto(bytes, i); patched++ }
        }
        assertTrue("test setup rewrites the local header and central directory names", patched == 2)
        assertTrue(Backup.restoreZip(ctx, ByteArrayInputStream(bytes)) is Backup.RestoreResult.Refused)
    }

    @Test
    fun lyingDeclaredSizesAreRefusedUpfront() {
        // Patch the central directory so every entry claims 3 GB uncompressed: the
        // declared-size budget must refuse the archive before anything is read.
        val bytes = zipWith(backupJson(), mapOf("junk.bin" to ByteArray(4))).readBytes()
        val sig = byteArrayOf(0x50, 0x4B, 0x01, 0x02) // PK\x01\x02 central file header
        var i = bytes.size - 22
        var patched = 0
        while (i >= 0) {
            if (bytes[i] == sig[0] && bytes[i + 1] == sig[1] && bytes[i + 2] == sig[2] && bytes[i + 3] == sig[3]) {
                val v = 3_000_000_000L
                for (k in 0..3) bytes[i + 24 + k] = (v shr (8 * k)).toByte()
                patched++
            }
            i--
        }
        assertTrue("test setup patches at least one central header", patched > 0)
        assertTrue(Backup.restoreZip(ctx, ByteArrayInputStream(bytes)) is Backup.RestoreResult.Refused)
    }

    @Test
    fun csvWithNewlineInAddressRoundTrips() {
        // The writer quotes address fields; a geocoded address with a newline in it must
        // come back through the importer as one record.
        val j = backupJson()
        j.put("hits", JSONArray().put(JSONObject()
            .put("_id", 1).put("spot_id", 1).put("ts", 1762000000000L)
            .put("lat", 40.0).put("lon", -74.0).put("heading", 90.0)
            .put("speed", 13.4).put("peak_g", 5.0).put("duration", 0).put("peaks", 1)
            .put("type", "POTHOLE")))
        done(Backup.restoreZip(ctx, zipWith(j, emptyMap())))
        val db = Db.get(ctx)
        val spotId = db.writableDatabase.let { w ->
            w.execSQL("UPDATE spots SET address=? WHERE report_id='BL-20261101-ABCD'", arrayOf("Main St\nSecond line"))
            w.rawQuery("SELECT id FROM spots WHERE report_id='BL-20261101-ABCD'", null).use { it.moveToFirst(); it.getLong(0) }
        }
        assertTrue(spotId > 0)
        val csv = Reports.csvText(ctx)
        db.writableDatabase.execSQL("DELETE FROM spots WHERE report_id='BL-20261101-ABCD'")
        val r = db.importCsv(csv)
        assertTrue("import of the app's own CSV succeeds", r != null)
        val back = db.writableDatabase.rawQuery("SELECT address FROM spots WHERE report_id='BL-20261101-ABCD'", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
        assertEquals("Main St\nSecond line", back)
    }

    @Test
    fun backupRowWithMissingColumnsDoesNotCrashReaders() {
        // A damage row missing note/status inserts SQL NULLs; the readers must tolerate them.
        val j = backupJson()
        j.getJSONArray("damage").getJSONObject(0).apply { remove("note"); remove("status") }
        val r = done(Backup.restoreZip(ctx, zipWith(j, emptyMap())))
        assertEquals(1, r.damages)
        assertTrue(Db.get(ctx).damages().isNotEmpty())
        assertTrue(Db.get(ctx).queuedDamages().isEmpty())
    }

    @Test
    fun stagingLeftoversAreHiddenAndCleanedUp() {
        done(Backup.restoreZip(ctx, damageZip()))
        val dir = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        File(dir, "half.jpg.restore-tmp").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse("a staging leftover is never shown as a photo",
            DamagePhotos.files(ctx, "BD-20261102-EFGH").any { it.name.contains("restore-tmp") })
        done(Backup.restoreZip(ctx, damageZip()))
        assertFalse("a staging leftover is cleaned up on the next restore",
            File(dir, "half.jpg.restore-tmp").exists())
    }

    @Test
    fun lyingSmallDeclaredSizeIsCaughtByTheActualRead() {
        // Central directory claims a 100-byte photo; the entry actually inflates past the
        // 60 MB per-entry cap. The declared budget passes; the real byte count must refuse.
        val dir = DamagePhotos.dir(ctx, "BD-20261102-EFGH").name
        val bytes = zipWith(backupJson(), mapOf("photos/$dir/big.jpg" to ByteArray(61_000_000))).readBytes()
        val sig = byteArrayOf(0x50, 0x4B, 0x01, 0x02) // PK\x01\x02 central file header
        var i = bytes.size - 22
        var patched = 0
        while (i >= 0) {
            if (bytes[i] == sig[0] && bytes[i + 1] == sig[1] && bytes[i + 2] == sig[2] && bytes[i + 3] == sig[3]) {
                val v = 100L
                for (k in 0..3) bytes[i + 24 + k] = (v shr (8 * k)).toByte() // uncompressed size
                patched++
            }
            i--
        }
        assertEquals("test setup patches json + photo central headers", 2, patched)
        val r = Backup.restoreZip(ctx, ByteArrayInputStream(bytes))
        assertTrue("actual bytes refuse what declared sizes passed, was $r", r is Backup.RestoreResult.Refused)
    }

    @Test
    fun rawArchiveCopyIsCapped() {
        // The archive file itself is bounded before any zip parsing: past the cap it is
        // refused instead of filling the disk.
        Backup.rawCapForTests = 1_000_000L
        val big = object : java.io.InputStream() {
            var left = 2_000_000
            override fun read(): Int { if (left-- <= 0) return -1; return 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len, left); left -= n; return n
            }
        }
        val r = Backup.restoreZip(ctx, big)
        assertTrue(r is Backup.RestoreResult.Refused)
        assertEquals("the file itself is too big", (r as Backup.RestoreResult.Refused).reason)
    }

    @Test
    fun actualAggregateAcrossEntriesIsEnforced() {
        // Each photo is under the per-entry cap; together they cross the aggregate gate on
        // real decompressed bytes, so the restore is refused.
        Backup.gateLimitForTests = 100_000_000L
        val dir = DamagePhotos.dir(ctx, "BD-20261102-EFGH").name
        val z = zipWith(backupJson(), mapOf(
            "photos/$dir/a.jpg" to ByteArray(40_000_000),
            "photos/$dir/b.jpg" to ByteArray(40_000_000),
            "photos/$dir/c.jpg" to ByteArray(40_000_000)))
        val r = Backup.restoreZip(ctx, z)
        assertTrue("aggregate real bytes refuse the restore, was $r", r is Backup.RestoreResult.Refused)
    }

    @Test
    fun exportFailureLeavesNoPartialFile() {
        // A refused export (here: one photo over the writer cap) must delete the partial
        // zip, never leave it for the share sheet.
        done(Backup.restoreZip(ctx, damageZip()))
        val dir = DamagePhotos.dir(ctx, "BD-20261102-EFGH")
        File(dir, "p1.jpg").writeBytes(ByteArray(60_000_001))
        assertThrows(Backup.BackupTooLarge::class.java) { Backup.shareIntent(ctx) }
        val exports = File(ctx.cacheDir, "exports")
        assertTrue("no partial export left behind", exports.listFiles()?.isEmpty() != false)
    }

    @Test
    fun failedAutoSaveDoesNotMoveTheTimestamp() {
        Prefs.lastBackupTs = 0L
        Prefs.backupFolder = ""
        val r = Backup.autoSaveResult(ctx)
        assertTrue("no folder is not a save, was $r", r !is Backup.SaveResult.Saved)
        assertEquals(0L, Prefs.lastBackupTs)
    }

    @Test
    fun failedSaveToDeadFolderKeepsTimestampAndNamesTheFailure() {
        Prefs.lastBackupTs = 1234L
        Prefs.lastBackupError = ""
        Prefs.backupFolder = "content://com.example.bogus/tree/dead"
        val r = Backup.autoSaveResult(ctx)
        assertTrue("a dead folder is a failure, was $r", r is Backup.SaveResult.Failed)
        assertEquals("a failed save must not move the backup timestamp", 1234L, Prefs.lastBackupTs)
        assertTrue("the failure is named for the Settings line", Prefs.lastBackupError.isNotBlank())
        Prefs.backupFolder = ""
    }

    @Test
    fun theOutcomeLineFollowsEveryOutcome() {
        // The SAF read-back success path is not Robolectric-feasible; the funnel itself is.
        Prefs.lastBackupError = "older error"
        Backup.noteOutcome(Backup.SaveResult.Saved)
        assertEquals("", Prefs.lastBackupError)
        Backup.noteOutcome(Backup.SaveResult.Failed("folder gone"))
        assertEquals("folder gone", Prefs.lastBackupError)
        Backup.noteOutcome(Backup.SaveResult.TooLarge("too big"))
        assertEquals("too big", Prefs.lastBackupError)
        Backup.noteOutcome(Backup.SaveResult.NoFolder)
        assertEquals("a folder deliberately unpicked clears a stale line", "", Prefs.lastBackupError)
    }

    @Test
    fun failedRenameIsFlaggedAndLeavesNoStaging() {
        // The rename half of stageCopy: a destination the rename cannot take (a non-empty
        // directory stands in for a locked or cross-mount target) throws, and the staging
        // file is cleaned up either way so a re-run never mistakes it for the real thing.
        val dir = File(ctx.filesDir, "rename-test").apply { mkdirs() }
        try {
            val src = File(dir, "src.jpg").apply { writeBytes(ByteArray(8) { 3 }) }
            val dest = File(dir, "dest.jpg").apply { mkdirs(); File(this, "blocker").writeBytes(ByteArray(1)) }
            assertThrows(java.io.IOException::class.java) { Backup.stageCopy(src, dest) }
            assertFalse("no staging file survives a failed rename", File(dir, "dest.jpg" + Backup.RESTORE_TMP).exists())
            assertTrue("the blocking destination is untouched", dest.isDirectory)
        } finally { dir.deleteRecursively() }
    }
}
