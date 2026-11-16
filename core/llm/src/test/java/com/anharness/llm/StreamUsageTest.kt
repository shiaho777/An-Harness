package com.anharness.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Usage extraction from SSE streams. The compaction pipeline anchors its
 * token estimate on Assistant.usage, so a silent parse miss here means
 * CJK-heavy chats under-count context and skip compaction — these tests
 * pin the wire shapes for both implemented protocols.
 *
 * MockWebServer only; no real endpoints, no keys.
 */
class StreamUsageTest {

    private fun sse(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun model(api: String, baseUrl: String) = ModelDescriptor(
        id = "test", name = "test", api = api, providerId = "test",
        baseUrl = baseUrl, contextWindow = 8192,
    )

    private fun context() = LlmContext("sys", listOf(LlmMessage.User("hi")))

    private fun options() = StreamOptions(apiKey = "test-key")

    @Test
    fun anthropicUsageCombinesInputCacheCountersAndFinalOutput() = runTest {
        val server = MockWebServer()
        server.enqueue(
            sse(
                """{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_creation_input_tokens":20,"cache_read_input_tokens":800,"output_tokens":1}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
                """{"type":"message_stop"}""",
            ),
        )
        server.start()
        try {
            val events = ModelStreamer().stream(
                model(LlmApi.ANTHROPIC_MESSAGES, server.url("/").toString()),
                context(), options(),
            ).toList()
            val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
            assertEquals("你好", done.message.text)
            // Full prompt = uncached + cache-write + cache-read = 920.
            assertEquals(920, done.message.usage?.inputTokens)
            assertEquals(42, done.message.usage?.outputTokens)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicWithoutUsageEventsKeepsNull() = runTest {
        val server = MockWebServer()
        server.enqueue(
            sse(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                """{"type":"message_stop"}""",
            ),
        )
        server.start()
        try {
            val events = ModelStreamer().stream(
                model(LlmApi.ANTHROPIC_MESSAGES, server.url("/").toString()),
                context(), options(),
            ).toList()
            val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
            assertNull(done.message.usage)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun openAiUsageChunkParsedAndIncludeUsageRequested() = runTest {
        val server = MockWebServer()
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":30}}""",
                "[DONE]",
            ),
        )
        server.start()
        try {
            val events = ModelStreamer().stream(
                model(LlmApi.OPENAI_COMPLETIONS, server.url("/").toString()),
                context(), options(),
            ).toList()
            val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
            assertEquals("hello", done.message.text)
            assertEquals(StopReason.STOP, done.message.stopReason)
            assertEquals(1200, done.message.usage?.inputTokens)
            assertEquals(30, done.message.usage?.outputTokens)
            // The request must ask for the usage chunk explicitly.
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"include_usage\":true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun openAiGatewayWithoutUsageChunkKeepsNull() = runTest {
        val server = MockWebServer()
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        server.start()
        try {
            val events = ModelStreamer().stream(
                model(LlmApi.OPENAI_COMPLETIONS, server.url("/").toString()),
                context(), options(),
            ).toList()
            val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
            assertNull(done.message.usage)
            assertEquals("hi", done.message.text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun openAiLengthStopSurvivesWithUsage() = runTest {
        val server = MockWebServer()
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"content":"trunc"},"finish_reason":"length"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4096}}""",
                "[DONE]",
            ),
        )
        server.start()
        try {
            val events = ModelStreamer().stream(
                model(LlmApi.OPENAI_COMPLETIONS, server.url("/").toString()),
                context(), options(),
            ).toList()
            val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
            assertEquals(StopReason.LENGTH, done.message.stopReason)
            assertEquals(4096, done.message.usage?.outputTokens)
        } finally {
            server.shutdown()
        }
    }

    // ── tool-history wire shapes ([T-core-tool-history]) ───────────

    private fun toolHistory() = LlmContext(
        "sys",
        listOf(
            LlmMessage.User("read it"),
            LlmMessage.Assistant(
                "reading",
                listOf(LlmToolCall("call_1", "file_read", buildJsonObject { put("path", "/a.txt") })),
            ),
            LlmMessage.ToolResult("call_1", "file_read", "content"),
        ),
    )

    private fun requestBody(server: MockWebServer): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun anthropicHistoryEchoesToolUseBlocks() = runTest {
        val server = MockWebServer()
        server.enqueue(sse("""{"type":"message_stop"}"""))
        server.start()
        try {
            ModelStreamer().stream(
                model(LlmApi.ANTHROPIC_MESSAGES, server.url("/").toString()),
                toolHistory(), options(),
            ).toList()
            val messages = requestBody(server)["messages"]!!.jsonArray
            assertEquals(3, messages.size)
            // Assistant turn: text block first, then tool_use.
            val assistant = messages[1].jsonObject
            assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
            val blocks = assistant["content"]!!.jsonArray
            assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
            val toolUse = blocks[1].jsonObject
            assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
            assertEquals("call_1", toolUse["id"]!!.jsonPrimitive.content)
            assertEquals("file_read", toolUse["name"]!!.jsonPrimitive.content)
            assertEquals("/a.txt", toolUse["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)
            // ToolResult: user message with a tool_result block.
            val result = messages[2].jsonObject
            assertEquals("user", result["role"]!!.jsonPrimitive.content)
            val rb = result["content"]!!.jsonArray[0].jsonObject
            assertEquals("tool_result", rb["type"]!!.jsonPrimitive.content)
            assertEquals("call_1", rb["tool_use_id"]!!.jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicParallelResultsMergeIntoOneUserMessage() = runTest {
        val server = MockWebServer()
        server.enqueue(sse("""{"type":"message_stop"}"""))
        server.start()
        try {
            val ctx = LlmContext(
                "sys",
                listOf(
                    LlmMessage.Assistant(
                        "",
                        listOf(
                            LlmToolCall("c1", "t", JsonObject(emptyMap())),
                            LlmToolCall("c2", "t", JsonObject(emptyMap())),
                        ),
                    ),
                    LlmMessage.ToolResult("c1", "t", "r1"),
                    LlmMessage.ToolResult("c2", "t", "r2"),
                    LlmMessage.User("next"),
                ),
            )
            ModelStreamer().stream(
                model(LlmApi.ANTHROPIC_MESSAGES, server.url("/").toString()),
                ctx, options(),
            ).toList()
            val messages = requestBody(server)["messages"]!!.jsonArray
            // assistant + ONE merged user(tool_result x2) + user(next)
            assertEquals(3, messages.size)
            assertEquals(2, messages[1].jsonObject["content"]!!.jsonArray.size)
            assertEquals("next", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun openAiHistoryEchoesToolCalls() = runTest {
        val server = MockWebServer()
        server.enqueue(sse("[DONE]"))
        server.start()
        try {
            ModelStreamer().stream(
                model(LlmApi.OPENAI_COMPLETIONS, server.url("/").toString()),
                toolHistory(), options(),
            ).toList()
            val messages = requestBody(server)["messages"]!!.jsonArray
            // system + user + assistant + tool
            assertEquals(4, messages.size)
            val assistant = messages[2].jsonObject
            val calls = assistant["tool_calls"]!!.jsonArray
            assertEquals(1, calls.size)
            val call = calls[0].jsonObject
            assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
            assertEquals("function", call["type"]!!.jsonPrimitive.content)
            val fn = call["function"]!!.jsonObject
            assertEquals("file_read", fn["name"]!!.jsonPrimitive.content)
            // arguments is a JSON STRING on the wire.
            val args = Json.parseToJsonElement(fn["arguments"]!!.jsonPrimitive.content).jsonObject
            assertEquals("/a.txt", args["path"]!!.jsonPrimitive.content)
            assertEquals("tool", messages[3].jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("call_1", messages[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicMaxTokensMapsToLengthStop() = runTest {
        val server = MockWebServer()
        server.enqueue(
            sse(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"c1","name":"shell_execute"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"trunc"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4096}}""",
                """{"type":"message_stop"}""",
            ),
        )
        server.start()
        try {
            val events = ModelStreamer().stream(
                model(LlmApi.ANTHROPIC_MESSAGES, server.url("/").toString()),
                context(), options(),
            ).toList()
            val done = events.filterIsInstance<LlmStreamEvent.Done>().single()
            // Truncation must surface as LENGTH so the loop fails the batch
            // instead of executing half-streamed arguments.
            assertEquals(StopReason.LENGTH, done.message.stopReason)
            assertEquals(1, done.message.toolCalls.size)
        } finally {
            server.shutdown()
        }
    }
}