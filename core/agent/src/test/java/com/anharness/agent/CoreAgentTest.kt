package com.anharness.agent

import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmStreamEvent
import com.anharness.llm.LlmToolDefinition
import com.anharness.llm.ModelDescriptor
import com.anharness.llm.StopReason
import com.anharness.llm.stringArg
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

private val fakeModel = ModelDescriptor(
    id = "test-model",
    name = "Test",
    api = "openai-completions",
    providerId = "test",
    baseUrl = null,
    contextWindow = 8192,
)

private fun toolDef(name: String, mode: String = "parallel") = LlmToolDefinition(
    name = name,
    description = "test tool $name",
    inputSchema = buildJsonObject {
        put("type", "object")
    },
    executionMode = mode,
)

private class FakeTool(
    override val definition: LlmToolDefinition,
    private val reply: String = "ok",
) : AgentTool {
    val seen = mutableListOf<AgentToolCall>()
    override suspend fun execute(call: AgentToolCall, signal: () -> Boolean, onUpdate: (String) -> Unit) =
        AgentToolResult(text = "$reply:${call.name}").also { seen.add(call) }
}

class AgentLoopTest {
    @Test
    fun doneUsageSurvivesOntoSessionAssistantMessage() = runTest {
        val session = AgentSession(systemPrompt = "sys", tools = emptyList())
        val loop = AgentLoop { _, _, _ ->
            flowOf(
                LlmStreamEvent.TextDelta("partial"),
                LlmStreamEvent.Done(
                    LlmMessage.Assistant(
                        "final",
                        usage = com.anharness.llm.LlmUsage(inputTokens = 1500, outputTokens = 60),
                    ),
                ),
            )
        }
        val out = loop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(model = fakeModel, apiKey = "k"),
            emit = {},
        )
        val last = out.last() as LlmMessage.Assistant
        assertEquals(1500, last.usage?.inputTokens)
        assertEquals(60, last.usage?.outputTokens)
        // The session copy (what compaction later sees) carries it too.
        val persisted = session.messages.filterIsInstance<LlmMessage.Assistant>().last()
        assertEquals(1500, persisted.usage?.inputTokens)
    }

    @Test
    fun runsToolsAndEmitsEvents() = runTest {
        val tool = FakeTool(toolDef("shell_execute"))
        val session = AgentSession(systemPrompt = "sys", tools = listOf(tool))
        // Queue: first turn returns a tool call, subsequent turns plain text.
        var n = 0
        val queueLoop = AgentLoop { _, _, _ ->
            flowOf(
                LlmStreamEvent.Done(
                    if (n++ == 0) {
                        LlmMessage.Assistant(
                            "run it",
                            listOf(com.anharness.llm.LlmToolCall("1", "shell_execute", JsonObject(emptyMap()))),
                        )
                    } else {
                        LlmMessage.Assistant("finished")
                    },
                ),
            )
        }
        val events = mutableListOf<AgentEvent>()
        val out = queueLoop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(model = fakeModel, apiKey = "k"),
            emit = { events.add(it) },
        )
        assertEquals(1, tool.seen.size)
        assertTrue(events.any { it is AgentEvent.ToolExecutionEnd })
        assertTrue(out.last() is LlmMessage.Assistant)
        assertEquals("finished", (out.last() as LlmMessage.Assistant).text)
    }

    @Test
    fun beforeToolCallBlockPreventsExecution() = runTest {
        val tool = FakeTool(toolDef("shell_execute"))
        val session = AgentSession(systemPrompt = "sys", tools = listOf(tool))
        var n = 0
        val loop = AgentLoop { _, _, _ ->
            flowOf(
                LlmStreamEvent.Done(
                    if (n++ == 0) {
                        LlmMessage.Assistant(
                            "run",
                            listOf(com.anharness.llm.LlmToolCall("1", "shell_execute", JsonObject(emptyMap()))),
                        )
                    } else {
                        LlmMessage.Assistant("stopped")
                    },
                ),
            )
        }
        loop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(
                model = fakeModel,
                apiKey = "k",
                beforeToolCall = { BeforeToolCallResult(block = true, reason = "denied") },
            ),
            emit = {},
        )
        assertTrue(tool.seen.isEmpty())
        val lastToolResult = session.messages.filterIsInstance<LlmMessage.ToolResult>().lastOrNull()
        assertNotNull(lastToolResult)
        assertTrue(lastToolResult!!.isError)
        assertTrue(lastToolResult.text.contains("denied"))
    }

    @Test
    fun lengthTruncationFailsToolCallsWithoutExecuting() = runTest {
        val tool = FakeTool(toolDef("shell_execute"))
        val session = AgentSession(systemPrompt = "sys", tools = listOf(tool))
        var n = 0
        val loop = AgentLoop { _, _, _ ->
            flowOf(
                LlmStreamEvent.Done(
                    if (n++ == 0) {
                        LlmMessage.Assistant(
                            "partial",
                            listOf(com.anharness.llm.LlmToolCall("1", "shell_execute", JsonObject(emptyMap()))),
                            StopReason.LENGTH,
                        )
                    } else {
                        LlmMessage.Assistant("recovered")
                    },
                ),
            )
        }
        loop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(model = fakeModel, apiKey = "k"),
            emit = {},
        )
        assertTrue(tool.seen.isEmpty())
        assertTrue(session.messages.filterIsInstance<LlmMessage.ToolResult>().any {
            it.text.contains("output token limit")
        })
    }

    @Test
    fun unknownToolProducesErrorResult() = runTest {
        val session = AgentSession(systemPrompt = "sys", tools = emptyList())
        var n = 0
        val loop = AgentLoop { _, _, _ ->
            flowOf(
                LlmStreamEvent.Done(
                    if (n++ == 0) {
                        LlmMessage.Assistant(
                            "oops",
                            listOf(com.anharness.llm.LlmToolCall("1", "nope", JsonObject(emptyMap()))),
                        )
                    } else {
                        LlmMessage.Assistant("ok")
                    },
                ),
            )
        }
        loop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(model = fakeModel, apiKey = "k"),
            emit = {},
        )
        assertTrue(session.messages.filterIsInstance<LlmMessage.ToolResult>().single().isError)
    }

    @Test
    fun schemaViolationFailsCallBeforeExecution() = runTest {
        val def = LlmToolDefinition(
            name = "file_read",
            description = "read",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                })
                put("required", kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonPrimitive("path"))))
            },
        )
        val tool = FakeTool(def)
        val session = AgentSession(systemPrompt = "sys", tools = listOf(tool))
        var n = 0
        val loop = AgentLoop { _, _, _ ->
            flowOf(
                LlmStreamEvent.Done(
                    if (n++ == 0) {
                        LlmMessage.Assistant(
                            "read",
                            listOf(com.anharness.llm.LlmToolCall("1", "file_read", JsonObject(emptyMap()))),
                        )
                    } else {
                        LlmMessage.Assistant("done")
                    },
                ),
            )
        }
        loop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(model = fakeModel, apiKey = "k"),
            emit = {},
        )
        assertTrue(tool.seen.isEmpty()) // never executed
        val result = session.messages.filterIsInstance<LlmMessage.ToolResult>().single()
        assertTrue(result.isError)
        assertTrue(result.text.contains("missing required argument \"path\""))
    }

    @Test
    fun transformContextCanRewriteMessagesAndSystemPrompt() = runTest {
        val seenSystemPrompts = mutableListOf<String>()
        val session = AgentSession(systemPrompt = "original sys")
        val loop = AgentLoop { _, ctx, _ ->
            seenSystemPrompts.add(ctx.systemPrompt)
            flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("ok")))
        }
        loop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(
                model = fakeModel,
                apiKey = "k",
                transformContext = { input ->
                    ContextTransformResult(
                        messages = input.messages + LlmMessage.User("[injected]"),
                        systemPrompt = input.systemPrompt + " +extra",
                    )
                },
            ),
            emit = {},
        )
        assertEquals(listOf("original sys +extra"), seenSystemPrompts)
    }

    @Test
    fun beforeRequestPatchesStreamOptions() = runTest {
        val seenMaxTokens = mutableListOf<Int>()
        val session = AgentSession(systemPrompt = "sys")
        val loop = AgentLoop { _, _, opts ->
            seenMaxTokens.add(opts.maxTokens)
            flowOf(LlmStreamEvent.Done(LlmMessage.Assistant("ok")))
        }
        loop.run(
            prompts = listOf(LlmMessage.User("hi")),
            session = session,
            config = AgentLoopConfig(
                model = fakeModel,
                apiKey = "k",
                maxTokens = 4096,
                beforeRequest = { it.copy(maxTokens = 512) },
            ),
            emit = {},
        )
        assertEquals(listOf(512), seenMaxTokens)
    }
}

class AgentSessionStoreTest {
    @Test
    fun createAppendForkClone() {
        val root = Files.createTempDirectory("anharness-sessions").toFile()
        try {
            val store = AgentSessionStore(root)
            val id = store.create("t")
            store.append(id, LlmMessage.User("hello"))
            store.append(id, LlmMessage.Assistant("world"))
            assertEquals(2, store.load(id).size)

            val fork = store.fork(id, uptoIndex = 1, title = "f")
            assertEquals(1, store.load(fork).size)

            val clone = store.clone(id)
            assertEquals(2, store.load(clone).size)
            assertEquals(3, store.list().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun assistantToolCallsAndUsageRoundTrip() {
        val root = Files.createTempDirectory("anharness-sessions").toFile()
        try {
            val store = AgentSessionStore(root)
            val id = store.create("t")
            val call = com.anharness.llm.LlmToolCall(
                "call_1", "file_read",
                kotlinx.serialization.json.buildJsonObject { put("path", "/a.txt") },
            )
            val assistant = LlmMessage.Assistant(
                "reading", listOf(call), StopReason.STOP,
                usage = com.anharness.llm.LlmUsage(inputTokens = 900, outputTokens = 40),
            )
            store.append(id, LlmMessage.User("read /a.txt"))
            store.append(id, assistant)
            store.append(id, LlmMessage.ToolResult("call_1", "file_read", "content"))

            val loaded = store.load(id)
            assertEquals(3, loaded.size)
            val back = loaded[1] as LlmMessage.Assistant
            assertEquals(assistant, back) // full equality incl. args JSON + usage
            // The chain is intact: result id matches a persisted call id.
            val result = loaded[2] as LlmMessage.ToolResult
            assertTrue(back.toolCalls.any { it.id == result.toolCallId })

            // Fork carries the calls too (used to lose them silently).
            val fork = store.fork(id, title = "f")
            val forked = store.load(fork)[1] as LlmMessage.Assistant
            assertEquals(1, forked.toolCalls.size)
            assertEquals("/a.txt", forked.toolCalls[0].arguments.stringArg("path"))
            assertEquals(900, forked.usage?.inputTokens)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyRowsWithoutToolCallsStillDecode() {
        val root = Files.createTempDirectory("anharness-sessions").toFile()
        try {
            val store = AgentSessionStore(root)
            val id = store.create("t")
            // Hand-write a pre-fix assistant row (no toolCalls/usage keys).
            File(File(root, id), "messages.jsonl").writeText(
                """{"role":"assistant","text":"old","stopReason":"STOP"}""" + "\n",
            )
            val loaded = store.load(id)
            assertEquals(1, loaded.size)
            val a = loaded[0] as LlmMessage.Assistant
            assertEquals("old", a.text)
            assertTrue(a.toolCalls.isEmpty())
            assertNull(a.usage)
        } finally {
            root.deleteRecursively()
        }
    }
}

class SkillStoreTest {
    @Test
    fun listsAndMatchesByMetadataOnly() {
        val root = Files.createTempDirectory("anharness-skills").toFile()
        try {
            val dir = File(root, "pdf-scan").apply { mkdirs() }
            File(dir, "SKILL.md").writeText("# PDF Scan\ndescription: scan PDFs and extract tables\n\nBODY ".repeat(50))
            val store = SkillStore(listOf(root))
            assertEquals(1, store.list().size)
            assertEquals(1, store.match("extract tables from PDF").size)
            assertTrue(store.match("unrelated weather query").isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
