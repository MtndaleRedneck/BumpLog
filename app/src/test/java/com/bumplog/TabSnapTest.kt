package com.bumplog

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSnapTest {
    private val a = Stamp(5, 10L)

    @Test fun coldProcessHasNothingWarm() {
        TabSnap.clear()
        assertNull(TabSnap.stats.warm(a)); assertNull(TabSnap.home.warm(a)); assertNull(TabSnap.review.warm(a)); assertNull(TabSnap.reported.warm(a))
    }

    @Test fun sameStampIsWarm() {
        TabSnap.clear()
        TabSnap.reported.put(a, ReportedSnap(emptyList()))
        assertNotNull(TabSnap.reported.warm(Stamp(5, 10L)))
    }

    @Test fun anyWriteMakesItColdSoOldDataNeverShows() {
        TabSnap.clear()
        TabSnap.reported.put(a, ReportedSnap(emptyList()))
        assertNull(TabSnap.reported.warm(Stamp(5, 11L)))   // a write that did not bump the Live tick
        assertNull(TabSnap.reported.warm(Stamp(6, 10L)))   // a Live tick bump
    }

    @Test fun aLoadStampedBeforeAWriteIsStaleOnceTheWriteLands() {
        TabSnap.clear()
        TabSnap.reported.put(Stamp(5, 10L), ReportedSnap(emptyList()))   // load began at writes=10, a write landed at 11
        assertNull(TabSnap.reported.warm(Stamp(5, 11L)))
    }

    @Test fun wipeRaceClearAfterWriteDropsAnInFlightSnapshot() {
        TabSnap.clear()
        TabSnap.reported.put(a, ReportedSnap(emptyList()))
        TabSnap.clear()
        assertNull(TabSnap.reported.warm(a))
    }

    @Test fun dbWriteCounterMovesOnNoteWrite() {
        val before = Db.writes; Db.noteWrite()
        assertEquals(before + 1, Db.writes)
        assertEquals(Stamp(1, before + 1, Db.startOfToday()), Stamp.now(1))
        assertNotEquals(Stamp.now(1), Stamp.now(1).copy(day = 1L))   // a new day makes a snapshot cold
    }

    @Test fun geometryKeyMatchesOnlyIdenticalGeometry() {
        val k = GeoKey(700f, 3, 2.75f, 1f)
        assertEquals(k, GeoKey(700f, 3, 2.75f, 1f))
        assertNotEquals(k, GeoKey(640f, 3, 2.75f, 1f)); assertNotEquals(k, GeoKey(700f, 4, 2.75f, 1f)); assertNotEquals(k, GeoKey(700f, 3, 2.75f, 1.3f))
    }

    @Test fun coldFirstFrameReadsNowSoItIsNeverBlank() {
        TabSnap.clear()
        var reads = 0
        val v = firstFrame(TabSnap.reported, 5) { reads++; ReportedSnap(emptyList()) }
        assertNotNull(v); assertEquals(1, reads)
    }

    @Test fun warmFirstFrameDoesNotReadAgain() {
        TabSnap.clear()
        var reads = 0
        firstFrame(TabSnap.reported, 5) { reads++; ReportedSnap(emptyList()) }
        firstFrame(TabSnap.reported, 5) { reads++; ReportedSnap(emptyList()) }
        assertEquals(1, reads)
    }

    @Test fun afterAWriteTheFirstFrameReadsFreshDataNotTheOldSnapshot() {
        TabSnap.clear()
        var n = 0
        val a1 = firstFrame(TabSnap.reported, 5) { ReportedSnap(listOf()) .also { n++ } }
        Db.noteWrite()
        firstFrame(TabSnap.reported, 5) { n++; ReportedSnap(listOf()) }
        assertEquals(2, n); assertNotNull(a1)
    }

    @Test fun anUnusableWarmSnapshotIsReplacedByAFreshRead() {
        TabSnap.clear()
        TabSnap.home.put(Stamp.now(5), HomeSnap(emptyList(), 3, 7L))
        val v = firstFrame(TabSnap.home, 5, usable = { it.driveKey == 9L }) { HomeSnap(emptyList(), 0, 9L) }
        assertEquals(9L, v!!.driveKey)
    }

    @Test fun firstFrameGivesUpOnABusyDatabaseThenStillWarmsTheSlot() {
        val slot = SnapSlot<ReportedSnap>(); val gate = java.util.concurrent.CountDownLatch(1)
        val t0 = System.nanoTime()
        val r = firstFrame(slot, 9) { gate.await(); ReportedSnap(emptyList()) }   // a read stuck behind an import
        assertNull(r)
        assertTrue("main thread waited too long", (System.nanoTime() - t0) / 1_000_000 < 1000)
        gate.countDown()
        var warm = false
        for (k in 0 until 40) { if (slot.warm(Stamp.now(9)) != null) { warm = true; break }; Thread.sleep(50) }
        assertTrue("late read should warm the slot under its own stamp", warm)
    }

    @Test fun firstFrameHelperThreadsStayBoundedWhileTheDatabaseIsBusy() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val t0 = System.nanoTime()
        repeat(12) { assertNull(firstFrame(SnapSlot<ReportedSnap>(), 20 + it) { gate.await(); ReportedSnap(emptyList()) }) }   // 12 tab switches during an import
        assertTrue("12 blocked switches must not stall the caller", (System.nanoTime() - t0) / 1_000_000 < 2000)
        val helpers = Thread.getAllStackTraces().keys.count { it.name == "bump-first-frame" && it.isAlive }
        assertTrue("helper threads must stay at 2, were $helpers", helpers <= 2)
        gate.countDown()
    }

    @Test fun aLateOlderReadNeverReplacesANewerSnapshot() {
        val slot = SnapSlot<ReportedSnap>()
        val newer = ReportedSnap(emptyList()); val older = ReportedSnap(emptyList())
        slot.put(Stamp(6, 11L), newer); slot.put(Stamp(5, 10L), older)
        assertEquals(newer, slot.warm(Stamp(6, 11L)))
        assertNull(slot.warm(Stamp(5, 10L)))
    }

    /** The pool is shared by every test in the JVM: wait until earlier tests' gated tasks have drained. */
    private fun drainPool() { for (k in 0 until 60) { if (firstFramePool.activeCount == 0 && firstFramePool.queue.isEmpty()) return; Thread.sleep(50) } }

    @Test fun aTimedOutQueuedReadLeavesTheQueue() {
        drainPool()
        val gate = java.util.concurrent.CountDownLatch(1)
        // Two reads occupy both helpers; the next four queue and time out. Each must be removed on timeout, so the queue is empty afterwards.
        repeat(6) { assertNull(firstFrame(SnapSlot<ReportedSnap>(), 100 + it) { gate.await(); ReportedSnap(emptyList()) }) }
        val queued = firstFramePool.queue.size; val active = firstFramePool.activeCount
        gate.countDown()
        assertEquals("both helpers must really have been busy, or the test proves nothing", 2, active)
        assertEquals("cancelled queued reads must not stay in the queue", 0, queued)
    }

    @Test fun aSaturatedPoolRejectsWithoutReadingOrBlocking() {
        drainPool()
        val gate = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.CountDownLatch(2)
        // Fill it directly: 2 helpers stuck on the gate and 4 queued tasks (the whole queue).
        repeat(2) { firstFramePool.execute { running.countDown(); gate.await() } }
        assertTrue(running.await(2, java.util.concurrent.TimeUnit.SECONDS))
        repeat(4) { firstFramePool.execute { gate.await() } }
        var reads = 0
        val t0 = System.nanoTime()
        val r = firstFrame(SnapSlot<ReportedSnap>(), 200) { reads++; ReportedSnap(emptyList()) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        gate.countDown()
        assertNull(r); assertEquals("a rejected read must never run", 0, reads)
        assertTrue("a rejected read must return at once, not after the gated helpers finish (took $ms ms)", ms < 1000)
    }

    // DbQ: the queue core has no Android types, so these run as plain unit tests with the main-thread hop replaced.
    private fun <T> queued(work: () -> T): Pair<T?, Throwable?> {
        val latch = java.util.concurrent.CountDownLatch(1); var ok: T? = null; var err: Throwable? = null
        DbQ.toMain = { it.run() }
        DbQ.submit(work, { err = it; latch.countDown() }) { ok = it; latch.countDown() }
        assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
        return ok to err
    }

    @Test fun dbQueueRunsWorkInTheOrderItWasQueuedAndOffTheCallerThread() {
        DbQ.toMain = { it.run() }
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val callerThread = Thread.currentThread()
        val latch = java.util.concurrent.CountDownLatch(50)
        var workThread: Thread? = null
        repeat(50) { i -> DbQ.submit({ if (i == 0) workThread = Thread.currentThread(); Thread.sleep(if (i % 7 == 0) 3L else 0L); order.add(i) }, { latch.countDown() }) { latch.countDown() } }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals((0 until 50).toList(), order.toList())
        assertTrue(workThread !== callerThread)
    }

    @Test fun aFailedQueuedWorkCallsFailNeverDone() {
        val (ok, err) = queued<Int> { throw IllegalStateException("boom") }
        assertNull(ok); assertTrue(err is IllegalStateException)
    }

    @Test fun anErrorInQueuedWorkStillReachesFailSoABusyFlagClears() {
        val (ok, err) = queued<Int> { throw OutOfMemoryError("test") }
        assertNull(ok); assertNotNull(err)
    }

    @Test fun aSuccessfulQueuedWorkDeliversItsResultToDone() {
        val (ok, err) = queued { 41 + 1 }
        assertEquals(42, ok); assertNull(err)
    }

    // PendingAsk: the "did the email go out?" question, with Prefs replaced by a map-backed store that is restored after each test.
    class MapStore(val slowWrite: Boolean = false, val syncOk: Boolean = true) : PendingAsk.Store {
        override fun sync() = syncOk
        @Volatile var f = ""; @Volatile var k = ""; @Volatile var a = ""
        override fun read() = Triple(f, k, a)
        override fun write(sentFor: String, kind: String, again: String) {
            f = sentFor; if (slowWrite) Thread.sleep(1); k = kind; if (slowWrite) Thread.sleep(1); a = again   // field by field, like three separate edits
        }
    }
    @org.junit.After fun restoreGlobals() { PendingAsk.resetStore(); DbQ.resetToMain(); DamageMarks.resetStore() }

    @Test fun aDurableQuestionThatCannotBeCommittedIsRefusedAndTheOlderQuestionComesBack() {
        val m = MapStore(syncOk = false); PendingAsk.store = m
        PendingAsk.set("3", "again", "oldsnap")
        var threw = false
        try { PendingAsk.set("9", "again", "snap", durable = true) } catch (e: java.io.IOException) { threw = true }
        assertTrue(threw); assertEquals("3", m.f); assertEquals("oldsnap", m.a)   // the unanswered older question and its rollback survive
    }

    @Test fun aFailedDurableSetDoesNotUndoANewerQuestion() {
        val m = MapStore(); PendingAsk.store = m
        val store2 = object : PendingAsk.Store {
            override fun read() = m.read(); override fun write(sentFor: String, kind: String, again: String) = m.write(sentFor, kind, again)
            override fun sync(): Boolean { PendingAsk.set("77", "trip"); return false }   // a main-thread setter lands during the commit
        }
        PendingAsk.store = store2
        try { PendingAsk.set("9", "again", "snap", durable = true) } catch (e: java.io.IOException) {}
        assertEquals("77", m.f); assertEquals("trip", m.k)
    }

    @Test fun aLegacyAgainSnapshotIsReportedAsCannotUndoNeverAsRolledBack() {
        assertTrue(Rollback.plan("again", "5|12|1000|1100|NONE|0") === RollbackPlan.CannotUndo)   // 6 fields from an older build
        assertTrue(Rollback.plan("again", "") === RollbackPlan.CannotUndo)
        assertTrue(Rollback.plan("trip", "") === RollbackPlan.None)
        assertTrue(Rollback.plan("single", "garbage") === RollbackPlan.None)
        val p = Rollback.plan("again", Rollback(5, 12, 1000L, 1100L, "NONE", 0L, 2000L).encode())
        assertTrue(p is RollbackPlan.Undo && p.r.expectedTs == 2000L)
    }

    @Test fun rollbackSnapshotNeedsAllSevenFields() {
        val r = Rollback(5, 12, 1000L, 1100L, "NONE", 0L, 2000L)
        val p = Rollback.parse(r.encode())!!
        assertEquals(5L, p.id); assertEquals(12, p.reportNo); assertEquals(2000L, p.expectedTs); assertEquals("NONE", p.outcome)
        assertEquals(null, Rollback.parse("5|12|1000|1100|NONE|0"))          // the old 6-field form is not guessed at
        assertEquals(null, Rollback.parse("5|x|1000|1100|NONE|0|2000"))
    }

    class MarkMap(var v: String = "", val commitOk: Boolean = true) : DamageMarks.MarkStore {
        override fun read() = v; override fun write(v: String) { this.v = v }
        override fun sync(): Boolean = commitOk
    }

    @Test fun anInterruptedHandOffStaysHandingAndIsNeverAutoSettled() {
        DamageMarks.store = MarkMap()
        assertTrue(DamageMarks.beginHandOffDurable(4))
        assertEquals(setOf(4L), DamageMarks.handing()); assertEquals(emptySet<Long>(), DamageMarks.pending())   // retryAll/settle only walks pending()
    }

    @Test fun aReturnedHandOffBecomesHandedAndSurvivesReentry() {
        DamageMarks.store = MarkMap()
        DamageMarks.beginHandOffDurable(4); DamageMarks.handedOff(4)
        assertEquals(setOf(4L), DamageMarks.pending()); assertEquals(emptySet<Long>(), DamageMarks.handing())
    }

    @Test fun aRefusedHandOffOrAnAnswerRemovesTheMarkAndOthersStay() {
        DamageMarks.store = MarkMap()
        DamageMarks.beginHandOffDurable(4); DamageMarks.handedOff(4); DamageMarks.beginHandOffDurable(5)
        DamageMarks.remove(5)
        assertEquals(setOf(4L), DamageMarks.pending()); assertEquals(emptySet<Long>(), DamageMarks.handing())
        DamageMarks.clearAll(); assertEquals(emptySet<Long>(), DamageMarks.pending())
    }

    @Test fun aReaderIsNotBlockedWhileTheDiskCommitRuns() {
        var readerDone = false
        val m = object : DamageMarks.MarkStore {
            var v = ""
            override fun read() = v; override fun write(v: String) { this.v = v }
            override fun sync(): Boolean {   // the "disk commit": a main-thread style reader must finish while this is still running
                val t = Thread { DamageMarks.pending(); DamageMarks.handing(); readerDone = true }
                t.start(); t.join(2000)
                return true
            }
        }
        DamageMarks.store = m
        assertTrue(DamageMarks.beginHandOffDurable(4))
        assertTrue("pending()/handing() must not wait behind the commit", readerDone)
        assertEquals(setOf(4L), DamageMarks.handing())
    }

    @Test fun aHandingMarkThatCannotBeCommittedIsRefusedAndWritesNothing() {
        val m = MarkMap(commitOk = false); DamageMarks.store = m
        assertEquals(false, DamageMarks.beginHandOffDurable(4)); assertEquals("", m.v)
    }

    @Test fun clearingAfterTheRollbackKeepsANewerQuestionAndAClearTwiceIsHarmless() {
        val m = MapStore(); PendingAsk.store = m
        val shown = PendingAsk.set("9", "again", "snap")
        PendingAsk.set("1,2", "trip")                        // a newer question appears while the rollback runs
        assertEquals(false, PendingAsk.clearIf(shown)); assertEquals("1,2", m.f)
        val g = PendingAsk.set("9", "again", "snap")
        assertTrue(PendingAsk.clearIf(g)); assertEquals(false, PendingAsk.clearIf(g))
    }

    @Test fun anOlderCompletionNeverErasesANewerPendingQuestion() {
        val m = MapStore(); PendingAsk.store = m
        PendingAsk.set("1,2", "trip")
        val shown = PendingAsk.current()               // the dialog is built from this
        PendingAsk.set("7", "again", "snap")           // a newer hand-off sets its own question while the dialog is up
        assertEquals("the late answer must not clear it", false, PendingAsk.clearIf(shown.gen))
        assertEquals("7", m.f); assertEquals("again", m.k); assertEquals("snap", m.a)
    }

    @Test fun aCompletionForTheShownQuestionClearsIt() {
        val m = MapStore(); PendingAsk.store = m
        PendingAsk.set("1,2", "trip")
        assertTrue(PendingAsk.clearIf(PendingAsk.current().gen))
        assertEquals("", m.f); assertEquals("", m.k); assertEquals("", m.a)
    }

    @Test fun theSameQuestionSetTwiceStillCountsAsNewer() {
        val m = MapStore(); PendingAsk.store = m
        PendingAsk.set("5", "trip")
        val shown = PendingAsk.current()
        PendingAsk.set("5", "trip")                    // same values, new hand-off: identity is the generation, not the text
        assertEquals(false, PendingAsk.clearIf(shown.gen)); assertEquals("5", m.f)
    }

    @Test fun aNewQuestionDropsTheOldRollbackSnapshot() {
        val m = MapStore(); PendingAsk.store = m
        PendingAsk.set("9", "again", "old|snapshot")
        PendingAsk.set("1,2", "trip")
        assertEquals("", m.a)
    }

    @Test fun reserveClaimsAFreeSlotAndRefusesAnUnansweredOneWithoutTouchingIt() {
        val m = MapStore(); PendingAsk.store = m
        val snap = Rollback(5, 4, 100L, 90L, "", 0L, 777L).encode()
        assertEquals(true, PendingAsk.reserve("5", "again", snap) >= 0)
        assertEquals(-1, PendingAsk.reserve("7,8", "trip"))
        assertEquals(-1, PendingAsk.reserve("9", "single"))
        assertEquals("5", m.f); assertEquals("again", m.k); assertEquals(snap, m.a)   // byte for byte
    }

    @Test fun aBlankOrUnparseableSlotIsNotAQuestionAndNeverLocksSending() {
        val m = MapStore(); PendingAsk.store = m
        assertEquals(true, PendingAsk.reserve("1", "trip") >= 0)
        PendingAsk.clearAll()
        m.write("not,ids", "trip", "")
        assertEquals(false, PendingAsk.hasUnanswered())
        assertEquals(true, PendingAsk.reserve("2", "trip") >= 0); assertEquals("2", m.f)
    }

    @Test fun aRefusedHandOffGivesBackOnlyItsOwnReservation() {
        val m = MapStore(); PendingAsk.store = m
        val tok = PendingAsk.reserve("3", "trip")
        assertEquals(true, PendingAsk.clearIf(tok)); assertEquals("", m.f)
        val tok2 = PendingAsk.reserve("4", "trip"); PendingAsk.clearAll(); val tok3 = PendingAsk.reserve("6", "single")
        assertEquals(false, PendingAsk.clearIf(tok2)); assertEquals("6", m.f); assertEquals(true, tok3 >= 0)
    }

    @Test fun aDurableReservationThatCannotBeSavedGivesTheSlotBack() {
        val m = MapStore(syncOk = false); PendingAsk.store = m
        try { PendingAsk.reserve("3", "again", "x", durable = true); fail("must throw") } catch (e: java.io.IOException) { }
        assertEquals("", m.f); assertEquals("", m.k); assertEquals("", m.a)
    }

    @Test fun twoSendersRacingForTheSlotHaveExactlyOneWinner() {
        repeat(200) {
            val m = MapStore(slowWrite = true); PendingAsk.store = m
            val go = java.util.concurrent.CountDownLatch(1); val res = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            val ts = listOf("A", "B").mapIndexed { i, n -> Thread { go.await(); res.add(PendingAsk.reserve((10 + i).toString(), "trip")) }.also { it.start() } }
            go.countDown(); ts.forEach { it.join() }
            assertEquals(1, res.count { it >= 0 }); assertEquals(1, res.count { it == -1 })
            assertEquals(true, m.f == "10" || m.f == "11")
        }
    }

    @Test fun aReaderNeverSeesAHalfPublishedQuestion() {
        val m = MapStore(slowWrite = true); PendingAsk.store = m
        PendingAsk.set("0", "trip")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = Thread { var i = 0; while (!stop.get()) { if (i % 2 == 0) PendingAsk.set("A", "again", "snapA") else PendingAsk.set("B", "trip", ""); i++ } }
        writer.start()
        var bad = 0
        repeat(300) { val q = PendingAsk.current(); val ok = (q.sentFor == "A" && q.kind == "again" && q.again == "snapA") || (q.sentFor == "B" && q.kind == "trip" && q.again == "") || (q.sentFor == "0" && q.kind == "trip"); if (!ok) bad++; Thread.sleep(1) }
        stop.set(true); writer.join()
        assertEquals("a read must see one whole question", 0, bad)
    }
}
