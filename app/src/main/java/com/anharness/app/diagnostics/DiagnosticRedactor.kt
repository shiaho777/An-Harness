package com.anharness.app.diagnostics

/**
 * [T-diag-redaction] Pure redaction rules for diagnostic bundles (P5
 * engineering parity: dsh-Go's sanitized diagnostics export).
 *
 * A diagnostics bundle leaves the device (user shares it with a
 * maintainer), so anything credential-shaped or personally identifying
 * must be masked BEFORE packaging. Rules are conservative: over-masking
 * a log line is acceptable; leaking one API key is not.
 *
 * Pure string transformations — JVM-testable, no Android dependencies.
 */
object DiagnosticRedactor {

    const val MASK = "«redacted»"

    private data class Rule(val regex: Regex, val replacement: String)

    private val rules: List<Rule> = listOf(
        // Bearer / basic auth headers (keep the scheme for diagnosis).
        Rule(Regex("""(?i)(authorization\s*[:=]\s*(?:bearer|basic)\s+)[A-Za-z0-9+/_\-.=]+"""), "$1" + MASK),
        // Common key/token/secret assignments in logs, env dumps, JSON.
        Rule(Regex("""(?i)((?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|auth[_-]?token|secret|password|passwd|client[_-]?secret)["']?\s*[:=]\s*["']?)[^\s"',;&}{]{4,}"""), "$1" + MASK),
        // Vendor key shapes.
        Rule(Regex("""sk-[A-Za-z0-9_\-]{8,}"""), MASK),                       // OpenAI-style
        Rule(Regex("""sk-ant-[A-Za-z0-9_\-]{8,}"""), MASK),                   // Anthropic
        Rule(Regex("""ghp_[A-Za-z0-9]{20,}"""), MASK),                        // GitHub PAT
        Rule(Regex("""github_pat_[A-Za-z0-9_]{20,}"""), MASK),
        Rule(Regex("""xox[baprs]-[A-Za-z0-9\-]{10,}"""), MASK),               // Slack
        Rule(Regex("""AIza[A-Za-z0-9_\-]{30,}"""), MASK),                     // Google API
        Rule(Regex("""eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}"""), MASK), // JWT
        // URL userinfo (https://user:pass@host).
        Rule(Regex("""(://[^/\s:@]+:)[^@\s]+(@)"""), "$1" + MASK + "$2"),
        // Cookie / set-cookie header values.
        Rule(Regex("""(?i)((?:set-)?cookie\s*[:=]\s*)[^\r\n]+"""), "$1" + MASK),
        // Email addresses.
        Rule(Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""), MASK),
        // Long digit runs (phone numbers, card-like); keep short numbers
        // (ports, counts, line numbers) intact.
        Rule(Regex("""(?<![\w.])\+?\d[\d \-]{9,}\d(?![\w.])"""), MASK),
        // Home-directory usernames in paths.
        Rule(Regex("""(/(?:Users|home)/)[^/\s]+"""), "$1" + MASK),
    )

    /** Redact one text blob. Idempotent: masking an already-masked blob is stable. */
    fun redact(text: String): String {
        var out = text
        for (rule in rules) {
            out = rule.regex.replace(out, rule.replacement)
        }
        return out
    }

    /** File names that must never enter a bundle regardless of content. */
    private val forbiddenNamePatterns = listOf(
        Regex("""(?i).*secret.*"""),
        Regex("""(?i).*credential.*"""),
        Regex("""(?i).*keystore.*"""),
        Regex("""(?i).*\.pem$"""),
        Regex("""(?i).*\.key$"""),
        Regex("""(?i).*id_rsa.*"""),
        Regex("""(?i).*cookies.*"""),
        Regex("""(?i).*provider-customization\.properties$"""),
    )

    fun isForbiddenFile(name: String): Boolean =
        forbiddenNamePatterns.any { it.matches(name) }
}