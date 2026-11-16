package com.anharness.agent

import com.anharness.llm.LlmMessage
import com.anharness.llm.StopReason
import com.anharness.llm.StreamFn
import com.anharness.llm.stringArg
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * [T-subagent-spawn] Sub-agent derivation for core:agent (P3, dsh-Go
 * lineage-tree parity — core slice).
 *
 * Problem: the loop had no way to delegate a bounded sub-task to a child
 * agent; everything ran in one flat conversation, so long research/tool
 * chains polluted the parent context and nothing enforced depth or
 * concurrency limits.
 *
 * Design:
 *  - A child runs the SAME [AgentLoop] with its own fresh [AgentSession]
 *    (clean context: system prompt + task, no parent history).
 *  - The model FOLLOWS THE PARENT config by default (dsh constraint);
 *    an explicit override is allowed but inherits every guard hook.
 *  - Limits: maxDepth (lineage) + maxConcurrency (global semaphore).
 *    Violations become error tool results — never throws (loop contract).
 *  - Permissions only tighten: the parent's beforeToolCall runs FIRST;
 *    a child may add its own gate after it, never replace it. A parent
 *    block stays blocked.
 *  - The child's final assistant text returns to the parent as an ordinary
 *    tool result, so the every-result-terminate batch semantics are
 *    untouched.
 *  - Lineage: each spawn records parent/child ids + depth in a
 *    [SubAgentRegistry] the UI can render as a tree (next slice).
 */

/** One node in the spawn lineage. */
data class SubAgentRecord(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val task: String,
    val startedAt: Long,
    @Volatile var finishedAt: Long? = null,
    @Volatile var ok: Boolean? = null,
)

/**
 * Process-wide lineage + concurrency accounting. Thread-safe: spawns run
 * on the loop's parallel batch dispatcher.
 */
class SubAgentRegistry(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {
    private val records = java.util.concurrent.ConcurrentHashMap<String, SubAgentRecord>()
    private val slots = Semaphore(maxConcurrency.coerceAtLeast(1))
    val activeCount = AtomicInteger(0)

    fun record(node: SubAgentRecord) { records[node.id] = node }
    fun get(id: String): SubAgentRecord? = records[id]
    fun all(): List<SubAgentRecord> = records.values.sortedBy { it.startedAt }
    fun childrenOf(parentId: String?): List<SubAgentRecord> =
        records.values.filter { it.parentId == parentId }.sortedBy { it.startedAt }

    internal suspend fun <T> withSlot(block: suspend () -> T): T = slots.withPermit { block() }

    companion object {
        const val DEFAULT_MAX_DEPTH = 3
        const val DEFAULT_MAX_CONCURRENCY = 4
    }
}

/** Spawn request from the parent loop. */
data class SubAgentTask(
    val task: String,
    /** Optional child system prompt; defaults to the parent's. */
    val systemPrompt: String? = null,
    /** Optional tool subset; defaults to the parent's tools. */
    val tools: List<AgentTool>? = null,
    /** Optional extra gate — runs AFTER the parent's, can only tighten. */
    val beforeToolCall: (suspend (BeforeToolCallContext) -> BeforeToolCallResult?)? = null,
    /** Optional model/apiKey override; null fields follow the parent. */
    val model: com.anharness.llm.ModelDescriptor? = null,
)

/**
 * Runs sub-agents for one parent loop. Create one per parent run (or share
 * a registry across runs to keep the whole lineage tree).
 */
class SubAgentRunner(
    private val streamFn: StreamFn,
    private val registry: SubAgentRegistry = SubAgentRegistry(),
    private val parentId: String? = null,
    private val parentDepth: Int = 0,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Spawn one child and return its outcome as a tool result. Never throws
     * for policy/runtime failures — they become error results (loop
     * contract). Cancellation propagates.
     */
    suspend fun spawn(
        task: SubAgentTask,
        parentSession: AgentSession,
        parentConfig: AgentLoopConfig,
        isCancelled: () -> Boolean = { false },
        emit: AgentEventSink = {},
    ): AgentToolResult {
        val depth = parentDepth + 1
        if (depth > registry.maxDepth) {
            return errorResult(
                "Sub-agent depth limit reached (max ${registry.maxDepth}). " +
                    "Finish the work at this level instead of delegating further.",
            )
        }
        if (task.task.isBlank()) {
            return errorResult("Sub-agent task must not be blank.")
        }
        val id = UUID.randomUUID().toString()
        val node = SubAgentRecord(id, parentId, depth, task.task, now())
        registry.record(node)
        registry.activeCount.incrementAndGet()
        return try {
            registry.withSlot {
                if (isCancelled()) return@withSlot errorResult("Operation aborted")
                val childConfig = childConfig(parentConfig, task)
                val baseTools = task.tools ?: parentSession.tools
                // The child can itself delegate ONLY while depth remains.
                // At the cap we expose no spawn tool, so a model cannot loop
                // on a delegation that can only return a depth-limit error.
                // The child's runner delegates against the CHILD's own
                // session/config (its tools + tightened gate), not the parent's.
                val childTools = if (depth < registry.maxDepth) {
                    val childRunner = SubAgentRunner(
                        streamFn = streamFn,
                        registry = registry,
                        parentId = id,
                        parentDepth = depth,
                        now = now,
                    )
                    val childSession = AgentSession(
                        systemPrompt = task.systemPrompt ?: parentSession.systemPrompt,
                        tools = baseTools,
                    )
                    baseTools + childRunner.asTool(childSession, childConfig, isCancelled)
                } else {
                    baseTools
                }
                val childSession = AgentSession(
                    systemPrompt = task.systemPrompt ?: parentSession.systemPrompt,
                    tools = childTools,
                )
                val messages = try {
                    AgentLoop(streamFn).run(
                        prompts = listOf(LlmMessage.User(task.task)),
                        session = childSession,
                        config = childConfig,
                        emit = emit,
                        isCancelled = isCancelled,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    node.finishedAt = now(); node.ok = false
                    return@withSlot errorResult("Sub-agent failed: ${e.message ?: e.toString()}")
                }
                val last = messages.lastOrNull()
                val failed = last is LlmMessage.Assistant &&
                    (last.stopReason == StopReason.ERROR || last.stopReason == StopReason.ABORTED)
                val text = (last as? LlmMessage.Assistant)?.text.orEmpty()
                node.finishedAt = now(); node.ok = !failed
                if (failed) {
                    errorResult("Sub-agent ended with ${last!!.stopReason}: ${text.ifBlank { "(no output)" }}")
                } else {
                    AgentToolResult(
                        text = text.ifBlank { "(sub-agent produced no text)" },
                        details = mapOf("subAgentId" to id, "depth" to depth.toString()),
                    )
                }
            }
        } finally {
            registry.activeCount.decrementAndGet()
            if (node.finishedAt == null) { node.finishedAt = now(); node.ok = false }
        }
    }

    /**
     * The parent's beforeToolCall is the authority; a child gate may only
     * add restrictions after it. A parent block is never overridden.
     */
    private fun childConfig(parent: AgentLoopConfig, task: SubAgentTask): AgentLoopConfig {
        val parentBefore = parent.beforeToolCall
        val childBefore = task.beforeToolCall
        val combined: (suspend (BeforeToolCallContext) -> BeforeToolCallResult?)? =
            if (parentBefore == null && childBefore == null) null
            else { ctx ->
                val p = parentBefore?.invoke(ctx)
                if (p?.block == true) p
                else childBefore?.invoke(ctx) ?: p
            }
        return parent.copy(
            model = task.model ?: parent.model,
            beforeToolCall = combined,
            // Child context is fresh: parent steering/follow-up queues and
            // turn hooks belong to the parent conversation, not the child.
            getSteeringMessages = null,
            getFollowUpMessages = null,
            prepareNextTurn = null,
            shouldStopAfterTurn = null,
            transformContext = null,
        )
    }

    /** Expose delegation to a child loop as an ordinary tool. */
    fun asTool(
        parentSession: AgentSession,
        parentConfig: AgentLoopConfig,
        isCancelled: () -> Boolean = { false },
    ): AgentTool = object : AgentTool {
        override val definition = spawnToolDefinition(registry.maxDepth - parentDepth)
        override suspend fun execute(
            call: AgentToolCall,
            signal: () -> Boolean,
            onUpdate: (String) -> Unit,
        ): AgentToolResult {
            val taskText = call.arguments.stringArg("task")
            return spawn(
                SubAgentTask(task = taskText),
                parentSession,
                parentConfig,
                isCancelled = { isCancelled() || signal() },
            )
        }
    }

    companion object {
        const val TOOL_NAME = "spawn_subagent"

        fun spawnToolDefinition(remainingDepth: Int): com.anharness.llm.LlmToolDefinition =
            com.anharness.llm.LlmToolDefinition(
                name = TOOL_NAME,
                description = "Delegate a bounded sub-task to a fresh sub-agent with its own context. " +
                    "The sub-agent inherits this session's tools and model, and returns its final answer " +
                    "as this tool's result. Use for self-contained research/analysis that would clutter " +
                    "the main conversation. Remaining delegation depth: $remainingDepth.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("task") {
                            put("type", "string")
                            put("description", "The complete, self-contained task for the sub-agent.")
                        }
                    }
                    putJsonArray("required") { add("task") }
                },
                executionMode = "parallel",
            )
    }
}