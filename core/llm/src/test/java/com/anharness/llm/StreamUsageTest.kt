package com.anharness.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
}