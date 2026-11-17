package com.anharness.app.remote

/**
 * [T-remote-resync] Snapshot + sequence-number event folding for remote
 * session reconnect (P4, dsh-Go `chat_state.dart` parity — pure core slice).
 *
 * Problem: a remote agent session streams events over a flaky link. On
 * reconnect the client must rebuild the exact current state without
 * replaying the whole history or double-applying events it already saw.
 *
 * Model (mirrors dsh):
 *  - The server periodically emits a SNAPSHOT carrying the full state and
 *    the sequence number [Snapshot.seq] it reflects.
 *  - Between snapshots it emits EVENTS, each with a monotonically
 *    increasing [Event.seq].
 *  - The client folds events onto the last snapshot. On reconnect it
 *    requests a fresh snapshot and replays only events with seq GREATER
 *    than the snapshot's seq — older events are dropped (already in the
 *    snapshot), gaps are tolerated (the next snapshot heals them).
 *
 * This object is the pure folding/reconciliation logic; the transport and
 * the concrete state type live elsewhere. Generic over state S so it is
 * reusable and JVM-testable with a trivial state.
 */
object SessionResync {

    /** A full-state checkpoint at sequence [seq]. */
    data class Snapshot<S>(val seq: Long, val state: S)

    /** An incremental change at sequence [seq]. */
    data class Event<S>(val seq: Long, val apply: (S) -> S)

    /**
     * Client-side reconciliation state: the last trusted snapshot plus the
     * events folded on top of it. [lastSeq] is the highest sequence applied.
     */
    class Fold<S>(initial: Snapshot<S>) {
        var snapshot: Snapshot<S> = initial
            private set
        var current: S = initial.state
            private set
        var lastSeq: Long = initial.seq
            private set

        /**
         * Fold one event. Events at or below [lastSeq] are ignored (already
         * applied — idempotent replay). Returns true when the event changed
         * state, false when it was a duplicate/stale.
         */
        fun apply(event: Event<S>): Boolean {
            if (event.seq <= lastSeq) return false
            current = event.apply(current)
            lastSeq = event.seq
            return true
        }

        /**
         * Adopt a fresh snapshot (e.g. after reconnect). The snapshot becomes
         * the new base; any buffered events older than it are discarded by
         * construction since [lastSeq] jumps to the snapshot's seq.
         */
        fun resnapshot(snap: Snapshot<S>) {
            snapshot = snap
            current = snap.state
            lastSeq = snap.seq
        }
    }

    /**
     * Reconcile a reconnect: given the fresh [snap] and a batch of replayed
     * [events] (possibly out of order / containing duplicates / pre-snapshot
     * noise), return the rebuilt state. Only events with seq > snap.seq are
     * applied, in ascending seq order. This is the pure function the
     * reconnect path calls; [Fold] is the streaming counterpart.
     */
    fun <S> reconcile(snap: Snapshot<S>, events: List<Event<S>>): S {
        var state = snap.state
        events.filter { it.seq > snap.seq }
            .sortedBy { it.seq }
            .forEach { state = it.apply(state) }
        return state
    }

    /**
     * Decide what to request on reconnect: if the client's [lastSeq] is
     * behind the server's advertised [serverSeq], a resync is needed. When
     * the gap is small the client may ask for events since [lastSeq];
     * when large (or unknown) it asks for a full snapshot. The threshold
     * is a policy knob; default favors a snapshot once the gap exceeds
     * [snapshotGapThreshold] events to bound replay cost.
     */
    fun resyncStrategy(
        lastSeq: Long,
        serverSeq: Long,
        snapshotGapThreshold: Long = DEFAULT_SNAPSHOT_GAP,
    ): Strategy {
        if (serverSeq <= lastSeq) return Strategy.UpToDate
        val gap = serverSeq - lastSeq
        return if (gap > snapshotGapThreshold) Strategy.FullSnapshot
        else Strategy.EventsSince(lastSeq)
    }

    sealed interface Strategy {
        data object UpToDate : Strategy
        data object FullSnapshot : Strategy
        data class EventsSince(val seq: Long) : Strategy
    }

    const val DEFAULT_SNAPSHOT_GAP = 256L
}