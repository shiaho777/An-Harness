package com.anharness.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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

/**
 * Tool-argument validation against the tool's input schema (port of pi
 * `validateToolArguments`). Checks the subset of JSON Schema the catalog
 * emits: `type`, `properties`, `required`, `enum`. Extra keys are allowed
 * (models routinely add presentation fields). Failures are descriptive —
 * they become error tool results, never exceptions.
 */
fun validateToolArguments(tool: LlmToolDefinition, args: JsonObject): Result<JsonObject> {
    val schema = tool.inputSchema
    val violations = mutableListOf<String>()

    val required = (schema["required"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    for (key in required) {
        if (key !in args || args[key] is kotlinx.serialization.json.JsonNull) {
            violations += "missing required argument \"$key\""
        }
    }

    val properties = (schema["properties"] as? JsonObject).orEmpty()
    for ((key, value) in args) {
        val propSchema = properties[key] as? JsonObject ?: continue // unknown key: allowed
        if (value is kotlinx.serialization.json.JsonNull) continue
        val declared = (propSchema["type"] as? JsonPrimitive)?.contentOrNull
        if (declared != null && !jsonTypeMatches(declared, value)) {
            violations += "argument \"$key\" must be $declared"
        }
        val enum = (propSchema["enum"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (enum.isNotEmpty() && (value as? JsonPrimitive)?.contentOrNull !in enum) {
            violations += "argument \"$key\" must be one of ${enum.joinToString(", ") { "\"$it\"" }}"
        }
    }

    return if (violations.isEmpty()) Result.success(args)
    else Result.failure(IllegalArgumentException(violations.joinToString("; ")))
}

private fun jsonTypeMatches(declared: String, value: kotlinx.serialization.json.JsonElement): Boolean =
    when (declared) {
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive &&
            (value.contentOrNull == "true" || value.contentOrNull == "false")
        "integer" -> value is JsonPrimitive && value.intOrNull != null
        "number" -> value is JsonPrimitive &&
            (value.intOrNull != null || value.doubleOrNull != null)
        "object" -> value is JsonObject
        "array" -> value is kotlinx.serialization.json.JsonArray
        else -> true // unknown declared type: don't reject what we can't check
    }

fun LlmToolCall.argument(key: String): JsonElement? = arguments[key]

fun JsonObject.stringArg(key: String, default: String = ""): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: default

fun JsonObject.intArg(key: String, default: Int? = null): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: default
