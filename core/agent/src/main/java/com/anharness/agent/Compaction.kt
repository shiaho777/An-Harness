package com.anharness.agent

import com.anharness.llm.LlmMessage

/**
 * Port of pi compaction (`branch-summarization`).
 * v0.1 ships a deterministic sliding window; LLM summarization
 * plugs in via [Summarizer] without changing the loop.
 */
interface CompactionStrategy {
    /** Return the messages that should actually be sent this turn. */
    suspend fun compact(messages: List<LlmMessage>): List<LlmMessage>
}

/** Keep system-adjacent head + last N messages. Zero LLM cost. */
class SlidingWindowCompaction(private val keepLast: Int = 40) : CompactionStrategy {
    override suspend fun compact(messages: List<LlmMessage>): List<LlmMessage> {
        if (messages.size <= keepLast) return messages
        val head = messages.firstOrNull { it is LlmMessage.User }
        val tail = messages.takeLast(keepLast)
        val notice = LlmMessage.User(
            "[compaction] ${messages.size - tail.size} older messages trimmed " +
                "by sliding window; continuing from recent context.",
        )
        return listOfNotNull(head, notice) + tail
    }
}

/** LLM-backed summarizer hook (branch summarization lands here in P2). */
interface Summarizer {
    suspend fun summarize(messages: List<LlmMessage>): String
}
