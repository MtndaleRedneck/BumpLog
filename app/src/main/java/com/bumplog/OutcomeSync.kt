package com.bumplog

/**
 * Keeps a spot's two Fixed marks in step: Mark fixed in Review and the Fixed outcome on the Reported tab
 * are the same fact, so each path updates the other's state. Pure rules, unit-tested on the JVM.
 */
object OutcomeSync {
    enum class Mark { FIX, REVIVE, NONE }

    /**
     * An outcome picked on the Reported tab: FIXED also marks the spot fixed. Any other outcome on a spot
     * that was marked fixed (from Review) revives it as confirmed with warnings back on.
     */
    fun forOutcome(outcome: String, review: String): Mark = when {
        outcome == "FIXED" -> Mark.FIX
        review == "FIXED" -> Mark.REVIVE
        else -> Mark.NONE
    }

    /** Mark fixed from Review counts as the Fixed outcome only when the report actually went to the city. */
    fun fixedOutcome(reportStatus: String): Boolean = reportStatus == "SENT"
}
