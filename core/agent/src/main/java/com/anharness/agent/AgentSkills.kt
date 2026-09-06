package com.anharness.agent

import java.io.File

/**
 * Skill = folder + SKILL.md (compatible with OpenMinis / Claude / Codex skills).
 * Metadata stays in context for triggering; body loads only on use
 * (port of pi skills.ts + OpenMinis SkillsManagement).
 */
data class SkillDescriptor(
    val name: String,
    val description: String,
    val dir: File,
) {
    /** Heavy body, read lazily. */
    fun loadBody(): String = File(dir, "SKILL.md").takeIf { it.exists() }?.readText().orEmpty()
}

class SkillStore(private val roots: List<File>) {
    fun list(): List<SkillDescriptor> {
        return roots.filter { it.isDirectory }.flatMap { root ->
            root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
                val skillFile = File(dir, "SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                val head = skillFile.bufferedReader().use { it.readLine() ?: "" } +
                    skillFile.bufferedReader().use { r -> r.readLines().take(20).joinToString("\n") }
                SkillDescriptor(
                    name = dir.name,
                    description = extractDescription(head),
                    dir = dir,
                )
            }.orEmpty()
        }
    }

    /** Keyword trigger: cheap metadata match, body loads only on hit. */
    fun match(query: String, limit: Int = 3): List<SkillDescriptor> {
        val q = query.lowercase()
        val keywords = q.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (keywords.isEmpty()) return emptyList()
        return list().map { skill ->
            val hay = (skill.name + " " + skill.description).lowercase()
            val score = keywords.count { hay.contains(it) }
            skill to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun extractDescription(head: String): String {
        // Convention: `description: ...` front-matter or first `# heading` block.
        head.lineSequence().firstOrNull { it.trimStart().startsWith("description:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return head.lineSequence()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }
}
