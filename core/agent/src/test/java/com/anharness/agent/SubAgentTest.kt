package com.anharness.agent

import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmStreamEvent
import com.anharness.llm.LlmToolDefinition
import com.anharness.llm.ModelDescriptor
import com.anharness.llm.StopReason
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-spawn] Contract tests for sub-agent derivation. Faux StreamFn
 * + fake tools, no network/keys — same style as CoreAgentTest.
 */
class SubAgentTest {

    private val model = ModelDescriptor(
        id = "test", name = "test", api = "openai-completions",
        providerId = "test", baseUrl = null, contextWindow = 8192,
    )

    private fun config(before: (suspend (BeforeToolCallContext) -> BeforeToolCallResult?)? = null) =
        AgentLoopConfig(model = model, apiKey = "k", beforeToolCall = before)

    private fun session(tools: List<AgentTool> = emptyList()) =
        AgentSession(systemPrompt = "parent sys", tools = tools)

    /** Child replies with fixed text, no tool calls. */
    private fun replyFn(text: String): com.anharness.llm.StreamFn = { _, _, _ ->
        flowOf(LlmStreamEvent.Done(LlmMessage.Assistant(text)))
    }

    private class FakeTool(name: String) : AgentTool {
        override val definition = LlmToolDefinition(
            name = name, description = "fake", inputSchema = buildJsonObject { put("type", "object") },
        )
        val seen = mutableListOf<AgentToolCall>()
        override suspend fun execute(call: AgentToolCall, signal: () -> Boolean, onUpdate: (String) -> Unit) =
            AgentToolResult(text = "ok:${call.name}").also { seen.add(call) }
    }

    // ── basic spawn ──────────────────────────────────────────────────────

    @Test
    fun spawnReturnsChildFinalTextAsToolResult() = runTest {
        val runner = SubAgentRunner(replyFn("child answer"))
        val result = runner.spawn(SubAgentTask("do research"), session(), config())
        assertFalse(result.isError)
        assertEquals("child answer", result.text)
        assertNotNull(result.details["subAgentId"])
        assertEquals("1", result.details["depth"])
    }

    @Test
    fun blankTaskIsErrorResultNotThrow() = runTest {
        val runner = SubAgentRunner(replyFn("x"))
        val result = runner.spawn(SubAgentTask("   "), session(), config())
        assertTrue(result.isError)
        assertTrue(result.text.contains("blank"))
    }

    @Test
    fun childErrorStopBecomesErrorResult() = runTest {
        val fn: com.anharness.llm.StreamFn = { _, _, _ ->
            flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("boom", stopReason = StopReason.ERROR)))
        }
        val runner = SubAgentRunner(fn)
        val result = runner.spawn(SubAgentTask("t"), session(), config())
        assertTrue(result.isError)
        assertTrue(result.text.contains("ERROR"))
    }

    @Test
    fun childStreamFailureBecomesErrorResultNotThrow() = runTest {
        val fn: com.anharness.llm.StreamFn = { _, _, _ ->
            flowOf(LlmStreamEvent.Error("upstream down"))
        }
        val runner = SubAgentRunner(fn)
        val result = runner.spawn(SubAgentTask("t"), session(), config())
        assertTrue(result.isError)
    }

    // ── depth limit ──────────────────────────────────────────────────────

    @Test
    fun depthLimitBlocksFurtherDelegation() = runTest {
        val registry = SubAgentRegistry(maxDepth = 2)
        // parentDepth=2 → child would be depth 3 > max 2.
        val runner = SubAgentRunner(replyFn("x"), registry, parentId = "p", parentDepth = 2)
        val result = runner.spawn(SubAgentTask("t"), session(), config())
        assertTrue(result.isError)
        assertTrue(result.text.contains("depth limit"))
    }

    @Test
    fun nestedDelegationStopsAtMaxDepth() = runTest {
        // Child that always tries to delegate further; depth cap must end it.
        val registry = SubAgentRegistry(maxDepth = 2)
        val fn: com.anharness.llm.StreamFn = { _, ctx, _ ->
            // A real model delegates once, then answers after the tool result.
            // Only call spawn when it is available AND no result is back yet.
            val hasSpawn = ctx.tools.any { it.name == SubAgentRunner.TOOL_NAME }
            val hasResult = ctx.messages.any { it is LlmMessage.ToolResult }
            if (hasSpawn && !hasResult) {
                flowOf(
                    LlmStreamEvent.Done(
                        LlmMessage.Assistant(
                            "",
                            listOf(com.anharness.llm.LlmToolCall("c1", SubAgentRunner.TOOL_NAME, buildJsonObject { put("task", "deeper") })),
                        ),
                    ),
                )
            } else {
                flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("leaf")))
            }
        }
        val runner = SubAgentRunner(fn, registry)
        val result = runner.spawn(SubAgentTask("root task"), session(), config())
        // The chain terminates at the depth cap, not a hang.
        assertNotNull(result)
        assertFalse(result.isError)
        assertEquals("leaf", result.text)
        assertTrue(registry.all().size <= 2)
        assertTrue(registry.all().all { it.depth <= 2 })
    }

    // ── concurrency limit ────────────────────────────────────────────────

    @Test
    fun concurrencyIsBounded() = runTest {
        val registry = SubAgentRegistry(maxDepth = 3, maxConcurrency = 2)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        // Track in-flight child loops via the registry's activeCount, sampled
        // inside the (non-suspend) StreamFn while a child holds a slot.
        val fn: com.anharness.llm.StreamFn = { _, _, _ ->
            peak.updateAndGet { maxOf(it, registry.activeCount.get()) }
            flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("done")))
        }
        val runner = SubAgentRunner(fn, registry)
        coroutineScope {
            val jobs = (1..5).map { i ->
                async { runner.spawn(SubAgentTask("t$i"), session(), config()) }
            }
            jobs.forEach { it.await() }
        }
        assertTrue("peak concurrency ${peak.get()} must be <= 2", peak.get() <= 2)
    }

    // ── permission inheritance: tighten-only ─────────────────────────────

    @Test
    fun parentBlockStaysBlockedInChild() = runTest {
        val tool = FakeTool("shell_execute")
        // Child tries to call the tool; parent blocks everything.
        val fn: com.anharness.llm.StreamFn = { _, ctx, _ ->
            if (ctx.messages.none { it is LlmMessage.ToolResult }) {
                flowOf(
                    LlmStreamEvent.Done(
                        LlmMessage.Assistant("", listOf(com.anharness.llm.LlmToolCall("c1", "shell_execute", JsonObject(emptyMap())))),
                    ),
                )
            } else {
                flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("finished")))
            }
        }
        val parentBefore: (suspend (BeforeToolCallContext) -> BeforeToolCallResult?) =
            { BeforeToolCallResult(block = true, reason = "parent denies") }
        val runner = SubAgentRunner(fn)
        runner.spawn(SubAgentTask("t"), session(listOf(tool)), config(parentBefore))
        assertTrue("parent block must prevent child execution", tool.seen.isEmpty())
    }

    @Test
    fun childGateCanTightenButNotLoosen() = runTest {
        val tool = FakeTool("shell_execute")
        val fn: com.anharness.llm.StreamFn = { _, ctx, _ ->
            if (ctx.messages.none { it is LlmMessage.ToolResult }) {
                flowOf(
                    LlmStreamEvent.Done(
                        LlmMessage.Assistant("", listOf(com.anharness.llm.LlmToolCall("c1", "shell_execute", JsonObject(emptyMap())))),
                    ),
                )
            } else {
                flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("finished")))
            }
        }
        // Parent allows; child gate blocks. Net: blocked (tighten allowed).
        val runner = SubAgentRunner(fn)
        runner.spawn(
            SubAgentTask("t", beforeToolCall = { BeforeToolCallResult(block = true, reason = "child denies") }),
            session(listOf(tool)),
            config(before = null),
        )
        assertTrue(tool.seen.isEmpty())
    }

    @Test
    fun childGateCannotOverrideParentBlock() = runTest {
        val tool = FakeTool("shell_execute")
        val fn: com.anharness.llm.StreamFn = { _, ctx, _ ->
            if (ctx.messages.none { it is LlmMessage.ToolResult }) {
                flowOf(
                    LlmStreamEvent.Done(
                        LlmMessage.Assistant("", listOf(com.anharness.llm.LlmToolCall("c1", "shell_execute", JsonObject(emptyMap())))),
                    ),
                )
            } else {
                flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("finished")))
            }
        }
        // Parent blocks; child gate tries to allow (returns null). Must stay blocked.
        val runner = SubAgentRunner(fn)
        runner.spawn(
            SubAgentTask("t", beforeToolCall = { null }),
            session(listOf(tool)),
            config(before = { BeforeToolCallResult(block = true, reason = "parent denies") }),
        )
        assertTrue(tool.seen.isEmpty())
    }

    @Test
    fun allowedToolRunsInChild() = runTest {
        val tool = FakeTool("shell_execute")
        val fn: com.anharness.llm.StreamFn = { _, ctx, _ ->
            if (ctx.messages.none { it is LlmMessage.ToolResult }) {
                flowOf(
                    LlmStreamEvent.Done(
                        LlmMessage.Assistant("", listOf(com.anharness.llm.LlmToolCall("c1", "shell_execute", JsonObject(emptyMap())))),
                    ),
                )
            } else {
                flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("finished")))
            }
        }
        val runner = SubAgentRunner(fn)
        val result = runner.spawn(SubAgentTask("t"), session(listOf(tool)), config())
        assertEquals(1, tool.seen.size)
        assertFalse(result.isError)
    }

    // ── model follows parent ─────────────────────────────────────────────

    @Test
    fun childFollowsParentModelByDefault() = runTest {
        var seenModel: String? = null
        val fn: com.anharness.llm.StreamFn = { m, _, _ ->
            seenModel = m.id
            flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("ok")))
        }
        val runner = SubAgentRunner(fn)
        runner.spawn(SubAgentTask("t"), session(), config())
        assertEquals("test", seenModel)
    }

    // ── lineage registry ─────────────────────────────────────────────────

    @Test
    fun lineageRecordsParentChildAndDepth() = runTest {
        val registry = SubAgentRegistry(maxDepth = 3)
        val runner = SubAgentRunner(replyFn("ok"), registry, parentId = "root", parentDepth = 0)
        val result = runner.spawn(SubAgentTask("task A"), session(), config())
        val id = result.details["subAgentId"]!!
        val node = registry.get(id)!!
        assertEquals("root", node.parentId)
        assertEquals(1, node.depth)
        assertEquals("task A", node.task)
        assertNotNull(node.finishedAt)
        assertEquals(true, node.ok)
        assertEquals(1, registry.childrenOf("root").size)
    }

    @Test
    fun spawnToolDefinitionCarriesRemainingDepth() {
        val def = SubAgentRunner.spawnToolDefinition(remainingDepth = 2)
        assertEquals(SubAgentRunner.TOOL_NAME, def.name)
        assertTrue(def.description.contains("2"))
    }

    @Test
    fun cancelledSpawnReturnsAbortedError() = runTest {
        val runner = SubAgentRunner(replyFn("x"))
        val result = runner.spawn(SubAgentTask("t"), session(), config(), isCancelled = { true })
        assertTrue(result.isError)
        assertTrue(result.text.contains("aborted"))
    }
}