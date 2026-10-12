package com.bumplog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val speedMph: Float = 0f,
    val gps: Boolean = false,
    val biggestG: Float = 0f,
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
