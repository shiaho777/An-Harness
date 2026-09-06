package com.anharness.app.harness

import android.content.Context
import com.anharness.agent.AgentLoopConfig
import com.anharness.agent.AgentTool
import com.anharness.agent.AgentToolCall
import com.anharness.agent.AgentToolResult
import com.anharness.agent.AfterToolCallContext
import com.anharness.agent.AfterToolCallResult
import com.anharness.agent.BeforeToolCallContext
import com.anharness.agent.BeforeToolCallResult
import com.anharness.agent.SlidingWindowCompaction
import com.anharness.app.agent.Level
import com.anharness.app.agent.ToolLoopDetector
import com.anharness.app.data.model.AgentToolDefinition
import com.anharness.app.tools.AgentTools
import com.anharness.app.tools.FileEditTool
import com.anharness.app.tools.FileReadTool
import com.anharness.app.tools.FileWriteTool
import com.anharness.app.tools.ToolExecutionResult
import com.anharness.llm.LlmToolDefinition
import com.anharness.llm.ModelDescriptor
import com.anharness.llm.ThinkingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Adapter between the OpenMinis tool executors and the pi-ported
 * `core:agent` loop. Owns:
 * - definition conversion ([AgentToolDefinition] → [LlmToolDefinition])
 * - file-tool adapters (standalone objects, only need Context + sessionId)
 * - delegation hook for shell/browser/memory (ChatViewModel supplies the
 *   executor until the ViewModel itself migrates to AgentLoop in P2)
 * - [ToolLoopDetector] wired as before/afterToolCall (pi hook semantics)
 */
object HarnessBridge {

    // ── definition conversion ──────────────────────────────────────

    fun AgentToolDefinition.toLlmTool(executionMode: String = "parallel"): LlmToolDefinition {
        val props = buildJsonObject {
            parameters.forEach { (key, param) ->
                put(key, buildJsonObject {
                    put("type", param.type)
                    put("description", param.description)
                    param.enumValues?.let { enums ->
                        put("enum", JsonArray(enums.map { JsonPrimitive(it) }))
                    }
                })
            }
        }
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }
        return LlmToolDefinition(
            name = name,
            description = description,
            inputSchema = schema,
            executionMode = executionMode,
        )
    }

    // ── AgentTool adapters ─────────────────────────────────────────

    private abstract class BaseTool(
        appDefinition: AgentToolDefinition,
        executionMode: String = "parallel",
    ) : AgentTool {
        override val definition: LlmToolDefinition = appDefinition.toLlmTool(executionMode)

        protected fun argsJson(call: AgentToolCall): String =
            org.json.JSONObject(call.arguments.toMap()).toString()

        private fun JsonObject.toMap(): Map<String, Any?> =
            entries.associate { (k, v) -> k to v.toPlain() }

        private fun kotlinx.serialization.json.JsonElement.toPlain(): Any? = when (this) {
            is JsonPrimitive -> contentOrNull ?: toString()
            is JsonObject -> toMap()
            is JsonArray -> map { it.toPlain() }
        }
    }

    /** File tools are standalone — no ViewModel needed. */
    fun fileTools(
        context: Context,
        sessionId: () -> String,
    ): List<AgentTool> = listOf(
        object : BaseTool(FileReadTool.definition()) {
            override suspend fun execute(
                call: AgentToolCall,
                signal: () -> Boolean,
                onUpdate: (String) -> Unit,
            ): AgentToolResult =
                FileReadTool.execute(argsJson(call), sessionId(), context).toAgentResult()
        },
        object : BaseTool(FileWriteTool.definition()) {
            override suspend fun execute(
                call: AgentToolCall,
                signal: () -> Boolean,
                onUpdate: (String) -> Unit,
            ): AgentToolResult =
                FileWriteTool.execute(argsJson(call), sessionId(), context).toAgentResult()
        },
        object : BaseTool(FileEditTool.definition()) {
            override suspend fun execute(
                call: AgentToolCall,
                signal: () -> Boolean,
                onUpdate: (String) -> Unit,
            ): AgentToolResult =
                FileEditTool.execute(argsJson(call), sessionId(), context).toAgentResult()
        },
    )

    /**
     * Generic adapter for tools whose executor still lives in ChatViewModel
     * (shell_execute, browser_use, memory_*, read_image).
     * The ViewModel passes `::executeTool`-equivalent lambdas; P2 migrates
     * the bodies here and drops the lambda.
     */
    fun delegatingTool(
        definition: AgentToolDefinition,
        executionMode: String = "parallel",
        executor: suspend (argsJson: String) -> ToolExecutionResult,
    ): AgentTool = object : BaseTool(definition, executionMode) {
        override suspend fun execute(
            call: AgentToolCall,
            signal: () -> Boolean,
            onUpdate: (String) -> Unit,
        ): AgentToolResult = executor(argsJson(call)).toAgentResult()
    }

    fun ToolExecutionResult.toAgentResult(): AgentToolResult = AgentToolResult(
        text = output,
        isError = !success,
    )

    // ── loop-guard hooks (ToolLoopDetector as pi hooks) ────────────

    /** One instance per session — matches ToolLoopDetector's contract. */
    class LoopGuard(private val detector: ToolLoopDetector = ToolLoopDetector()) {
        fun reset() = detector.reset()

        suspend fun before(ctx: BeforeToolCallContext): BeforeToolCallResult? {
            val check = detector.check(ctx.toolCall.name, ctx.args.toPlainMap())
            return when (check.level) {
                Level.NONE -> null
                Level.WARNING -> null // warnings surface via after(); don't block
                Level.CRITICAL -> BeforeToolCallResult(
                    block = true,
                    reason = check.message,
                    terminate = true,
                )
            }
        }

        suspend fun after(ctx: AfterToolCallContext): AfterToolCallResult? {
            val outcome = detector.record(
                ctx.toolCall.name,
                ctx.args.toPlainMap(),
                ctx.result.text,
                errorMessage = if (ctx.result.isError) ctx.result.text else null,
            )
            return when (outcome.level) {
                Level.NONE -> null
                Level.CRITICAL -> null // critical is a before()-only path by spec
                Level.WARNING -> AfterToolCallResult(
                    text = ctx.result.text + "\n\n" + (outcome.message.orEmpty()),
                )
            }
        }

        private fun JsonObject.toPlainMap(): Map<String, Any?> =
            entries.associate { (k, v) -> k to v.toPlain() }

        private fun kotlinx.serialization.json.JsonElement.toPlain(): Any? = when (this) {
            is JsonPrimitive -> contentOrNull ?: toString()
            is JsonObject -> toPlainMap()
            is JsonArray -> map { it.toPlain() }
        }
    }

    // ── config assembly ────────────────────────────────────────────

    /**
     * Canonical tool list for a session. Mirrors the exposure gates in
     * [AgentTools.makeAgentTools] (vision/memory toggles) so the model
     * never sees tools the harness can't execute.
     */
    fun agentToolDefinitions(
        supportsImageInput: Boolean = true,
        visionGroupConfigured: Boolean = false,
        memoryEnabled: Boolean = true,
    ): List<AgentToolDefinition> = AgentTools.makeAgentTools(
        supportsImageInput = supportsImageInput,
        visionGroupConfigured = visionGroupConfigured,
        memoryEnabled = memoryEnabled,
    )

    fun buildLoopConfig(
        model: ModelDescriptor,
        apiKey: String,
        baseUrl: String? = null,
        thinking: ThinkingLevel? = null,
        guard: LoopGuard = LoopGuard(),
        compaction: SlidingWindowCompaction = SlidingWindowCompaction(),
        getSteering: (suspend () -> List<com.anharness.llm.LlmMessage>)? = null,
        getFollowUp: (suspend () -> List<com.anharness.llm.LlmMessage>)? = null,
    ): AgentLoopConfig = AgentLoopConfig(
        model = model,
        apiKey = apiKey,
        baseUrl = baseUrl,
        thinking = thinking,
        beforeToolCall = { guard.before(it) },
        afterToolCall = { guard.after(it) },
        transformContext = { messages ->
            // Compaction only kicks in past the window; cheap path is a passthrough.
            if (messages.size > 80) compaction.compact(messages) else messages
        },
        getSteeringMessages = getSteering,
        getFollowUpMessages = getFollowUp,
    )
}
