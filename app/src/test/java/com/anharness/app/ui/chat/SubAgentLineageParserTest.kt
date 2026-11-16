package com.anharness.app.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-app-wiring] Parse/sort contract for the lineage payload. Pure
 * JVM (org.json is a test dependency), no Android UI.
 */
class SubAgentLineageParserTest {

    private fun node(
        id: String, parentId: String?, depth: Int, task: String,
        ok: Boolean?, startedAt: Long, finishedAt: Long?,
    ) = JSONObject().apply {
        put("id", id)
        put("parentId", parentId ?: JSONObject.NULL)
        put("depth", depth)
        put("task", task)
        put("ok", ok ?: JSONObject.NULL)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt ?: JSONObject.NULL)
    }

    @Test
    fun `blank payload yields empty list`() {
        assertTrue(SubAgentLineageParser.parse("").isEmpty())
        assertTrue(SubAgentLineageParser.parse("   ").isEmpty())
    }

    @Test
    fun `malformed payload yields empty list not throw`() {
        assertTrue(SubAgentLineageParser.parse("not json").isEmpty())
        assertTrue(SubAgentLineageParser.parse("{broken").isEmpty())
    }

    @Test
    fun `null json fields map to kotlin null`() {
        val payload = JSONArray().put(node("a", null, 1, "t", null, 100, null)).toString()
        val n = SubAgentLineageParser.parse(payload).single()
        assertNull(n.parentId)
        assertNull(n.ok)
        assertNull(n.finishedAt)
        assertEquals(SubAgentLineageParser.Status.RUNNING, n.status)
        assertNull(n.durationMs)
    }

    @Test
    fun `status maps from ok flag`() {
        val payload = JSONArray()
            .put(node("a", null, 1, "running", null, 1, null))
            .put(node("b", null, 1, "done", true, 2, 5))
            .put(node("c", null, 1, "failed", false, 3, 9))
            .toString()
        val nodes = SubAgentLineageParser.parse(payload).associateBy { it.id }
        assertEquals(SubAgentLineageParser.Status.RUNNING, nodes["a"]!!.status)
        assertEquals(SubAgentLineageParser.Status.DONE, nodes["b"]!!.status)
        assertEquals(SubAgentLineageParser.Status.FAILED, nodes["c"]!!.status)
    }

    @Test
    fun `duration is non-negative`() {
        val payload = JSONArray()
            .put(node("a", null, 1, "t", true, 1000, 3500))
            .put(node("b", null, 1, "t", true, 5000, 4000)) // clock skew
            .toString()
        val nodes = SubAgentLineageParser.parse(payload).associateBy { it.id }
        assertEquals(2500L, nodes["a"]!!.durationMs)
        assertEquals(0L, nodes["b"]!!.durationMs)
    }

    @Test
    fun `sorted by depth then start time`() {
        val payload = JSONArray()
            .put(node("deep", "root", 2, "child", true, 50, 60))
            .put(node("root", null, 1, "root", true, 10, 20))
            .put(node("root2", null, 1, "later root", true, 30, 40))
            .toString()
        val ids = SubAgentLineageParser.parse(payload).map { it.id }
        assertEquals(listOf("root", "root2", "deep"), ids)
    }

    @Test
    fun `parent id preserved for tree linkage`() {
        val payload = JSONArray()
            .put(node("root", null, 1, "r", true, 1, 2))
            .put(node("child", "root", 2, "c", true, 3, 4))
            .toString()
        val nodes = SubAgentLineageParser.parse(payload).associateBy { it.id }
        assertEquals("root", nodes["child"]!!.parentId)
        assertNull(nodes["root"]!!.parentId)
    }
}