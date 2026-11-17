package com.anharness.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-remote-resync] Pure folding/reconciliation contract. State is a simple
 * list of appended ints so seq handling is observable without any Android
 * or network dependency.
 */
class SessionResyncTest {

    private fun snap(seq: Long, vararg items: Int) =
        SessionResync.Snapshot(seq, items.toList())

    private fun ev(seq: Long, value: Int) =
        SessionResync.Event(seq) { state: List<Int> -> state + value }

    // ── streaming fold ───────────────────────────────────────────────────

    @Test
    fun `fold applies ascending events`() {
        val fold = SessionResync.Fold(snap(0))
        assertTrue(fold.apply(ev(1, 10)))
        assertTrue(fold.apply(ev(2, 20)))
        assertEquals(listOf(10, 20), fold.current)
        assertEquals(2L, fold.lastSeq)
    }

    @Test
    fun `duplicate and stale events are idempotent`() {
        val fold = SessionResync.Fold(snap(5, 1, 2))
        assertFalse(fold.apply(ev(5, 99)))   // == lastSeq
        assertFalse(fold.apply(ev(3, 99)))   // < lastSeq
        assertEquals(listOf(1, 2), fold.current)
        assertEquals(5L, fold.lastSeq)
        assertTrue(fold.apply(ev(6, 3)))
        assertEquals(listOf(1, 2, 3), fold.current)
    }

    @Test
    fun `resnapshot rebases and drops older events`() {
        val fold = SessionResync.Fold(snap(0))
        fold.apply(ev(1, 10))
        fold.apply(ev(2, 20))
        // Server sends a fresh snapshot reflecting seq 5.
        fold.resnapshot(snap(5, 1, 2, 3, 4, 5))
        assertEquals(listOf(1, 2, 3, 4, 5), fold.current)
        assertEquals(5L, fold.lastSeq)
        // Replayed pre-snapshot events must NOT re-apply.
        assertFalse(fold.apply(ev(2, 99)))
        assertFalse(fold.apply(ev(5, 99)))
        assertTrue(fold.apply(ev(6, 6)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), fold.current)
    }

    // ── reconnect reconcile ──────────────────────────────────────────────

    @Test
    fun `reconcile applies only post-snapshot events in order`() {
        val events = listOf(ev(3, 30), ev(1, 10), ev(2, 20), ev(4, 40))
        val state = SessionResync.reconcile(snap(2, 100, 200), events)
        // seq 1,2 dropped (<= snapshot seq 2); 3,4 applied ascending.
        assertEquals(listOf(100, 200, 30, 40), state)
    }

    @Test
    fun `reconcile with no newer events returns snapshot state`() {
        val state = SessionResync.reconcile(snap(10, 1, 2), listOf(ev(5, 9), ev(10, 9)))
        assertEquals(listOf(1, 2), state)
    }

    @Test
    fun `reconcile tolerates duplicates`() {
        val events = listOf(ev(3, 30), ev(3, 30), ev(4, 40))
        val state = SessionResync.reconcile(snap(2), events)
        // Both seq-3 events apply (reconcile is a batch rebuild, not the
        // idempotent streaming fold); the transport dedups before calling.
        assertEquals(listOf(30, 30, 40), state)
    }

    // ── resync strategy ──────────────────────────────────────────────────

    @Test
    fun `up to date when server not ahead`() {
        assertEquals(SessionResync.Strategy.UpToDate, SessionResync.resyncStrategy(10, 10))
        assertEquals(SessionResync.Strategy.UpToDate, SessionResync.resyncStrategy(10, 5))
    }

    @Test
    fun `small gap requests events since last seq`() {
        val s = SessionResync.resyncStrategy(100, 110, snapshotGapThreshold = 256)
        assertEquals(SessionResync.Strategy.EventsSince(100), s)
    }

    @Test
    fun `large gap requests full snapshot`() {
        val s = SessionResync.resyncStrategy(100, 1000, snapshotGapThreshold = 256)
        assertEquals(SessionResync.Strategy.FullSnapshot, s)
    }

    @Test
    fun `gap exactly at threshold uses events`() {
        val s = SessionResync.resyncStrategy(0, 256, snapshotGapThreshold = 256)
        assertEquals(SessionResync.Strategy.EventsSince(0), s)
        val s2 = SessionResync.resyncStrategy(0, 257, snapshotGapThreshold = 256)
        assertEquals(SessionResync.Strategy.FullSnapshot, s2)
    }
}