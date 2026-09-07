package com.anharness.agent

/**
 * Unified tool-output truncation contract (port of pi `utils/truncate.ts`).
 *
 * Two independent limits — whichever is hit first wins:
 *  - line limit (default [DEFAULT_MAX_LINES])
 *  - byte limit (default [DEFAULT_MAX_BYTES])
 *
 * Never returns partial lines, except the tail-truncation edge case where the
 * final line alone exceeds the byte limit. Byte accounting is UTF-8 aware.
 *
 * Every caller should surface the metadata to the model so it can page further
 * (`offset` hints, spill-file paths) instead of silently losing output.
 */
object Truncation {
    const val DEFAULT_MAX_LINES = 2000
    const val DEFAULT_MAX_BYTES = 50 * 1024 // 50KB
    const val GREP_MAX_LINE_LENGTH = 500

    enum class TruncatedBy { LINES, BYTES }

    data class Result(
        val content: String,
        val truncated: Boolean,
        val truncatedBy: TruncatedBy?,
        val totalLines: Int,
        val totalBytes: Int,
        val outputLines: Int,
        val outputBytes: Int,
        /** Only for tail truncation when the final line alone exceeds the byte limit. */
        val lastLinePartial: Boolean = false,
        /** Only for head truncation when the first line alone exceeds the byte limit. */
        val firstLineExceedsLimit: Boolean = false,
        val maxLines: Int = DEFAULT_MAX_LINES,
        val maxBytes: Int = DEFAULT_MAX_BYTES,
    )

    /** UTF-8 byte length without allocating a ByteArray. */
    fun utf8ByteLength(content: String): Int {
        var bytes = 0
        var i = 0
        while (i < content.length) {
            val c = content[i].code
            when {
                c <= 0x7F -> bytes += 1
                c <= 0x7FF -> bytes += 2
                Character.isHighSurrogate(c.toChar()) &&
                    i + 1 < content.length &&
                    Character.isLowSurrogate(content[i + 1]) -> {
                    bytes += 4
                    i++
                }
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
    }

    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        return if (content.endsWith("\n")) lines.dropLast(1) else lines
    }

    private fun untouched(content: String, maxLines: Int, maxBytes: Int): Result = Result(
        content = content,
        truncated = false,
        truncatedBy = null,
        totalLines = splitLines(content).size,
        totalBytes = utf8ByteLength(content),
        outputLines = splitLines(content).size,
        outputBytes = utf8ByteLength(content),
        maxLines = maxLines,
        maxBytes = maxBytes,
    )

    /**
     * Keep the FIRST lines/bytes — for file reads where the beginning matters.
     * If the first line alone exceeds [maxBytes], returns empty content with
     * `firstLineExceedsLimit=true` so the caller can suggest a recovery path.
     */
    fun head(content: String, maxLines: Int = DEFAULT_MAX_LINES, maxBytes: Int = DEFAULT_MAX_BYTES): Result {
        val lines = splitLines(content)
        val totalBytes = utf8ByteLength(content)
        if (lines.size <= maxLines && totalBytes <= maxBytes) {
            return Result(
                content, false, null, lines.size, totalBytes, lines.size, totalBytes,
                maxLines = maxLines, maxBytes = maxBytes,
            )
        }

        if (lines.isNotEmpty() && utf8ByteLength(lines[0]) > maxBytes) {
            return Result(
                "", true, TruncatedBy.BYTES, lines.size, totalBytes, 0, 0,
                firstLineExceedsLimit = true, maxLines = maxLines, maxBytes = maxBytes,
            )
        }

        val out = ArrayList<String>()
        var outBytes = 0
        var by = TruncatedBy.LINES
        for ((i, line) in lines.withIndex()) {
            if (i >= maxLines) break
            val lineBytes = utf8ByteLength(line) + (if (i > 0) 1 else 0) // +1 for '\n'
            if (outBytes + lineBytes > maxBytes) {
                by = TruncatedBy.BYTES
                break
            }
            out.add(line)
            outBytes += lineBytes
        }
        if (out.size >= maxLines && outBytes <= maxBytes) by = TruncatedBy.LINES
        val joined = out.joinToString("\n")
        return Result(
            joined, true, by, lines.size, totalBytes, out.size, utf8ByteLength(joined),
            maxLines = maxLines, maxBytes = maxBytes,
        )
    }

    /**
     * Keep the LAST lines/bytes — for command output where errors and final
     * state live at the end. May return a partial first line when the final
     * line alone exceeds [maxBytes] (`lastLinePartial=true`).
     */
    fun tail(content: String, maxLines: Int = DEFAULT_MAX_LINES, maxBytes: Int = DEFAULT_MAX_BYTES): Result {
        val lines = splitLines(content)
        val totalBytes = utf8ByteLength(content)
        if (lines.size <= maxLines && totalBytes <= maxBytes) {
            return Result(
                content, false, null, lines.size, totalBytes, lines.size, totalBytes,
                maxLines = maxLines, maxBytes = maxBytes,
            )
        }

        val out = ArrayDeque<String>()
        var outBytes = 0
        var by = TruncatedBy.LINES
        var partial = false
        var i = lines.size - 1
        while (i >= 0 && out.size < maxLines) {
            val line = lines[i]
            val lineBytes = utf8ByteLength(line) + (if (out.isNotEmpty()) 1 else 0)
            if (outBytes + lineBytes > maxBytes) {
                by = TruncatedBy.BYTES
                if (out.isEmpty()) {
                    // Single line exceeds the byte budget: keep its tail end.
                    val kept = truncateStringToBytesFromEnd(line, maxBytes)
                    out.addFirst(kept)
                    outBytes = utf8ByteLength(kept)
                    partial = true
                }
                break
            }
            out.addFirst(line)
            outBytes += lineBytes
            i--
        }
        if (out.size >= maxLines && outBytes <= maxBytes) by = TruncatedBy.LINES
        val joined = out.joinToString("\n")
        return Result(
            joined, true, by, lines.size, totalBytes, out.size, utf8ByteLength(joined),
            lastLinePartial = partial, maxLines = maxLines, maxBytes = maxBytes,
        )
    }

    /** Cut a string to [maxBytes] keeping its END; stays on UTF-8 code-point boundaries. */
    private fun truncateStringToBytesFromEnd(str: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var bytes = 0
        var start = str.length
        var i = str.length
        while (i > 0) {
            var charStart = i - 1
            val code = str[charStart].code
            val charBytes: Int
            if (code in 0xDC00..0xDFFF && charStart > 0 && str[charStart - 1].code in 0xD800..0xDBFF) {
                charStart--
                charBytes = 4
            } else {
                charBytes = when {
                    code <= 0x7F -> 1
                    code <= 0x7FF -> 2
                    else -> 3
                }
            }
            if (bytes + charBytes > maxBytes) break
            bytes += charBytes
            start = charStart
            i = charStart
        }
        return str.substring(start)
    }

    /** Truncate a single line to [maxChars], appending a marker (grep-style output). */
    fun line(lineText: String, maxChars: Int = GREP_MAX_LINE_LENGTH): String =
        if (lineText.length <= maxChars) lineText
        else lineText.substring(0, maxChars) + "... [truncated]"
}
