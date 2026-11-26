package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(Stamp(1, before + 1), Stamp.now(1))
    }

    @Test fun geometryKeyMatchesOnlyIdenticalGeometry() {
        val k = GeoKey(700f, 3, 2.75f, 1f)
        assertEquals(k, GeoKey(700f, 3, 2.75f, 1f))
        assertNotEquals(k, GeoKey(640f, 3, 2.75f, 1f)); assertNotEquals(k, GeoKey(700f, 4, 2.75f, 1f)); assertNotEquals(k, GeoKey(700f, 3, 2.75f, 1.3f))
    }
}
