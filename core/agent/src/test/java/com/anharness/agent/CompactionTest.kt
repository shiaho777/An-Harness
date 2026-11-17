package com.anharness.agent

import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CompactionTest {

    private fun user(chars: Int) = LlmMessage.User("u".repeat(chars))
    private fun assistant(chars: Int) = LlmMessage.Assistant("a".repeat(chars))
    private fun toolResult(chars: Int) = LlmMessage.ToolResult("id", "shell_execute", "t".repeat(chars))

    /** 25 tokens per 100-char message — makes cut-point math readable. */
    private fun turn(chars: Int): List<LlmMessage> = listOf(user(chars), assistant(chars), toolResult(chars))

    // ── estimation & threshold ─────────────────────────────────────

    @Test
    fun estimateTokensIsCharsOverFour() {
        assertEquals(25, estimateTokens(user(100)))
        // 4 turns × 3 messages × 100 chars = 1200 chars → 300 tokens.
        assertEquals(300, estimateContextTokens(turn(100) + turn(100) + turn(100) + turn(100)))
    }

    @Test
    fun shouldCompactRespectsReserveAndEnabled() {
        val settings = CompactionSettings(enabled = true, reserveTokens = 100, keepRecentTokens = 200)
        assertTrue(shouldCompact(950, 1000, settings))
        assertFalse(shouldCompact(900, 1000, settings))
        assertFalse(shouldCompact(950, 1000, settings.copy(enabled = false)))
    }

    // ── cut points ─────────────────────────────────────────────────

    @Test
    fun cutNeverLandsOnToolResult() {
        // 10 turns × 75 tokens = 750 tokens; keep 150 → cut in the last 2 turns.
        val messages = (1..10).flatMap { turn(100) }
        val cut = findCutPoint(messages, keepRecentTokens = 150)
        assertFalse(messages[cut.firstKeptIndex] is LlmMessage.ToolResult)
        // Retained tail starts a whole turn here (cut on a User message).
        assertTrue(messages[cut.firstKeptIndex] is LlmMessage.User)
        assertFalse(cut.isSplitTurn)
    }

    @Test
    fun splitTurnWhenCutLandsOnAssistant() {
        // A long assistant message forces the budget to stop mid-turn.
        val messages = turn(100) + turn(100) + listOf(
            user(100),
            assistant(2000),
            toolResult(100),
        )
        val cut = findCutPoint(messages, keepRecentTokens = 100)
        assertTrue(cut.isSplitTurn)
        assertEquals(7, cut.firstKeptIndex) // the long assistant
        assertEquals(6, cut.turnStartIndex) // its user message
        // Chain intact: retained tail is assistant + its tool result.
        assertTrue(messages[cut.firstKeptIndex] is LlmMessage.Assistant)
    }

    @Test
    fun preparationPartitionsWithoutLossOrOverlap() {
        val messages = turn(100) + turn(100) + listOf(user(100), assistant(2000), toolResult(100))
        val prep = prepareCompaction(messages, CompactionSettings(keepRecentTokens = 100))!!
        assertEquals(6, prep.messagesToSummarize.size)
        assertEquals(1, prep.turnPrefixMessages.size)
        assertEquals(2, prep.retainedTail.size)
        assertEquals(
            messages,
            prep.messagesToSummarize + prep.turnPrefixMessages + prep.retainedTail,
        )
    }

    @Test
    fun preparationReturnsNullWhenNothingToSummarize() {
        assertNull(prepareCompaction(turn(100), CompactionSettings()))
    }

    // ── file ops ───────────────────────────────────────────────────

    @Test
    fun extractFileOpsInventoriesPaths() {
        val messages = listOf<LlmMessage>(
            LlmMessage.Assistant(
                "reading",
                listOf(
                    LlmToolCall("1", "file_read", JsonObject(mapOf("path" to JsonPrimitive("/a.txt")))),
                    LlmToolCall("2", "file_edit", JsonObject(mapOf("path" to JsonPrimitive("/b.txt")))),
                    LlmToolCall("3", "shell_execute", JsonObject(mapOf("command" to JsonPrimitive("ls")))),
                ),
            ),
        )
        val ops = extractFileOps(messages)
        assertEquals(setOf("/a.txt"), ops.read)
        assertEquals(setOf("/b.txt"), ops.modified)
        assertTrue(formatFileOps(ops).contains("## Files Touched"))
    }

    // ── summary generation ─────────────────────────────────────────

    private class FakeRequest(var reply: String? = "summary text") : SummaryRequest {
        val prompts = mutableListOf<Pair<String, String>>()
        override suspend fun complete(systemPrompt: String, userPrompt: String): String? {
            prompts.add(systemPrompt to userPrompt)
            return reply
        }
    }

    @Test
    fun firstSummaryUsesCheckpointPrompt() = runTest {
        val prep = prepareCompaction(turn(100) + turn(100) + turn(100), CompactionSettings(keepRecentTokens = 40))!!
        val request = FakeRequest()
        generateSummary(prep, previousSummary = null, customInstructions = null, request = request)
        assertEquals(SUMMARIZATION_SYSTEM_PROMPT, request.prompts[0].first)
        assertTrue(request.prompts[0].second.contains("Create a structured context checkpoint summary"))
        assertFalse(request.prompts[0].second.contains("<previous-summary>"))
    }

    @Test
    fun iterativeSummaryUpdatesPrevious() = runTest {
        val prep = prepareCompaction(turn(100) + turn(100) + turn(100), CompactionSettings(keepRecentTokens = 40))!!
        val request = FakeRequest()
        generateSummary(prep, previousSummary = "OLD SUMMARY", customInstructions = null, request = request)
        assertTrue(request.prompts[0].second.contains("<previous-summary>\nOLD SUMMARY\n</previous-summary>"))
        assertTrue(request.prompts[0].second.contains("NEW conversation messages"))
    }

    @Test
    fun splitTurnIssuesSecondPrefixCompletion() = runTest {
        val messages = turn(100) + turn(100) + listOf(user(100), assistant(2000), toolResult(100))
        val prep = prepareCompaction(messages, CompactionSettings(keepRecentTokens = 100))!!
        val request = FakeRequest()
        val summary = generateSummary(prep, previousSummary = null, customInstructions = null, request = request)!!
        assertEquals(2, request.prompts.size)
        assertTrue(request.prompts[1].second.contains("PREFIX of a turn"))
        assertTrue(summary.contains("Turn Context (split turn):"))
        assertTrue(summary.contains("summary text"))
    }

    @Test
    fun failureLeavesNoPartialSummary() = runTest {
        val prep = prepareCompaction(turn(100) + turn(100) + turn(100), CompactionSettings(keepRecentTokens = 40))!!
        val request = FakeRequest(reply = null)
        assertNull(generateSummary(prep, previousSummary = null, customInstructions = null, request = request))
    }

    // ── the strategy ───────────────────────────────────────────────

    @Test
    fun belowThresholdPassesThrough() = runTest {
        val strategy = LlmSummarizingCompaction(
            contextWindow = ContextWindowSource { 100_000 },
            settingsSource = CompactionSettingsSource { CompactionSettings() },
            request = FakeRequest(),
        )
        val messages = turn(100)
        assertEquals(messages, strategy.compact(messages))
    }

    @Test
    fun aboveThresholdReplacesHistoryWithSummaryMessage() = runTest {
        // 10 turns × 75 tokens = 750; window 500 − reserve 100 → compact.
        val messages = (1..10).flatMap { turn(100) }
        var persisted: String? = null
        val strategy = LlmSummarizingCompaction(
            contextWindow = ContextWindowSource { 500 },
            settingsSource = CompactionSettingsSource {
                CompactionSettings(reserveTokens = 100, keepRecentTokens = 150)
            },
            request = FakeRequest(reply = "SUMMARY"),
            onSummary = { persisted = it },
        )
        val out = strategy.compact(messages)
        assertTrue(out.first() is LlmMessage.User)
        assertTrue((out.first() as LlmMessage.User).text.contains("<summary>\nSUMMARY"))
        // Retained tail is the untouched recent turns.
        assertEquals(messages.takeLast(out.size - 1), out.drop(1))
        assertNotNull(persisted)
    }

    @Test
    fun summarizationFailureKeepsOriginalContext() = runTest {
        val messages = (1..10).flatMap { turn(100) }
        val strategy = LlmSummarizingCompaction(
            contextWindow = ContextWindowSource { 500 },
            settingsSource = CompactionSettingsSource { CompactionSettings(reserveTokens = 100) },
            request = FakeRequest(reply = null),
        )
        assertEquals(messages, strategy.compact(messages))
    }

    @Test
    fun serializeConversationCapsLongBlocks() {
        val out = serializeConversation(listOf(LlmMessage.User("x".repeat(5000))))
        assertTrue(out.contains("chars omitted"))
        assertTrue(out.length < 5000)
    }

    // ── usage-anchored estimation ──────────────────────────────────

    private fun anchoredAssistant(input: Int, output: Int) = LlmMessage.Assistant(
        "a".repeat(100),
        usage = com.anharness.llm.LlmUsage(inputTokens = input, outputTokens = output),
    )

    @Test
    fun anchoredEstimateUsesLastUsagePlusDelta() {
        val messages = listOf(
            user(400),                    // pre-anchor: covered by usage, not re-counted
            anchoredAssistant(900, 100),  // anchor: 1000 real tokens
            user(100),                    // delta: 25 heuristic tokens
            toolResult(100),              // delta: 25
        )
        assertEquals(1050, estimateContextTokensAnchored(messages))
        // Plain heuristic stays untouched (chars/4 contract).
        assertEquals(estimateContextTokens(messages), messages.sumOf(::estimateTokens))
    }

    @Test
    fun anchoredEstimatePrefersLastAnchor() {
        val messages = listOf(
            anchoredAssistant(500, 50),
            user(100),
            anchoredAssistant(2000, 200), // newer anchor wins
            user(40),                     // delta 10
        )
        assertEquals(2210, estimateContextTokensAnchored(messages))
    }

    @Test
    fun anchoredEstimateFallsBackWithoutUsage() {
        val messages = turn(100) + turn(100)
        assertEquals(estimateContextTokens(messages), estimateContextTokensAnchored(messages))
        // Assistant with output-only usage is NOT an anchor (input unknown).
        val outputOnly = listOf(
            LlmMessage.Assistant("x", usage = com.anharness.llm.LlmUsage(outputTokens = 999)),
            user(100),
        )
        assertEquals(estimateContextTokens(outputOnly), estimateContextTokensAnchored(outputOnly))
    }

    @Test
    fun cjkUndercountTriggersCompactionOnlyWhenAnchored() = runTest {
        // 400 CJK chars ≈ 400+ real tokens, but chars/4 says 100. Window
        // 300 − reserve 50 → threshold 250: heuristic passes through,
        // anchor must compact. This is the exact production failure mode.
        val cjk = LlmMessage.User("测".repeat(400))
        val messages = listOf(
            cjk,
            anchoredAssistant(450, 30), // real context 480 > 250
            user(40),
            assistant(40),
        )
        val settings = CompactionSettings(reserveTokens = 50, keepRecentTokens = 30)
        assertFalse(shouldCompact(estimateContextTokens(messages), 300, settings))
        assertTrue(shouldCompact(estimateContextTokensAnchored(messages), 300, settings))

        val strategy = LlmSummarizingCompaction(
            contextWindow = ContextWindowSource { 300 },
            settingsSource = CompactionSettingsSource { settings },
            request = FakeRequest(reply = "SUMMARY"),
        )
        val out = strategy.compact(messages)
        assertTrue("anchored context must compact", out.first() is LlmMessage.User)
        assertTrue((out.first() as LlmMessage.User).text.contains("SUMMARY"))
    }

    @Test
    fun anchoredBelowThresholdStillPassesThrough() = runTest {
        val messages = listOf(user(100), anchoredAssistant(50, 10), user(40))
        val strategy = LlmSummarizingCompaction(
            contextWindow = ContextWindowSource { 100_000 },
            settingsSource = CompactionSettingsSource { CompactionSettings() },
            request = FakeRequest(),
        )
        assertEquals(messages, strategy.compact(messages))
    }

    @Test
    fun anchoredSummarizationFailureKeepsOriginalContext() = runTest {
        val messages = listOf(
            LlmMessage.User("测".repeat(400)),
            anchoredAssistant(450, 30),
            user(40),
            assistant(40),
        )
        val strategy = LlmSummarizingCompaction(
            contextWindow = ContextWindowSource { 300 },
            settingsSource = CompactionSettingsSource {
                CompactionSettings(reserveTokens = 50, keepRecentTokens = 30)
            },
            request = FakeRequest(reply = null),
        )
        assertEquals(messages, strategy.compact(messages))
    }
}
