package com.anharness.app.harness

import com.anharness.app.data.model.AgentContentPart
import com.anharness.app.data.model.AgentToolDefinition
import com.anharness.app.data.model.LLMMessage
import com.anharness.app.data.model.LLMModel
import com.anharness.app.data.model.LLMResponse
import com.anharness.app.data.model.LLMStreamChunk
import com.anharness.app.data.model.ThinkingLevel
import com.anharness.app.provider.LLMProvider
import com.anharness.llm.LlmMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactionAdapterTest {

    @Test
    fun toLlmMessagesFlattensToolCallsAndResults() {
        val userMsg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "read this file",
        )
        val assistantMsg = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "reading now",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    id = "call_1",
                    name = "file_read",
                    input = JSONObject(mapOf("path" to "/a.txt")),
                ),
            ),
        )
        val toolResultMsg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "call_1",
                    name = "file_read",
                    content = "file content here",
                ),
            ),
        )

        val core = CompactionAdapter.toLlmMessages(listOf(userMsg, assistantMsg, toolResultMsg))
        assertEquals(3, core.size)
        assertTrue(core[0] is LlmMessage.User)
        assertEquals("read this file", (core[0] as LlmMessage.User).text)

        assertTrue(core[1] is LlmMessage.Assistant)
        val assistant = core[1] as LlmMessage.Assistant
        assertEquals(1, assistant.toolCalls.size)
        assertEquals("file_read", assistant.toolCalls[0].name)

        assertTrue(core[2] is LlmMessage.ToolResult)
        val tr = core[2] as LlmMessage.ToolResult
        assertEquals("file_read", tr.toolName)
        assertEquals("file content here", tr.text)
    }

    private open class FakeProvider(var reply: String = "SUMMARY") : LLMProvider {
        override val name: String = "fake"
        override var model: LLMModel = LLMModel(id = "m", displayName = "M", provider = "fake")
        val calls = mutableListOf<String>()

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse {
            calls.add(messages.first().content)
            return LLMResponse(text = reply, stopReason = "stop", usage = null)
        }

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = emptyFlow()
    }

    private class ThrowingProvider : FakeProvider() {
        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = throw RuntimeException("provider down")
    }

    @Test
    fun summarizeAllGeneratesStructuredSummaryThroughProvider() = runTest {
        val provider = FakeProvider(reply = "## Goal\nTest\n\n## Progress\n### Done\n- [x] Tested")
        val messages = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "do something"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "doing",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "1", name = "file_write",
                        input = JSONObject(mapOf("path" to "/out.txt")),
                    ),
                ),
            ),
        )
        val summary = CompactionAdapter.summarizeAll(messages, previousSummary = null, provider = provider)
        assertNotNull(summary)
        assertTrue(summary!!.contains("## Goal"))
        // File-op inventory must be appended.
        assertTrue(summary.contains("## Files Touched"))
        assertTrue(summary.contains("/out.txt"))
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun summarizeAllPropagatesPreviousSummaryForIterativeUpdate() = runTest {
        val provider = FakeProvider()
        val messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "next turn"))
        CompactionAdapter.summarizeAll(messages, previousSummary = "PREV_SUMMARY", provider = provider)
        assertTrue(provider.calls.single().contains("<previous-summary>\nPREV_SUMMARY\n</previous-summary>"))
    }

    @Test
    fun summarizeAllReturnsNullWhenProviderThrows() = runTest {
        val messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi"))
        val summary = CompactionAdapter.summarizeAll(messages, null, ThrowingProvider())
        assertNull(summary) // graceful degradation
    }

    // ── usage anchor injection ─────────────────────────────────────

    @Test
    fun withUsageAnchorStampsLastAssistantOnly() {
        val core = listOf(
            LlmMessage.User("q1"),
            LlmMessage.Assistant("a1"),
            LlmMessage.User("q2"),
            LlmMessage.Assistant("a2"),
            LlmMessage.ToolResult("id", "file_read", "content"),
        )
        val anchored = CompactionAdapter.withUsageAnchor(core, latestContextTokens = 12_000)
        assertNull((anchored[1] as LlmMessage.Assistant).usage)
        assertEquals(12_000, (anchored[3] as LlmMessage.Assistant).usage?.inputTokens)
        // Original list untouched (no shared mutation).
        assertNull((core[3] as LlmMessage.Assistant).usage)
        // Anchored estimate now dominates the heuristic.
        assertEquals(
            12_000 + com.anharness.agent.estimateTokens(core[4]),
            com.anharness.agent.estimateContextTokensAnchored(anchored),
        )
    }

    @Test
    fun withUsageAnchorNoopOnMissingOrNonPositiveCount() {
        val core = listOf(LlmMessage.User("q"), LlmMessage.Assistant("a"))
        assertSame(core, CompactionAdapter.withUsageAnchor(core, null))
        assertSame(core, CompactionAdapter.withUsageAnchor(core, 0))
        assertSame(core, CompactionAdapter.withUsageAnchor(core, -5))
        // No assistant message → nothing to stamp.
        val usersOnly = listOf(LlmMessage.User("q"))
        assertSame(usersOnly, CompactionAdapter.withUsageAnchor(usersOnly, 999))
    }

    @Test
    fun summarizeAllAcceptsUsageAnchorWithoutBehaviorChange() = runTest {
        val provider = FakeProvider()
        val messages = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "问题"),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "回答"),
        )
        val summary = CompactionAdapter.summarizeAll(
            messages, previousSummary = null, provider = provider, latestContextTokens = 8_000,
        )
        assertNotNull(summary)
        assertEquals(1, provider.calls.size)
    }
}
