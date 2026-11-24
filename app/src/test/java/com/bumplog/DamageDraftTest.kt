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
    fun photoSetSurvivesRebuildsReordersAndLookalikes() {
        val src = java.io.File(ctx.cacheDir, "src"); src.mkdirs()
        fun pic(name: String, size: Int, fill: Byte) =
            android.net.Uri.fromFile(java.io.File(src, name).apply { writeBytes(ByteArray(size) { fill }) })
        val one = pic("a.jpg", 100, 1); val two = pic("b.jpg", 100, 2); val three = pic("c.jpg", 50, 3)
        val rid = "BD-20261123-AAAA"
        fun attached() = DamagePhotos.files(ctx, rid).map { it.readBytes().toList() }

        DamagePhotos.syncDraft(ctx, rid, listOf(one, two))
        val first = attached()
        assertEquals(2, first.size)
        // A rebuilt sheet retries the same list: identical outgoing set, no duplicates on disk.
        DamagePhotos.syncDraft(ctx, rid, listOf(one, two))
        assertEquals(first, attached())
        assertEquals(2, DamagePhotos.dir(ctx, rid).listFiles()!!.count { it.name.startsWith("photo-") })
        // Same SIZE, different content: the lookalike is a distinct photo, never skipped.
        DamagePhotos.syncDraft(ctx, rid, listOf(two, one)) // reordered
        assertEquals(first.toSet(), attached().toSet())
        // Removing the first pick: the outgoing set drops it; the file stays on disk (no deletes).
        DamagePhotos.syncDraft(ctx, rid, listOf(two))
        assertEquals(setOf(ByteArray(100) { 2 }.toList()), attached().toSet())
        assertEquals(2, DamagePhotos.dir(ctx, rid).listFiles()!!.count { it.name.startsWith("photo-") })
        // A genuinely new pick joins.
        DamagePhotos.syncDraft(ctx, rid, listOf(two, three))
        assertEquals(setOf(ByteArray(100) { 2 }.toList(), ByteArray(50) { 3 }.toList()), attached().toSet())
        three.let { }
    }

    @Test
    fun clearingEveryPickEmptiesTheOutgoingSet() {
        val src = java.io.File(ctx.cacheDir, "src-clear"); src.mkdirs()
        fun pic(name: String, fill: Byte) =
            android.net.Uri.fromFile(java.io.File(src, name).apply { writeBytes(ByteArray(40) { fill }) })
        val one = pic("a.jpg", 1); val two = pic("b.jpg", 2)
        val rid = "BD-20261123-CCCC"
        DamagePhotos.syncDraft(ctx, rid, listOf(one, two))
        org.junit.Assert.assertEquals(2, DamagePhotos.files(ctx, rid).size)
        // Clearing the sheet picks and rebuilding sends NOTHING - the old set must not linger.
        DamagePhotos.syncDraft(ctx, rid, emptyList())
        org.junit.Assert.assertEquals(emptyList<java.io.File>(), DamagePhotos.files(ctx, rid))
    }

    @Test
    fun sameContentPickedTwiceAttachesOnce() {
        val src = java.io.File(ctx.cacheDir, "src-dupe"); src.mkdirs()
        fun pic(name: String) =
            android.net.Uri.fromFile(java.io.File(src, name).apply { writeBytes(ByteArray(40) { 7 }) })
        val one = pic("a.jpg"); val two = pic("b.jpg") // different files, identical content
        val rid = "BD-20261123-DDDD"
        DamagePhotos.syncDraft(ctx, rid, listOf(one, two))
        org.junit.Assert.assertEquals(1, DamagePhotos.files(ctx, rid).size)
    }

    @Test
    fun anUnreadablePickFailsWholeAndKeepsTheLastValidSet() {
        val src = java.io.File(ctx.cacheDir, "src-fail"); src.mkdirs()
        val good = android.net.Uri.fromFile(java.io.File(src, "a.jpg").apply { writeBytes(ByteArray(40) { 9 }) })
        val gone = android.net.Uri.fromFile(java.io.File(src, "deleted.jpg")) // never written: open fails
        val rid = "BD-20261123-EEEE"
        DamagePhotos.syncDraft(ctx, rid, listOf(good))
        val before = DamagePhotos.files(ctx, rid).map { it.readBytes().toList() }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            DamagePhotos.syncDraft(ctx, rid, listOf(good, gone))
        }
        org.junit.Assert.assertEquals("the last valid selection stays in force", before, DamagePhotos.files(ctx, rid).map { it.readBytes().toList() })
    }

    @Test
    fun aRetryAfterAFailedHandoffAppliesEveryEdit() {
        // The mail handoff failing leaves the row unsent; editing the sheet and tapping send
        // again must update the SAME row and the outgoing photo set, never replay the first build.
        val db = Db.get(ctx)
        val a = db.addDamage(7L, 0, "Tire", "first note", 1000L, "draft-1")
        val src = java.io.File(ctx.cacheDir, "src-retry"); src.mkdirs()
        fun pic(name: String, fill: Byte) =
            android.net.Uri.fromFile(java.io.File(src, name).apply { writeBytes(ByteArray(40) { fill }) })
        DamagePhotos.syncDraft(ctx, a.reportId, listOf(pic("one.jpg", 1)))
        // handoff "fails" here (no markDamageSent), he edits hit, category, note and photos, retries:
        val b = db.addDamage(8L, 0, "Rim", "edited note", 2000L, "draft-1")
        DamagePhotos.syncDraft(ctx, b.reportId, listOf(pic("two.jpg", 2)))
        org.junit.Assert.assertEquals("same draft, same row", a.id, b.id)
        org.junit.Assert.assertEquals("the row follows the newly picked hit", 8L, db.damageForDraft("draft-1")?.hitId)
        val row = db.damages().single { it.id == a.id }
        org.junit.Assert.assertEquals("Rim", row.category)
        org.junit.Assert.assertEquals("edited note", row.note)
        org.junit.Assert.assertEquals("the outgoing set is the edited pick, not the first build",
            setOf(ByteArray(40) { 2 }.toList()), DamagePhotos.files(ctx, b.reportId).map { it.readBytes().toList() }.toSet())
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
