package com.bumplog

/** Shared queue and send-button rules, independent of Android UI. */
object TripReportPolicy {
    fun queueWhere(test: Boolean): String = "file_trip=1 AND review='CONFIRMED' AND report_status='NONE'" +
        if (test) " AND drive_id=${Db.TEST_DRIVE} AND report_id LIKE 'TEST-%'" else " AND report_id NOT LIKE 'TEST-%'"

    fun canSendNow(spot: Spot, running: Boolean, activeDriveId: Long): Boolean =
        spot.review == "CONFIRMED" && spot.reportStatus == "NONE" &&
            !(running && spot.driveId == activeDriveId)
}
