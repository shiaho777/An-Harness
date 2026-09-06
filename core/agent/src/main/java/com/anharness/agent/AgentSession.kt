package com.anharness.agent

import com.anharness.llm.LlmMessage
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Port of pi session management (`Session` JSONL tree + fork/clone).
 * Layout: <root>/<sessionId>/messages.jsonl + meta.json
 * (mirrors pi `~/.pi/agent/sessions`, adapted to app filesDir).
 */
class AgentSessionStore(
    private val root: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    data class SessionMeta(val id: String, val title: String, val createdAt: Long, val updatedAt: Long)

    fun create(title: String = "New session"): String {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        writeMeta(dir, SessionMeta(id, title, System.currentTimeMillis(), System.currentTimeMillis()))
        File(dir, "messages.jsonl").writeText("")
        return id
    }

    fun append(sessionId: String, message: LlmMessage) {
        val dir = File(root, sessionId).apply { mkdirs() }
        File(dir, "messages.jsonl").appendText(encode(message) + "\n")
        touch(dir)
    }

    fun load(sessionId: String): List<LlmMessage> {
        val f = File(File(root, sessionId), "messages.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { decode(it) }.getOrNull() }
    }

    /** Fork: copy history up to [uptoIndex] into a new session (port of pi /fork). */
    fun fork(sessionId: String, uptoIndex: Int = Int.MAX_VALUE, title: String = "Fork"): String {
        val history = load(sessionId)
        val slice = if (uptoIndex >= history.size) history else history.subList(0, uptoIndex.coerceAtLeast(0))
        val newId = create(title)
        val dir = File(root, newId)
        File(dir, "messages.jsonl").writeText(slice.joinToString("\n") { encode(it) }.let {
            if (slice.isEmpty()) "" else it + "\n"
        })
        return newId
    }

    /** Clone: full copy (port of pi /clone). */
    fun clone(sessionId: String, title: String = "Clone"): String = fork(sessionId, Int.MAX_VALUE, title)

    fun list(): List<SessionMeta> {
        if (!root.exists()) return emptyList()
        return root.listFiles()?.mapNotNull { dir ->
            runCatching {
                val o = json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
                SessionMeta(
                    id = o["id"]!!.jsonPrimitive.content,
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: dir.name,
                    createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            }.getOrNull()
        }?.sortedByDescending { it.updatedAt }.orEmpty()
    }

    private fun writeMeta(dir: File, meta: SessionMeta) {
        File(dir, "meta.json").writeText(json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("id", meta.id)
                put("title", meta.title)
                put("createdAt", meta.createdAt.toString())
                put("updatedAt", meta.updatedAt.toString())
            },
        ))
    }

    private fun touch(dir: File) {
        runCatching {
            val f = File(dir, "meta.json")
            val o = json.parseToJsonElement(f.readText()).jsonObject.toMutableMap()
            o["updatedAt"] = JsonPrimitive(System.currentTimeMillis().toString())
            f.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(o)))
        }
    }

    private fun encode(m: LlmMessage): String = json.encodeToString(
        JsonObject.serializer(),
        when (m) {
            is LlmMessage.User -> buildJsonObject {
                put("role", "user")
                put("text", m.text)
            }
            is LlmMessage.Assistant -> buildJsonObject {
                put("role", "assistant")
                put("text", m.text)
                put("stopReason", m.stopReason.name)
            }
            is LlmMessage.ToolResult -> buildJsonObject {
                put("role", "toolResult")
                put("toolCallId", m.toolCallId)
                put("toolName", m.toolName)
                put("text", m.text)
                put("isError", m.isError.toString())
            }
        },
    )

    private fun decode(line: String): LlmMessage {
        val o = json.parseToJsonElement(line).jsonObject
        return when (o["role"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> LlmMessage.Assistant(
                text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                stopReason = runCatching {
                    com.anharness.llm.StopReason.valueOf(o["stopReason"]?.jsonPrimitive?.contentOrNull ?: "STOP")
                }.getOrDefault(com.anharness.llm.StopReason.STOP),
            )
            "toolResult" -> LlmMessage.ToolResult(
                toolCallId = o["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                toolName = o["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                isError = o["isError"]?.jsonPrimitive?.contentOrNull == "true",
            )
            else -> LlmMessage.User(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}
