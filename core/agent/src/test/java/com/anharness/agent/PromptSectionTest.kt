package com.anharness.agent

import org.junit.Assert.*
import org.junit.Test

class PromptSectionTest {

    @Test
    fun assemblesInOrderRegardlessOfInputOrder() {
        val out = SystemPromptAssembler.assemble(
            listOf(
                PromptSection("b", order = 20, text = "BBB"),
                PromptSection("a", order = 10, text = "AAA"),
                PromptSection("c", order = 30, text = "CCC"),
            ),
        )
        assertEquals("AAA\n\nBBB\n\nCCC", out)
    }

    @Test
    fun disabledAndEmptySectionsLeaveNoDanglingSeparators() {
        val out = SystemPromptAssembler.assemble(
            listOf(
                PromptSection("a", order = 10, text = "AAA"),
                PromptSection("off", order = 20, enabled = false, text = "HIDDEN"),
                PromptSection("empty", order = 25, text = ""),
                PromptSection("b", order = 30, text = "BBB"),
            ),
        )
        assertEquals("AAA\n\nBBB", out)
    }

    @Test
    fun orderTiesBreakByNameForDeterminism() {
        val first = SystemPromptAssembler.assemble(
            listOf(
                PromptSection("z", order = 10, text = "Z"),
                PromptSection("a", order = 10, text = "A"),
            ),
        )
        val second = SystemPromptAssembler.assemble(
            listOf(
                PromptSection("a", order = 10, text = "A"),
                PromptSection("z", order = 10, text = "Z"),
            ),
        )
        assertEquals(first, second)
        assertEquals("A\n\nZ", first)
    }

    @Test
    fun byteIdenticalToLegacyManualAssembly() {
        // The app legacy layout: base, then each non-null fragment preceded by
        // "\n\n", then the runtime block. The assembler must reproduce it
        // exactly — the prompt is KV-cache-sensitive.
        val base = "identity line\nbig block"
        val skill = "<available_skills>x</available_skills>"
        val mcp = "<available_mcps>y</available_mcps>"
        val runtime = "Runtime context:\n- Current date: 2026-09-08 (Asia/Shanghai)\n- Device language: en\n- minis-model-use models available: 3"

        val legacy = buildString {
            append(base)
            append("\n\n").append(skill)
            append("\n\n").append(mcp)
            // global + daily fragments absent in this scenario
            append("\n\n").append(runtime)
        }
        val assembled = SystemPromptAssembler.assemble(
            listOf(
                PromptSection("base", order = 10, text = base),
                PromptSection("skills", order = 20, text = skill),
                PromptSection("mcp", order = 30, text = mcp),
                PromptSection("memory:global", order = 40, enabled = false, text = ""),
                PromptSection("memory:daily", order = 50, enabled = false, text = ""),
                PromptSection("runtime", order = 60, text = runtime),
            ),
        )
        assertEquals(legacy, assembled)
    }

    @Test
    fun sectionNameMustBeSingleLineAndNonBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptSection(name = "", order = 10, text = "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PromptSection(name = "a\nb", order = 10, text = "x")
        }
    }
}
