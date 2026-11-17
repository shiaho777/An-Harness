package com.anharness.app.remote

/**
 * [T-remote-ssh] Pure config/validation layer for remote SSH hosts (P4).
 *
 * Kept free of Android/SSH dependencies so the validation + command-building
 * contract is JVM-testable (same pattern as PhoneSafetyGate / ShellTimeoutPolicy).
 * The manager owns persistence; the transport owns the socket; this object
 * only decides what a well-formed host/command looks like.
 */
object RemoteHostConfig {

    /** Auth method for a host. Password and private-key secrets live in
     *  encrypted prefs, never in the host record itself. */
    enum class AuthMethod { PASSWORD, PRIVATE_KEY }

    /** A host record WITHOUT secrets (safe to list/log). */
    data class Host(
        val id: String,
        val label: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val authMethod: AuthMethod,
        /** TOFU-pinned SHA-256 host key fingerprint (base64), null until first connect. */
        val pinnedFingerprint: String? = null,
    )

    const val DEFAULT_PORT = 22
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /** Remote command timeout, aligned with the agent shell clamp (1..900s). */
    const val MIN_TIMEOUT_S = 1
    const val MAX_TIMEOUT_S = 900
    const val DEFAULT_TIMEOUT_S = 60

    /** Output cap, aligned with ExecutionCoordinator's local shell truncation. */
    const val MAX_OUTPUT_CHARS = 50_000

    /**
     * Validate host fields. Returns null when valid, else a human-readable
     * error. Hostname must be non-blank and free of whitespace/shell
     * metacharacters (it is passed to the SSH client, not a shell, but we
     * reject anything that looks like an injection attempt anyway).
     */
    fun validate(hostname: String, port: Int, username: String): String? {
        if (hostname.isBlank()) return "hostname is required"
        if (hostname.any { it.isWhitespace() }) return "hostname must not contain whitespace"
        if (hostname.any { it in ";|&$`\\\"'<>" }) return "hostname contains invalid characters"
        if (port !in MIN_PORT..MAX_PORT) return "port must be $MIN_PORT..$MAX_PORT"
        if (username.isBlank()) return "username is required"
        if (username.any { it.isWhitespace() }) return "username must not contain whitespace"
        return null
    }

    /** Clamp a requested timeout into the supported range. */
    fun clampTimeout(seconds: Int?): Int =
        (seconds ?: DEFAULT_TIMEOUT_S).coerceIn(MIN_TIMEOUT_S, MAX_TIMEOUT_S)

    /**
     * Truncate output keeping head + tail (mirrors TerminalSanitizer's
     * local-shell behavior) so a huge remote result doesn't blow the context.
     */
    fun truncateOutput(output: String, maxChars: Int = MAX_OUTPUT_CHARS): String {
        if (output.length <= maxChars) return output
        val keep = maxChars / 2
        val head = output.take(keep)
        val tail = output.takeLast(keep)
        val omitted = output.length - head.length - tail.length
        return "$head\n…[$omitted chars omitted]…\n$tail"
    }

    /**
     * Build the remote command string. We run the user's command through the
     * remote login shell verbatim (that is the point of remote_shell), but
     * reject a blank command here so the caller gets a clear error instead of
     * an empty remote exec.
     */
    fun buildCommand(command: String): String? {
        val trimmed = command.trim()
        return trimmed.ifBlank { null }
    }
}