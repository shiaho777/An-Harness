package com.anharness.app.sandbox

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * Sanitize terminal output:
     * 1. Strip ANSI/VT escape sequences FIRST — escapes are zero-width on
     *    a real terminal, so they must not occupy overwrite columns.
     * 2. CR folding — simulate carriage return overwriting.
     * 3. Drop remaining control chars / PRoot "null" artifacts.
     *
     * No final trim: trailing spaces can be real overwrite residue
     * ("downloading" → "complete   " keeps the last three columns), and
     * stripping them silently corrupts progress-bar output.
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // Pass 1: Strip ANSI sequences (before folding — see above).
        val stripped = ANSI_REGEX.replace(raw, "")

        // Pass 2: CR folding
        val crFolded = foldCarriageReturns(stripped)

        // Pass 3: Remove null bytes and non-printable control chars (except \n \t)
        val cleaned = crFolded.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        // Pass 4: Remove "null" artifacts from PRoot/pipe issues
        // - Lines that are entirely "null"
        // - Runs of repeated "null" (e.g., "nullnullnull" → "")
        // - Lines that are just "null" appended to a prefix (e.g., "file:nullnullnull")
        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "") // Remove runs of 2+ consecutive "null"

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2)
        return noNullLines.replace(Regex("\n{3,}"), "\n\n")
    }

    /**
     * Truncate output if it exceeds maxChars, keeping head and tail.
     */
    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    /**
     * Simulate CR (\r) with a real column buffer: each \r resets the
     * cursor to column 0 and subsequent characters OVERWRITE in place —
     * columns beyond the new text keep their previous content. The old
     * "last non-empty segment wins" model dropped that residue, turning
     * "AAAA\rBB" into "BB" instead of the terminal-visible "BBAA", and
     * losing wget/progress-bar tails. A trailing \r before \n (CRLF)
     * resets an already-flushed line cursor and is a no-op.
     */
    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()

        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')

            if ('\r' !in line) {
                result.append(line)
                continue
            }

            val buffer = StringBuilder()
            var cursor = 0
            for (ch in line) {
                if (ch == '\r') {
                    cursor = 0
                    continue
                }
                if (cursor < buffer.length) {
                    buffer.setCharAt(cursor, ch)
                } else {
                    buffer.append(ch)
                }
                cursor++
            }
            result.append(buffer)
        }

        return result.toString()
    }
}
