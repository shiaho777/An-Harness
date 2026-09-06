package com.anharness.app.harness

import com.anharness.app.data.AgentLoopPrefs
import com.anharness.app.tools.AgentTools
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * P1 pilot wiring tests. JVM-only: definition conversion is pure kotlinx,
 * prefs use the in-memory test setter (no Robolectric in this module).
 * File execution itself is covered by device/instrumented tests — here we
 * pin the contract the ViewModel branch depends on.
 */
class HarnessPilotTest {

    @After
    fun tearDown() {
        AgentLoopPrefs.setCachedEnabledForTest(false)
    }

    @Test
    fun prefsDefaultOffAndToggle() {
        AgentLoopPrefs.setCachedEnabledForTest(false)
        assertFalse(AgentLoopPrefs.isEnabled())
        AgentLoopPrefs.setCachedEnabledForTest(true)
        assertTrue(AgentLoopPrefs.isEnabled())
    }

    @Test
    fun fileReadDefinitionSurvivesConversion() {
        val defs = HarnessBridge.agentToolDefinitions()
        val fileRead = defs.first { it.name == "file_read" }
        val llm = with(HarnessBridge) { fileRead.toLlmTool() }
        assertEquals("file_read", llm.name)
        val props = llm.inputSchema["properties"] as kotlinx.serialization.json.JsonObject
        assertTrue(props.containsKey("path"))
        assertTrue(props.containsKey("tool_title"))
        val required = llm.inputSchema["required"] as kotlinx.serialization.json.JsonArray
        assertTrue(required.any { (it as kotlinx.serialization.json.JsonPrimitive).content == "path" })
    }

    @Test
    fun makeAgentToolsAlwaysExposesFileRead() {
        // The pilot branch assumes file_read is unconditionally exposed
        // (AgentTools.kt adds it before any gate). Pin that.
        val names = AgentTools.makeAgentTools().map { it.name }
        assertTrue(names.contains("file_read"))
        val noMemory = AgentTools.makeAgentTools(memoryEnabled = false).map { it.name }
        assertTrue(noMemory.contains("file_read"))
        assertFalse(noMemory.contains("memory_write"))
    }
}
