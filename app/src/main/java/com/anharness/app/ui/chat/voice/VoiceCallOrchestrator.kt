package com.anharness.app.ui.chat.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.anharness.app.speech.ReadAloudPlayer
import com.anharness.app.speech.RecognitionState
import com.anharness.app.speech.SpeechRecognitionManager
import com.anharness.app.speech.VoiceCallLogic
import com.anharness.app.speech.VoiceCallLogic.Action
import com.anharness.app.speech.VoiceCallLogic.Event
import com.anharness.app.speech.VoiceCallLogic.State
import com.anharness.app.speech.VoiceOutputState
import com.anharness.app.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * [T-voice-call-duplex] Side-effect shell around the pure [VoiceCallLogic]
 * transition table. Mounted only while the voice panel is active; it does
 * nothing unless [VoiceModePrefs.callMode] is on.
 *
 * Wires four event sources into the decider:
 *  - VAD speech-start (barge-in)  → SpeechRecognitionManager.speechStartEvents
 *  - final transcript             → the panel's capture callback (fed via [onFinal])
 *  - reply streaming state        → viewModel.isStreaming / streamingById
 *  - reply TTS speaking state     → replyTts.isSpeaking
 *
 * and executes the decided actions: open the mic, stop TTS, send the turn.
 * The decider stays pure; every Android touch lives here.
 */
@Composable
fun VoiceCallOrchestrator(
    viewModel: ChatViewModel,
    replyTts: ReadAloudPlayer,
    ensureMicPermission: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(State.IDLE) }

    fun apply(decision: VoiceCallLogic.Decision) {
        state = decision.next
        for (action in decision.actions) {
            when (action) {
                Action.None -> {}
                Action.StopTts -> {
                    // Barge-in: STOP (drop queue), never suspend — a suspended
                    // reply would resume over the user's new turn.
                    replyTts.stop()
                    VoiceOutputState.activePlayer?.stop()
                }
                Action.HangUp -> {
                    if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                        SpeechRecognitionManager.cancelRecording()
                    }
                    VoiceModePrefs.callMode = false
                }
                is Action.Send -> {
                    viewModel.setInputText("")
                    viewModel.sendMessage(action.text)
                }
                Action.StartListening -> {
                    scope.launch {
                        if (!ensureMicPermission()) return@launch
                        if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) return@launch
                        // Finals/empties flow back through the panel's shared
                        // callCaptureEvents channel (the panel owns the capture
                        // callback), so the orchestrator stays the single
                        // decision point without a second callback wiring.
                        SpeechRecognitionManager.startRecording(
                            onPartialOrFinal = { text, isFinal ->
                                if (!isFinal) return@startRecording
                                val ev = if (text.isBlank()) VoiceModePrefs.CallCapture.Empty
                                    else VoiceModePrefs.CallCapture.Final(text)
                                VoiceModePrefs.callCaptureEvents.tryEmit(ev)
                            },
                            onError = { _, _ ->
                                VoiceModePrefs.callCaptureEvents.tryEmit(VoiceModePrefs.CallCapture.Empty)
                            },
                        )
                    }
                }
            }
        }
    }

    fun dispatch(event: Event) {
        apply(VoiceCallLogic.decide(state, event))
    }

    // Call on/off.
    LaunchedEffect(Unit) {
        snapshotFlow { VoiceModePrefs.callMode }
            .distinctUntilChanged()
            .collect { on ->
                dispatch(if (on) Event.CallStarted else Event.CallEnded)
            }
    }

    // Barge-in: user starts speaking.
    LaunchedEffect(Unit) {
        SpeechRecognitionManager.speechStartEvents.collect {
            if (VoiceModePrefs.callMode) dispatch(Event.SpeechStarted)
        }
    }

    // Finals / empty captures pushed by the panel (or our own re-arm capture).
    LaunchedEffect(Unit) {
        VoiceModePrefs.callCaptureEvents.collect { capture ->
            if (!VoiceModePrefs.callMode) return@collect
            when (capture) {
                is VoiceModePrefs.CallCapture.Final -> dispatch(Event.FinalTranscript(capture.text))
                VoiceModePrefs.CallCapture.Empty -> dispatch(Event.CaptureEmpty)
            }
        }
    }

    // Reply lifecycle: streaming + TTS speaking → THINKING/SPEAKING/finished.
    LaunchedEffect(Unit) {
        combine(
            viewModel.isStreaming,
            replyTts.isSpeaking,
        ) { streaming, speaking -> streaming to speaking }
            .distinctUntilChanged()
            .collect { (streaming, speaking) ->
                if (!VoiceModePrefs.callMode) return@collect
                when {
                    speaking -> dispatch(Event.TtsStarted)
                    streaming -> dispatch(Event.ReplyStarted)
                    // Neither streaming nor speaking: if we were mid-reply,
                    // it has finished → re-arm. The decider ignores this from
                    // IDLE/LISTENING, so it is safe to emit liberally.
                    else -> dispatch(Event.ReplyFinished)
                }
            }
    }
}