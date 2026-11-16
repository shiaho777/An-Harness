package com.anharness.app.harness

import com.anharness.app.data.model.AgentToolDefinition
import com.anharness.app.data.model.AgentToolParam
import com.anharness.app.data.model.LLMMessage
import com.anharness.app.data.model.LLMStreamChunk
import com.anharness.app.provider.LLMProvider
import com.anharness.llm.LlmContext
import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmStreamEvent
import com.anharness.llm.LlmToolCall
import com.anharness.llm.LlmToolDefinition
import com.anharness.llm.LlmUsage
import com.anharness.llm.ModelDescriptor
import com.anharness.llm.StopReason
import com.anharness.llm.StreamFn
import com.anharness.llm.StreamOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * [T-subagent-app-wiring] Bridges the app's [LLMProvider] stack to the
 * core:agent [StreamFn] contract so sub-agents (and any future core-loop
 * migration) run on the SAME configured providers the chat uses — OAuth,
 * gateways, thinking clamps and all — instead of a parallel raw-SSE path.
 *
 * The adapter is a pure translation layer:
 *  - core LlmContext → app LLMMessage history + AgentToolDefinition tools
 *  - app LLMStreamChunk → core LlmStreamEvent
 * Failures follow the core contract: emit [LlmStreamEvent.Error], never throw.
 */
object ProviderStreamAdapter {

    /** Build a StreamFn backed by a live provider instance. */
    fun streamFn(provider: LLMProvider): StreamFn = { model, context, options ->
        flow {
            val text = StringBuilder()
            val toolCalls = mutableListOf<LlmToolCall>()
            var stop: StopReason = StopReason.STOP
            var usage: LlmUsage? = null
            try {
                provider.streamMessage(
                    messages = context.messages.toAppMessages(),
                    systemPrompt = context.systemPrompt,
                    maxTokens = options.maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = context.tools.map { it.toAppDefinition() },
                    thinkingLevel = options.thinking.toAppThinking(),
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> {
                            text.append(chunk.text)
                            emit(LlmStreamEvent.TextDelta(chunk.text))
                        }
                        is LLMStreamChunk.ToolCallComplete -> {
                            val args = chunk.args.toKotlinx()
                            toolCalls.add(LlmToolCall(chunk.id, chunk.name, args))
                        }
                        is LLMStreamChunk.Usage -> {
                            usage = LlmUsage(
                                inputTokens = chunk.usage.latestContextTokens.takeIf { it > 0 }
                                    ?: chunk.usage.inputTokens,
                                outputTokens = chunk.usage.outputTokens,
                            )
                        }
                        is LLMStreamChunk.Finished -> {
                            stop = chunk.stopReason.toCoreStop()
                        }
                        // Thinking/reasoning/media chunks don't map to core
                        // events; the sub-agent loop only needs text + tools.
                        else -> {}
                    }
                }
                emit(
                    LlmStreamEvent.Done(
                        LlmMessage.Assistant(text.toString(), toolCalls.toList(), stop, usage),
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(LlmStreamEvent.Error(e.message ?: e.toString()))
            }
        }
    }

    /** A ModelDescriptor mirroring the provider's current model (for config). */
    fun descriptor(provider: LLMProvider): ModelDescriptor {
        val m = provider.model
        return ModelDescriptor(
            id = m.id,
            name = m.displayName,
            api = "openai-completions",
            providerId = m.provider,
            baseUrl = null,
            contextWindow = m.contextWindow,
            supportsThinking = m.supportsReasoning == true,
        )
    }

    // ── core → app ───────────────────────────────────────────────────────

    private fun List<LlmMessage>.toAppMessages(): List<LLMMessage> = map { m ->
        when (m) {
            is LlmMessage.User -> LLMMessage(role = LLMMessage.Role.USER, content = m.text)
            is LlmMessage.Assistant -> LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = m.text,
                contentParts = m.toolCalls.map { call ->
                    com.anharness.app.data.model.AgentContentPart.ToolUse(
                        id = call.id,
                        name = call.name,
                        input = org.json.JSONObject(call.arguments.toString()),
                    )
                },
            )
            is LlmMessage.ToolResult -> LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    com.anharness.app.data.model.AgentContentPart.ToolResult(
                        id = m.toolCallId,
                        name = m.toolName,
                        content = m.text,
                        isError = m.isError,
                    ),
                ),
            )
        }
    }

    private fun LlmToolDefinition.toAppDefinition(): AgentToolDefinition {
        val props = (inputSchema["properties"] as? JsonObject).orEmpty()
        val params = props.entries.associate { (key, value) ->
            val obj = value as? JsonObject ?: JsonObject(emptyMap())
            key to AgentToolParam(
                type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "string",
                description = (obj["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                enumValues = (obj["enum"] as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull
                },
            )
        }
        val required = (inputSchema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return AgentToolDefinition(
            name = name,
            description = description,
            parameters = params,
            required = required,
        )
    }

    private fun com.anharness.llm.ThinkingLevel?.toAppThinking(): com.anharness.app.data.model.ThinkingLevel =
        when (this) {
            null -> com.anharness.app.data.model.ThinkingLevel.OFF
            com.anharness.llm.ThinkingLevel.MINIMAL -> com.anharness.app.data.model.ThinkingLevel.LOW
            com.anharness.llm.ThinkingLevel.LOW -> com.anharness.app.data.model.ThinkingLevel.LOW
            com.anharness.llm.ThinkingLevel.MEDIUM -> com.anharness.app.data.model.ThinkingLevel.MEDIUM
            com.anharness.llm.ThinkingLevel.HIGH -> com.anharness.app.data.model.ThinkingLevel.HIGH
            com.anharness.llm.ThinkingLevel.XHIGH -> com.anharness.app.data.model.ThinkingLevel.XHIGH
            com.anharness.llm.ThinkingLevel.MAX -> com.anharness.app.data.model.ThinkingLevel.MAX
        }

    // ── app → core ───────────────────────────────────────────────────────

    private fun String?.toCoreStop(): StopReason = when (this?.lowercase()) {
        "length", "max_tokens", "max_output_tokens" -> StopReason.LENGTH
        null, "" -> StopReason.STOP
        else -> StopReason.STOP
    }

    private fun org.json.JSONObject.toKotlinx(): JsonObject =
        runCatching { Json.parseToJsonElement(toString()).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    private val kotlinx.serialization.json.JsonElement.jsonObject: JsonObject
        get() = this as? JsonObject ?: JsonObject(emptyMap())
}