package com.anharness.agent

import org.junit.Assert.*
import org.junit.Test

class FuzzyMatchTest {

    @Test
    fun exactMatchSplicesOriginalCharacters() {
        val content = "fun main() {\n    println(\"hi\")\n}\n"
        val out = FuzzyMatch.applyStringEdit(content, "println(\"hi\")", "println(\"bye\")")
        assertTrue(out is FuzzyMatch.EditOutcome.Applied)
        val applied = out as FuzzyMatch.EditOutcome.Applied
        assertFalse(applied.fuzzy)
        assertEquals(1, applied.replacements)
        assertEquals("fun main() {\n    println(\"bye\")\n}\n", applied.content)
    }

    @Test
    fun smartQuotesAndDashesMatchFuzzily() {
        // File has smart quotes + em dash; model reproduces with ASCII.
        val content = "val s = \u201Chello\u201D \u2014 note\n"
        val out = FuzzyMatch.applyStringEdit(content, "val s = \"hello\" - note", "val t = \"bye\"")
        assertTrue(out is FuzzyMatch.EditOutcome.Applied)
        val applied = out as FuzzyMatch.EditOutcome.Applied
        assertTrue(applied.fuzzy)
        assertEquals("val t = \"bye\"\n", applied.content)
    }

    @Test
    fun trailingWhitespaceDriftIsTolerated() {
        val content = "line one   \nline two\n"
        val out = FuzzyMatch.applyStringEdit(content, "line one\nline two", "both replaced")
        assertTrue(out is FuzzyMatch.EditOutcome.Applied)
        assertEquals("both replaced\n", (out as FuzzyMatch.EditOutcome.Applied).content)
    }

    @Test
    fun crlfFileKeepsCrLfEndings() {
        val content = "a = 1\r\nb = 2\r\n"
        val out = FuzzyMatch.applyStringEdit(content, "b = 2", "b = 3")
        assertTrue(out is FuzzyMatch.EditOutcome.Applied)
        val applied = out as FuzzyMatch.EditOutcome.Applied
        assertEquals("a = 1\r\nb = 3\r\n", applied.content)
    }

    @Test
    fun nbspMatchesPlainSpace() {
        val content = "if (x == 1) return\n"
        val out = FuzzyMatch.applyStringEdit(content, "if (x == 1) return", "if (x == 2) return")
        assertTrue(out is FuzzyMatch.EditOutcome.Applied)
    }

    @Test
    fun notFoundWhenNothingMatches() {
        val out = FuzzyMatch.applyStringEdit("abc\n", "zzz", "q")
        assertTrue(out is FuzzyMatch.EditOutcome.NotFound)
    }

    @Test
    fun ambiguousWithoutReplaceAll() {
        val content = "x = 1\ny = 2\nx = 1\n"
        val out = FuzzyMatch.applyStringEdit(content, "x = 1", "x = 9")
        assertTrue(out is FuzzyMatch.EditOutcome.Ambiguous)
        assertEquals(2, (out as FuzzyMatch.EditOutcome.Ambiguous).occurrences)
    }

    @Test
    fun replaceAllReplacesEveryOccurrence() {
        val content = "x = 1\ny = 2\nx = 1\n"
        val out = FuzzyMatch.applyStringEdit(content, "x = 1", "x = 9", replaceAll = true)
        assertTrue(out is FuzzyMatch.EditOutcome.Applied)
        val applied = out as FuzzyMatch.EditOutcome.Applied
        assertEquals(2, applied.replacements)
        assertEquals("x = 9\ny = 2\nx = 9\n", applied.content)
    }

    @Test
    fun fuzzySplicePreservesUntouchedContent() {
        // The drift (trailing spaces) sits INSIDE the needle's span, so exact
        // matching fails; content outside the match keeps its original characters.
        val content = "header \u2014 keep\nold line   \nnext line\nfooter   \n"
        val out = FuzzyMatch.applyStringEdit(content, "old line\nnext line", "replaced\ntwo")
        assertTrue(out is FuzzyMatch.EditOutcome.Applied)
        val applied = out as FuzzyMatch.EditOutcome.Applied
        assertTrue(applied.fuzzy)
        // The em dash header and the trailing spaces on footer survive.
        assertEquals("header \u2014 keep\nreplaced\ntwo\nfooter   \n", applied.content)
    }

    @Test
    fun emptyOldStringIsNotFound() {
        assertTrue(FuzzyMatch.applyStringEdit("abc", "", "x") is FuzzyMatch.EditOutcome.NotFound)
    }
}
