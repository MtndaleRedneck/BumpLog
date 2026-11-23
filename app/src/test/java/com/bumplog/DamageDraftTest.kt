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
 * Draft identity, not the hit, decides whether filing again is a retry or a new report:
 * rebuilding one sheet session retries its one row (report id, number and photos stay with
 * it), while a deliberate second report - even for the same hit and the same category - is a
 * separate row, and a draft already handed to the mail app never files twice.
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
    fun retryingOneDraftUpdatesOneRow() {
        val db = Db.get(ctx)
        val a = db.addDamage(7L, 0, "Tire", "first", 1000L, "draft-1")
        val b = db.addDamage(7L, 0, "Rim", "second", 2000L, "draft-1")
        assertEquals("same draft, same row", a.id, b.id)
        assertEquals("the report keeps its identity", a.reportId, b.reportId)
        assertEquals("Rim", b.category)
        assertEquals("second", b.note)
        assertEquals(1, db.damages().count { it.hitId == 7L })
    }

    @Test
    fun separateDraftsStaySeparateEvenForOneHit() {
        val db = Db.get(ctx)
        // Same hit, same category: two sheet sessions are two reports, never merged.
        val a = db.addDamage(7L, 0, "Tire", "morning", 1000L, "draft-1")
        val b = db.addDamage(7L, 0, "Tire", "evening", 2000L, "draft-2")
        assertNotEquals(a.id, b.id)
        // And a different category of the same hit is its own report too.
        db.addDamage(7L, 0, "Rim", "also bent", 3000L, "draft-3")
        assertEquals(3, db.damages().count { it.hitId == 7L })
        assertEquals(listOf("morning", "evening", "also bent"), db.damages().filter { it.hitId == 7L }.sortedBy { it.ts }.map { it.note })
    }

    @Test
    fun aSentDraftNeverFilesTwice() {
        val db = Db.get(ctx)
        val a = db.addDamage(9L, 0, "Tire", "done", 1000L, "draft-1")
        db.markDamageSent(a.id)
        val b = db.addDamage(9L, 0, "Tire", "edited after send", 3000L, "draft-1")
        assertEquals("the sent row is returned untouched", a.id, b.id)
        assertEquals("SENT", b.status)
        assertEquals("done", b.note)
        assertEquals(1, db.damages().count { it.hitId == 9L })
    }
}
