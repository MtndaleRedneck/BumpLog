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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The damage-report handoff against a controlled mail handler on a real device.
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

        CaptureMailActivity.latch = CountDownLatch(1)
        CaptureMailActivity.received = null
        CaptureMailActivity.photoBytes = java.util.Collections.emptyMap()
        CaptureMailActivity.readFailure = null

        fireToControlledHandler(Reports.damageMail(ctx, d, hit, spot))
        assertTrue("the controlled handler really received it",
            CaptureMailActivity.latch!!.await(15, TimeUnit.SECONDS))

        val got = CaptureMailActivity.received!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, got.action)
        assertEquals(listOf("assistant@example.com"), got.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList())
        assertTrue("subject carries the report ref",
            got.getStringExtra(Intent.EXTRA_SUBJECT)!!.contains(d.ref(hit)))
        val body = got.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(body.contains("Damage: RIM"))
        assertTrue(body.contains("Notes: bent"))
        assertTrue("body carries the map link", body.contains("40.700000"))
        assertNull("the photo grant worked end to end", CaptureMailActivity.readFailure)
        assertEquals("the one photo arrived and read fully", setOf(2048), CaptureMailActivity.photoBytes.values.toSet())
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

        CaptureMailActivity.latch = CountDownLatch(1)
        CaptureMailActivity.received = null

        fireToControlledHandler(Reports.damageMail(ctx, d, hit, db.spot(1)))
        assertTrue(CaptureMailActivity.latch!!.await(15, TimeUnit.SECONDS))
        val got = CaptureMailActivity.received!!
        assertEquals(Intent.ACTION_SENDTO, got.action)
        // RFC 6068: subject and body ride in the mailto query (the 1.16.0 bug class).
        val data = got.dataString!!
        assertTrue(data.startsWith("mailto:"))
        assertTrue("subject rides in the query", data.contains("subject="))
        assertTrue("body rides in the query, %20-encoded", data.contains("body=") && !data.contains("+"))
        assertNotNull(got.getStringExtra(Intent.EXTRA_TEXT))
    }
}
