package com.bumplog

import org.junit.Assert.assertEquals
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

    // PendingAsk: the "did the email go out?" state, with Prefs replaced by a map.
    private fun fakePending(): MutableMap<String, String> {
        val m = mutableMapOf("for" to "", "kind" to "", "again" to "")
        PendingAsk.write = { f, k, a -> if (f != null) m["for"] = f; if (k != null) m["kind"] = k; if (a != null) m["again"] = a }
        return m
    }

    @Test fun anOlderCompletionNeverErasesANewerPendingQuestion() {
        val m = fakePending()
        PendingAsk.set("1,2", "trip")
        val olderTap = PendingAsk.token()             // the user answers the trip question; its write is queued
        PendingAsk.set("7", "again", "snap")          // meanwhile a newer mail hand-off sets its own question
        assertEquals("the late completion must not clear it", false, PendingAsk.clearIf(olderTap))
        assertEquals("7", m["for"]); assertEquals("again", m["kind"]); assertEquals("snap", m["again"])
    }

    @Test fun aCompletionForTheCurrentQuestionClearsIt() {
        val m = fakePending()
        PendingAsk.set("1,2", "trip")
        val tap = PendingAsk.token()
        assertTrue(PendingAsk.clearIf(tap))
        assertEquals("", m["for"]); assertEquals("", m["kind"]); assertEquals("", m["again"])
    }

    @Test fun theSameQuestionSetTwiceStillCountsAsNewer() {
        val m = fakePending()
        PendingAsk.set("5", "trip")
        val tap = PendingAsk.token()
        PendingAsk.set("5", "trip")                   // same values, new hand-off: identity is the generation, not the text
        assertEquals(false, PendingAsk.clearIf(tap)); assertEquals("5", m["for"])
    }
}
