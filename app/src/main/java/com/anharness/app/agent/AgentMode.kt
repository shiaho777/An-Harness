package com.anharness.app.agent

/**
 * [T-android-agent-modes] Per-session agent composition (dsh agent-presets
 * parity, adapted: no cordis.yml — modes are typed data, not plugin trees).
 *
 * A session names a mode and gets that mode's tool catalog, prompt
 * sections, and skill surface. Modes are locked once the session has
 * produced content (dsh constraint: swapping tools mid-conversation
 * orphans recorded tool calls the new composition cannot execute).
 *
 * STANDARD is the identity mode: its tool catalog and prompt are
 * byte-identical to the pre-modes behavior.
 */
enum class AgentMode(
    val id: String,
    val displayName: String,
    val description: String,
    /** Tool names exposed to the model. Empty = no tools (pure chat). */
    val enabledTools: Set<String>,
    /** Whether skills/MCP prompt sections are injected (pointless without tools). */
    val includeSkillSections: Boolean = true,
    /** Optional guidance section injected at order 25 (after plan:policy). */
    val guidance: String? = null,
) {
    STANDARD(
        id = "standard",
        displayName = "Standard",
        description = "Full agent: shell, files, browser, memory, images.",
        enabledTools = setOf(
            "shell_execute", "file_read", "file_write", "file_edit",
            "read_image", "browser_use", "memory_write", "memory_get",
            "exit_plan_mode",
        ),
    ),
    CODE(
        id = "code",
        displayName = "Code",
        description = "Software work: shell, files, images. No browser.",
        enabledTools = setOf(
            "shell_execute", "file_read", "file_write", "file_edit",
            "read_image", "memory_write", "memory_get", "exit_plan_mode",
        ),
        guidance = "Code mode is active: shell, file, and image tools are available. " +
            "Browser access is NOT available in this mode — do not attempt browser_use.",
    ),
    RESEARCH(
        id = "research",
        displayName = "Research",
        description = "Read-only: read files, browse the web, search memory. No writes.",
        enabledTools = setOf(
            "file_read", "read_image", "browser_use", "memory_get", "exit_plan_mode",
        ),
        guidance = "Research mode is active: you may read files, browse the web, and search " +
            "memory, but you CANNOT write or edit files, run shell commands, or save memories. " +
            "Gather information and report findings.",
    ),
    CHAT(
        id = "chat",
        displayName = "Chat",
        description = "Pure conversation. No tools.",
        enabledTools = emptySet(),
        includeSkillSections = false,
        guidance = "Chat mode is active: no tools are available in this session. " +
            "Answer directly from your knowledge; do not attempt tool calls.",
    ),
    ;

    companion object {
        val DEFAULT = STANDARD

        /** Parse a persisted id; unknown/blank falls back to [DEFAULT]. */
        fun fromId(id: String?): AgentMode =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: DEFAULT

        /** Next mode in cycle order (for the /mode slash command). */
        fun AgentMode.next(): AgentMode = entries[(ordinal + 1) % entries.size]
    }
}
