package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSnapTest {
    private val a = Stamp(5, 10L)

    @Test fun coldProcessHasNothingWarm() {
        TabSnap.clear()
        assertNull(TabSnap.stats.warm(a)); assertNull(TabSnap.home.warm(a)); assertNull(TabSnap.review.warm(a)); assertNull(TabSnap.reported.warm(a))
    }

    @Test fun sameStampIsWarm() {
        TabSnap.clear()
        TabSnap.reported.put(a, ReportedSnap(emptyList()))
        assertNotNull(TabSnap.reported.warm(Stamp(5, 10L)))
    }

    @Test fun anyWriteMakesItColdSoOldDataNeverShows() {
        TabSnap.clear()
        TabSnap.reported.put(a, ReportedSnap(emptyList()))
        assertNull(TabSnap.reported.warm(Stamp(5, 11L)))   // a write that did not bump the Live tick
        assertNull(TabSnap.reported.warm(Stamp(6, 10L)))   // a Live tick bump
    }

    @Test fun aLoadStampedBeforeAWriteIsStaleOnceTheWriteLands() {
        TabSnap.clear()
        TabSnap.reported.put(Stamp(5, 10L), ReportedSnap(emptyList()))   // load began at writes=10, a write landed at 11
        assertNull(TabSnap.reported.warm(Stamp(5, 11L)))
    }

    @Test fun wipeRaceClearAfterWriteDropsAnInFlightSnapshot() {
        TabSnap.clear()
        TabSnap.reported.put(a, ReportedSnap(emptyList()))
        TabSnap.clear()
        assertNull(TabSnap.reported.warm(a))
    }

    @Test fun dbWriteCounterMovesOnNoteWrite() {
        val before = Db.writes; Db.noteWrite()
        assertEquals(before + 1, Db.writes)
        assertEquals(Stamp(1, before + 1, Db.startOfToday()), Stamp.now(1))
        assertNotEquals(Stamp.now(1), Stamp.now(1).copy(day = 1L))   // a new day makes a snapshot cold
    }

    @Test fun geometryKeyMatchesOnlyIdenticalGeometry() {
        val k = GeoKey(700f, 3, 2.75f, 1f)
        assertEquals(k, GeoKey(700f, 3, 2.75f, 1f))
        assertNotEquals(k, GeoKey(640f, 3, 2.75f, 1f)); assertNotEquals(k, GeoKey(700f, 4, 2.75f, 1f)); assertNotEquals(k, GeoKey(700f, 3, 2.75f, 1.3f))
    }

    @Test fun coldFirstFrameReadsNowSoItIsNeverBlank() {
        TabSnap.clear()
        var reads = 0
        val v = firstFrame(TabSnap.reported, 5) { reads++; ReportedSnap(emptyList()) }
        assertNotNull(v); assertEquals(1, reads)
    }

    @Test fun warmFirstFrameDoesNotReadAgain() {
        TabSnap.clear()
        var reads = 0
        firstFrame(TabSnap.reported, 5) { reads++; ReportedSnap(emptyList()) }
        firstFrame(TabSnap.reported, 5) { reads++; ReportedSnap(emptyList()) }
        assertEquals(1, reads)
    }

    @Test fun afterAWriteTheFirstFrameReadsFreshDataNotTheOldSnapshot() {
        TabSnap.clear()
        var n = 0
        val a1 = firstFrame(TabSnap.reported, 5) { ReportedSnap(listOf()) .also { n++ } }
        Db.noteWrite()
        firstFrame(TabSnap.reported, 5) { n++; ReportedSnap(listOf()) }
        assertEquals(2, n); assertNotNull(a1)
    }

    @Test fun anUnusableWarmSnapshotIsReplacedByAFreshRead() {
        TabSnap.clear()
        TabSnap.home.put(Stamp.now(5), HomeSnap(emptyList(), 3, 7L))
        val v = firstFrame(TabSnap.home, 5, usable = { it.driveKey == 9L }) { HomeSnap(emptyList(), 0, 9L) }
        assertEquals(9L, v!!.driveKey)
    }

    @Test fun firstFrameGivesUpOnABusyDatabaseThenStillWarmsTheSlot() {
        val slot = SnapSlot<ReportedSnap>(); val gate = java.util.concurrent.CountDownLatch(1)
        val t0 = System.nanoTime()
        val r = firstFrame(slot, 9) { gate.await(); ReportedSnap(emptyList()) }   // a read stuck behind an import
        assertNull(r)
        assertTrue("main thread waited too long", (System.nanoTime() - t0) / 1_000_000 < 1000)
        gate.countDown()
        var warm = false
        for (k in 0 until 40) { if (slot.warm(Stamp.now(9)) != null) { warm = true; break }; Thread.sleep(50) }
        assertTrue("late read should warm the slot under its own stamp", warm)
    }
}
