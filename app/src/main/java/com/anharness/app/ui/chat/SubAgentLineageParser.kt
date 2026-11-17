package com.anharness.app.ui.chat

import org.json.JSONArray

/**
 * [T-subagent-app-wiring] Pure parser for the sub-agent lineage payload the
 * /subagent command attaches to its system-info card. Kept separate from the
 * Composable so the parse/sort contract is JVM-testable (no Android UI).
 */
internal object SubAgentLineageParser {

    data class Node(
        val id: String,
        val parentId: String?,
        val depth: Int,
        val task: String,
        val ok: Boolean?,
        val startedAt: Long,
        val finishedAt: Long?,
    ) {
        val status: Status get() = when (ok) {
            null -> Status.RUNNING
            true -> Status.DONE
            false -> Status.FAILED
        }
        val durationMs: Long? get() = finishedAt?.let { (it - startedAt).coerceAtLeast(0) }
    }

    enum class Status { RUNNING, DONE, FAILED }

    /**
     * Parse the JSON array payload into nodes sorted by (depth, startedAt).
     * Malformed/blank payload → empty list (the sheet shows an empty state),
     * never throws. A null parentId/ok/finishedAt in JSON maps to Kotlin null.
     */
    fun parse(payload: String): List<Node> {
        if (payload.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(payload)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Node(
                    id = o.optString("id"),
                    parentId = o.optString("parentId").takeIf { it.isNotEmpty() && it != "null" },
                    depth = o.optInt("depth", 1),
                    task = o.optString("task"),
                    ok = if (o.isNull("ok")) null else o.optBoolean("ok"),
                    startedAt = o.optLong("startedAt"),
                    finishedAt = if (o.isNull("finishedAt")) null else o.optLong("finishedAt"),
                )
            }.sortedWith(compareBy({ it.depth }, { it.startedAt }))
        }.getOrDefault(emptyList())
    }
}