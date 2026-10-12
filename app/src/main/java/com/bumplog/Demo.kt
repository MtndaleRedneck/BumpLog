package com.bumplog

import android.content.Context

/** Debug builds only: fills the app with sample spots so screenshots and manual checks have something to show. */
object Demo {
    fun seed(ctx: Context) {
        val db = Db.get(ctx)
        if (db.allSpots().isNotEmpty()) return
        val now = System.currentTimeMillis()
        val drive = now - 3_600_000
        fun add(road: String, minsAgo: Int, g: Float, mph: Float, t: DefectType, lat: Double, lon: Double, review: String = "PENDING") {
            val h = Hit(0, 0, drive, now - minsAgo * 60_000L, lat, lon, 90.0, mph, g, 120, 2, t)
            val s = db.recordHit(h, t)
            db.setAddress(s.id, road); db.setReview(s.id, review)
        }
        add("Example Ave", 26, 0.94f, 38f, DefectType.POTHOLE, 39.5000, -98.3500, "CONFIRMED")
        add("Sample Rd", 31, 0.71f, 41f, DefectType.POTHOLE, 39.5100, -98.3300)
        add("Demo Hwy 1", 48, 1.34f, 55f, DefectType.FAULTING, 39.5300, -98.3700)
        add("Test Blvd", 70, 0.83f, 29f, DefectType.JOINT, 39.4900, -98.3400)
        db.addDamage(db.hits(1).first().id, 0, "Tire", "Demo", now)
    }
}
