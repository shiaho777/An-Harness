package com.anharness.app.tools

import android.content.Context
import com.anharness.agent.FuzzyMatch
import com.anharness.app.data.model.AgentToolDefinition
import com.anharness.app.data.model.AgentToolParam
import com.anharness.app.sandbox.PRootKernel
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Make targeted edits to an existing file using exact string replacement. ALWAYS use file_read first to see the current file contents before editing. Prefer file_edit over file_write when modifying existing files — only the changed part needs to be specified. The old_string must match exactly one location in the file (including whitespace/indentation), unless replace_all is true.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Fix typo in Python script', 'Update config value'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to the file to edit (e.g. /root/script.py)"),
            "old_string" to AgentToolParam("string", "The exact text to find in the file. Must match precisely including whitespace and indentation. Must be unique in the file unless replace_all is true."),
            "new_string" to AgentToolParam("string", "The replacement text. Use empty string to delete old_string."),
            "replace_all" to AgentToolParam("boolean", "If true, replace ALL occurrences of old_string (default: false)"),
        ),
        required = listOf("tool_title", "path", "old_string", "new_string"),
        propertyOrdering = listOf("tool_title", "path", "old_string", "new_string", "replace_all"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val oldString = args.optString("old_string", "")
            val newString = args.optString("new_string", "")
            val replaceAll = args.optBoolean("replace_all", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }
            if (oldString.isEmpty()) {
                return ToolExecutionResult("Error: 'old_string' is required and cannot be empty", false, toolTitle = toolTitle)
            }

            // T219: read-only mount guard — see FileWriteTool for rationale.
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            val content = file.readText()

            // Exact match first, then normalized fuzzy fallback (smart quotes,
            // unicode dashes/spaces, trailing whitespace, CRLF drift) — port of
            // pi edit-diff semantics via core:agent FuzzyMatch.
            when (val edit = FuzzyMatch.applyStringEdit(content, oldString, newString, replaceAll)) {
                is FuzzyMatch.EditOutcome.NotFound ->
                    return ToolExecutionResult("Error: old_string not found in $path", false, toolTitle = toolTitle)
                is FuzzyMatch.EditOutcome.Ambiguous ->
                    return ToolExecutionResult(
                        "Error: old_string found ${edit.occurrences} times in $path. Use replace_all=true to " +
                            "replace all occurrences, or provide a more specific old_string that matches exactly once.",
                        false, toolTitle = toolTitle,
                    )
                is FuzzyMatch.EditOutcome.Applied -> {
                    file.writeText(edit.content)
                    val fuzzyNote = if (edit.fuzzy) {
                        " (fuzzy match: whitespace/quote/dash differences were normalized — " +
                            "the file's original characters outside the match were preserved)"
                    } else {
                        ""
                    }
                    return ToolExecutionResult(
                        "Edited $path (${edit.replacements} replacement(s), ${edit.content.length} bytes)$fuzzyNote",
                        true, toolTitle = toolTitle,
                    )
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }
}
