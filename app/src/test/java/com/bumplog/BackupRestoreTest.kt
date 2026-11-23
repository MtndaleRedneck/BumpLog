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
        val z = zipWith(backupJson(), listOf(
            "photos/$dir/p1.jpg" to ByteArray(10) { 1 },
            "photos/$dir/p1.jpg" to ByteArray(10) { 2 }))
        assertTrue(Backup.restoreZip(ctx, z) is Backup.RestoreResult.Refused)
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
        done(Backup.restoreZip(ctx, damageZip()))
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
}
