package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Test

class PassengerStepTest {
    private val min = 60_000L
    @Test fun farFromTheEndNothingHappens() = assertEquals(PassengerStep.NONE, passengerStep(60 * min, false, 0f, 10 * min))
    @Test fun warnsOnceInTheLastTenMinutes() {
        assertEquals(PassengerStep.WARN, passengerStep(9 * min, false, 40f, 0))
        assertEquals(PassengerStep.NONE, passengerStep(9 * min, true, 40f, 0))
    }
    @Test fun neverFlipsWhileMoving() {
        assertEquals(PassengerStep.NONE, passengerStep(0, true, 45f, 0))
        assertEquals(PassengerStep.NONE, passengerStep(0, true, 0f, 30_000L))
    }
    @Test fun flipsOnceStoppedForTwoMinutes() = assertEquals(PassengerStep.FLIP, passengerStep(0, true, 0f, 2 * min))
}

class TripRolloverTest {
    @Test fun passengerSurvivesARestStop() {
        val prev = LiveState(running = true, paused = true, gps = true, tick = 4)
        val next = tripRolloverState(prev, true, 99L)
        assertEquals(true, next.paused); assertEquals(true, next.running); assertEquals(true, next.gps); assertEquals(99L, next.driveId)
    }
    @Test fun driverStaysDriver() = assertEquals(false, tripRolloverState(LiveState(running = true, tick = 1), false, 5L).paused)
}

class AutoOffTest {
    private val min = 60_000L
    @Test fun standstillPastTheLimitTurnsOff() = assertEquals(true, autoOffDue(0L, 31 * min, 30))
    @Test fun crawlingTrafficKeepsLogging() = assertEquals(false, autoOffDue(29 * min, 31 * min, 30))
    @Test fun movingKeepsLogging() = assertEquals(false, autoOffDue(31 * min, 31 * min, 30))
    @Test fun exactlyAtTheLimitStillOn() = assertEquals(false, autoOffDue(0L, 30 * min, 30))
}
