package com.bumplog

import android.content.Context

/** Debug builds only: fills the app with sample spots so screenshots and manual checks have something to show. */
object Demo {
    fun seed(ctx: Context) {
        val db = Db.get(ctx)
        if (db.allSpots().isNotEmpty()) return
        val now = System.currentTimeMillis()
        var drive = now - 3_600_000
        fun add(road: String, minsAgo: Int, g: Float, mph: Float, t: DefectType, lat: Double, lon: Double, review: String = "PENDING"): Spot {
            val h = Hit(0, 0, drive, now - minsAgo * 60_000L, lat, lon, 90.0, mph, g, 120, 2, t)
            val s = db.recordHit(h, t)
            db.setAddress(s.id, road); db.setReview(s.id, review)
            return s
        }
        drive = now - 30 * 3_600_000L
        add("Late Lane", 6 * 60, 0.76f, 31f, DefectType.POTHOLE, 39.4400, -98.2900)
        add("Sent Lane", 29 * 60, 0.88f, 33f, DefectType.POTHOLE, 39.5200, -98.3600, "CONFIRMED").also { db.update(it.id, "report_status" to "SENT", "sent_ts" to now - 28 * 3_600_000L - 1_800_000L) }
        add("Sent Lane", 26 * 60, 0.91f, 35f, DefectType.POTHOLE, 39.5200, -98.3600, "CONFIRMED")
        add("Mill St", 28 * 60, 0.67f, 36f, DefectType.POTHOLE, 39.4700, -98.3100)
        add("Quarry Rd", 27 * 60, 1.12f, 48f, DefectType.FAULTING, 39.4600, -98.3900)
        drive = now - 3_600_000
        add("Example Ave", 26, 0.94f, 38f, DefectType.POTHOLE, 39.5000, -98.3500, "CONFIRMED")
        add("Sample Rd", 31, 0.71f, 41f, DefectType.POTHOLE, 39.5100, -98.3300)
        add("Demo Hwy 1", 48, 1.34f, 55f, DefectType.FAULTING, 39.5300, -98.3700)
        add("Test Blvd", 70, 0.83f, 29f, DefectType.RUMBLE, 39.4900, -98.3400)
        Prefs.lastDriveId = drive
        db.addDamage(db.hits(1).first().id, 0, "Tire", "Demo", now)
    }
}
