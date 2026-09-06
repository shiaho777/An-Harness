package com.anharness.llm

import kotlinx.serialization.json.JsonObject

/**
 * Port of pi `Model<Api>` + `Provider<Api>`.
 * Catalog owns discovery; streamers own the wire protocol.
 */
data class ModelDescriptor(
    val id: String,
    val name: String,
    val api: String,
    val providerId: String,
    val baseUrl: String?,
    val contextWindow: Int?,
    val costTier: String? = null,
    val supportsVision: Boolean = false,
    val supportsThinking: Boolean = false,
)

data class ProviderDescriptor(
    val id: String,
    val name: String,
    val envKeys: List<String> = emptyList(),
    val defaultBaseUrl: String? = null,
)

/** Tool schema handed to the LLM. Mirrors pi TypeBox Tool[] (name/description/json-schema). */
data class LlmToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** "parallel" (default) or "sequential" — sequential forces one-at-a-time execution. */
    val executionMode: String = "parallel",
)

data class LlmUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

/**
 * Builtin registry. Port of pi `builtinModels()`.
 * Dynamic providers (OpenRouter etc.) refresh via [ModelCatalog.refresh].
 */
object BuiltinModels {
    fun all(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "claude-opus-4-6",
            name = "Claude Opus 4.6",
            api = LlmApi.ANTHROPIC_MESSAGES,
            providerId = "anthropic",
            baseUrl = "https://api.anthropic.com",
            contextWindow = 200_000,
            supportsVision = true,
            supportsThinking = true,
        ),
        ModelDescriptor(
            id = "gpt-5.2",
            name = "GPT-5.2",
            api = LlmApi.OPENAI_RESPONSES,
            providerId = "openai",
            baseUrl = "https://api.openai.com",
            contextWindow = 400_000,
            supportsVision = true,
            supportsThinking = true,
        ),
        ModelDescriptor(
            id = "gemini-3-pro",
            name = "Gemini 3 Pro",
            api = LlmApi.GOOGLE_GENERATIVE_AI,
            providerId = "google",
            baseUrl = "https://generativelanguage.googleapis.com",
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsThinking = true,
        ),
    )
}

/** Thread-safe catalog: sync reads, async refresh for dynamic providers. */
class ModelCatalog(initial: List<ModelDescriptor> = BuiltinModels.all()) {
    private val lock = Any()
    private var models: List<ModelDescriptor> = initial

    fun getModels(): List<ModelDescriptor> = synchronized(lock) { models }

    fun getModel(id: String): ModelDescriptor? = synchronized(lock) {
        models.firstOrNull { it.id == id }
    }

    fun register(extra: List<ModelDescriptor>) = synchronized(lock) {
        val ids = extra.map { it.id }.toSet()
        models = models.filterNot { it.id in ids } + extra
    }

    /**
     * Merge OpenAI-compatible `/models` listing into the catalog.
     * Used for OpenRouter / self-hosted gateways (port of pi refresh()).
     */
    fun mergeOpenAiListing(providerId: String, baseUrl: String, ids: List<String>) {
        register(ids.map {
            ModelDescriptor(
                id = it,
                name = it,
                api = LlmApi.OPENAI_COMPLETIONS,
                providerId = providerId,
                baseUrl = baseUrl,
                contextWindow = null,
            )
        })
    }
}
