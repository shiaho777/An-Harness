package com.anharness.app.speech

import com.anharness.app.speech.VoiceCallLogic.Action
import com.anharness.app.speech.VoiceCallLogic.Event
import com.anharness.app.speech.VoiceCallLogic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-call-duplex] Transition-table tests for the hands-free call
 * decider. Pure JVM — the whole barge-in / auto-send / re-arm contract is
 * exercised without audio hardware, mirroring CapturePauseLogicTest.
 */
class VoiceCallLogicTest {

    private fun decide(s: State, e: Event) = VoiceCallLogic.decide(s, e)

    // ── call lifecycle ───────────────────────────────────────────────────

    @Test
    fun `call start opens the mic from idle`() {
        val d = decide(State.IDLE, Event.CallStarted)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StartListening), d.actions)
    }

    @Test
    fun `call start is idempotent when already in a call`() {
        for (s in listOf(State.LISTENING, State.THINKING, State.SPEAKING)) {
            val d = decide(s, Event.CallStarted)
            assertEquals(s, d.next)
            assertTrue(d.actions.isEmpty())
        }
    }

    @Test
    fun `hang up stops tts and leaves call mode from any state`() {
        for (s in State.entries) {
            val d = decide(s, Event.CallEnded)
            assertEquals(State.IDLE, d.next)
            assertTrue(d.actions.contains(Action.StopTts))
            assertTrue(d.actions.contains(Action.HangUp))
        }
    }

    // ── barge-in ─────────────────────────────────────────────────────────

    @Test
    fun `speech over a spoken reply stops tts and returns to listening`() {
        val d = decide(State.SPEAKING, Event.SpeechStarted)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StopTts), d.actions)
    }

    @Test
    fun `speech while thinking also barges in`() {
        val d = decide(State.THINKING, Event.SpeechStarted)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StopTts), d.actions)
    }

    @Test
    fun `speech while already listening does nothing`() {
        val d = decide(State.LISTENING, Event.SpeechStarted)
        assertEquals(State.LISTENING, d.next)
        assertTrue(d.actions.isEmpty())
    }

    @Test
    fun `barge-in uses stop not suspend`() {
        // The contract: no resume-style action may appear on barge-in, or the
        // old reply would talk over the user's new turn.
        val d = decide(State.SPEAKING, Event.SpeechStarted)
        assertTrue(d.actions.all { it == Action.StopTts })
    }

    // ── auto-send ────────────────────────────────────────────────────────

    @Test
    fun `final transcript sends and moves to thinking`() {
        val d = decide(State.LISTENING, Event.FinalTranscript("hello"))
        assertEquals(State.THINKING, d.next)
        assertEquals(listOf(Action.Send("hello")), d.actions)
    }

    @Test
    fun `final transcript is trimmed before send`() {
        val d = decide(State.LISTENING, Event.FinalTranscript("  hi  "))
        assertEquals(listOf(Action.Send("hi")), d.actions)
    }

    @Test
    fun `blank final never sends`() {
        for (blank in listOf("", "   ", "\n")) {
            val d = decide(State.LISTENING, Event.FinalTranscript(blank))
            assertEquals(State.LISTENING, d.next)
            assertTrue(d.actions.isEmpty())
        }
    }

    @Test
    fun `final outside listening is ignored`() {
        for (s in listOf(State.THINKING, State.SPEAKING, State.IDLE)) {
            val d = decide(s, Event.FinalTranscript("stray"))
            assertEquals(s, d.next)
            assertTrue(d.actions.isEmpty())
        }
    }

    // ── empty capture re-arm ─────────────────────────────────────────────

    @Test
    fun `empty capture re-arms the mic while listening`() {
        val d = decide(State.LISTENING, Event.CaptureEmpty)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StartListening), d.actions)
    }

    @Test
    fun `empty capture elsewhere does nothing`() {
        for (s in listOf(State.THINKING, State.SPEAKING, State.IDLE)) {
            val d = decide(s, Event.CaptureEmpty)
            assertEquals(s, d.next)
            assertTrue(d.actions.isEmpty())
        }
    }

    // ── reply lifecycle + re-arm ─────────────────────────────────────────

    @Test
    fun `tts start moves thinking to speaking`() {
        val d = decide(State.THINKING, Event.TtsStarted)
        assertEquals(State.SPEAKING, d.next)
    }

    @Test
    fun `reply finished re-arms from speaking`() {
        val d = decide(State.SPEAKING, Event.ReplyFinished)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StartListening), d.actions)
    }

    @Test
    fun `reply finished re-arms from thinking (no audio reply)`() {
        val d = decide(State.THINKING, Event.ReplyFinished)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StartListening), d.actions)
    }

    @Test
    fun `reply finished never re-arms from idle or listening`() {
        for (s in listOf(State.IDLE, State.LISTENING)) {
            val d = decide(s, Event.ReplyFinished)
            assertEquals(s, d.next)
            assertTrue("must not open a second mic", d.actions.isEmpty())
        }
    }

    // ── recoverable error keeps the call alive ───────────────────────────

    @Test
    fun `recoverable error re-arms from listening`() {
        val d = decide(State.LISTENING, Event.RecoverableError)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StartListening), d.actions)
    }

    @Test
    fun `recoverable error during reply stops tts and re-arms`() {
        val d = decide(State.SPEAKING, Event.RecoverableError)
        assertEquals(State.LISTENING, d.next)
        assertEquals(listOf(Action.StopTts, Action.StartListening), d.actions)
    }

    @Test
    fun `recoverable error in idle stays idle`() {
        val d = decide(State.IDLE, Event.RecoverableError)
        assertEquals(State.IDLE, d.next)
        assertTrue(d.actions.isEmpty())
    }

    // ── full happy path: listen → send → speak → barge-in → listen ───────

    @Test
    fun `full duplex loop`() {
        var s = State.IDLE
        s = decide(s, Event.CallStarted).next            // LISTENING
        assertEquals(State.LISTENING, s)
        s = decide(s, Event.FinalTranscript("hi")).next   // THINKING
        assertEquals(State.THINKING, s)
        s = decide(s, Event.TtsStarted).next              // SPEAKING
        assertEquals(State.SPEAKING, s)
        val barge = decide(s, Event.SpeechStarted)        // barge-in
        assertEquals(State.LISTENING, barge.next)
        assertEquals(listOf(Action.StopTts), barge.actions)
        s = barge.next
        s = decide(s, Event.FinalTranscript("again")).next // THINKING
        assertEquals(State.THINKING, s)
        s = decide(s, Event.ReplyFinished).next            // re-arm LISTENING
        assertEquals(State.LISTENING, s)
    }
}