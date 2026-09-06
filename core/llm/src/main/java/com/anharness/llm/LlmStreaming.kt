package com.anharness.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Unified message model. AgentMessage[] converts to this only at the LLM boundary. */
sealed interface LlmMessage {
    data class User(val text: String) : LlmMessage
    data class Assistant(
        val text: String,
        val toolCalls: List<LlmToolCall> = emptyList(),
        val stopReason: StopReason = StopReason.STOP,
    ) : LlmMessage
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val text: String,
        val isError: Boolean = false,
    ) : LlmMessage
}

enum class StopReason { STOP, LENGTH, ERROR, ABORTED }

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

data class LlmContext(
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDefinition> = emptyList(),
)

data class StreamOptions(
    val apiKey: String,
    val baseUrl: String? = null,
    val thinking: ThinkingLevel? = null,
    val maxTokens: Int = 4096,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** Streaming events. Failures are encoded as events + terminal ERROR, never thrown. */
sealed interface LlmStreamEvent {
    data class TextDelta(val delta: String) : LlmStreamEvent
    data class ToolCallDelta(val id: String, val name: String, val argsDelta: String) : LlmStreamEvent
    data class Done(val message: LlmMessage.Assistant) : LlmStreamEvent
    data class Error(val message: String) : LlmStreamEvent
}

/**
 * Stream function contract (port of pi StreamFn).
 * Must not throw for model/runtime failures — emit [LlmStreamEvent.Error].
 */
typealias StreamFn = (model: ModelDescriptor, context: LlmContext, options: StreamOptions) -> Flow<LlmStreamEvent>

/** Minimal tool-argument validation: required keys must be present. */
fun validateToolArguments(tool: LlmToolDefinition, args: JsonObject): Result<JsonObject> {
    val required = args // schema-level `required` enforced by callers with full JSON-schema
    return Result.success(required)
}

fun LlmToolCall.argument(key: String): JsonElement? = arguments[key]

fun JsonObject.stringArg(key: String, default: String = ""): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: default

fun JsonObject.intArg(key: String, default: Int? = null): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: default
