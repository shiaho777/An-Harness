package com.anharness.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retry policy with deterministic exponential backoff (port of pi
 * `RetryPolicy` + `retry.ts`): delay = baseDelayMs * 2^(attempt-1),
 * no jitter — replays identically across drives and processes.
 *
 * Loop-level streaming retries stay with the caller (a failed stream can have
 * already emitted partial content). This helper covers self-contained calls:
 * the compaction/branch summarizers and other one-shot completions.
 */
data class RetryPolicy(
    val enabled: Boolean = true,
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1000,
) {
    val maxAttempts: Int get() = if (enabled) maxRetries + 1 else 1

    /** Deterministic: attempt 1 → base, 2 → 2×base, 3 → 4×base. */
    fun delayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        var d = baseDelayMs
        repeat(attempt - 1) {
            d = d shl 1
            if (d < 0) return Long.MAX_VALUE // overflow guard
        }
        return d
    }
}

suspend fun <T> withRetry(
    policy: RetryPolicy,
    isRetryable: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    var last: Throwable? = null
    for (attempt in 1..policy.maxAttempts) {
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!isRetryable(e) || attempt == policy.maxAttempts) throw e
            last = e
            delay(policy.delayMs(attempt))
        }
    }
    throw last ?: error("unreachable")
}

/** Outcome of a one-shot completion. Failures are values, never thrown. */
sealed interface CompleteOutcome {
    data class Ok(val text: String, val stopReason: StopReason) : CompleteOutcome
    data class Failed(val error: String, val attempts: Int) : CompleteOutcome
}

/**
 * Collect one assistant response from a [StreamFn] as a suspending call,
 * retrying stream-level [LlmStreamEvent.Error]s per [policy]. The stream is
 * only safe to retry before content: [CompleteOutcome.Failed] means no usable
 * text was produced in the final attempt.
 *
 * Used by the compaction summarizer (standalone requests, isolated from the
 * session stream — port of pi `completeSimpleWithRetries`).
 */
suspend fun completeSimple(
    streamFn: StreamFn,
    model: ModelDescriptor,
    systemPrompt: String,
    userPrompt: String,
    options: StreamOptions,
    policy: RetryPolicy = RetryPolicy(),
): CompleteOutcome {
    var attempts = 0
    var lastError = "unknown error"
    val context = LlmContext(systemPrompt, listOf(LlmMessage.User(userPrompt)))
    while (attempts < policy.maxAttempts) {
        attempts++
        val text = StringBuilder()
        var stop: StopReason = StopReason.STOP
        var failed = false
        try {
            streamFn(model, context, options).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> text.append(event.delta)
                    is LlmStreamEvent.ToolCallDelta -> Unit // summaries are text-only
                    is LlmStreamEvent.Done -> {
                        text.clear().append(event.message.text)
                        stop = event.message.stopReason
                    }
                    is LlmStreamEvent.Error -> {
                        lastError = event.message
                        failed = true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            failed = true
        }
        if (!failed) return CompleteOutcome.Ok(text.toString(), stop)
        if (attempts < policy.maxAttempts) delay(policy.delayMs(attempts))
    }
    return CompleteOutcome.Failed(lastError, attempts)
}
