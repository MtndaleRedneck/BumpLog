package com.bumplog

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The damage-report handoff against a controlled mail handler on a real device.
 * The handler (CaptureMailActivity) runs in the TEST package's own process while
 * these tests run in the target app's process, so what it received comes back
 * over Binder through CaptureProvider; static fields cannot cross processes.
 * Two phases per case: (1) the intent the app builds really matches a mail-style
 * handler's filters (package-manager resolution), and (2) the handler really
 * receives the address, subject, body and photos - and can read the photos
 * through the FileProvider grant exactly as a mail app must. Delivery is pinned
 * to the controlled handler because an emulator image can carry a second
 * mail-capable app, and firing the raw intent there would park the system
 * resolver dialog and deliver nothing; on a real phone that dialog is the
 * correct production behavior, so it is not what this test verifies.
 */
@RunWith(AndroidJUnit4::class)
class ControlledMailDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Prove the raw intent matches the controlled mail-style handler's filters,
     * then deliver a component-pinned copy of it through the production handoff so
     * the received extras, clip data and URI grants are verified deterministically.
     *
     * Package visibility (target 34) filters query results by CALLER: the
     * instrumentation package always sees its own CaptureMailActivity, while the
     * app package's view depends on its <queries> declaration (SENDTO/mailto
     * only). Both views are LOGGED verbatim so a gap is direct evidence, and the
     * assertion runs on the visibility-independent instrumentation view. The
     * system-side resolver used by startActivity is not visibility-filtered, so
     * neither view speaks for actual delivery - phase 2 covers delivery. */
    private fun fireToControlledHandler(raw: Intent) {
        fun view(pm: android.content.pm.PackageManager, who: String): List<String> {
            val names = pm.queryIntentActivities(raw, 0)
                .map { it.activityInfo.packageName + "/" + it.activityInfo.name }
            System.out.println("ControlledMailDeviceTest: $who query view for " +
                raw.action + " " + raw.type + " " + raw.data + " -> " + names)
            return names
        }
        view(ctx.packageManager, "app-context")
        val testView = view(
            InstrumentationRegistry.getInstrumentation().context.packageManager,
            "instrumentation-context"
        )
        assertTrue(
            "the intent really matches the controlled mail-style handler's filters",
            testView.contains("com.bumplog.test/com.bumplog.CaptureMailActivity")
        )
        assertTrue(
            "the handoff fires without an exception",
            handOffMail(
                ctx,
                Intent(raw).setComponent(
                    ComponentName("com.bumplog.test", "com.bumplog.CaptureMailActivity")
                )
            )
        )
    }


    /**
     * Clear the handler's recorded observations (cross-process, via provider call).
     * Fail closed: a null return means the provider did not acknowledge the reset,
     * so a later record could be a STALE previous-case one - refuse to continue.
     * (Each case also asserts its own action/ref content, so a stale record of a
     * different case would fail loudly rather than pass, but never rely on that.)
     */
    private fun resetCapture() {
        val ack = InstrumentationRegistry.getInstrumentation().context.contentResolver
            .call(Uri.parse("content://" + CaptureProvider.AUTHORITY), "reset", null, null)
        assertTrue("the capture provider acknowledged the observation reset", ack != null)
    }

    /**
     * Wait for the handler's observation record over Binder. The poll only bounds
     * cross-process propagation; the proof is the returned CONTENT. Two distinct
     * failure lines separate "provider unreachable" from "handler never recorded".
     */
    private fun awaitCapture(): Map<String, String?> {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val uri = Uri.parse("content://" + CaptureProvider.AUTHORITY + "/obs")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var answered = false
        while (System.nanoTime() < deadline) {
            resolver.query(uri, null, null, null, null)?.use { c ->
                answered = true
                if (c.moveToLast()) {
                    val row = HashMap<String, String?>()
                    for (n in c.columnNames) row[n] = c.getString(c.getColumnIndexOrThrow(n))
                    return row
                }
            }
            Thread.sleep(250)
        }
        val why = if (answered) "the controlled handler recorded no observation within 15s"
            else "the capture provider never answered within 15s"
        fail(why)
        throw AssertionError(why)
    }

    @Before
    fun setUp() {
        Db.closeForTests()
        ctx.deleteDatabase("spotter.db")
        Prefs.activeAddress = "assistant@example.com"
    }

    @After
    fun tearDown() {
        Db.closeForTests()
        ctx.deleteDatabase("spotter.db")
        Prefs.activeAddress = ""
    }

    @Test
    fun damageReportHandoffDeliversAddressSubjectBodyAndReadablePhotos() {
        val db = Db.get(ctx)
        db.writableDatabase.execSQL(
            """INSERT INTO spots(report_id, lat, lon, heading, type, type_user, max_g, hit_count, speed,
            first_ts, last_ts, drive_id, review, warn, report_status, sent_ts, address, clean_passes)
            VALUES('R-1', 40.7, -73.9, 90.0, 'POTHOLE', 0, 3.5, 1, 25.0, 1000, 2000, 7,
            'CONFIRMED', 1, 'NONE', 0, 'Main St', 0)"""
        )
        db.writableDatabase.execSQL(
            """INSERT INTO hits(spot_id, drive_id, ts, lat, lon, heading, speed, peak_g, duration, peaks, type)
            VALUES(1, 7, 2000, 40.7, -73.9, 90.0, 25.0, 3.5, 300, 2, 'POTHOLE')"""
        )
        val d = db.addDamage(1, 1, "RIM", "bent", 3000, "draft-mail-test")
        // A photo on disk the way DamagePhotos leaves one (content-hash name).
        File(DamagePhotos.dir(ctx, d.reportId), "0abc123def4567.jpg").writeBytes(ByteArray(2048) { 0x25 })
        val hit = db.hits().first { it.id == 1L }
        val spot = db.spot(1)

        resetCapture()
        fireToControlledHandler(Reports.damageMail(ctx, d, hit, spot))
        val got = awaitCapture()
        // Two-PID evidence: the record crossed processes over Binder.
        System.out.println("ControlledMailDeviceTest: test-process pid=" + android.os.Process.myPid() +
            " handler pid=" + got["handlerPid"])

        assertEquals(Intent.ACTION_SEND_MULTIPLE, got["action"])
        assertEquals(listOf("assistant@example.com"), got["email"]!!.split("\n"))
        assertTrue("subject carries the report ref", got["subject"]!!.contains(d.ref(hit)))
        val body = got["body"]!!
        assertTrue(body.contains("Damage: RIM"))
        assertTrue(body.contains("Notes: bent"))
        assertTrue("body carries the map link", body.contains("40.700000"))
        assertNull("the photo grant worked end to end", got["readFailure"])
        assertEquals("the one photo arrived and read fully",
            setOf(2048), got["photoBytes"]!!.split("\n").map { it.substringAfterLast('=').toInt() }.toSet())
    }

    @Test
    fun photoLessReportUsesTheMailtoPath() {
        val db = Db.get(ctx)
        db.writableDatabase.execSQL(
            """INSERT INTO spots(report_id, lat, lon, heading, type, type_user, max_g, hit_count, speed,
            first_ts, last_ts, drive_id, review, warn, report_status, sent_ts, address, clean_passes)
            VALUES('R-2', 40.8, -73.8, 10.0, 'MANHOLE', 0, 2.5, 1, 20.0, 1000, 2000, 7,
            'CONFIRMED', 0, 'NONE', 0, 'Oak Ave', 0)"""
        )
        db.writableDatabase.execSQL(
            """INSERT INTO hits(spot_id, drive_id, ts, lat, lon, heading, speed, peak_g, duration, peaks, type)
            VALUES(1, 7, 2000, 40.8, -73.8, 10.0, 20.0, 2.5, 200, 1, 'MANHOLE')"""
        )
        val d = db.addDamage(1, 1, "WHEEL", "scuffed", 3000, "draft-mailto-test")
        val hit = db.hits().first { it.id == 1L }

        resetCapture()
        fireToControlledHandler(Reports.damageMail(ctx, d, hit, db.spot(1)))
        val got = awaitCapture()

        assertEquals(Intent.ACTION_SENDTO, got["action"])
        // RFC 6068: subject and body ride in the mailto query (the 1.16.0 bug class).
        val data = got["data"]!!
        assertTrue(data.startsWith("mailto:"))
        assertTrue("subject rides in the query", data.contains("subject="))
        // Unique per-case damage words (RFC 3986-unreserved, so they survive the
        // mailto query encoding verbatim): a delayed record from the other case
        // (RIM/bent) can never satisfy these, so cross-case contamination fails
        // loudly. The encoded subject's exact ref form is production output and
        // is NOT asserted from an invented shape here.
        assertTrue("body carries THIS case's damage", data.contains("WHEEL") && data.contains("scuffed"))
        assertTrue("body rides in the query, %20-encoded", data.contains("body=") && !data.contains("+"))
        assertNotNull(got["body"])
    }
}
