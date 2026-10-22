package com.bumplog

import kotlin.math.*

object Geo {
    const val FT_PER_M = 3.28084
    const val MPH_PER_MPS = 2.23694

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing in degrees, 0..360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest absolute difference between two compass angles, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    /** Moves a point [d] metres along [brg] degrees. */
    fun offset(lat: Double, lon: Double, brg: Double, d: Double): Pair<Double, Double> {
        val r = 6371000.0
        val dr = d / r; val b = Math.toRadians(brg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(dr) + cos(p1) * sin(dr) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(dr) * cos(p1), cos(dr) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** Lateral (sideways) offset in meters of point P from the line through A heading `heading`. */
    fun lateralOffset(aLat: Double, aLon: Double, heading: Double, pLat: Double, pLon: Double): Double {
        val d = distance(aLat, aLon, pLat, pLon)
        val brg = bearing(aLat, aLon, pLat, pLon)
        return abs(d * sin(Math.toRadians(angleDiff(brg, heading))))
    }
}
