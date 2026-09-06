package com.anharness.llm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun builtinModelsResolve() {
        val catalog = ModelCatalog()
        assertTrue(catalog.getModels().isNotEmpty())
        assertNotNull(catalog.getModel("claude-opus-4-6"))
    }

    @Test
    fun mergeOpenAiListingAddsGatewayModels() {
        val catalog = ModelCatalog()
        catalog.mergeOpenAiListing("openrouter", "https://openrouter.ai/api", listOf("xai/grok-4"))
        val m = catalog.getModel("xai/grok-4")
        assertNotNull(m)
        assertEquals(LlmApi.OPENAI_COMPLETIONS, m!!.api)
        assertEquals("openrouter", m.providerId)
    }
}

class AuthResolverTest {
    @Test
    fun explicitKeyWinsOverStoredAndEnv() = runTest {
        val store = InMemoryCredentialStore()
        store.write("openai", "stored-key")
        val resolver = AuthResolver(store, env = { null })
        assertEquals("explicit", resolver.resolve("openai", explicitApiKey = "explicit")!!.apiKey)
        assertEquals("stored-key", resolver.resolve("openai")!!.apiKey)
    }

    @Test
    fun fallsBackToEnvThenNull() = runTest {
        val resolver = AuthResolver(InMemoryCredentialStore(), env = { k ->
            if (k == "ANTHROPIC_API_KEY") "env-key" else null
        })
        assertEquals("env-key", resolver.resolve("anthropic")!!.apiKey)
        assertNull(resolver.resolve("openai"))
    }
}
