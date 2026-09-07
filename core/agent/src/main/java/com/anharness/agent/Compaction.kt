package com.anharness.agent

import com.anharness.llm.CompleteOutcome
import com.anharness.llm.LlmMessage
import com.anharness.llm.RetryPolicy
import com.anharness.llm.completeSimple
import com.anharness.llm.stringArg

/**
 * Compaction pipeline (port of pi `harness/compaction/compaction.ts`,
 * adapted to the flat LlmMessage model).
 *
 * Design invariants, same as pi:
 *  - cut points respect turn boundaries: a retained tail never starts with a
 *    ToolResult, so every tool-call chain survives whole;
 *  - a cut that lands mid-turn is a *split turn*: the turn's prefix is
 *    summarized with a dedicated prompt and appended to the history summary;
 *  - summaries are iterative: the previous summary is updated with new
 *    messages, never rebuilt from scratch;
 *  - the summary is a structured work handoff (Goal / Constraints / Progress /
 *    Key Decisions / Next Steps / Critical Context), plus an inventory of
 *    files read and modified;
 *  - compaction failure leaves the context untouched (the caller keeps going
 *    uncompacted) — silently degrading to a sliding window would hide the
 *    failure.
 *
 * Token accounting: v0.2 uses the chars/4 heuristic for every message. Pi
 * additionally anchors on the last assistant usage block; LlmMessage carries
 * no usage, so that anchor lands when usage tracking reaches core:llm.
 */

interface CompactionStrategy {
    /** Return the messages that should actually be sent this turn. */
    suspend fun compact(messages: List<LlmMessage>): List<LlmMessage>
}

/** Keep system-adjacent head + last N messages. Zero LLM cost. */
class SlidingWindowCompaction(private val keepLast: Int = 40) : CompactionStrategy {
    override suspend fun compact(messages: List<LlmMessage>): List<LlmMessage> {
        if (messages.size <= keepLast) return messages
        val head = messages.firstOrNull { it is LlmMessage.User }
        val tail = messages.takeLast(keepLast)
        val notice = LlmMessage.User(
            "[compaction] ${messages.size - tail.size} older messages trimmed " +
                "by sliding window; continuing from recent context.",
        )
        return listOfNotNull(head, notice) + tail
    }
}

// ── settings & estimation ──────────────────────────────────────────

data class CompactionSettings(
    val enabled: Boolean = true,
    /** Tokens reserved for the summary prompt + output. */
    val reserveTokens: Int = 16_384,
    /** Approximate recent-context tokens to keep after compaction. */
    val keepRecentTokens: Int = 20_000,
)

fun interface CompactionSettingsSource {
    fun get(): CompactionSettings
}

fun interface ContextWindowSource {
    fun get(): Int
}

/** Conservative chars/4 estimate, matching pi's heuristic. */
fun estimateTokens(message: LlmMessage): Int {
    val chars = when (message) {
        is LlmMessage.User -> message.text.length
        is LlmMessage.Assistant -> message.text.length +
            message.toolCalls.sumOf { it.name.length + it.arguments.toString().length }
        is LlmMessage.ToolResult -> message.text.length
    }
    return (chars + 3) / 4
}

fun estimateContextTokens(messages: List<LlmMessage>): Int = messages.sumOf(::estimateTokens)

fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings): Boolean =
    settings.enabled && contextTokens > contextWindow - settings.reserveTokens

// ── cut-point selection ────────────────────────────────────────────

data class CutPoint(
    /** Index of the first message retained after compaction. */
    val firstKeptIndex: Int,
    /** Index of the User message opening the split turn, or -1. */
    val turnStartIndex: Int,
    /** Whether the cut splits an in-progress turn. */
    val isSplitTurn: Boolean,
)

/**
 * Find the cut that keeps ~[keepRecentTokens] of recent messages. Cut
 * candidates are User and Assistant messages only — never a ToolResult — so
 * the retained tail always contains whole tool chains.
 */
fun findCutPoint(messages: List<LlmMessage>, keepRecentTokens: Int): CutPoint {
    val candidates = messages.indices.filter {
        messages[it] is LlmMessage.User || messages[it] is LlmMessage.Assistant
    }
    if (candidates.isEmpty()) return CutPoint(0, -1, false)

    var accumulated = 0
    var cutIndex = candidates.first()
    for (i in messages.indices.reversed()) {
        accumulated += estimateTokens(messages[i])
        if (accumulated >= keepRecentTokens) {
            cutIndex = candidates.firstOrNull { it >= i } ?: candidates.first()
            break
        }
    }

    val cutIsUser = messages[cutIndex] is LlmMessage.User
    val turnStart = if (cutIsUser) {
        -1
    } else {
        var idx = -1
        for (i in cutIndex downTo 0) {
            if (messages[i] is LlmMessage.User) { idx = i; break }
        }
        idx
    }
    return CutPoint(cutIndex, turnStart, !cutIsUser && turnStart != -1)
}

// ── preparation ────────────────────────────────────────────────────

class CompactionPreparation(
    val messagesToSummarize: List<LlmMessage>,
    val turnPrefixMessages: List<LlmMessage>,
    val retainedTail: List<LlmMessage>,
    val isSplitTurn: Boolean,
    val tokensBefore: Int,
)

/** Partition [messages] for compaction, or return null when there is nothing to compact. */
fun prepareCompaction(
    messages: List<LlmMessage>,
    settings: CompactionSettings,
): CompactionPreparation? {
    if (messages.size < 2) return null
    val tokensBefore = estimateContextTokens(messages)
    val cut = findCutPoint(messages, settings.keepRecentTokens)
    if (cut.firstKeptIndex <= 0) return null // nothing would be summarized

    val historyEnd = if (cut.isSplitTurn) cut.turnStartIndex else cut.firstKeptIndex
    return CompactionPreparation(
        messagesToSummarize = messages.subList(0, historyEnd),
        turnPrefixMessages = if (cut.isSplitTurn) {
            messages.subList(cut.turnStartIndex, cut.firstKeptIndex)
        } else {
            emptyList()
        },
        retainedTail = messages.subList(cut.firstKeptIndex, messages.size),
        isSplitTurn = cut.isSplitTurn,
        tokensBefore = tokensBefore,
    )
}

// ── file-operation inventory ───────────────────────────────────────

class FileOps {
    val read = LinkedHashSet<String>()
    val modified = LinkedHashSet<String>()
    val isEmpty: Boolean get() = read.isEmpty() && modified.isEmpty()
}

/** Inventory the file paths the compacted history touched (read vs modified). */
fun extractFileOps(messages: List<LlmMessage>): FileOps {
    val ops = FileOps()
    for (msg in messages) {
        if (msg !is LlmMessage.Assistant) continue
        for (call in msg.toolCalls) {
            val path = call.arguments.stringArg("path")
            if (path.isBlank()) continue
            when (call.name) {
                "file_read", "read_image" -> ops.read.add(path)
                "file_write", "file_edit" -> ops.modified.add(path)
            }
        }
    }
    return ops
}

fun formatFileOps(ops: FileOps): String {
    if (ops.isEmpty) return ""
    val sb = StringBuilder("\n\n## Files Touched")
    if (ops.read.isNotEmpty()) sb.append("\n- Read: ").append(ops.read.joinToString(", "))
    if (ops.modified.isNotEmpty()) sb.append("\n- Modified: ").append(ops.modified.joinToString(", "))
    return sb.toString()
}

// ── prompts (verbatim ports of pi's) ───────────────────────────────

const val SUMMARIZATION_SYSTEM_PROMPT =
    "You are a context summarization assistant. Your task is to read a conversation between a user and an " +
        "AI assistant, then produce a structured summary following the exact format specified.\n\n" +
        "Do NOT continue the conversation. Do NOT respond to any questions in the conversation. " +
        "ONLY output the structured summary."

val SUMMARIZATION_PROMPT = """
The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or "(none)" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages.
""".trimIndent()

val UPDATE_SUMMARIZATION_PROMPT = """
The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from "In Progress" to "Done" when completed
- UPDATE "Next Steps" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages.
""".trimIndent()

val TURN_PREFIX_SUMMARIZATION_PROMPT = """
This is the PREFIX of a turn that was too large to keep. The SUFFIX (recent work) is retained.

Summarize the prefix to provide context for the retained suffix:

## Original Request
[What did the user ask for in this turn?]

## Early Progress
- [Key decisions and work done in the prefix]

## Context for Suffix
- [Information needed to understand the retained recent work]

Be concise. Focus on what's needed to understand the kept suffix.
""".trimIndent()

const val COMPACTION_SUMMARY_PREFIX =
    "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
const val COMPACTION_SUMMARY_SUFFIX = "\n</summary>"

// ── serialization for the summary prompt ───────────────────────────

/** Per-block cap inside the serialized conversation — the prompt must stay bounded. */
private const val SERIALIZE_BLOCK_CAP = 2_000

private fun String.capped(): String =
    if (length <= SERIALIZE_BLOCK_CAP) this
    else substring(0, SERIALIZE_BLOCK_CAP) + "… [${length - SERIALIZE_BLOCK_CAP} chars omitted]"

fun serializeConversation(messages: List<LlmMessage>): String = messages.joinToString("\n\n") { msg ->
    when (msg) {
        is LlmMessage.User -> "[User]: ${msg.text.capped()}"
        is LlmMessage.Assistant -> buildString {
            append("[Assistant]: ").append(msg.text.capped())
            for (call in msg.toolCalls) {
                append("\n[Tool call: ").append(call.name)
                append("(").append(call.arguments.toString().capped()).append(")]")
            }
        }
        is LlmMessage.ToolResult -> buildString {
            append("[Tool result (").append(msg.toolName).append(")")
            if (msg.isError) append(" ERROR")
            append("]: ").append(msg.text.capped())
        }
    }
}

// ── summary generation ─────────────────────────────────────────────

/** One-shot completion boundary (pi's SummaryRequest). Null = failed; compaction aborts. */
fun interface SummaryRequest {
    suspend fun complete(systemPrompt: String, userPrompt: String): String?
}

/**
 * Generate (or iteratively update) the compaction summary for a preparation.
 * Split turns issue two completions: history, then the turn prefix.
 */
suspend fun generateSummary(
    preparation: CompactionPreparation,
    previousSummary: String?,
    customInstructions: String?,
    request: SummaryRequest,
): String? {
    var historyText = "No prior history."
    if (preparation.messagesToSummarize.isNotEmpty()) {
        historyText = request.complete(
            SUMMARIZATION_SYSTEM_PROMPT,
            buildSummaryPrompt(preparation.messagesToSummarize, previousSummary, customInstructions),
        ) ?: return null
    }

    var summary = historyText
    if (preparation.isSplitTurn && preparation.turnPrefixMessages.isNotEmpty()) {
        val prefixText = request.complete(
            SUMMARIZATION_SYSTEM_PROMPT,
            "<conversation>\n${serializeConversation(preparation.turnPrefixMessages)}\n</conversation>\n\n" +
                TURN_PREFIX_SUMMARIZATION_PROMPT,
        ) ?: return null
        summary += "\n\n---\n\n**Turn Context (split turn):**\n\n$prefixText"
    }

    val fileOps = extractFileOps(preparation.messagesToSummarize + preparation.turnPrefixMessages)
    return summary + formatFileOps(fileOps)
}

private fun buildSummaryPrompt(
    messages: List<LlmMessage>,
    previousSummary: String?,
    customInstructions: String?,
): String {
    var base = if (previousSummary != null) UPDATE_SUMMARIZATION_PROMPT else SUMMARIZATION_PROMPT
    if (!customInstructions.isNullOrBlank()) base += "\n\nAdditional focus: $customInstructions"
    return buildString {
        append("<conversation>\n")
        append(serializeConversation(messages))
        append("\n</conversation>\n\n")
        if (previousSummary != null) {
            append("<previous-summary>\n").append(previousSummary).append("\n</previous-summary>\n\n")
        }
        append(base)
    }
}

/** Adapt a core:llm StreamFn into the SummaryRequest boundary, with deterministic retries. */
class LlmSummaryRequest(
    private val streamFn: com.anharness.llm.StreamFn,
    private val model: com.anharness.llm.ModelDescriptor,
    private val options: com.anharness.llm.StreamOptions,
    private val retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 2, baseDelayMs = 500),
) : SummaryRequest {
    override suspend fun complete(systemPrompt: String, userPrompt: String): String? =
        when (val out = completeSimple(streamFn, model, systemPrompt, userPrompt, options, retryPolicy)) {
            is CompleteOutcome.Ok -> out.text.ifBlank { null }
            is CompleteOutcome.Failed -> null
        }
}

// ── the strategy ───────────────────────────────────────────────────

/**
 * Compaction that summarizes dropped history with an LLM (pi semantics).
 * On threshold miss the context passes through untouched; on summarization
 * failure it also passes through — silently degrading to sliding window
 * would trade correctness for an illusion of health.
 */
class LlmSummarizingCompaction(
    private val contextWindow: ContextWindowSource,
    private val settingsSource: CompactionSettingsSource,
    private val request: SummaryRequest,
    private val previousSummary: () -> String? = { null },
    private val onSummary: (String) -> Unit = {},
    private val customInstructions: (() -> String?)? = null,
) : CompactionStrategy {
    override suspend fun compact(messages: List<LlmMessage>): List<LlmMessage> {
        val settings = settingsSource.get()
        if (!shouldCompact(estimateContextTokens(messages), contextWindow.get(), settings)) return messages
        val preparation = prepareCompaction(messages, settings) ?: return messages
        val summary = generateSummary(
            preparation,
            previousSummary(),
            customInstructions?.invoke(),
            request,
        ) ?: return messages
        onSummary(summary)
        return listOf(LlmMessage.User(COMPACTION_SUMMARY_PREFIX + summary + COMPACTION_SUMMARY_SUFFIX)) +
            preparation.retainedTail
    }
}
