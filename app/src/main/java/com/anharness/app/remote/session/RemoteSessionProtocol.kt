package com.anharness.app.remote.session

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-remote-session] Wire protocol + state model for relocating an agent
 * session to a remote host (P4 slice 4, dsh-Go "engine elsewhere, phone as
 * thin client" parity).
 *
 * The phone keeps the UI; the agent LOOP runs on the remote host and emits
 * a stream of sequenced frames. The client folds them into a [SessionState]
 * via SessionResync. This file is the PURE protocol layer — frame
 * encode/decode + state folding — so it is fully JVM-testable without a
 * socket. The transport (SSH channel today, Noise/WebRTC later) and the
 * reconnect driver live in sibling files.
 *
 * Frame kinds (newline-delimited JSON, one frame per line):
 *  - snapshot {seq, messages[], running}      full checkpoint
 *  - event    {seq, kind, ...}                incremental change
 *  - request  {action, ...}                   client → server command
 *
 * Event kinds: text_delta, tool_start, tool_result, turn_end, error.
 * Sequence numbers are monotonic per session; the client drops anything
 * at or below its last applied seq (idempotent replay on reconnect).
 */
object RemoteSessionProtocol {

    const val PROTOCOL_VERSION = 1

    // ── state model ──────────────────────────────────────────────────────

    /** One rendered message in the remote conversation. */
    data class Message(
        val role: String,           // user | assistant | tool
        val text: String,
        val toolName: String? = null,
        val isError: Boolean = false,
    )

    /** The foldable client-side view of a remote session. */
    data class SessionState(
        val messages: List<Message> = emptyList(),
        val running: Boolean = false,
        val lastError: String? = null,
    )

    // ── frames ───────────────────────────────────────────────────────────

    sealed interface Frame {
        val seq: Long

        data class Snapshot(
            override val seq: Long,
            val messages: List<Message>,
            val running: Boolean,
        ) : Frame

        data class Event(
            override val seq: Long,
            val kind: String,
            val text: String = "",
            val toolName: String? = null,
            val isError: Boolean = false,
        ) : Frame
    }

    /** Client → server commands. */
    sealed interface Request {
        data class Send(val text: String) : Request
        data object Stop : Request
        data class Resync(val sinceSeq: Long) : Request
        data object Snapshot : Request
    }

    // ── folding (pure) ───────────────────────────────────────────────────

    /**
     * Apply one event to a state. text_delta appends to the last assistant
     * message (creating one if needed); tool_start/tool_result append tool
     * messages; turn_end clears running; error records the message.
     */
    fun applyEvent(state: SessionState, ev: Frame.Event): SessionState = when (ev.kind) {
        "text_delta" -> {
            val msgs = state.messages.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && last.role == "assistant" && last.toolName == null) {
                msgs[msgs.size - 1] = last.copy(text = last.text + ev.text)
            } else {
                msgs.add(Message("assistant", ev.text))
            }
            state.copy(messages = msgs, running = true)
        }
        "tool_start" -> state.copy(
            messages = state.messages + Message("tool", ev.text, toolName = ev.toolName),
            running = true,
        )
        "tool_result" -> state.copy(
            messages = state.messages + Message("tool", ev.text, toolName = ev.toolName, isError = ev.isError),
            running = true,
        )
        "turn_end" -> state.copy(running = false)
        "error" -> state.copy(running = false, lastError = ev.text)
        else -> state // unknown event kinds are ignored forward-compatibly
    }

    /** Build a SessionState from a snapshot frame. */
    fun fromSnapshot(snap: Frame.Snapshot): SessionState =
        SessionState(messages = snap.messages, running = snap.running)

    // ── encode / decode (pure) ───────────────────────────────────────────

    fun encodeFrame(frame: Frame): String = when (frame) {
        is Frame.Snapshot -> JSONObject().apply {
            put("type", "snapshot")
            put("v", PROTOCOL_VERSION)
            put("seq", frame.seq)
            put("running", frame.running)
            put("messages", JSONArray().apply {
                frame.messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("text", m.text)
                        if (m.toolName != null) put("toolName", m.toolName)
                        if (m.isError) put("isError", true)
                    })
                }
            })
        }.toString()
        is Frame.Event -> JSONObject().apply {
            put("type", "event")
            put("v", PROTOCOL_VERSION)
            put("seq", frame.seq)
            put("kind", frame.kind)
            put("text", frame.text)
            if (frame.toolName != null) put("toolName", frame.toolName)
            if (frame.isError) put("isError", true)
        }.toString()
    }

    /** Decode one line. Returns null for blank/malformed/unknown frames. */
    fun decodeFrame(line: String): Frame? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val o = JSONObject(trimmed)
            if (o.optInt("v", 0) != PROTOCOL_VERSION) return null
            val seq = o.getLong("seq")
            when (o.optString("type")) {
                "snapshot" -> {
                    val arr = o.optJSONArray("messages") ?: JSONArray()
                    val msgs = (0 until arr.length()).map { i ->
                        val m = arr.getJSONObject(i)
                        Message(
                            role = m.optString("role"),
                            text = m.optString("text"),
                            toolName = m.optString("toolName").takeIf { it.isNotEmpty() },
                            isError = m.optBoolean("isError", false),
                        )
                    }
                    Frame.Snapshot(seq, msgs, o.optBoolean("running", false))
                }
                "event" -> Frame.Event(
                    seq = seq,
                    kind = o.optString("kind"),
                    text = o.optString("text"),
                    toolName = o.optString("toolName").takeIf { it.isNotEmpty() },
                    isError = o.optBoolean("isError", false),
                )
                else -> null
            }
        }.getOrNull()
    }

    fun encodeRequest(req: Request): String = when (req) {
        is Request.Send -> JSONObject().put("action", "send").put("text", req.text).toString()
        Request.Stop -> JSONObject().put("action", "stop").toString()
        is Request.Resync -> JSONObject().put("action", "resync").put("sinceSeq", req.sinceSeq).toString()
        Request.Snapshot -> JSONObject().put("action", "snapshot").toString()
    }
}