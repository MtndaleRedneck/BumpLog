package com.bumplog

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class BackupTest {

    @Test fun dayKeyReadsExactAndProviderRenamedNames() {
        assertEquals("20261025", Backup.dayKey("BumpLog-backup-20261025.zip"))
        assertEquals("20261025", Backup.dayKey("BumpLog-backup-20261025 (1).zip"))
        assertEquals("20261025", Backup.dayKey("BumpLog-backup-20261025 (12).zip"))
    }

    @Test fun dayKeyRejectsForeignAndMalformedNames() {
        assertNull(Backup.dayKey("BumpLog-backup-final.zip"))
        assertNull(Backup.dayKey("BumpLog-backup-2026102.zip"))   // 7 digits
        assertNull(Backup.dayKey("BumpLog-backup-202610251.zip")) // 9 digits: not this app's name
        assertNull(Backup.dayKey("somethingelse.zip"))
    }

    /** Tab HOLD item 1: a second save on the same day (provider-named " (1)") must share the day's slot,
     * the NEWER save must survive for that day, and the busy day must not push an older day out. */
    @Test fun busyDayCountsOnceAndKeepsItsNewestSave() {
        fun z(id: String, ts: Long, name: String) = Triple(id, ts, name)
        val zips = listOf(
            z("d2", 100, "BumpLog-backup-20261018.zip"),
            z("d3", 200, "BumpLog-backup-20261019.zip"),
            z("d4", 300, "BumpLog-backup-20261020.zip"),
            z("d5", 400, "BumpLog-backup-20261021.zip"),
            z("busy-first", 500, "BumpLog-backup-20261025.zip"),      // first save of the day
            z("busy-second", 600, "BumpLog-backup-20261025 (1).zip"), // trip email later the same day
            z("d6", 700, "BumpLog-backup-20261022.zip"),
            z("d7", 800, "BumpLog-backup-20261023.zip"),
            z("d8", 900, "BumpLog-backup-20261024.zip"))
        val keep = Backup.selectKeep(zips)
        assertTrue("the day's newer save survives", "busy-second" in keep)
        assertFalse("the day's older exact-named save is pruned", "busy-first" in keep)
        assertTrue("the oldest day is not pushed out by the busy day", "d2" in keep)
        assertEquals(8, keep.size) // 8 distinct days, one survivor each
    }

    @Test fun floorKeepsNewestTwoFilesEvenWithinOneDay() {
        val zips = listOf(
            Triple("only", 100L, "BumpLog-backup-20261025.zip"))
        assertEquals(setOf("only"), Backup.selectKeep(zips))
        val two = listOf(
            Triple("first", 100L, "BumpLog-backup-20261025.zip"),
            Triple("second", 200L, "BumpLog-backup-20261025 (1).zip"))
        assertEquals(setOf("first", "second"), Backup.selectKeep(two))
    }

    @Test fun foreignFilesInTheFolderAreNeverPruned() {
        val zips = listOf(
            Triple("foreign", 50L, "BumpLog-backup-final.zip"),
            Triple("a", 100L, "BumpLog-backup-20261025.zip"))
        val keep = Backup.selectKeep(zips)
        assertTrue("foreign" in keep)
        assertTrue("a" in keep)
    }

    @Test fun newestPerDayFollowsLastModifiedNotName() {
        // Same day, but this time the exact-named file is the NEWER one (clock or provider quirks).
        val zips = listOf(
            Triple("renamed-older", 100L, "BumpLog-backup-20261025 (1).zip"),
            Triple("exact-newer", 200L, "BumpLog-backup-20261025.zip"))
        val keep = Backup.selectKeep(zips + (1..6).map { Triple("o$it", 1000L + it, "BumpLog-backup-2026101$it.zip") })
        assertTrue("exact-newer" in keep)
        assertFalse("renamed-older" in keep)
    }

    @Test fun writeOrDeleteReturnsFileOnlyAfterACleanWrite() {
        val sink = ByteArrayOutputStream()
        val deleted = mutableListOf<String>()
        val out = Backup.writeOrDelete(
            create = { "f" },
            openOut = { sink },
            write = { it.write("zip-bytes".toByteArray()) },
            delete = { deleted.add(it) })
        assertEquals("f", out)
        assertTrue(deleted.isEmpty())
        assertEquals("zip-bytes", sink.toString())
    }

    @Test fun writeOrDeleteDeletesWhenTheStreamNeverOpens() {
        val deleted = mutableListOf<String>()
        val out = Backup.writeOrDelete<String>(create = { "f" }, openOut = { null }, write = { }, delete = { deleted.add(it) })
        assertNull(out)
        assertEquals(listOf("f"), deleted)
    }

    /** Tab HOLD item 2: a write that throws midway must still remove the partial file. */
    @Test fun writeOrDeleteDeletesWhenTheWriteThrows() {
        val deleted = mutableListOf<String>()
        val out = Backup.writeOrDelete(
            create = { "f" },
            openOut = { ByteArrayOutputStream() as OutputStream },
            write = { throw java.io.IOException("disk full") },
            delete = { deleted.add(it) })
        assertNull(out)
        assertEquals(listOf("f"), deleted)
    }

    @Test fun writeOrDeleteNeverDeletesWhenCreateFailsAndSurvivesDeleteThrowing() {
        var deletes = 0
        assertNull(Backup.writeOrDelete<String>(create = { null }, openOut = { null }, write = { }, delete = { deletes++ }))
        assertEquals(0, deletes)
        val out = Backup.writeOrDelete<String>(
            create = { "f" }, openOut = { null }, write = { },
            delete = { throw RuntimeException("provider gone") })
        assertNull(out) // no exception escapes
    }
}

class RestoreGateTest {

    @Test fun countsEveryEntryAndCapsThem() {
        val g = Backup.RestoreGate()
        repeat(5_000) { assertTrue(g.countEntry()) }
        assertFalse("entry 5001 is past the cap", g.countEntry())
    }

    @Test fun rejectsDuplicateAndOversizedVoiceClips() {
        val g = Backup.RestoreGate()
        assertTrue(g.admitVoice("a.clip", 100))
        assertFalse("a real backup has each voice name once", g.admitVoice("a.clip", 100))
        assertFalse("over 5 MB is not a clip this app wrote", g.admitVoice("b.clip", 5_000_001))
        assertTrue(g.admitVoice("b.clip", 5_000_000))
    }

    @Test fun capsCumulativeBytesAcrossJsonPhotosAndVoice() {
        val g = Backup.RestoreGate()
        assertTrue(g.addBytes(30_000_000L))   // a full backup.json
        assertTrue(g.addBytes(1_470_000_000L)) // photos + voice up to the same cap
        assertEquals(1_500_000_000L, g.total)
        assertFalse("one byte past the cap", g.addBytes(1))
    }

    @Test fun runPhaseFlagsFailureWithoutThrowing() {
        assertTrue(Backup.runPhase { })
        var ran = false
        assertFalse(Backup.runPhase { ran = true; throw java.io.IOException("disk full") })
        assertTrue("the phase itself ran", ran)
    }
}

class BackupBudgetTest {

    @Test fun skippedEntriesCountInTheSameBudget() {
        // The spreadsheet export and any unrecognized entry count toward the one byte
        // budget even though restore never opens them; exactly at the cap still passes.
        assertNull(Backup.budgetRefusal(listOf(1_500_000_000L)))
        assertNull(Backup.budgetRefusal(listOf(100L, 1_499_999_900L)))
        assertNotNull("one byte past the cap, wherever it lives", Backup.budgetRefusal(listOf(100L, 1_499_999_901L)))
        assertNotNull(Backup.budgetRefusal(listOf(1_500_000_001L)))
    }

    @Test fun capsEntryCount() {
        assertNull(Backup.budgetRefusal(List(5_000) { 1L }))
        assertNotNull("entry 5001 is past the cap", Backup.budgetRefusal(List(5_001) { 1L }))
    }
}
