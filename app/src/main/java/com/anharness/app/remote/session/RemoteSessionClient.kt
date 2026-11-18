package com.anharness.app.remote.session

import com.anharness.app.remote.SessionResync
import kotlinx.coroutines.flow.Flow

/**
 * [T-remote-session] Transport abstraction + reconnecting client for a
 * remote agent session (P4 slice 4).
 *
 * The transport is a pluggable interface so the session logic runs over
 * the encrypted SSH channel today and a Noise/WebRTC tunnel later without
 * changing the client. The client owns the fold (via [SessionResync]) and
 * the reconnect policy; it never touches sockets directly.
 *
 * Reconnect contract:
 *  - On connect, request a snapshot, adopt it as the fold base.
 *  - Stream events, folding each (idempotent — stale seqs dropped).
 *  - On disconnect, ask the transport to reconnect, then reconcile using
 *    [SessionResync.resyncStrategy]: small gap → events since lastSeq,
 *    large gap → fresh snapshot. The fold is rebuilt exactly once.
 */
interface SessionTransport {
    /** Open the channel. Throws on failure. */
    suspend fun connect()

    /** Server → client frames as a cold flow; completes when the link drops. */
    fun frames(): Flow<RemoteSessionProtocol.Frame>

    /** Client → server command. */
    suspend fun send(request: RemoteSessionProtocol.Request)

    /** Highest seq the server currently has (for resync strategy). */
    suspend fun serverSeq(): Long

    suspend fun close()
}

/**
 * Drives one remote session over a [SessionTransport]. Pure-ish: the only
 * side effects are transport calls; all state reconciliation delegates to
 * the tested [SessionResync] core. [onState] is invoked whenever the folded
 * state changes so the UI can render.
 */
class RemoteSessionClient(
    private val transport: SessionTransport,
    private val onState: (RemoteSessionProtocol.SessionState) -> Unit,
    /**
     * Optional factory for a FRESH transport on reconnect (a dropped SSH
     * channel cannot be reused). Defaults to reusing [transport]; tests
     * inject a factory returning a new fake per reconnect.
     */
    private val reconnectTransport: () -> SessionTransport = { transport },
) {
    private var fold: SessionResync.Fold<RemoteSessionProtocol.SessionState>? = null
    private var active: SessionTransport = transport

    val currentState: RemoteSessionProtocol.SessionState?
        get() = fold?.current

    val lastSeq: Long get() = fold?.lastSeq ?: -1L

    /**
     * Connect and stream until the link drops. Returns normally on a clean
     * close; throws if the initial connect fails. The caller decides whether
     * to [reconnect].
     */
    suspend fun run() {
        active = transport
        active.connect()
        // Bootstrap: request a snapshot and wait for it as the fold base.
        active.send(RemoteSessionProtocol.Request.Snapshot)
        collectFrames()
    }

    /**
     * Reconnect after a drop: open a FRESH transport, then reconcile the gap
     * using the tested resync strategy to bound replay cost.
     */
    suspend fun reconnect() {
        active = reconnectTransport()
        active.connect()
        val f = fold
        if (f == null) {
            active.send(RemoteSessionProtocol.Request.Snapshot)
        } else {
            val server = active.serverSeq()
            when (val strategy = SessionResync.resyncStrategy(f.lastSeq, server)) {
                SessionResync.Strategy.UpToDate -> { /* nothing to do */ }
                SessionResync.Strategy.FullSnapshot ->
                    active.send(RemoteSessionProtocol.Request.Snapshot)
                is SessionResync.Strategy.EventsSince ->
                    active.send(RemoteSessionProtocol.Request.Resync(strategy.seq))
            }
        }
        collectFrames()
    }

    private suspend fun collectFrames() {
        active.frames().collect { frame ->
            when (frame) {
                is RemoteSessionProtocol.Frame.Snapshot -> {
                    val state = RemoteSessionProtocol.fromSnapshot(frame)
                    val cur = fold
                    if (cur == null) fold = SessionResync.Fold(SessionResync.Snapshot(frame.seq, state))
                    else cur.resnapshot(SessionResync.Snapshot(frame.seq, state))
                    onState(fold!!.current)
                }
                is RemoteSessionProtocol.Frame.Event -> {
                    val cur = fold ?: return@collect // no base yet — ignore
                    val ev = SessionResync.Event<RemoteSessionProtocol.SessionState>(frame.seq) { s ->
                        RemoteSessionProtocol.applyEvent(s, frame)
                    }
                    if (cur.apply(ev)) onState(cur.current)
                }
            }
        }
    }

    suspend fun send(text: String) = active.send(RemoteSessionProtocol.Request.Send(text))
    suspend fun stop() = active.send(RemoteSessionProtocol.Request.Stop)
    suspend fun close() = active.close()
}