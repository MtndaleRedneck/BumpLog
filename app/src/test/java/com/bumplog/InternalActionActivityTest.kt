package com.bumplog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The restart-logging and send-trip notification actions fire a PendingIntent straight at this
 * NON-EXPORTED activity - a receiver in between is the Android 12+ notification trampoline and
 * is blocked on targetSdk 34. It applies the side effect through Prefs and opens MainActivity
 * plainly: no command extras to forge, nothing stranded in the receiver. Its exported="false"
 * manifest flag is the boundary that keeps other apps from reaching the side effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InternalActionActivityTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Db.closeForTests()
        Prefs.init(ctx)
        Prefs.detectionEnabled = false
        Prefs.restartWanted = false
        Prefs.pendingTrip = 0L
        Prefs.askSentFor = ""
    }

    @Test
    fun restartSetsStateForTheNormalStartFlowAndOpensMainPlainly() {
        Robolectric.buildActivity(InternalActionActivity::class.java, InternalActionActivity.restartIntent(ctx)).create()
        assertTrue(Prefs.detectionEnabled)
        assertTrue("MainActivity applies its own start + permission flow", Prefs.restartWanted)
        val next = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next?.component?.className)
        assertNull("the app opens without command extras", next?.getStringExtra("do"))
    }

    @Test
    fun tripSendWaitsForSetupWhenSetupIsMissing() {
        Prefs.setupDone = false
        Robolectric.buildActivity(InternalActionActivity::class.java, InternalActionActivity.sendTripIntent(ctx, 42L)).create()
        assertEquals(42L, Prefs.pendingTrip)
        assertEquals("nothing is marked sent before the mail handoff", "", Prefs.askSentFor)
    }

    @Test
    fun tripSendWithoutReportsMarksNothing() {
        Prefs.setupDone = true
        Robolectric.buildActivity(InternalActionActivity::class.java, InternalActionActivity.sendTripIntent(ctx, 42L)).create()
        assertEquals(0L, Prefs.pendingTrip)
        assertEquals("", Prefs.askSentFor)
    }
}
