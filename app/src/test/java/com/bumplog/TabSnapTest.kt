package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSnapTest {
    @Test fun clearMarksEveryTabUnloadedSoNoStaleDataShows() {
        TabSnap.homeLoaded = true; TabSnap.reviewLoaded = true; TabSnap.statsLoaded = true; TabSnap.reportedLoaded = true
        TabSnap.homeDriveKey = 7
        TabSnap.clear()
        assertFalse(TabSnap.homeLoaded); assertFalse(TabSnap.reviewLoaded); assertFalse(TabSnap.statsLoaded); assertFalse(TabSnap.reportedLoaded)
        assertEquals(-1L, TabSnap.homeDriveKey)
    }

    @Test fun aColdProcessStartsUnloaded() {
        TabSnap.clear()
        assertTrue(!TabSnap.homeLoaded && !TabSnap.reviewLoaded && !TabSnap.statsLoaded && !TabSnap.reportedLoaded)
    }
}
