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
        Prefs.askSentFor = ""; Prefs.askSentKind = ""; Prefs.askAgain = ""; Prefs.activeAddress = ""
        PendingAsk.resetStore()
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

    private fun seedTrip() {
        Prefs.setupDone = true; Prefs.activeAddress = "assistant@example.com"
        Db.get(ctx).writableDatabase.execSQL(
            """INSERT INTO spots(report_id, lat, lon, heading, type, type_user, max_g, hit_count, speed,
            first_ts, last_ts, drive_id, review, warn, report_status, sent_ts, address, clean_passes, file_trip)
            VALUES('R-1', 40.7, -73.9, 90.0, 'POTHOLE', 0, 3.5, 1, 25.0, 1000, 2000, 42,
            'CONFIRMED', 1, 'NONE', 0, 'Main St', 0, 1)""")
    }
    private fun startedMail(): List<android.content.Intent> {
        val app = Shadows.shadowOf(ctx as android.app.Application); val out = ArrayList<android.content.Intent>()
        while (true) { val i = app.nextStartedActivity ?: break; out.add(i) }
        return out
    }

    @Test
    fun tripSendFromTheNotificationClaimsTheQuestionBeforeTheMailOpens() {
        seedTrip(); startedMail()
        Robolectric.buildActivity(InternalActionActivity::class.java, InternalActionActivity.sendTripIntent(ctx, 42L)).create()
        val started = startedMail()
        assertEquals("a mail intent was handed off", 1, started.count { it.action == android.content.Intent.ACTION_SENDTO || it.action == android.content.Intent.ACTION_SEND || it.action == android.content.Intent.ACTION_SEND_MULTIPLE })
        assertEquals("trip", PendingAsk.current().kind); assertEquals("1", PendingAsk.current().sentFor)
    }

    @Test
    fun tripSendFromTheNotificationOpensNoMailWhileAnOlderQuestionIsUnanswered() {
        seedTrip(); startedMail()
        val snap = Rollback(5, 4, 100L, 90L, "", 0L, 777L).encode()
        PendingAsk.set("5", "again", snap)
        Robolectric.buildActivity(InternalActionActivity::class.java, InternalActionActivity.sendTripIntent(ctx, 42L)).create()
        val started = startedMail()
        assertEquals("only MainActivity opens, which shows the older question", listOf(MainActivity::class.java.name), started.map { it.component?.className })
        assertEquals("5", Prefs.askSentFor); assertEquals("again", Prefs.askSentKind); assertEquals(snap, Prefs.askAgain)   // untouched
    }

    @Test
    fun aRefusedMailHandOffGivesTheReservationBack() {
        seedTrip(); Prefs.activeAddress = ""; startedMail()   // no assistant address: the hand-off is refused
        Robolectric.buildActivity(InternalActionActivity::class.java, InternalActionActivity.sendTripIntent(ctx, 42L)).create()
        assertEquals("", Prefs.askSentFor)
    }

    @Test
    fun theSharedSendHelperOpensNothingWhileAQuestionIsUnansweredAndGivesBackARefusedClaim() {
        Prefs.activeAddress = "assistant@example.com"; startedMail()
        val mail = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:assistant@example.com"))
        // Free slot: the mail is handed off and the question is ours.
        assertTrue(sendWithQuestion(ctx, "1", "trip", android.content.Intent(mail)))
        assertEquals(1, startedMail().size); assertEquals("1", PendingAsk.current().sentFor)
        // Unanswered question: no external intent, the stored question is untouched.
        assertEquals(false, sendWithQuestion(ctx, "2", "single", android.content.Intent(mail)))
        assertEquals(0, startedMail().size); assertEquals("1", PendingAsk.current().sentFor); assertEquals("trip", PendingAsk.current().kind)
        // Refused hand-off (no assistant address): the claim is given back.
        PendingAsk.clearAll(); Prefs.activeAddress = ""
        assertEquals(false, sendWithQuestion(ctx, "3", "single", android.content.Intent(mail)))
        assertEquals("", PendingAsk.current().sentFor)
    }

    @Test
    fun aMailThatCannotBeBuiltIsARefusedHandOffAndOpensNothing() {
        Prefs.activeAddress = "assistant@example.com"; startedMail()
        assertEquals(false, handOffBuiltMail(ctx) { error("cannot build") })
        assertEquals(0, startedMail().size)
        val mail = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:assistant@example.com"))
        assertTrue(handOffBuiltMail(ctx) { mail }); assertEquals(1, startedMail().size)
    }
}
