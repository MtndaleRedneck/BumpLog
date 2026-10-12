package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveCounterTest {
    @Test fun runningUsesLiveDrive() {
        assertEquals(5L, homeDriveId(true, 5L, 4L))
    }

    @Test fun parkedUsesLastDrive() {
        assertEquals(4L, homeDriveId(false, 5L, 4L))
    }

    @Test fun newDriveDoesNotInheritOldCount() {
        // Drive 2 starts: the counter points at drive 2, not drive 1.
        assertEquals(2L, homeDriveId(true, 2L, 1L))
    }

    @Test fun noDriveYetIsZero() {
        assertEquals(0L, homeDriveId(false, 0L, 0L))
    }

    @Test fun labelsFlipWithState() {
        assertEquals("HITS THIS DRIVE", homeDriveLabel(true))
        assertEquals("HITS LAST DRIVE", homeDriveLabel(false))
    }
}
