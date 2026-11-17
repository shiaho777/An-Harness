package com.anharness.app.remote.session

import com.anharness.app.remote.session.RemoteSessionProtocol.Frame
import com.anharness.app.remote.session.RemoteSessionProtocol.Message
import com.anharness.app.remote.session.RemoteSessionProtocol.Request
import com.anharness.app.remote.session.RemoteSessionProtocol.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-remote-session] Protocol encode/decode + folding + reconnect contract.
 * Fake transport, no network — mirrors SessionResyncTest's pure style.
 */
class RemoteSessionTest {

    // ── encode / decode round-trip ───────────────────────────────────────

    @Test
    fun `snapshot round-trips`() {
        val snap = Frame.Snapshot(
            seq = 7,
            messages = listOf(Message("user", "hi"), Message("assistant", "hello"), Message("tool", "ok", toolName = "shell")),
            running = true,
        )
        val decoded = RemoteSessionProtocol.decodeFrame(RemoteSessionProtocol.encodeFrame(snap))
        assertEquals(snap, decoded)
    }

    @Test
    fun `event round-trips`() {
        val ev = Frame.Event(seq = 3, kind = "text_delta", text = "abc")
        assertEquals(ev, RemoteSessionProtocol.decodeFrame(RemoteSessionProtocol.encodeFrame(ev)))
        val tool = Frame.Event(seq = 4, kind = "tool_result", text = "done", toolName = "shell", isError = true)
        assertEquals(tool, RemoteSessionProtocol.decodeFrame(RemoteSessionProtocol.encodeFrame(tool)))
    }

    @Test
    fun `malformed and wrong-version frames decode to null`() {
        assertNull(RemoteSessionProtocol.decodeFrame(""))
        assertNull(RemoteSessionProtocol.decodeFrame("   "))
        assertNull(RemoteSessionProtocol.decodeFrame("not json"))
        assertNull(RemoteSessionProtocol.decodeFrame("""{"type":"event","v":999,"seq":1,"kind":"x"}"""))
        assertNull(RemoteSessionProtocol.decodeFrame("""{"type":"unknown","v":1,"seq":1}"""))
    }

    @Test
    fun `request encoding`() {
        assertTrue(RemoteSessionProtocol.encodeRequest(Request.Send("hi")).contains("\"send\""))
        assertTrue(RemoteSessionProtocol.encodeRequest(Request.Stop).contains("\"stop\""))
        assertTrue(RemoteSessionProtocol.encodeRequest(Request.Resync(5)).contains("\"sinceSeq\":5"))
        assertTrue(RemoteSessionProtocol.encodeRequest(Request.Snapshot).contains("\"snapshot\""))
    }

    // ── folding ──────────────────────────────────────────────────────────

    @Test
    fun `text deltas accumulate into one assistant message`() {
        var s = SessionState()
        s = RemoteSessionProtocol.applyEvent(s, Frame.Event(1, "text_delta", "Hel"))
        s = RemoteSessionProtocol.applyEvent(s, Frame.Event(2, "text_delta", "lo"))
        assertEquals(1, s.messages.size)
        assertEquals("Hello", s.messages[0].text)
        assertTrue(s.running)
    }

    @Test
    fun `tool events append tool messages`() {
        var s = SessionState(messages = listOf(Message("assistant", "thinking")))
        s = RemoteSessionProtocol.applyEvent(s, Frame.Event(1, "tool_start", "running", toolName = "shell"))
        s = RemoteSessionProtocol.applyEvent(s, Frame.Event(2, "tool_result", "ok", toolName = "shell"))
        assertEquals(3, s.messages.size)
        assertEquals("tool", s.messages[1].role)
        assertEquals("shell", s.messages[2].toolName)
    }

    @Test
    fun `text delta after tool starts a new assistant message`() {
        var s = SessionState(messages = listOf(Message("tool", "result", toolName = "shell")))
        s = RemoteSessionProtocol.applyEvent(s, Frame.Event(1, "text_delta", "answer"))
        assertEquals(2, s.messages.size)
        assertEquals("assistant", s.messages[1].role)
    }

    @Test
    fun `turn_end clears running, error records`() {
        var s = SessionState(running = true)
        s = RemoteSessionProtocol.applyEvent(s, Frame.Event(1, "turn_end"))
        assertFalse(s.running)
        s = RemoteSessionProtocol.applyEvent(s, Frame.Event(2, "error", "boom"))
        assertEquals("boom", s.lastError)
        assertFalse(s.running)
    }

    @Test
    fun `unknown event kind is ignored`() {
        val s = SessionState(messages = listOf(Message("user", "x")))
        assertEquals(s, RemoteSessionProtocol.applyEvent(s, Frame.Event(1, "future_kind", "y")))
    }

    // ── client over fake transport ───────────────────────────────────────

    private class FakeTransport(
        private val frameList: List<Frame>,
        private var serverSeqValue: Long = 0,
    ) : SessionTransport {
        val sent = mutableListOf<Request>()
        var connected = 0
        override suspend fun connect() { connected++ }
        override fun frames(): Flow<Frame> = flowOf(*frameList.toTypedArray())
        override suspend fun send(request: Request) { sent.add(request) }
        override suspend fun serverSeq(): Long = serverSeqValue
        override suspend fun close() {}
    }

    @Test
    fun `run bootstraps from snapshot then folds events`() = runTest {
        val transport = FakeTransport(
            listOf(
                Frame.Snapshot(0, listOf(Message("user", "hi")), false),
                Frame.Event(1, "text_delta", "Hel"),
                Frame.Event(2, "text_delta", "lo"),
                Frame.Event(3, "turn_end"),
            ),
        )
        val states = mutableListOf<SessionState>()
        val client = RemoteSessionClient(transport, onState = { states.add(it) })
        client.run()
        assertEquals(Request.Snapshot, transport.sent.first())
        val final = client.currentState!!
        assertEquals("Hello", final.messages.last().text)
        assertFalse(final.running)
        assertEquals(3L, client.lastSeq)
    }

    @Test
    fun `stale events below snapshot seq are dropped`() = runTest {
        val transport = FakeTransport(
            listOf(
                Frame.Snapshot(5, listOf(Message("assistant", "base")), false),
                Frame.Event(3, "text_delta", "STALE"),
                Frame.Event(6, "text_delta", "!"),
            ),
        )
        val client = RemoteSessionClient(transport, onState = {})
        client.run()
        assertEquals("base!", client.currentState!!.messages.last().text)
        assertEquals(6L, client.lastSeq)
    }

    @Test
    fun `reconnect with small gap requests events since lastSeq`() = runTest {
        val first = FakeTransport(
            listOf(Frame.Snapshot(0, emptyList(), false), Frame.Event(1, "text_delta", "a")),
        )
        // Reconnect transport: server at seq 3, gap (3-1=2) is small →
        // client should ask for events since 1, then fold 2 and 3.
        val second = FakeTransport(
            listOf(Frame.Event(2, "text_delta", "b"), Frame.Event(3, "turn_end")),
            serverSeqValue = 3,
        )
        val client = RemoteSessionClient(first, onState = {}, reconnectTransport = { second })
        client.run()
        assertEquals(1L, client.lastSeq)
        client.reconnect()
        assertEquals(Request.Resync(1), second.sent.first())
        assertEquals("ab", client.currentState!!.messages.last().text)
        assertFalse(client.currentState!!.running)
        assertEquals(3L, client.lastSeq)
    }

    @Test
    fun `reconnect with large gap requests full snapshot`() = runTest {
        val first = FakeTransport(
            listOf(Frame.Snapshot(0, emptyList(), false), Frame.Event(1, "text_delta", "a")),
        )
        // Server far ahead (gap > 256) → full snapshot, not incremental.
        val second = FakeTransport(
            listOf(Frame.Snapshot(1000, listOf(Message("assistant", "rebuilt")), false)),
            serverSeqValue = 1000,
        )
        val client = RemoteSessionClient(first, onState = {}, reconnectTransport = { second })
        client.run()
        client.reconnect()
        assertEquals(Request.Snapshot, second.sent.first())
        assertEquals("rebuilt", client.currentState!!.messages.last().text)
        assertEquals(1000L, client.lastSeq)
    }

    @Test
    fun `reconnect from empty requests full snapshot`() = runTest {
        val transport = FakeTransport(
            listOf(Frame.Snapshot(0, listOf(Message("user", "x")), false)),
            serverSeqValue = 1000,
        )
        val client = RemoteSessionClient(transport, onState = {})
        client.reconnect()
        // No prior fold → bootstrap snapshot request.
        assertEquals(Request.Snapshot, transport.sent.first())
        assertEquals(1, transport.connected)
    }

    @Test
    fun `send and stop forward to transport`() = runTest {
        val transport = FakeTransport(emptyList())
        val client = RemoteSessionClient(transport, onState = {})
        client.send("hello")
        client.stop()
        assertEquals(Request.Send("hello"), transport.sent[0])
        assertEquals(Request.Stop, transport.sent[1])
    }
}