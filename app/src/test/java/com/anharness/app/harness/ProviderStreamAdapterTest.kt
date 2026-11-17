package com.anharness.app.harness

import com.anharness.app.data.model.AgentToolDefinition
import com.anharness.app.data.model.LLMMessage
import com.anharness.app.data.model.LLMModel
import com.anharness.app.data.model.LLMResponse
import com.anharness.app.data.model.LLMStreamChunk
import com.anharness.app.data.model.LLMUsage
import com.anharness.app.data.model.ThinkingLevel
import com.anharness.app.provider.LLMProvider
import com.anharness.llm.LlmContext
import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmStreamEvent
import com.anharness.llm.LlmToolCall
import com.anharness.llm.LlmToolDefinition
import com.anharness.llm.ModelDescriptor
import com.anharness.llm.StopReason
import com.anharness.llm.StreamOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-app-wiring] Translation-contract tests for the provider→core
 * stream adapter. Fake provider, no network — mirrors CompactionAdapterTest.
 */
class ProviderStreamAdapterTest {

    private open class FakeProvider(
        private val chunks: List<LLMStreamChunk>,
    ) : LLMProvider {
        override val name = "fake"
        override var model = LLMModel(id = "m", displayName = "M", provider = "fake", contextWindow = 8192)
        var seenMessages: List<LLMMessage> = emptyList()
        var seenTools: List<AgentToolDefinition> = emptyList()
        var seenSystem: String? = null
        var seenThinking: ThinkingLevel? = null

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): LLMResponse = LLMResponse("", "stop", null)

        override fun streamMessageClamped(
            messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> {
            seenMessages = messages
            seenTools = tools
            seenSystem = systemPrompt
            seenThinking = thinkingLevel
            return flowOf(*chunks.toTypedArray())
        }
    }

    private val model = ModelDescriptor(
        id = "m", name = "M", api = "openai-completions", providerId = "fake",
        baseUrl = null, contextWindow = 8192,
    )

    private fun ctx(vararg messages: LlmMessage, tools: List<LlmToolDefinition> = emptyList()) =
        LlmContext(systemPrompt = "sys", messages = messages.toList(), tools = tools)

    private fun options() = StreamOptions(apiKey = "k")

    @Test
    fun textChunksBecomeDeltasAndDone() = runTest {
        val provider = FakeProvider(
            listOf(
                LLMStreamChunk.Started,
                LLMStreamChunk.Text("Hello"),
                LLMStreamChunk.Text(" world"),
                LLMStreamChunk.Finished("stop"),
            ),
        )
        val events = ProviderStreamAdapter.streamFn(provider)(model, ctx(LlmMessage.User("hi")), options()).toList()
        val deltas = events.filterIsInstance<LlmStreamEvent.TextDelta>()
        assertEquals(listOf("Hello", " world"), deltas.map { it.delta })
        val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
        assertEquals("Hello world", done.message.text)
        assertEquals(StopReason.STOP, done.message.stopReason)
    }

    @Test
    fun toolCallCompleteBecomesCoreToolCall() = runTest {
        val provider = FakeProvider(
            listOf(
                LLMStreamChunk.ToolCallComplete("c1", "file_read", org.json.JSONObject().put("path", "/a.txt")),
                LLMStreamChunk.Finished("tool_calls"),
            ),
        )
        val events = ProviderStreamAdapter.streamFn(provider)(model, ctx(LlmMessage.User("read")), options()).toList()
        val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
        assertEquals(1, done.message.toolCalls.size)
        val call = done.message.toolCalls[0]
        assertEquals("c1", call.id)
        assertEquals("file_read", call.name)
        assertEquals("/a.txt", (call.arguments["path"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun lengthStopMapsToLength() = runTest {
        val provider = FakeProvider(listOf(LLMStreamChunk.Text("trunc"), LLMStreamChunk.Finished("max_tokens")))
        val events = ProviderStreamAdapter.streamFn(provider)(model, ctx(LlmMessage.User("x")), options()).toList()
        assertEquals(StopReason.LENGTH, events.filterIsInstance<LlmStreamEvent.Done>().single().message.stopReason)
    }

    @Test
    fun usageCarriesFullContextTokens() = runTest {
        val provider = FakeProvider(
            listOf(
                LLMStreamChunk.Text("ok"),
                LLMStreamChunk.Usage(LLMUsage(inputTokens = 50, outputTokens = 10, latestContextTokens = 900)),
                LLMStreamChunk.Finished("stop"),
            ),
        )
        val events = ProviderStreamAdapter.streamFn(provider)(model, ctx(LlmMessage.User("x")), options()).toList()
        val usage = events.filterIsInstance<LlmStreamEvent.Done>().single().message.usage
        assertEquals(900, usage?.inputTokens)
        assertEquals(10, usage?.outputTokens)
    }

    @Test
    fun providerFailureBecomesErrorEventNotThrow() = runTest {
        val provider = object : FakeProvider(emptyList()) {
            override fun streamMessageClamped(
                messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
                temperature: Double?, imageParts: List<LLMMessage.ImagePart>,
                tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel,
            ): Flow<LLMStreamChunk> = throw RuntimeException("upstream down")
        }
        val events = ProviderStreamAdapter.streamFn(provider)(model, ctx(LlmMessage.User("x")), options()).toList()
        assertTrue(events.any { it is LlmStreamEvent.Error })
        assertTrue(events.none { it is LlmStreamEvent.Done })
    }

    @Test
    fun coreHistoryTranslatesToAppMessages() = runTest {
        val provider = FakeProvider(listOf(LLMStreamChunk.Finished("stop")))
        val history = listOf(
            LlmMessage.User("read it"),
            LlmMessage.Assistant("reading", listOf(LlmToolCall("c1", "file_read", buildJsonObject { put("path", "/a") }))),
            LlmMessage.ToolResult("c1", "file_read", "content"),
        )
        ProviderStreamAdapter.streamFn(provider)(model, ctx(*history.toTypedArray()), options()).toList()
        assertEquals(3, provider.seenMessages.size)
        assertEquals(LLMMessage.Role.USER, provider.seenMessages[0].role)
        assertEquals(LLMMessage.Role.ASSISTANT, provider.seenMessages[1].role)
        assertEquals(1, provider.seenMessages[1].contentParts.size)
        assertEquals(LLMMessage.Role.USER, provider.seenMessages[2].role)
        assertEquals("sys", provider.seenSystem)
    }

    @Test
    fun coreToolDefinitionTranslatesToAppSchema() = runTest {
        val provider = FakeProvider(listOf(LLMStreamChunk.Finished("stop")))
        val tool = LlmToolDefinition(
            name = "spawn_subagent",
            description = "delegate",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("task", buildJsonObject { put("type", "string"); put("description", "the task") })
                })
                put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("task"))))
            },
        )
        ProviderStreamAdapter.streamFn(provider)(model, ctx(LlmMessage.User("x"), tools = listOf(tool)), options()).toList()
        assertEquals(1, provider.seenTools.size)
        val def = provider.seenTools[0]
        assertEquals("spawn_subagent", def.name)
        assertEquals("string", def.parameters["task"]?.type)
        assertEquals(listOf("task"), def.required)
    }

    @Test
    fun thinkingLevelMapsToAppEnum() = runTest {
        val provider = FakeProvider(listOf(LLMStreamChunk.Finished("stop")))
        ProviderStreamAdapter.streamFn(provider)(
            model, ctx(LlmMessage.User("x")),
            StreamOptions(apiKey = "k", thinking = com.anharness.llm.ThinkingLevel.HIGH),
        ).toList()
        assertEquals(ThinkingLevel.HIGH, provider.seenThinking)
    }

    @Test
    fun descriptorMirrorsProviderModel() {
        val provider = FakeProvider(emptyList())
        val d = ProviderStreamAdapter.descriptor(provider)
        assertEquals("m", d.id)
        assertEquals(8192, d.contextWindow)
    }
}