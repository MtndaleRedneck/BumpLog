package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeSyncTest {
    @Test fun fixedOutcomeMarksSpotFixed() {
        assertEquals(OutcomeSync.Mark.FIX, OutcomeSync.forOutcome("FIXED", "CONFIRMED"))
        assertEquals(OutcomeSync.Mark.FIX, OutcomeSync.forOutcome("FIXED", "FIXED"))
    }

    @Test fun otherOutcomeRevivesASpotMarkedFixed() {
        // Mark fixed in Review, then "Waiting on the city" / "Unclear" / "Won't fix" in Reported: back to confirmed.
        assertEquals(OutcomeSync.Mark.REVIVE, OutcomeSync.forOutcome("NONE", "FIXED"))
        assertEquals(OutcomeSync.Mark.REVIVE, OutcomeSync.forOutcome("UNCLEAR", "FIXED"))
        assertEquals(OutcomeSync.Mark.REVIVE, OutcomeSync.forOutcome("WONTFIX", "FIXED"))
    }

    @Test fun otherOutcomeLeavesAConfirmedSpotAlone() {
        assertEquals(OutcomeSync.Mark.NONE, OutcomeSync.forOutcome("UNCLEAR", "CONFIRMED"))
        assertEquals(OutcomeSync.Mark.NONE, OutcomeSync.forOutcome("WONTFIX", "PENDING"))
    }

    @Test fun markFixedCountsAsOutcomeOnlyWhenSent() {
        assertTrue(OutcomeSync.fixedOutcome("SENT"))
        assertFalse(OutcomeSync.fixedOutcome("NONE"))
        assertFalse(OutcomeSync.fixedOutcome("HANDED_OFF"))
    }
}
