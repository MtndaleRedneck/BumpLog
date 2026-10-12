package com.bumplog

import android.content.Context

/** Simulation controls for pre-beta testing. They fire the real notifications and screens using flagged TEST spots. */
object Testing {
    fun majorHit(ctx: Context) {
        Notifier.channels(ctx)
        val s = Db.get(ctx).addTestSpot(DefectType.POTHOLE, confirmed = false, severity = "MAJOR", fileTrip = false, maxG = 1.6f)
        Notifier.severe(ctx, s, false)
        Sound.play(ctx, "Major bump detected.", true)
    }

    fun warnAhead(ctx: Context) {
        Notifier.channels(ctx)
        val s = Db.get(ctx).addTestSpot(DefectType.POTHOLE, confirmed = true, severity = "MAJOR", fileTrip = false, maxG = 1.6f)
        Notifier.ahead(ctx, s, 500, false)
        if (Prefs.audioMode == "VOICE" || Prefs.audioMode == "BOTH") Sound.play(ctx, "Road defect ahead", false)
        else Sound.play(ctx, "Road defect ahead", true)
    }

    /** Uses test spots already marked "file at end of trip" (for example from the simulated major hit); adds two samples if there are none. */
    fun endOfTrip(ctx: Context) {
        Notifier.channels(ctx)
        val db = Db.get(ctx)
        if (db.tripSpots(Db.TEST_DRIVE).isEmpty()) {
            db.addTestSpot(DefectType.POTHOLE, true, "MAJOR", true, 1.7f)
            db.addTestSpot(DefectType.FAULTING, true, "MINOR", true, 1.4f)
        }
        val n = db.tripSpots(Db.TEST_DRIVE).size
        Notifier.driveDone(ctx, n, n, Db.TEST_DRIVE)
    }
}
