package com.anharness.llm

/**
 * Port of pi auth layering (`CredentialStore` + env + OAuth).
 * Resolution order: explicit override > stored credential > env > ambient.
 * Android: stored credentials live in EncryptedSharedPreferences/Keystore
 * (see app auth module); this interface keeps core:llm platform-agnostic.
 */
interface CredentialStore {
    suspend fun read(providerId: String): String?
    suspend fun write(providerId: String, value: String)
    suspend fun delete(providerId: String)
}

class InMemoryCredentialStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override suspend fun read(providerId: String): String? = synchronized(map) { map[providerId] }
    override suspend fun write(providerId: String, value: String) { synchronized(map) { map[providerId] = value } }
    override suspend fun delete(providerId: String) { synchronized(map) { map.remove(providerId) } }
}

class AuthResolver(
    private val store: CredentialStore,
    private val env: (String) -> String? = { System.getenv(it) },
    private val providers: List<ProviderDescriptor> = listOf(
        ProviderDescriptor("anthropic", "Anthropic", listOf("ANTHROPIC_API_KEY")),
        ProviderDescriptor("openai", "OpenAI", listOf("OPENAI_API_KEY")),
        ProviderDescriptor("google", "Google", listOf("GEMINI_API_KEY", "GOOGLE_API_KEY")),
        ProviderDescriptor("openrouter", "OpenRouter", listOf("OPENROUTER_API_KEY")),
    ),
) {
    /** Explicit per-request key always wins (OAuth refresh path uses this). */
    suspend fun resolve(
        providerId: String,
        explicitApiKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ResolvedAuth? {
        if (!explicitApiKey.isNullOrBlank()) {
            return ResolvedAuth(providerId, explicitApiKey, extraHeaders)
        }
        store.read(providerId)?.takeIf { it.isNotBlank() }?.let {
            return ResolvedAuth(providerId, it, extraHeaders)
        }
        val desc = providers.firstOrNull { it.id == providerId }
        desc?.envKeys?.firstNotNullOfOrNull { env(it)?.takeIf { v -> v.isNotBlank() } }?.let {
            return ResolvedAuth(providerId, it, extraHeaders)
        }
        return null
    }

    suspend fun checkAuth(providerId: String): Boolean = resolve(providerId) != null
}

data class ResolvedAuth(
    val providerId: String,
    val apiKey: String,
    val extraHeaders: Map<String, String> = emptyMap(),
)
