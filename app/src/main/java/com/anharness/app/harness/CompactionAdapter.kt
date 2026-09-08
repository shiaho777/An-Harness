package com.anharness.app.harness

import com.anharness.agent.CompactionPreparation
import com.anharness.agent.SummaryRequest
import com.anharness.agent.generateSummary
import com.anharness.agent.prepareCompaction
import com.anharness.app.data.model.AgentContentPart
import com.anharness.app.data.model.LLMMessage
import com.anharness.app.data.model.ThinkingLevel
import com.anharness.app.provider.LLMProvider
import com.anharness.llm.LlmMessage
import com.anharness.llm.LlmToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Adapter bridging between OpenMinis [LLMMessage] history + [LLMProvider]
 * and the `core:agent` compaction pipeline (port of pi `compaction.ts`).
 *
 * Used by ChatViewModel to upgrade summary generation from the legacy
 * plain string join to pi's structured work handoff (Goal / Constraints /
 * Progress / Decisions / Next Steps / Critical Context + file inventory).
 */
object CompactionAdapter {

    /**
     * Convert an app [LLMMessage] to a core [LlmMessage], flattening
     * [AgentContentPart] tool calls and tool results into real messages so
     * the compaction pipeline can inventory file operations and preserve
     * tool-chain boundaries.
     */
    fun toLlmMessages(history: List<LLMMessage>): List<LlmMessage> = buildList {
        for (msg in history) {
            when (msg.role) {
                LLMMessage.Role.USER -> {
                    // Tool results live inside USER messages in OpenMinis history
                    val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                    if (toolResults.isNotEmpty()) {
                        for (tr in toolResults) {
                            add(
                                LlmMessage.ToolResult(
                                    toolCallId = tr.id,
                                    toolName = tr.name,
                                    text = tr.content,
                                    isError = tr.isError,
                                ),
                            )
                        }
                    } else {
                        val text = msg.content.ifEmpty {
                            msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                                .joinToString("\n") { it.text }
                        }
                        if (text.isNotEmpty()) add(LlmMessage.User(text))
                    }
                }
                LLMMessage.Role.ASSISTANT -> {
                    val toolCalls = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        .map { tu ->
                            val args = runCatching {
                                Json.parseToJsonElement(tu.input.toString()).jsonObject
                            }.getOrDefault(JsonObject(emptyMap()))
                            LlmToolCall(tu.id, tu.name, args)
                        }
                    val text = msg.content.ifEmpty {
                        msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                            .joinToString("\n") { it.text }
                    }
                    add(LlmMessage.Assistant(text = text, toolCalls = toolCalls))
                }
            }
        }
    }

    /**
     * Create a [SummaryRequest] backed by an active [LLMProvider].
     * Single-shot completion with max 4096 tokens, no tools, thinking OFF.
     */
    fun providerSummaryRequest(provider: LLMProvider): SummaryRequest = SummaryRequest { sysPrompt, userPrompt ->
        try {
            val response = provider.sendMessage(
                messages = listOf(
                    LLMMessage(role = LLMMessage.Role.USER, content = userPrompt),
                ),
                systemPrompt = sysPrompt,
                maxTokens = 4096,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            )
            response.text.trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Run the full pi compaction pipeline over [messages] using [provider].
     * Returns null on failure so the caller can fall back gracefully.
     */
    suspend fun generateStructuredSummary(
        messages: List<LLMMessage>,
        previousSummary: String?,
        provider: LLMProvider,
        keepRecentTokens: Int = 20_000,
    ): String? {
        val coreMessages = toLlmMessages(messages)
        if (coreMessages.size < 2) return null
        val prep = prepareCompaction(
            coreMessages,
            com.anharness.agent.CompactionSettings(keepRecentTokens = keepRecentTokens),
        ) ?: return null
        val request = providerSummaryRequest(provider)
        return generateSummary(prep, previousSummary, null, request)
    }

    /**
     * Summarize ALL of [messages] through the pi structured pipeline,
     * without the pipeline's own cut-point selection. Used by
     * `ChatViewModel.compactAll()`, which has already determined the
     * compacted range via its anchor logic — the pipeline only owns
     * HOW the summary is generated (structured format, iterative update,
     * file-op inventory), not WHAT gets compacted.
     *
     * Returns null on failure so the caller falls back to the legacy
     * plain-text summary path.
     */
    suspend fun summarizeAll(
        messages: List<LLMMessage>,
        previousSummary: String?,
        provider: LLMProvider,
    ): String? {
        val coreMessages = toLlmMessages(messages)
        if (coreMessages.isEmpty()) return null
        val prep = CompactionPreparation(
            messagesToSummarize = coreMessages,
            turnPrefixMessages = emptyList(),
            retainedTail = emptyList(),
            isSplitTurn = false,
            tokensBefore = com.anharness.agent.estimateContextTokens(coreMessages),
        )
        val request = providerSummaryRequest(provider)
        return generateSummary(prep, previousSummary, null, request)
    }
}
