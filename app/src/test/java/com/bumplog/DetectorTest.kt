package com.bumplog

import org.junit.Assert.*
import org.junit.Test

class DetectorTest {
    @Test fun singleSpikeIsOneEvent() {
        val d = HitDetector(0.8f)
        var ev: HitEvent? = null
        var t = 0L
        repeat(20) { d.onSample(t, 0.1f); t += 10 }
        listOf(0.9f, 1.4f, 1.0f, 0.3f).forEach { d.onSample(t, it); t += 10 }
        repeat(40) { d.onSample(t, 0.1f)?.let { e -> ev = e }; t += 10 }
        assertNotNull(ev)
        assertEquals(1.4f, ev!!.peakG, 0.001f)
    }

    @Test fun quietRoadMakesNoEvent() {
        val d = HitDetector(0.8f)
        repeat(500) { assertNull(d.onSample(it * 10L, 0.2f)) }
    }

    @Test fun geoDistanceAndAngles() {
        assertEquals(111195.0, Geo.distance(0.0, 0.0, 1.0, 0.0), 300.0)
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 0.001)
    }

    @Test fun busyShakeIsRumble() {
        val e = HitEvent(10_000, 1.0f, 900, 8)
        assertEquals(DefectType.RUMBLE, Classifier.guess(e, 50f, emptyList()))
    }
}
