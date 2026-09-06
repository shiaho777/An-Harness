package com.anharness.llm

/**
 * Port of pi `packages/ai/src/types.ts` KnownApi + thinking levels.
 * Single source of truth for which wire protocol a model speaks.
 * Mixed providers dispatch per-model (see ModelCatalog).
 */
object LlmApi {
    const val OPENAI_COMPLETIONS = "openai-completions"
    const val OPENAI_RESPONSES = "openai-responses"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    const val GOOGLE_GENERATIVE_AI = "google-generative-ai"
    const val GOOGLE_VERTEX = "google-vertex"
    const val PI_MESSAGES = "pi-messages"
}

/** Unified reasoning effort, mapped per-API by each streamer. Port of pi ThinkingLevel. */
enum class ThinkingLevel { MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

/** Per-model thinking support. OFF = no reasoning output. */
enum class ModelThinkingLevel { OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

fun ModelThinkingLevel.toThinkingLevel(): ThinkingLevel? = when (this) {
    ModelThinkingLevel.OFF -> null
    ModelThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
    ModelThinkingLevel.LOW -> ThinkingLevel.LOW
    ModelThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
    ModelThinkingLevel.HIGH -> ThinkingLevel.HIGH
    ModelThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
    ModelThinkingLevel.MAX -> ThinkingLevel.MAX
}
