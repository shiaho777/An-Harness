package com.anharness.app.speech

/**
 * [T-voice-call-duplex] Pure decision core for hands-free voice calls.
 *
 * Problem: voice input was half-duplex — silence closed the mic, the user
 * tapped Send, TTS read the reply, and the user had to tap the mic again.
 * A call needs: barge-in (user speaks over the reply → stop TTS instantly),
 * auto-send on a final transcript, and auto re-arm once the reply finishes
 * speaking.
 *
 * The whole transition table is a pure function so it is JVM-testable
 * (same pattern as CapturePauseLogicTest / ShizukuBackendStateTest). The
 * Compose orchestrator owns the side effects; this object only decides.
 *
 * States:
 *  - IDLE:      call mode off.
 *  - LISTENING: mic hot, waiting for the user.
 *  - THINKING:  transcript sent, reply streaming (no audio yet).
 *  - SPEAKING:  reply TTS playing; mic is re-armed for barge-in.
 *
 * Barge-in uses STOP, never suspend: a suspended reply would resume and
 * talk over the user's new turn once capture ends.
 */
object VoiceCallLogic {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    sealed interface Event {
        /** Call mode switched on (mic permission already granted). */
        data object CallStarted : Event
        /** Call mode switched off / hang up. */
        data object CallEnded : Event
        /** VAD detected the user starting to speak. */
        data object SpeechStarted : Event
        /** A non-blank final transcript arrived. */
        data class FinalTranscript(val text: String) : Event
        /** Capture ended with no usable text (silence / too short / error). */
        data object CaptureEmpty : Event
        /** The reply stream began (first delta / turn started). */
        data object ReplyStarted : Event
        /** Reply TTS started playing. */
        data object TtsStarted : Event
        /** Reply finished streaming AND TTS drained (nothing left to say). */
        data object ReplyFinished : Event
        /** Recognition error that should not kill the call (network etc.). */
        data object RecoverableError : Event
    }

    sealed interface Action {
        data object None : Action
        /** Open the mic (startRecording). */
        data object StartListening : Action
        /** Stop TTS outright (barge-in) and keep/return to listening. */
        data object StopTts : Action
        /** Send the transcript as a chat turn. */
        data class Send(val text: String) : Action
        /** Stop everything, leave call mode. */
        data object HangUp : Action
    }

    data class Decision(val next: State, val actions: List<Action>)

    /**
     * Transition table. Invariants:
     *  - SpeechStarted while SPEAKING/THINKING → StopTts + LISTENING
     *    (barge-in; the mic is already hot or re-armed by the orchestrator).
     *  - FinalTranscript only sends from LISTENING (a stray final during
     *    SPEAKING belongs to the barge-in capture that already moved us).
     *  - ReplyFinished re-arms the mic ONLY in SPEAKING/THINKING — never
     *    from IDLE (call off) and never while LISTENING (mic already hot).
     *  - Blank finals never send.
     */
    fun decide(state: State, event: Event): Decision = when (event) {
        Event.CallStarted ->
            if (state == State.IDLE) Decision(State.LISTENING, listOf(Action.StartListening))
            else Decision(state, emptyList())

        Event.CallEnded ->
            Decision(State.IDLE, listOf(Action.StopTts, Action.HangUp))

        Event.SpeechStarted -> when (state) {
            // Barge-in: kill the reply audio immediately. The mic stays hot.
            State.SPEAKING, State.THINKING ->
                Decision(State.LISTENING, listOf(Action.StopTts))
            // Already listening (or idle) — nothing to interrupt.
            else -> Decision(state, emptyList())
        }

        is Event.FinalTranscript -> {
            val text = event.text.trim()
            if (state == State.LISTENING && text.isNotEmpty()) {
                Decision(State.THINKING, listOf(Action.Send(text)))
            } else {
                Decision(state, emptyList())
            }
        }

        Event.CaptureEmpty -> when (state) {
            // Silence closed the mic with nothing to send → re-arm so the
            // user can keep talking without tapping anything.
            State.LISTENING -> Decision(State.LISTENING, listOf(Action.StartListening))
            else -> Decision(state, emptyList())
        }

        Event.ReplyStarted -> when (state) {
            State.THINKING -> Decision(State.THINKING, emptyList())
            // A reply starting while we thought we were listening means a
            // turn is in flight (e.g. auto-send raced) — follow it.
            State.LISTENING -> Decision(State.THINKING, emptyList())
            else -> Decision(state, emptyList())
        }

        Event.TtsStarted -> when (state) {
            State.THINKING, State.SPEAKING -> Decision(State.SPEAKING, emptyList())
            else -> Decision(state, emptyList())
        }

        Event.ReplyFinished -> when (state) {
            // Reply done speaking → hands-free re-arm.
            State.SPEAKING, State.THINKING ->
                Decision(State.LISTENING, listOf(Action.StartListening))
            else -> Decision(state, emptyList())
        }

        Event.RecoverableError -> when (state) {
            // Keep the call alive; re-arm the mic.
            State.LISTENING -> Decision(State.LISTENING, listOf(Action.StartListening))
            State.THINKING, State.SPEAKING -> Decision(State.LISTENING, listOf(Action.StopTts, Action.StartListening))
            State.IDLE -> Decision(State.IDLE, emptyList())
        }
    }
}