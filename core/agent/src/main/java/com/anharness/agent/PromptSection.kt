package com.anharness.agent

/**
 * Named, ordered, enable-able system-prompt section (borrowed from dsh
 * `ctx.systemPrompt` — the assembly discipline, not the Cordis runtime).
 *
 * The system prompt is built from sections instead of one hardcoded
 * string so that conditional fragments (memory, plan mode, presets) are
 * data, not branches inside a monolith. Sections render in [order];
 * ties are broken by name so output is deterministic.
 */
data class PromptSection(
    /** Stable identifier, e.g. "identity", "tools", "memory", "plan:policy". */
    val name: String,
    /** Render position. dsh convention: gaps of 10 leave room to insert. */
    val order: Int,
    /** Disabled sections are dropped before assembly. */
    val enabled: Boolean = true,
    /** Section body. A trailing newline inside [text] is preserved verbatim. */
    val text: String,
) {
    init {
        require(name.isNotBlank()) { "section name must not be blank" }
        require(!name.contains('\n')) { "section name must be single-line" }
    }
}

/**
 * Renders a section list into one system prompt. Enabled sections are
 * sorted by (order, name) and joined with a blank line; disabled or
 * empty-text sections contribute nothing — no dangling separators.
 */
object SystemPromptAssembler {
    fun assemble(sections: List<PromptSection>): String {
        return sections
            .asSequence()
            .filter { it.enabled }
            .filter { it.text.isNotEmpty() }
            .sortedWith(compareBy({ it.order }, { it.name }))
            .map { it.text }
            .toList()
            .joinToString("\n\n")
    }

    /** Convenience builder for callers that assemble inline. */
    fun assemble(vararg sections: PromptSection): String = assemble(sections.toList())
}
