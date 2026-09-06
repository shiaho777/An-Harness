package com.anharness.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OkHttp-SSE implementation. Dispatches per [ModelDescriptor.api]
 * (port of pi mixed-provider dispatch in packages/ai).
 */
class ModelStreamer(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun stream(model: ModelDescriptor, context: LlmContext, options: StreamOptions): Flow<LlmStreamEvent> =
        when (model.api) {
            LlmApi.ANTHROPIC_MESSAGES -> anthropicStream(model, context, options)
            LlmApi.OPENAI_COMPLETIONS, LlmApi.OPENAI_RESPONSES -> openAiChatStream(model, context, options)
            else -> openAiChatStream(model, context, options)
        }

    // ── Anthropic Messages ──────────────────────────────────────────
    private fun anthropicStream(
        model: ModelDescriptor,
        context: LlmContext,
        options: StreamOptions,
    ): Flow<LlmStreamEvent> = callbackFlow {
        val base = (options.baseUrl ?: model.baseUrl ?: "https://api.anthropic.com").trimEnd('/')
        val body = buildJsonObject {
            put("model", model.id)
            put("max_tokens", options.maxTokens)
            put("stream", true)
            put("system", context.systemPrompt)
            putJsonArray("messages") {
                context.messages.forEach { m ->
                    when (m) {
                        is LlmMessage.User -> add(buildJsonObject {
                            put("role", "user")
                            put("content", m.text)
                        })
                        is LlmMessage.Assistant -> add(buildJsonObject {
                            put("role", "assistant")
                            put("content", m.text)
                        })
                        is LlmMessage.ToolResult -> add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", m.toolCallId)
                                    put("content", m.text)
                                    if (m.isError) put("is_error", true)
                                })
                            }
                        })
                    }
                }
            }
            if (context.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    context.tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.inputSchema)
                        })
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("$base/v1/messages")
            .header("x-api-key", options.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .apply { options.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val text = StringBuilder()
        val toolArgs = mutableMapOf<String, StringBuilder>()
        val toolNames = mutableMapOf<String, String>()
        val toolOrder = mutableListOf<String>()

        val factory = EventSources.createFactory(client)
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val evt = json.parseToJsonElement(data).jsonObject
                    when (evt["type"]?.jsonPrimitive?.contentOrNull) {
                        "content_block_delta" -> {
                            val delta = evt["delta"]?.jsonObject ?: return
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> {
                                    val d = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    text.append(d)
                                    trySend(LlmStreamEvent.TextDelta(d))
                                }
                                "input_json_delta" -> {
                                    val idx = evt["index"]?.jsonPrimitive?.intOrNull ?: 0
                                    val idKey = toolOrder.getOrNull(idx) ?: return
                                    val d = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    toolArgs.getOrPut(idKey) { StringBuilder() }.append(d)
                                    trySend(LlmStreamEvent.ToolCallDelta(idKey, toolNames[idKey].orEmpty(), d))
                                }
                            }
                        }
                        "content_block_start" -> {
                            val block = evt["content_block"]?.jsonObject ?: return
                            if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                val idKey = block["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
                                toolNames[idKey] = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                toolArgs[idKey] = StringBuilder()
                                toolOrder.add(idKey)
                            }
                        }
                        "message_stop" -> {
                            val calls = toolOrder.map { idKey ->
                                val raw = toolArgs[idKey]?.toString().orEmpty()
                                val args = runCatching { json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }
                                    .getOrDefault(JsonObject(emptyMap()))
                                LlmToolCall(idKey, toolNames[idKey].orEmpty(), args)
                            }
                            trySend(LlmStreamEvent.Done(LlmMessage.Assistant(text.toString(), calls, StopReason.STOP)))
                            close()
                        }
                        "error" -> {
                            trySend(LlmStreamEvent.Error(evt["error"]?.toString() ?: data))
                            close()
                        }
                    }
                } catch (_: Exception) { /* ignore partial frames */ }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                trySend(LlmStreamEvent.Error(t?.message ?: "HTTP ${response?.code}"))
                close()
            }
        }
        factory.newEventSource(req, listener)
        awaitClose()
    }

    // ── OpenAI chat-completions (also covers most gateways) ─────────
    private fun openAiChatStream(
        model: ModelDescriptor,
        context: LlmContext,
        options: StreamOptions,
    ): Flow<LlmStreamEvent> = callbackFlow {
        val base = (options.baseUrl ?: model.baseUrl ?: "https://api.openai.com").trimEnd('/')
        val body = buildJsonObject {
            put("model", model.id)
            put("stream", true)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", context.systemPrompt)
                })
                context.messages.forEach { m ->
                    when (m) {
                        is LlmMessage.User -> add(buildJsonObject {
                            put("role", "user")
                            put("content", m.text)
                        })
                        is LlmMessage.Assistant -> add(buildJsonObject {
                            put("role", "assistant")
                            put("content", m.text)
                        })
                        is LlmMessage.ToolResult -> add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", m.toolCallId)
                            put("content", m.text)
                        })
                    }
                }
            }
            if (context.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    context.tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.inputSchema)
                            })
                        })
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer ${options.apiKey}")
            .header("content-type", "application/json")
            .apply { options.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val text = StringBuilder()
        val toolArgs = mutableMapOf<String, StringBuilder>()
        val toolNames = mutableMapOf<String, String>()
        var finishReason: String? = null

        val factory = EventSources.createFactory(client)
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    val calls = toolArgs.map { (callId, sb) ->
                        val args = runCatching {
                            json.parseToJsonElement(sb.toString().ifBlank { "{}" }).jsonObject
                        }.getOrDefault(JsonObject(emptyMap()))
                        LlmToolCall(callId, toolNames[callId].orEmpty(), args)
                    }
                    val stop = if (finishReason == "length") StopReason.LENGTH else StopReason.STOP
                    trySend(LlmStreamEvent.Done(LlmMessage.Assistant(text.toString(), calls, stop)))
                    close()
                    return
                }
                try {
                    val chunk = json.parseToJsonElement(data).jsonObject
                    val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
                    finishReason = choice["finish_reason"]?.let {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: finishReason
                    val delta = choice["delta"]?.jsonObject ?: return
                    delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                        text.append(it)
                        trySend(LlmStreamEvent.TextDelta(it))
                    }
                    (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
                        val o = tc.jsonObject
                        val callId = o["id"]?.jsonPrimitive?.contentOrNull
                            ?: toolArgs.keys.lastOrNull() ?: UUID.randomUUID().toString()
                        val fn = o["function"]?.jsonObject
                        fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { n ->
                            if (n.isNotBlank()) toolNames[callId] = n
                        }
                        val d = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
                        toolArgs.getOrPut(callId) { StringBuilder() }.append(d)
                        if (d.isNotEmpty()) {
                            trySend(LlmStreamEvent.ToolCallDelta(callId, toolNames[callId].orEmpty(), d))
                        }
                    }
                } catch (_: Exception) { /* ignore keep-alives */ }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                trySend(LlmStreamEvent.Error(t?.message ?: "HTTP ${response?.code}"))
                close()
            }
        }
        factory.newEventSource(req, listener)
        awaitClose()
    }
}

/** Default StreamFn wiring catalog + auth + SSE streamer. */
fun defaultStreamFn(
    catalog: ModelCatalog = ModelCatalog(),
    streamer: ModelStreamer = ModelStreamer(),
): StreamFn = { model, context, options ->
    streamer.stream(model, context, options)
}

fun LlmContext.withExtraMessages(extra: List<LlmMessage>): LlmContext = copy(messages = messages + extra)
