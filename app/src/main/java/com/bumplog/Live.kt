package com.bumplog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val speedMph: Float = 0f,
    val gps: Boolean = false,
    val hardestHitG: Float = 0f,
    val lastG: Float = 0f,
    val tick: Int = 0,          // bumps when the database changes, so screens reload
    val driveId: Long = 0,
    val wave: FloatArray = FloatArray(0),
)

/** What the foreground service is doing right now, shared with the screens. */
object Live {
    private val _s = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _s.asStateFlow()
    fun update(f: (LiveState) -> LiveState) { _s.value = f(_s.value) }
    fun bump() = update { it.copy(tick = it.tick + 1) }
}

/** Which drive the Home hit counter refers to: the live drive while running, else the last one (0 = none). */
fun homeDriveId(running: Boolean, liveDriveId: Long, lastDriveId: Long): Long =
    if (running) liveDriveId else lastDriveId

fun homeDriveLabel(running: Boolean): String = if (running) "HITS THIS DRIVE" else "HITS LAST DRIVE"

/** State at the start of a drive: the hardest hit starts over so "this trip" only covers the current drive. */
fun driveStartState(previousTick: Int, passenger: Boolean, driveId: Long) =
    LiveState(running = true, paused = passenger, driveId = driveId, tick = previousTick + 1)

fun hardestAfterHit(current: Float, peak: Float) = maxOf(current, peak)

fun homeHardestHitLabel(running: Boolean): String = if (running) "HARDEST HIT THIS DRIVE" else "HARDEST HIT LAST DRIVE"

enum class PassengerStep { NONE, WARN, FLIP }

/** Passenger timer rule: warn in the last 10 minutes, flip only after time is up AND the phone has been still for 2 minutes. */
fun passengerStep(leftMs: Long, warned: Boolean, speedMph: Float, stillMs: Long): PassengerStep = when {
    leftMs > 10 * 60_000L -> PassengerStep.NONE
    leftMs > 0 -> if (warned) PassengerStep.NONE else PassengerStep.WARN
    speedMph < 3f && stillMs >= 2 * 60_000L -> PassengerStep.FLIP
    else -> PassengerStep.NONE
}

/** A new trip after a long stop keeps the GPS state and, above all, the Passenger/Driver mode the user chose. */
fun tripRolloverState(previous: LiveState, passenger: Boolean, driveId: Long) =
    driveStartState(previous.tick, passenger, driveId).copy(gps = previous.gps)

const val STANDSTILL_MPH = 3f

/** Auto-off counts real standstill (not the speed gate), so crawling traffic never turns logging off mid-drive. */
fun autoOffDue(lastMovingMs: Long, nowMs: Long, minutes: Int) = nowMs - lastMovingMs > minutes * 60_000L
