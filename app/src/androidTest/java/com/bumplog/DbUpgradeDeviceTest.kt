package com.bumplog

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 1.17.0 -> 1.17.1 upgrade path on a real database file: a v6 database exactly as a
 * live install holds it (rows, counter, indexes) opens under the version-7 helper, gains
 * the damage.draft_id column, and loses nothing. Runs on a real device/emulator because
 * SQLiteOpenHelper's versioned open is the thing under test.
 */
@RunWith(AndroidJUnit4::class)
class DbUpgradeDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() { Db.closeForTests(); ctx.deleteDatabase("spotter.db") }

    @After
    fun tearDown() { Db.closeForTests(); ctx.deleteDatabase("spotter.db") }

    private fun createV6Database() {
        val path = ctx.getDatabasePath("spotter.db")
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        // The exact v6 schema: the current tables WITHOUT damage.draft_id.
        raw.execSQL(
            """CREATE TABLE spots(id INTEGER PRIMARY KEY AUTOINCREMENT, report_id TEXT, lat REAL, lon REAL, heading REAL,
            type TEXT, type_user INTEGER, max_g REAL, hit_count INTEGER, speed REAL, first_ts INTEGER, last_ts INTEGER,
            drive_id INTEGER, review TEXT, warn INTEGER, report_status TEXT, sent_ts INTEGER, address TEXT, clean_passes INTEGER,
            severity TEXT DEFAULT 'MAJOR', file_trip INTEGER DEFAULT 0, saves INTEGER DEFAULT 0,
            fixed_after_report INTEGER DEFAULT 0, fixed_ts INTEGER DEFAULT 0, report_no INTEGER DEFAULT 0,
            town TEXT DEFAULT '', county TEXT DEFAULT '', report_ts INTEGER DEFAULT 0,
            report_outcome TEXT DEFAULT 'NONE', outcome_ts INTEGER DEFAULT 0)"""
        )
        raw.execSQL(
            """CREATE TABLE hits(id INTEGER PRIMARY KEY AUTOINCREMENT, spot_id INTEGER, drive_id INTEGER, ts INTEGER,
            lat REAL, lon REAL, heading REAL, speed REAL, peak_g REAL, duration INTEGER, peaks INTEGER, type TEXT,
            low_conf INTEGER DEFAULT 0)"""
        )
        raw.execSQL("CREATE INDEX hits_spot ON hits(spot_id)")
        raw.execSQL("CREATE INDEX hits_drive ON hits(drive_id)")
        raw.execSQL(
            """CREATE TABLE damage(id INTEGER PRIMARY KEY AUTOINCREMENT, report_id TEXT, hit_id INTEGER, spot_id INTEGER,
            category TEXT, note TEXT, ts INTEGER, status TEXT, report_no INTEGER DEFAULT 0, report_ts INTEGER DEFAULT 0)"""
        )
        raw.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        // A realistic live-install state: a reported-and-fixed spot with its hit, a queued
        // damage report, and the shared counter mid-stream.
        raw.execSQL("INSERT INTO meta(k, v) VALUES('report_counter', '42')")
        raw.execSQL(
            """INSERT INTO spots(report_id, lat, lon, heading, type, type_user, max_g, hit_count, speed,
            first_ts, last_ts, drive_id, review, warn, report_status, sent_ts, address, clean_passes,
            severity, file_trip, saves, fixed_after_report, fixed_ts, report_no, town, county,
            report_ts, report_outcome, outcome_ts)
            VALUES('R-1', 40.7, -73.9, 90.0, 'POTHOLE', 0, 3.5, 2, 25.0, 1000, 2000, 7,
            'CONFIRMED', 1, 'SENT', 1500, 'Main St', 0, 'MAJOR', 0, 0, 0, 0, 17,
            'Springfield', 'Hampshire', 1400, 'FIXED', 9000)"""
        )
        raw.execSQL(
            """INSERT INTO hits(spot_id, drive_id, ts, lat, lon, heading, speed, peak_g, duration, peaks, type, low_conf)
            VALUES(1, 7, 2000, 40.7, -73.9, 90.0, 25.0, 3.5, 300, 2, 'POTHOLE', 1)"""
        )
        raw.execSQL(
            """INSERT INTO damage(report_id, hit_id, spot_id, category, note, ts, status, report_no, report_ts)
            VALUES('D-1', 1, 1, 'RIM', 'bent', 3000, 'QUEUED', 18, 2900)"""
        )
        raw.version = 6
        raw.close()
    }

    @Test
    fun v6ToV7KeepsEveryRowAndAddsDraftId() {
        createV6Database()

        val db = Db.get(ctx) // opening at version 7 runs the upgrade
        assertEquals("the report counter is untouched", 42, db.counterNext())

        db.readableDatabase.rawQuery("PRAGMA table_info(damage)", null).use { c ->
            val cols = generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
            assertTrue("draft_id column added", cols.contains("draft_id"))
        }
        db.readableDatabase.rawQuery(
            "SELECT report_no, town, county, report_ts, report_outcome, outcome_ts, report_status FROM spots WHERE report_id='R-1'", null
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(17, c.getInt(0))
            assertEquals("Springfield", c.getString(1))
            assertEquals("Hampshire", c.getString(2))
            assertEquals(1400L, c.getLong(3))
            assertEquals("FIXED", c.getString(4))
            assertEquals(9000L, c.getLong(5))
            assertEquals("SENT", c.getString(6))
        }
        db.readableDatabase.rawQuery("SELECT low_conf, peak_g FROM hits WHERE spot_id=1", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("the confidence flag survives", 1, c.getInt(0))
            assertEquals(3.5f, c.getFloat(1), 0.001f)
        }
        db.readableDatabase.rawQuery("SELECT status, report_no, draft_id FROM damage WHERE report_id='D-1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("QUEUED", c.getString(0))
            assertEquals(18, c.getInt(1))
            assertEquals("a pre-upgrade row owns no draft", "", c.getString(2))
        }
        // The app's own mapping reads the upgraded rows without a hitch.
        val damages = db.damages()
        assertEquals(1, damages.size)
        assertEquals("QUEUED", damages[0].status)
        assertEquals(1, db.hits().size)

        // And the upgrade is durable: a full close + reopen stays at version 7.
        Db.closeForTests()
        assertEquals(42, Db.get(ctx).counterNext())
    }
}
