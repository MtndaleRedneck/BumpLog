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
 * The non-exported receiver is the only bearer of notification-tap side effects: a trip
 * send and a logging restart arrive as receiver actions and land in Prefs for the app to
 * apply with its normal screens, never as forgeable extras on the exported activity.
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
    fun restartSetsStateForTheNormalStartFlow() {
        ActionReceiver().onReceive(ctx, Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.RESTART))
        assertTrue(Prefs.detectionEnabled)
        assertTrue("MainActivity applies its own start + permission flow", Prefs.restartWanted)
    }

    @Test
    fun tripSendWaitsForSetupWhenSetupIsMissing() {
        Prefs.setupDone = false
        ActionReceiver().onReceive(ctx, Intent(ctx, ActionReceiver::class.java)
            .setAction(ActionReceiver.SENDTRIP).putExtra("drive", 42L))
        assertEquals(42L, Prefs.pendingTrip)
        assertEquals("nothing is marked sent before the mail handoff", "", Prefs.askSentFor)
    }

    @Test
    fun tripSendWithoutReportsMarksNothing() {
        Prefs.setupDone = true
        ActionReceiver().onReceive(ctx, Intent(ctx, ActionReceiver::class.java)
            .setAction(ActionReceiver.SENDTRIP).putExtra("drive", 42L))
        assertEquals(0L, Prefs.pendingTrip)
        assertEquals("", Prefs.askSentFor)
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
