package com.bumplog

import org.junit.Assert.*
import org.junit.Test

class TripReportPolicyTest {
    private val spot = Spot(1, "BL-1", 0.0, 0.0, 0.0, DefectType.POTHOLE, true, 1.4f, 1, 30f,
        1, 1, 10, "CONFIRMED", true, "NONE", 0, "Example Rd", 0, fileTrip = true)

    @Test fun queueRequiresExplicitConfirmationAndNeverUsesRealDriveMembership() {
        val query = TripReportPolicy.queueWhere(false)
        assertTrue(query.contains("file_trip=1"))
        assertTrue(query.contains("review='CONFIRMED'"))
        assertTrue(query.contains("report_status='NONE'"))
        assertTrue(query.contains("report_id NOT LIKE 'TEST-%'"))
        assertFalse(query.contains("drive_id"))
    }
    @Test fun simulatedQueueCannotIncludeRealReports() {
        val query = TripReportPolicy.queueWhere(true)
        assertTrue(query.contains("drive_id=-77"))
        assertTrue(query.contains("report_id LIKE 'TEST-%'"))
    }
    @Test fun sendNowHiddenThroughoutActiveCurrentDriveEvenWhenNotQueued() {
        assertFalse(TripReportPolicy.canSendNow(spot, true, 10))
        assertFalse(TripReportPolicy.canSendNow(spot.copy(fileTrip = false), true, 10))
        assertTrue(TripReportPolicy.canSendNow(spot, true, 11))
        assertTrue(TripReportPolicy.canSendNow(spot, false, 10))
        assertFalse(TripReportPolicy.canSendNow(spot.copy(review = "PENDING"), false, 10))
        assertFalse(TripReportPolicy.canSendNow(spot.copy(reportStatus = "SENT"), false, 10))
    }
}
