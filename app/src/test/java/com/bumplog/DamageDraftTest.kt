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
    fun aRetryFollowsThePickedHit() {
        val db = Db.get(ctx)
        val a = db.addDamage(7L, 0, "Tire", "first", 1000L, "draft-1")
        // He taps a different hit before sending: the row follows, hit and all.
        val b = db.addDamage(8L, 0, "Tire", "first", 2000L, "draft-1")
        assertEquals(a.id, b.id)
        assertEquals(8L, b.hitId)
        assertEquals(8L, db.damageForDraft("draft-1")?.hitId)
    }

    @Test
    fun rebuildingWithTheSamePhotosAddsNothingTwice() {
        val src = java.io.File(ctx.cacheDir, "src"); src.mkdirs()
        val one = java.io.File(src, "a.jpg").apply { writeBytes(ByteArray(100) { 1 }) }
        val two = java.io.File(src, "b.jpg").apply { writeBytes(ByteArray(50) { 2 }) }
        val pics = listOf(android.net.Uri.fromFile(one), android.net.Uri.fromFile(two))
        DamagePhotos.addOnce(ctx, "BD-20261123-AAAA", pics)
        DamagePhotos.addOnce(ctx, "BD-20261123-AAAA", pics) // a rebuilt sheet retries this line
        assertEquals(2, DamagePhotos.files(ctx, "BD-20261123-AAAA").size)
        DamagePhotos.addOnce(ctx, "BD-20261123-AAAA", pics + android.net.Uri.fromFile(
            java.io.File(src, "c.jpg").apply { writeBytes(ByteArray(10) { 3 }) }))
        assertEquals("a genuinely new pick lands", 3, DamagePhotos.files(ctx, "BD-20261123-AAAA").size)
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
