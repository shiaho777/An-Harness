package com.anharness.agent

import org.junit.Assert.*
import org.junit.Test

class TruncationTest {
    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    @Test
    fun untouchedWhenUnderBothLimits() {
        val r = Truncation.head("a\nb\nc")
        assertFalse(r.truncated)
        assertNull(r.truncatedBy)
        assertEquals("a\nb\nc", r.content)
        assertEquals(3, r.totalLines)
    }

    @Test
    fun headKeepsFirstLinesAndReportsLineLimit() {
        val r = Truncation.head(lines(3000))
        assertTrue(r.truncated)
        assertEquals(Truncation.TruncatedBy.LINES, r.truncatedBy)
        assertEquals(2000, r.outputLines)
        assertEquals(3000, r.totalLines)
        assertTrue(r.content.startsWith("line 1"))
        assertFalse(r.content.contains("line 2001"))
    }

    @Test
    fun headByteLimitNeverReturnsPartialLines() {
        val longLines = (1..100).joinToString("\n") { "x".repeat(2000) }
        val r = Truncation.head(longLines, maxLines = 1000, maxBytes = 5000)
        assertTrue(r.truncated)
        assertEquals(Truncation.TruncatedBy.BYTES, r.truncatedBy)
        // Every returned line is complete (2000 chars each).
        r.content.split("\n").forEach { assertEquals(2000, it.length) }
        assertTrue(r.outputBytes <= 5000)
    }

    @Test
    fun headFirstLineExceedsLimit() {
        val r = Truncation.head("x".repeat(60000) + "\nshort", maxBytes = 1000)
        assertTrue(r.truncated)
        assertEquals(Truncation.TruncatedBy.BYTES, r.truncatedBy)
        assertTrue(r.firstLineExceedsLimit)
        assertEquals("", r.content)
    }

    @Test
    fun tailKeepsLastLines() {
        val r = Truncation.tail(lines(3000))
        assertTrue(r.truncated)
        assertEquals(Truncation.TruncatedBy.LINES, r.truncatedBy)
        assertTrue(r.content.endsWith("line 3000"))
        assertFalse(r.content.contains("line 1000\n"))
    }

    @Test
    fun tailPartialLineWhenSingleLineExceedsBytes() {
        val r = Truncation.tail("start\n" + "y".repeat(60000), maxBytes = 1000)
        assertTrue(r.truncated)
        assertEquals(Truncation.TruncatedBy.BYTES, r.truncatedBy)
        assertTrue(r.lastLinePartial)
        assertTrue(r.outputBytes <= 1000)
        assertEquals("y".repeat(1000), r.content)
    }

    @Test
    fun utf8ByteLengthIsMultibyteAware() {
        assertEquals(3, Truncation.utf8ByteLength("abc"))
        assertEquals(6, Truncation.utf8ByteLength("中中")) // 3 bytes each
        assertEquals(4, Truncation.utf8ByteLength("𝄞")) // surrogate pair, 4 bytes
    }

    @Test
    fun tailDoesNotSplitMultibyteCharacters() {
        val content = "中".repeat(1000)
        val r = Truncation.tail(content, maxBytes = 100)
        assertTrue(r.lastLinePartial)
        // 100 bytes / 3 bytes per char = 33 whole chars, no split char.
        assertEquals(33, r.content.length)
        assertTrue(r.content.all { it == '中' })
    }

    @Test
    fun formatSizeHumanReadable() {
        assertEquals("999B", Truncation.formatSize(999))
        assertEquals("1.5KB", Truncation.formatSize(1536))
        assertEquals("2.0MB", Truncation.formatSize(2 * 1024 * 1024))
    }

    @Test
    fun lineTruncationAppendsMarker() {
        assertEquals("short", Truncation.line("short"))
        val long = "z".repeat(600)
        assertEquals("z".repeat(500) + "... [truncated]", Truncation.line(long))
    }
}
