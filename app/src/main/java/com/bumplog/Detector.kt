package com.bumplog

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [label] is the plain name shown in the app. [technical] is the engineering term used in report emails.
 * [spoken] is how the type is named in alerts and prompts, [avoidedName] is its Stats line.
 */
enum class DefectType(val label: String, val reportCategory: String, val technical: String, val spoken: String, val avoidedName: String) {
    POTHOLE("Pothole", "Pothole", "Pothole", "pothole", "Potholes avoided"),
    FAULTING("Broken pavement (lip)", "Uneven pavement / faulting", "Faulting (pavement lip)", "bump", "Bumps avoided"),
    RUMBLE("Rough stretch", "Rough pavement", "Rough pavement / surface deterioration (washboarding)", "rough road", "Rough stretches avoided");

    companion object {
        /** Expansion joints are no longer a type: old JOINT records and guesses read as a rough stretch. */
        fun parse(s: String?): DefectType = if (s == "JOINT") RUMBLE else values().firstOrNull { it.name == s } ?: POTHOLE
    }
}

/** One continuous impact event: from the first sample over the cutoff to a quiet gap. */
data class HitEvent(
    val startMs: Long,
    val peakG: Float,
    val durationMs: Long,
    val peaks: Int,
)

/**
 * Turns a stream of gravity-free acceleration magnitudes (in g) into discrete hit events.
 * Pure logic, no Android types, so it can be unit tested.
 */
class HitDetector(var cutoffG: Float) {
    private var inEvent = false
    private var start = 0L
    private var lastAbove = 0L
    private var peak = 0f
    private var peaks = 0
    private var armed = true
    private val quietGapMs = 250L

    fun onSample(tsMs: Long, g: Float): HitEvent? {
        if (g >= cutoffG) {
            if (!inEvent) {
                inEvent = true; start = tsMs; peak = g; peaks = 1; armed = false
            } else if (g > peak) peak = g
            lastAbove = tsMs
            if (armed) { peaks++; armed = false }
        } else {
            if (g < cutoffG * 0.6f) armed = true
            if (inEvent && tsMs - lastAbove > quietGapMs) {
                inEvent = false
                return HitEvent(start, peak, lastAbove - start, peaks)
            }
        }
        return null
    }

    fun reset() { inEvent = false; armed = true }
}

object Classifier {
    /**
     * Best guess at what kind of defect caused an event. Heuristics, meant to be confirmed by the user.
     * recentStartsMs: start times (ms) of earlier events in this drive, oldest first, not including [e].
     */
    fun guess(e: HitEvent, speedMph: Float, recentStartsMs: List<Long>): DefectType {
        val window = recentStartsMs.filter { e.startMs - it in 0..6000 }
        // A long, busy shake is a rough stretch, not a single defect.
        if (e.durationMs >= 600 && e.peaks >= 5) return DefectType.RUMBLE
        if (window.size >= 3 && (e.startMs - window.first()) / (window.size) < 1000) return DefectType.RUMBLE
        // Evenly spaced thumps, like a series of seams, are a rough stretch rather than separate defects.
        val starts = (window + e.startMs).takeLast(4)
        if (starts.size >= 3) {
            val gaps = starts.zipWithNext { a, b -> (b - a).toDouble() }
            val mean = gaps.average()
            val sd = sqrt(gaps.map { (it - mean) * (it - mean) }.average())
            if (mean >= 500 && sd / mean < 0.2) return DefectType.RUMBLE
        }
        // One sharp step at highway speed looks like a pavement lip.
        if (e.peaks <= 2 && e.durationMs < 300 && speedMph >= 45f) return DefectType.FAULTING
        return DefectType.POTHOLE
    }
}

/** Hits to be ignored: the phone being dropped or bumped while stopped is not a road event. */
fun plausibleHit(e: HitEvent): Boolean = e.peakG < 6f && abs(e.durationMs) < 5000
