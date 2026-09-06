package com.anharness.agent

import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmStreamEvent
import com.anharness.llm.LlmToolDefinition
import com.anharness.llm.ModelDescriptor
import com.anharness.llm.StopReason
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
