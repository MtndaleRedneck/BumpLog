package com.bumplog

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The restart-notification path on a real device, from both sides of the tap.
 *
 * DISABLED baseline: with detection deliberately off, the boot receiver must
 * change nothing (it posts no "Logging paused" notice and never starts by
 * itself - BootReceiver returns before touching state when detection is off).
 * Notification ABSENCE itself is not asserted here (reading posted
 * notifications needs listener access); the asserted bound is the state no-op.
 *
 * Tap: the exact intent the notification's PendingIntent carries is delivered
 * to the non-exported InternalActionActivity the way the system delivers the
 * tap (same uid, so exported=false is no obstacle). The activity re-arms
 * detection, records restart_wanted, and chains MainActivity, whose start flow
 * consumes restart_wanted. Whether logging actually starts next depends on
 * runtime permissions and is deliberately out of scope. The framework's
 * delivery of the PendingIntent to a non-exported activity is covered by the
 * dumpsys boundary artifact, not re-proven here.
 */
@RunWith(AndroidJUnit4::class)
class RestartTapDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        Prefs.init(ctx)
        Prefs.detectionEnabled = false
        Prefs.restartWanted = false
    }

    @After
    fun tearDown() {
        Prefs.detectionEnabled = false
        Prefs.restartWanted = false
    }

    @Test
    fun bootWithDetectionDisabledChangesNothing() {
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse("detection stays off after boot", Prefs.detectionEnabled)
        assertFalse("no restart is armed after boot", Prefs.restartWanted)
    }

    @Test
    fun restartTapArmsDetectionAndTheStartFlowConsumesIt() {
        ctx.startActivity(
            InternalActionActivity.restartIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // InternalActionActivity chains MainActivity, whose start flow consumes
        // restart_wanted; give the real activity stack time to run.
        var consumed = false
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (Prefs.detectionEnabled && !Prefs.restartWanted) { consumed = true; break }
            Thread.sleep(200)
        }
        assertTrue("the tap re-armed detection", Prefs.detectionEnabled)
        assertTrue("the start flow consumed restart_wanted", consumed)
    }
}
