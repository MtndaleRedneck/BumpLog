package com.bumplog

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The receiver keeps only notification side effects that do not start an activity. The two
 * that open the app (restart, send trip) moved to InternalActionActivity: a receiver first in
 * a notification tap is the Android 12+ trampoline and is blocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionReceiverTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Db.closeForTests()
        Prefs.init(ctx)
        Prefs.detectionEnabled = false
        Prefs.restartWanted = false
        Prefs.pendingTrip = 0L
    }

    @Test
    fun stopStillCleansUpFlagsWhenIdle() {
        Prefs.detectionEnabled = true
        Prefs.passenger = true
        ActionReceiver().onReceive(ctx, Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.STOP))
        assertFalse(Prefs.detectionEnabled)
        assertFalse(Prefs.passenger)
    }
}
