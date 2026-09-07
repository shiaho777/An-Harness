package com.anharness.agent

import com.anharness.llm.LlmContext
import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmStreamEvent
import com.anharness.llm.StopReason
import com.anharness.llm.StreamFn
import com.anharness.llm.StreamOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Port of pi `packages/agent/src/agent-loop.ts` runLoop.
 *
 * Outer loop = follow-up messages after stop.
 * Inner loop = tool calls + steering messages.
 * Failures never throw — they surface as error tool results / ERROR stop.
 */
class AgentLoop(
    private val streamFn: StreamFn,
) {
    suspend fun run(
        prompts: List<LlmMessage>,
        session: AgentSession,
        config: AgentLoopConfig,
        emit: AgentEventSink,
        isCancelled: () -> Boolean = { false },
    ): List<LlmMessage> {
        val newMessages = mutableListOf<LlmMessage>()
        session.messages.addAll(prompts)
        newMessages.addAll(prompts)

        emit(AgentEvent.AgentStart)
        emit(AgentEvent.TurnStart)
        prompts.forEach {
            emit(AgentEvent.MessageStart(it))
            emit(AgentEvent.MessageEnd(it))
        }

        var activeConfig = config
        var pendingSteering: List<LlmMessage> = activeConfig.getSteeringMessages?.invoke().orEmpty()
        var lastTurn: TurnContext? = null
        var firstTurn = true

        while (true) {
            var hasMoreToolCalls = true
            while (hasMoreToolCalls || pendingSteering.isNotEmpty()) {
                if (!firstTurn || lastTurn != null) {
                    lastTurn?.let { turn ->
                        activeConfig.prepareNextTurn?.invoke(turn)?.let { activeConfig = it }
                        if (pendingSteering.isEmpty()) {
                            pendingSteering = activeConfig.getSteeringMessages?.invoke().orEmpty()
                        }
                    }
                    emit(AgentEvent.TurnStart)
                }
                firstTurn = false

                if (pendingSteering.isNotEmpty()) {
                    pendingSteering.forEach {
                        emit(AgentEvent.MessageStart(it))
                        emit(AgentEvent.MessageEnd(it))
                        session.messages.add(it)
                        newMessages.add(it)
                    }
                    pendingSteering = emptyList()
                }

                val assistant = streamAssistant(session, activeConfig, emit, isCancelled)
                session.messages.add(assistant)
                newMessages.add(assistant)

                if (assistant.stopReason == StopReason.ERROR || assistant.stopReason == StopReason.ABORTED) {
                    emit(AgentEvent.TurnEnd(assistant.text, emptyList()))
                    emit(AgentEvent.AgentEnd(newMessages.toList()))
                    return newMessages.toList()
                }

                val toolCalls = assistant.toolCalls.map { it.toAgent() }
                val toolResults = mutableListOf<LlmMessage.ToolResult>()
                hasMoreToolCalls = false
                if (toolCalls.isNotEmpty()) {
                    val batch = if (assistant.stopReason == StopReason.LENGTH) {
                        // Truncated output: args may be silently incomplete — fail all (port of pi).
                        val failed = toolCalls.map { call ->
                            emit(AgentEvent.ToolExecutionStart(call.id, call.name, call.arguments))
                            val r = errorResult(
                                "Tool \"${call.name}\" was not executed: response hit the output token " +
                                    "limit, arguments may be truncated. Re-issue with complete arguments.",
                            )
                            emit(AgentEvent.ToolExecutionEnd(call.id, call.name, r))
                            LlmMessage.ToolResult(call.id, call.name, r.text, isError = true) to r
                        }
                        BatchOutcome(failed.map { it.first }, failed.map { it.second })
                    } else {
                        executeBatch(session, assistant.text, toolCalls, activeConfig, emit, isCancelled)
                    }
                    // terminate hint: stop only when EVERY result asks for it (pi semantics).
                    val allTerminate = batch.outcomes.isNotEmpty() &&
                        batch.outcomes.all { it.terminate }
                    hasMoreToolCalls = batch.results.isNotEmpty() && !allTerminate
                    batch.results.forEach {
                        session.messages.add(it)
                        newMessages.add(it)
                        toolResults.add(it)
                    }
                }

                emit(AgentEvent.TurnEnd(assistant.text, toolResults.toList()))
                val turn = TurnContext(assistant.text, toolResults.toList(), newMessages.toList())
                lastTurn = turn
                if (activeConfig.shouldStopAfterTurn?.invoke(turn) == true) {
                    emit(AgentEvent.AgentEnd(newMessages.toList()))
                    return newMessages.toList()
                }
                pendingSteering = activeConfig.getSteeringMessages?.invoke().orEmpty()
            }

            val followUps = activeConfig.getFollowUpMessages?.invoke().orEmpty()
            if (followUps.isEmpty()) break
            pendingSteering = followUps
        }

        emit(AgentEvent.AgentEnd(newMessages.toList()))
        return newMessages.toList()
    }

    private suspend fun streamAssistant(
        session: AgentSession,
        config: AgentLoopConfig,
        emit: AgentEventSink,
        isCancelled: () -> Boolean,
    ): LlmMessage.Assistant {
        // Per-request context pipeline (pi transform_context): messages AND
        // system prompt may be rewritten before every call.
        var messages = session.messages.toList()
        var systemPrompt = session.systemPrompt
        config.transformContext?.let { transform ->
            val out = transform(ContextTransformInput(messages, systemPrompt))
            out.messages?.let { messages = it }
            out.systemPrompt?.let { systemPrompt = it }
        }
        val llmContext = LlmContext(
            systemPrompt = systemPrompt,
            messages = messages,
            tools = session.tools.map { it.definition },
        )
        var options = StreamOptions(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            thinking = config.thinking,
            maxTokens = config.maxTokens,
        )
        config.beforeRequest?.let { options = it(options) }
        emit(AgentEvent.MessageStart(LlmMessage.Assistant("")))
        val text = StringBuilder()
        val toolCalls = mutableListOf<com.anharness.llm.LlmToolCall>()
        val toolArgBufs = mutableMapOf<String, StringBuilder>()
        val toolNameById = mutableMapOf<String, String>()
        // A "length" stop means truncated args — must survive to the caller (pi parity).
        var doneStop: StopReason? = null
        // collect() is not inline: early stream errors unwind via carrier, not bare return.
        class StreamFailed(val assistant: LlmMessage.Assistant) : Exception()
        try {
            streamFn(config.model, llmContext, options).collect { event ->
                if (isCancelled()) return@collect
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        text.append(event.delta)
                        emit(AgentEvent.TextDelta(event.delta))
                    }
                    is LlmStreamEvent.ToolCallDelta -> {
                        toolNameById[event.id] = event.name
                        toolArgBufs.getOrPut(event.id) { StringBuilder() }.append(event.argsDelta)
                    }
                    is LlmStreamEvent.Done -> {
                        text.clear().append(event.message.text)
                        toolCalls.clear()
                        toolCalls.addAll(event.message.toolCalls)
                        doneStop = event.message.stopReason
                    }
                    is LlmStreamEvent.Error -> {
                        val msg = LlmMessage.Assistant(
                            text = text.toString(),
                            stopReason = if (isCancelled()) StopReason.ABORTED else StopReason.ERROR,
                        )
                        emit(AgentEvent.MessageEnd(msg))
                        throw StreamFailed(msg)
                    }
                }
            }
        } catch (e: StreamFailed) {
            return e.assistant
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = LlmMessage.Assistant(text.toString(), stopReason = StopReason.ERROR)
            emit(AgentEvent.MessageEnd(msg))
            return msg
        }
        // Fallback: streamer emitted deltas but no Done (defensive).
        if (toolCalls.isEmpty() && toolArgBufs.isNotEmpty()) {
            toolArgBufs.forEach { (id, sb) ->
                val args = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(sb.toString().ifBlank { "{}" })
                        as kotlinx.serialization.json.JsonObject
                }.getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap()))
                toolCalls.add(com.anharness.llm.LlmToolCall(id, toolNameById[id].orEmpty(), args))
            }
        }
        val stop = doneStop ?: if (isCancelled()) StopReason.ABORTED else StopReason.STOP
        val msg = LlmMessage.Assistant(text.toString(), toolCalls.toList(), stop)
        emit(AgentEvent.MessageEnd(msg))
        return msg
    }

    private data class BatchOutcome(val results: List<LlmMessage.ToolResult>, val outcomes: List<AgentToolResult>)

    private suspend fun executeBatch(
        session: AgentSession,
        assistantText: String,
        calls: List<AgentToolCall>,
        config: AgentLoopConfig,
        emit: AgentEventSink,
        isCancelled: () -> Boolean,
    ): BatchOutcome = coroutineScope {
        val byName = session.tools.associateBy { it.definition.name }
        val forceSequential = config.toolExecution == "sequential" ||
            calls.any { byName[it.name]?.definition?.executionMode == "sequential" }

        suspend fun runOne(call: AgentToolCall): Pair<LlmMessage.ToolResult, AgentToolResult> {
            emit(AgentEvent.ToolExecutionStart(call.id, call.name, call.arguments))
            val tool = byName[call.name]
            if (tool == null) {
                val r = errorResult("Tool ${call.name} not found")
                emit(AgentEvent.ToolExecutionEnd(call.id, call.name, r))
                return LlmMessage.ToolResult(call.id, call.name, r.text, true) to r
            }
            // Validate args against the tool schema before anything else (pi
            // prepareToolCall): malformed calls become error results, not executions.
            val validation = com.anharness.llm.validateToolArguments(tool.definition, call.arguments)
            if (validation.isFailure) {
                val r = errorResult("Invalid arguments for ${call.name}: ${validation.exceptionOrNull()?.message}")
                emit(AgentEvent.ToolExecutionEnd(call.id, call.name, r))
                return LlmMessage.ToolResult(call.id, call.name, r.text, true) to r
            }
            val before = config.beforeToolCall?.invoke(BeforeToolCallContext(call, call.arguments, assistantText))
            if (before?.block == true) {
                val r = errorResult(before.reason ?: "Tool execution was blocked", before.terminate)
                emit(AgentEvent.ToolExecutionEnd(call.id, call.name, r))
                return LlmMessage.ToolResult(call.id, call.name, r.text, true) to r.copy(terminate = before.terminate)
            }
            if (isCancelled()) {
                val r = errorResult("Operation aborted")
                emit(AgentEvent.ToolExecutionEnd(call.id, call.name, r))
                return LlmMessage.ToolResult(call.id, call.name, r.text, true) to r
            }
            val executed = try {
                // Progress partials are coalesced into the final result in v0.1;
                // fine-grained ToolExecutionUpdate streaming lands with the
                // persistent-shell tool (see app HarnessBridge).
                tool.execute(call, isCancelled)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errorResult(e.message ?: e.toString())
            }
            var final = executed
            var finalIsError = executed.isError
            config.afterToolCall?.invoke(AfterToolCallContext(call, call.arguments, executed))?.let { override ->
                final = executed.copy(
                    text = override.text ?: executed.text,
                    isError = override.isError ?: executed.isError,
                    terminate = override.terminate ?: executed.terminate,
                )
                finalIsError = final.isError
            }
            emit(AgentEvent.ToolExecutionEnd(call.id, call.name, final))
            return LlmMessage.ToolResult(call.id, call.name, final.text, finalIsError) to final
        }

        if (forceSequential) {
            val results = mutableListOf<LlmMessage.ToolResult>()
            val outcomes = mutableListOf<AgentToolResult>()
            for (call in calls) {
                if (isCancelled()) break
                val (msg, out) = runOne(call)
                results.add(msg)
                outcomes.add(out)
            }
            BatchOutcome(results, outcomes)
        } else {
            val deferred = calls.map { call -> async { runOne(call) } }
            val pairs = deferred.awaitAll()
            BatchOutcome(pairs.map { it.first }, pairs.map { it.second })
        }
    }
}
