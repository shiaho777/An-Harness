package com.anharness.app.harness

import com.anharness.app.data.repository.MemoryRepository
import com.anharness.app.tools.MemoryToolRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P2 executor-migration tests (issue #16). JVM-only: memory executors run
 * against a temp-dir MemoryRepository (pure file I/O); the shell/browser
 * bodies need PRoot/WebView and stay on device — here we pin the wiring
 * contract the ViewModel pilot branch depends on:
 * - wrappers expose the app-level ToolExecutionResult (title, success,
 *   error text) alongside the AgentToolResult projection
 * - failures are error results, never throws (AgentLoop contract)
 * - memory records fire for the SessionMemorySheet exactly once per
 *   successful call
 * - unknown tool names fail fast from executePilotTool (caller falls back)
 */
class ExecutorToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun memoryRepo(): MemoryRepository = MemoryRepository(tmp.newFolder())

    private fun writeArgs(content: String, title: String = "Note preference"): String {
        val esc = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"content":"$esc","tool_title":"$title"}"""
    }

    // ── memory_write ─────────────────────────────────────────────

    @Test
    fun memoryWritePersistsAndRecords() {
        val repo = memoryRepo()
        val records = mutableListOf<MemoryToolRecord>()

        val outcome = ExecutorTools.executeMemoryWrite(
            repository = repo,
            memoryEnabled = { true },
            argsJson = writeArgs("## Topic\nUser prefers Kotlin"),
            onRecord = { records.add(it) },
        )

        assertTrue(outcome.result.success)
        assertTrue(outcome.result.output.startsWith("Memory saved"))
        assertEquals("Note preference", outcome.result.toolTitle)
        assertEquals(1, records.size)
        val rec = records[0]
        assertTrue(rec.isWrite)
        assertEquals("## Topic", rec.preview)
        assertEquals("## Topic\nUser prefers Kotlin", rec.writtenContent)

        // AgentToolResult projection carries the same text + error flag.
        assertFalse(outcome.agentResult.isError)
        assertEquals(outcome.result.output, outcome.agentResult.text)

        // The entry actually landed in today's daily log — read it back
        // through the repository's own getter.
        val dumped = repo.getMemory("", "daily")
        assertTrue(dumped.contains("User prefers Kotlin"))
    }

    @Test
    fun memoryWriteDisabledFailsWithoutRecordingOrTouchingDisk() {
        val repo = memoryRepo()
        val records = mutableListOf<MemoryToolRecord>()

        val outcome = ExecutorTools.executeMemoryWrite(
            repository = repo,
            memoryEnabled = { false },
            argsJson = writeArgs("should not land"),
            onRecord = { records.add(it) },
        )

        assertFalse(outcome.result.success)
        assertTrue(outcome.result.output.contains("disabled"))
        assertEquals("Memory (disabled)", outcome.result.toolTitle)
        // No record — the sheet must not list a call that wrote nothing.
        assertTrue(records.isEmpty())
        assertTrue(outcome.agentResult.isError)
        // And nothing leaked to disk.
        assertFalse(repo.getMemory("", "daily").contains("should not land"))
    }

    @Test
    fun memoryWriteWithoutRepositoryFailsGracefully() {
        val outcome = ExecutorTools.executeMemoryWrite(
            repository = null,
            memoryEnabled = { true },
            argsJson = writeArgs("x"),
        )
        assertFalse(outcome.result.success)
        assertEquals("Error: Memory not available", outcome.result.output)
    }

    @Test
    fun memoryWriteBlankContentFails() {
        val records = mutableListOf<MemoryToolRecord>()
        val outcome = ExecutorTools.executeMemoryWrite(
            repository = memoryRepo(),
            memoryEnabled = { true },
            argsJson = """{"content":"","tool_title":"t"}""",
            onRecord = { records.add(it) },
        )
        assertFalse(outcome.result.success)
        assertTrue(records.isEmpty())
    }

    // ── memory_get ───────────────────────────────────────────────

    @Test
    fun memoryGetReadsBackWrittenEntry() {
        val repo = memoryRepo()
        ExecutorTools.executeMemoryWrite(repo, { true }, writeArgs("## Coffee\nUser takes oat milk"))

        val records = mutableListOf<MemoryToolRecord>()
        val outcome = ExecutorTools.executeMemoryGet(
            repository = repo,
            argsJson = """{"keywords":"coffee","tool_title":"Recall coffee"}""",
            onRecord = { records.add(it) },
        )

        assertTrue(outcome.result.success)
        assertTrue(outcome.result.output.contains("oat milk"))
        assertEquals("Recall coffee", outcome.result.toolTitle)
        assertEquals(1, records.size)
        assertFalse(records[0].isWrite)
        assertEquals("Search: coffee", records[0].preview)
        assertEquals("coffee", records[0].keywords)
    }

    @Test
    fun memoryGetWithoutRepositoryFailsGracefully() {
        val outcome = ExecutorTools.executeMemoryGet(
            repository = null,
            argsJson = """{"keywords":"x"}""",
        )
        assertFalse(outcome.result.success)
        assertEquals("Error: Memory not available", outcome.result.output)
    }

    // ── pure helpers ─────────────────────────────────────────────

    @Test
    fun linuxPathToMinisUrlPercentEncodes() {
        assertEquals(
            "minis://browser/shot%20a.jpg",
            ExecutorTools.linuxPathToMinisURL("/var/minis/browser/shot a.jpg"),
        )
        assertEquals(
            "minis://workspace/index.html",
            ExecutorTools.linuxPathToMinisURL("/var/minis/workspace/index.html"),
        )
        // Non-minis paths and bare namespaces return null.
        assertNull(ExecutorTools.linuxPathToMinisURL("/data/local/tmp/x"))
        assertNull(ExecutorTools.linuxPathToMinisURL("/var/minis/bare"))
    }

    @Test
    fun wrapForBashGuardsOnCommandVAndCleansUp() {
        // android.util.Base64 returns null on the JVM (returnDefaultValues),
        // so assert the structural contract only; the payload round-trip is
        // covered by device tests.
        val wrapped = ExecutorTools.wrapForBash("echo hi")
        assertTrue(wrapped.startsWith("( command -v bash >/dev/null 2>&1 || exit 119;"))
        assertTrue(wrapped.contains("base64 -d > /tmp/.minis-exec-${'$'}${'$'}.sh"))
        assertTrue(wrapped.endsWith("rm -f /tmp/.minis-exec-${'$'}${'$'}.sh; exit ${'$'}rc )"))
    }
}
