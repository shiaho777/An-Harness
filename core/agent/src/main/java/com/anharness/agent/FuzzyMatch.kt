package com.anharness.agent

/**
 * Fuzzy text matching for the edit tool (port of pi `tools/edit-diff.ts`).
 *
 * Models routinely reproduce file content with invisible drift — smart quotes
 * instead of ASCII quotes, em-dashes instead of `-`, NBSP instead of space,
 * trailing whitespace, CRLF vs LF. Exact matching fails on that drift and
 * forces a wasteful read→retry cycle.
 *
 * Strategy (mirrors pi):
 *  1. Work on LF-normalized text; remember the file's original ending style.
 *  2. Try exact match first — zero risk, zero drift.
 *  3. Fall back to normalized comparison ([normalize]) while keeping a
 *     char-level map back to the LF text, so the replacement splices into the
 *     ORIGINAL characters and untouched content is preserved byte-for-byte.
 *  4. Restore the original line-ending style in the result.
 */
object FuzzyMatch {

    enum class Ending { LF, CRLF }

    sealed interface EditOutcome {
        /** Splice succeeded. [fuzzy]=true means only normalized matching worked. */
        data class Applied(val content: String, val replacements: Int, val fuzzy: Boolean) : EditOutcome
        data object NotFound : EditOutcome
        /** [occurrences] candidates exist but replaceAll was false. */
        data class Ambiguous(val occurrences: Int) : EditOutcome
    }

    fun detectLineEnding(content: String): Ending {
        val crlf = content.indexOf("\r\n")
        val lf = content.indexOf('\n')
        if (lf == -1) return Ending.LF
        if (crlf == -1) return Ending.LF
        return if (crlf < lf) Ending.CRLF else Ending.LF
    }

    fun normalizeToLF(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    fun restoreLineEndings(text: String, ending: Ending): String =
        if (ending == Ending.CRLF) text.replace("\n", "\r\n") else text

    /**
     * Progressive normalization for fuzzy matching (port of pi
     * `normalizeForFuzzyMatch`): strip trailing whitespace per line, smart
     * quotes → ASCII, Unicode dashes → '-', special spaces → ' '.
     * NFKC is applied per character so the emitted characters can be mapped
     * back to their source positions.
     */
    fun normalize(text: String): String = normalizeWithMap(text).first

    /**
     * Normalize [text] and return (normalized, map) where map[i] is the index
     * in the ORIGINAL text of the character that produced normalized[i].
     * Characters dropped by per-line trailing-whitespace stripping are absent
     * from the normalized text, so spans between two mapped chars transparently
     * cover dropped drift in the original.
     */
    internal fun normalizeWithMap(text: String): Pair<String, IntArray> {
        val out = StringBuilder(text.length)
        val map = ArrayList<Int>(text.length)
        var lineStart = 0
        var i = 0
        fun emit(c: Char, src: Int) {
            out.append(c)
            map.add(src)
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                // trimEnd: drop buffered trailing whitespace of the current line
                while (out.length > lineStart && (out[out.length - 1] == ' ' || out[out.length - 1] == '\t')) {
                    out.deleteCharAt(out.length - 1)
                    map.removeAt(map.size - 1)
                }
                emit('\n', i)
                lineStart = out.length
                i++
                continue
            }
            // NFKC per char (may expand, e.g. ﬁ → "fi"), then fold lookalikes.
            val nfkc = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFKC)
            for (k in nfkc.indices) {
                emit(foldChar(nfkc[k]), i)
            }
            i++
        }
        // trimEnd on the final line (no trailing newline)
        while (out.length > lineStart && (out[out.length - 1] == ' ' || out[out.length - 1] == '\t')) {
            out.deleteCharAt(out.length - 1)
            map.removeAt(map.size - 1)
        }
        return out.toString() to map.toIntArray()
    }

    private fun foldChar(c: Char): Char = when (c) {
        '‘', '’', '‚', '‛' -> '\''
        '“', '”', '„', '‟' -> '"'
        '‐', '‑', '‒', '–', '—', '―', '−' -> '-'
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　' -> ' '
        else -> c
    }

    /**
     * Replace occurrences of [oldString] in [content] with [newString],
     * tolerating invisible drift. Exact matches are preferred; normalized
     * matching is the fallback. Splices into the original characters and
     * restores the file's line-ending style.
     */
    fun applyStringEdit(
        content: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean = false,
    ): EditOutcome {
        if (oldString.isEmpty()) return EditOutcome.NotFound

        val ending = detectLineEnding(content)
        val lf = normalizeToLF(content)
        val oldLf = normalizeToLF(oldString)
        val newLf = normalizeToLF(newString)

        // 1. exact — splice directly into the LF view.
        var spans = exactSpans(lf, oldLf)
        var fuzzy = false

        // 2. fuzzy — normalized comparison, splice mapped back to the LF view.
        if (spans.isEmpty()) {
            fuzzy = true
            spans = fuzzySpans(lf, oldLf)
        }

        if (spans.isEmpty()) return EditOutcome.NotFound
        if (spans.size > 1 && !replaceAll) return EditOutcome.Ambiguous(spans.size)

        val targets = if (replaceAll) spans else spans.take(1)
        val edited = StringBuilder(lf)
        for (span in targets.asReversed()) {
            edited.replace(span.first, span.last + 1, newLf)
        }
        return EditOutcome.Applied(
            content = restoreLineEndings(edited.toString(), ending),
            replacements = targets.size,
            fuzzy = fuzzy,
        )
    }

    private fun exactSpans(haystack: String, needle: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            out.add(idx until idx + needle.length)
            from = idx + needle.length
        }
        return out
    }

    private fun fuzzySpans(lf: String, oldLf: String): List<IntRange> {
        val (nHay, src) = normalizeWithMap(lf)
        val nNeedle = normalize(oldLf)
        if (nNeedle.isEmpty()) return emptyList()

        val spans = mutableListOf<IntRange>()
        var from = 0
        var lastOriginalEnd = -1
        while (true) {
            val idx = nHay.indexOf(nNeedle, from)
            if (idx < 0) break
            val origStart = src[idx]
            val origEnd = src[idx + nNeedle.length - 1] + 1
            // Overlap guard: two normalized matches can share original chars
            // when trailing whitespace was dropped between them.
            if (origStart >= lastOriginalEnd) {
                spans.add(origStart until origEnd)
                lastOriginalEnd = origEnd
            }
            from = idx + nNeedle.length
        }
        return spans
    }
}
