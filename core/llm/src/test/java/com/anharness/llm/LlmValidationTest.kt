package com.anharness.llm

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

private val model = ModelDescriptor(
    id = "m", name = "m", api = LlmApi.OPENAI_COMPLETIONS,
    providerId = "test", baseUrl = null, contextWindow = 8192,
)

class ValidateToolArgumentsTest {
    private val tool = LlmToolDefinition(
        name = "file_read",
        description = "read a file",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("lines", buildJsonObject { put("type", "integer") })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.JsonArray(
                        listOf(JsonPrimitive("head"), JsonPrimitive("tail"))))
                })
            })
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
        },
    )

    @Test
    fun validArgsPass() {
        val args = JsonObject(mapOf(
            "path" to JsonPrimitive("/a.txt"),
            "lines" to JsonPrimitive(10),
            "mode" to JsonPrimitive("head"),
        ))
        assertTrue(validateToolArguments(tool, args).isSuccess)
    }

    @Test
    fun missingRequiredFails() {
        val result = validateToolArguments(tool, JsonObject(emptyMap()))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("missing required argument \"path\""))
    }

    @Test
    fun wrongTypeFails() {
        val args = JsonObject(mapOf("path" to JsonPrimitive(42)))
        val result = validateToolArguments(tool, args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("must be string"))
    }

    @Test
    fun enumViolationFails() {
        val args = JsonObject(mapOf(
            "path" to JsonPrimitive("/a"),
            "mode" to JsonPrimitive("middle"),
        ))
        val result = validateToolArguments(tool, args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("one of"))
    }

    @Test
    fun extraKeysAreAllowed() {
        val args = JsonObject(mapOf(
            "path" to JsonPrimitive("/a"),
            "tool_title" to JsonPrimitive("Read the file"),
        ))
        assertTrue(validateToolArguments(tool, args).isSuccess)
    }
}

class RetryPolicyTest {
    @Test
    fun deterministicBackoff() {
        val policy = RetryPolicy(maxRetries = 3, baseDelayMs = 1000)
        assertEquals(1000, policy.delayMs(1))
        assertEquals(2000, policy.delayMs(2))
        assertEquals(4000, policy.delayMs(3))
        assertEquals(4, policy.maxAttempts)
        assertEquals(1, RetryPolicy(enabled = false).maxAttempts)
    }

    @Test
    fun completeSimpleRetriesErrorsThenSucceeds() = runTest {
        var calls = 0
        val streamFn: StreamFn = { _, _, _ ->
            calls++
            kotlinx.coroutines.flow.flow {
                if (calls < 3) emit(LlmStreamEvent.Error("boom $calls"))
                else emit(LlmStreamEvent.Done(LlmMessage.Assistant("recovered")))
            }
        }
        val out = completeSimple(streamFn, model, "sys", "hi", StreamOptions(apiKey = "k"), RetryPolicy(maxRetries = 2, baseDelayMs = 1))
        assertEquals(3, calls)
        assertTrue(out is CompleteOutcome.Ok)
        assertEquals("recovered", (out as CompleteOutcome.Ok).text)
    }

    @Test
    fun completeSimpleGivesUpAfterMaxAttempts() = runTest {
        var calls = 0
        val streamFn: StreamFn = { _, _, _ ->
            calls++
            kotlinx.coroutines.flow.flow { emit(LlmStreamEvent.Error("always down")) }
        }
        val out = completeSimple(streamFn, model, "sys", "hi", StreamOptions(apiKey = "k"), RetryPolicy(maxRetries = 2, baseDelayMs = 1))
        assertEquals(3, calls)
        assertTrue(out is CompleteOutcome.Failed)
        assertEquals(3, (out as CompleteOutcome.Failed).attempts)
        assertEquals("always down", out.error)
    }

    @Test
    fun completeSimpleCollectsDeltasWithoutDone() = runTest {
        val streamFn: StreamFn = { _, _, _ ->
            kotlinx.coroutines.flow.flowOf(
                LlmStreamEvent.TextDelta("sum"),
                LlmStreamEvent.TextDelta("mary"),
            )
        }
        val out = completeSimple(streamFn, model, "sys", "hi", StreamOptions(apiKey = "k"), RetryPolicy(enabled = false))
        assertTrue(out is CompleteOutcome.Ok)
        assertEquals("summary", (out as CompleteOutcome.Ok).text)
    }

    @Test
    fun retryPolicyDelaysDeterministically() {
        val p = RetryPolicy(maxRetries = 4, baseDelayMs = 100)
        assertEquals(listOf(100L, 200L, 400L, 800L), (1..4).map { p.delayMs(it) })
    }
}
