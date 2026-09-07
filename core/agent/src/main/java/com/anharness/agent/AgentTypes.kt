package com.anharness.agent

import com.anharness.llm.LlmContext
import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmToolDefinition
import com.anharness.llm.LlmUsage
import com.anharness.llm.ModelDescriptor
import com.anharness.llm.StreamFn
import com.anharness.llm.StreamOptions
import com.anharness.llm.ThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Domain tool-call block (port of pi AgentToolCall). */
data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/** Executed tool result (port of pi AgentToolResult). */
data class AgentToolResult(
    val text: String,
    val isError: Boolean = false,
    val details: Map<String, String> = emptyMap(),
    val usage: LlmUsage? = null,
    /** Hint: stop after this batch if every result sets it. */
    val terminate: Boolean = false,
)

/** Tool hosted by the harness (shell/file/browser/offload all implement this). */
interface AgentTool {
    val definition: LlmToolDefinition
    suspend fun execute(
        call: AgentToolCall,
        signal: () -> Boolean = { false },
        onUpdate: (String) -> Unit = {},
    ): AgentToolResult
}

fun errorResult(message: String, terminate: Boolean = false) =
    AgentToolResult(text = message, isError = true, terminate = terminate)

/** Port of pi BeforeToolCallResult / AfterToolCallResult. */
data class BeforeToolCallResult(val block: Boolean = false, val reason: String? = null, val terminate: Boolean = false)
data class AfterToolCallResult(
    val text: String? = null,
    val isError: Boolean? = null,
    val terminate: Boolean? = null,
)

data class BeforeToolCallContext(
    val toolCall: AgentToolCall,
    val args: JsonObject,
    val assistantText: String,
)

data class AfterToolCallContext(
    val toolCall: AgentToolCall,
    val args: JsonObject,
    val result: AgentToolResult,
)

data class TurnContext(
    val assistantText: String,
    val toolResults: List<LlmMessage.ToolResult>,
    val newMessages: List<LlmMessage>,
)

/** Input to the per-request context transform (pi's transform_context hook). */
data class ContextTransformInput(
    val messages: List<LlmMessage>,
    val systemPrompt: String,
)

/** Result of the transform; null fields mean "keep as-is". */
data class ContextTransformResult(
    val messages: List<LlmMessage>? = null,
    val systemPrompt: String? = null,
)

/** Port of pi AgentLoopConfig. */
data class AgentLoopConfig(
    val model: ModelDescriptor,
    val apiKey: String,
    val baseUrl: String? = null,
    val thinking: ThinkingLevel? = null,
    val maxTokens: Int = 4096,
    /** "sequential" forces one-at-a-time; default parallels unless a tool opts into sequential. */
    val toolExecution: String = "parallel",
    /**
     * Per-request context pipeline (pi transform_context): may rewrite the
     * message list and/or the system prompt before every LLM call.
     */
    val transformContext: (suspend (ContextTransformInput) -> ContextTransformResult)? = null,
    /** Per-request stream-options patch (pi before_request), e.g. token caps per step. */
    val beforeRequest: (suspend (StreamOptions) -> StreamOptions)? = null,
    val beforeToolCall: (suspend (BeforeToolCallContext) -> BeforeToolCallResult?)? = null,
    val afterToolCall: (suspend (AfterToolCallContext) -> AfterToolCallResult?)? = null,
    val shouldStopAfterTurn: (suspend (TurnContext) -> Boolean)? = null,
    /** Steering = injected before next LLM call; follow-up = injected after stop. */
    val getSteeringMessages: (suspend () -> List<LlmMessage>)? = null,
    val getFollowUpMessages: (suspend () -> List<LlmMessage>)? = null,
    val prepareNextTurn: (suspend (TurnContext) -> AgentLoopConfig?)? = null,
)

/** Port of pi AgentEvent. */
sealed interface AgentEvent {
    data object AgentStart : AgentEvent
    data object TurnStart : AgentEvent
    data class MessageStart(val message: LlmMessage) : AgentEvent
    data class MessageEnd(val message: LlmMessage) : AgentEvent
    data class TextDelta(val delta: String) : AgentEvent
    data class ToolExecutionStart(val toolCallId: String, val toolName: String, val args: JsonObject) : AgentEvent
    data class ToolExecutionUpdate(val toolCallId: String, val toolName: String, val partial: String) : AgentEvent
    data class ToolExecutionEnd(val toolCallId: String, val toolName: String, val result: AgentToolResult) : AgentEvent
    data class TurnEnd(val assistantText: String, val toolResults: List<LlmMessage.ToolResult>) : AgentEvent
    data class AgentEnd(val messages: List<LlmMessage>) : AgentEvent
}

typealias AgentEventSink = suspend (AgentEvent) -> Unit

data class AgentSession(
    val systemPrompt: String,
    val messages: MutableList<LlmMessage> = mutableListOf(),
    val tools: List<AgentTool> = emptyList(),
)

fun AgentSession.toLlmContext(transformed: List<LlmMessage>? = null) = LlmContext(
    systemPrompt = systemPrompt,
    messages = transformed ?: messages.toList(),
    tools = tools.map { it.definition },
)

internal fun AgentToolCall.toLlm() = com.anharness.llm.LlmToolCall(id, name, arguments)
internal fun com.anharness.llm.LlmToolCall.toAgent() = AgentToolCall(id, name, arguments)
