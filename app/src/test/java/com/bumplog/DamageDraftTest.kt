package com.bumplog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Building the damage draft is not lifecycle-proof: a recomposition or process death between
 * building and sending used to stack a second row for the same hit. The database now keeps one
 * unsent draft per hit; only a row already handed to the mail app stays as history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DamageDraftTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Db.closeForTests()
        Prefs.init(ctx)
    }

    @Test
    fun buildingTheDraftTwiceUpdatesOneRow() {
        val db = Db.get(ctx)
        val a = db.addDamage(7L, 0, "Tire", "first", 1000L)
        val b = db.addDamage(7L, 0, "Rim", "second", 2000L)
        assertEquals("same draft, not a new row", a.id, b.id)
        assertEquals("the report keeps its identity", a.reportId, b.reportId)
        assertEquals("Rim", b.category)
        assertEquals("second", b.note)
        assertEquals(1, db.damages().count { it.hitId == 7L })
        assertEquals("Rim", db.openDamageForHit(7L)?.category)
    }

    @Test
    fun aSentReportIsHistoryAndNeverReused() {
        val db = Db.get(ctx)
        val a = db.addDamage(9L, 0, "Tire", "done", 1000L)
        db.markDamageSent(a.id)
        val b = db.addDamage(9L, 0, "Tire", "new incident", 3000L)
        assertNotEquals(a.id, b.id)
        assertEquals(2, db.damages().count { it.hitId == 9L })
    }
}
