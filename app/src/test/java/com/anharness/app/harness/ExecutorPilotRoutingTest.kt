package com.anharness.app.harness

import com.anharness.app.data.repository.MemoryRepository
import com.anharness.app.tools.AgentTools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P2 pilot routing tests (issue #16). JVM-only, no Robolectric — same
 * boundary as HarnessPilotTest: shell/browser EXECUTION needs PRoot /
 * WebView and stays on device, but everything the ViewModel's
 * executeViaHarness branch depends on before execution is JVM-testable:
 * - toolDefinition(name) resolves the four migrated tools
 * - definition conversion (AgentToolDefinition → LlmToolDefinition)
 *   preserves name, schema, and sequential execution mode for shell/browser
 * - executePilotTool routes memory calls through the harness executors
 * - executePilotTool throws on tools outside the migration set so the
 *   ViewModel's runCatching legacy fallback fires
 */
class ExecutorPilotRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun memoryRepo(): MemoryRepository = MemoryRepository(tmp.newFolder())

    private fun stubDeps(repo: MemoryRepository? = null): HarnessBridge.ExecutorDependencies =
        object : HarnessBridge.ExecutorDependencies {
            override val memoryRepository = repo
            override val memoryEnabled = true
            override val browserTabPool: com.anharness.app.browser.BrowserTabPool
                get() = throw UnsupportedOperationException("browser body runs on device only")
        }

    @Test
    fun toolDefinitionResolvesAllFourMigratedTools() {
        for (name in listOf("shell_execute", "browser_use", "memory_write", "memory_get")) {
            assertEquals(name, HarnessBridge.toolDefinition(name).name)
        }
    }

    @Test
    fun toolDefinitionThrowsOnUnknownTool() {
        try {
            HarnessBridge.toolDefinition("exit_plan_mode")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected — exit_plan_mode stays ViewModel-executed
        }
    }

    @Test
    fun shellAndBrowserDefinitionsCarrySequentialExecutionMode() {
        with(HarnessBridge) {
            val shell = toolDefinition("shell_execute").toLlmTool("sequential")
            val browser = toolDefinition("browser_use").toLlmTool("sequential")
            assertEquals("shell_execute", shell.name)
            assertEquals("browser_use", browser.name)
            assertEquals("sequential", shell.executionMode)
            assertEquals("sequential", browser.executionMode)
        }
    }

    @Test
    fun shellDefinitionRequiresCommand() {
        with(HarnessBridge) {
            val llm = toolDefinition("shell_execute").toLlmTool()
            val required = llm.inputSchema["required"] as kotlinx.serialization.json.JsonArray
            val names = required.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            assertTrue(names.containsAll(listOf("tool_title", "command")))
        }
    }

    @Test
    fun browserDefinitionPreservesActionEnum() {
        with(HarnessBridge) {
            val llm = toolDefinition("browser_use").toLlmTool()
            val props = llm.inputSchema["properties"] as kotlinx.serialization.json.JsonObject
            val action = props["action"] as kotlinx.serialization.json.JsonObject
            val enums = (action["enum"] as kotlinx.serialization.json.JsonArray)
                .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            // Spot-check the actions the ViewModel dispatch knows about.
            assertTrue(enums.containsAll(listOf("navigate", "screenshot", "click", "type", "fetch")))
        }
    }

    @Test
    fun memoryDefinitionsMatchCatalog() {
        // The harness wrappers must expose byte-equivalent definitions to the
        // AgentTools catalog the legacy path served — otherwise the model
        // sees a different tool depending on the pilot flag.
        val catalog = AgentTools.makeAgentTools(memoryEnabled = true)
            .filter { it.name in setOf("memory_write", "memory_get") }
            .associateBy { it.name }
        with(HarnessBridge) {
            for (name in listOf("memory_write", "memory_get")) {
                val viaBridge = toolDefinition(name)
                val viaCatalog = catalog.getValue(name)
                assertEquals(viaCatalog.name, viaBridge.name)
                assertEquals(viaCatalog.description, viaBridge.description)
                assertEquals(viaCatalog.required, viaBridge.required)
                assertEquals(viaCatalog.parameters.keys, viaBridge.parameters.keys)
            }
        }
    }

    @Test
    fun executePilotToolRoutesMemoryWriteAndRead() = runTest {
        val repo = memoryRepo()
        val deps = stubDeps(repo)

        val written = HarnessBridge.executePilotTool(
            name = "memory_write",
            toolCallId = "t1",
            argsJson = """{"content":"## Pilot\nrouted through harness","tool_title":"Save"}""",
            context = NULL_CONTEXT,
            sessionId = { "s" },
            deps = deps,
        )
        assertTrue(written.success)
        assertTrue(written.output.startsWith("Memory saved"))
        assertEquals("Save", written.toolTitle)

        val read = HarnessBridge.executePilotTool(
            name = "memory_get",
            toolCallId = "t2",
            argsJson = """{"keywords":"pilot","tool_title":"Recall"}""",
            context = NULL_CONTEXT,
            sessionId = { "s" },
            deps = deps,
        )
        assertTrue(read.success)
        assertTrue(read.output.contains("routed through harness"))
        assertEquals("Recall", read.toolTitle)
    }

    @Test
    fun executePilotToolThrowsForNonMigratedTool() = runTest {
        try {
            HarnessBridge.executePilotTool(
                name = "read_image",
                toolCallId = "t1",
                argsJson = """{"path":"/x.png"}""",
                context = NULL_CONTEXT,
                sessionId = { "s" },
                deps = stubDeps(),
            )
            org.junit.Assert.fail("expected IllegalArgumentException — read_image is not migrated")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("read_image"))
        }
    }

    /**
     * Test seam: the memory branches never dereference the Context, so JVM
     * tests (no Robolectric in this module) pass null. Shell/browser
     * branches would require it and are device-tested.
     */
    private val NULL_CONTEXT: android.content.Context? = null
}
