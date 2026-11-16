package com.anharness.app.sandbox.offload

import com.anharness.app.scheduled.ScheduledRepeatMode
import com.anharness.app.scheduled.ScheduledTargetMode
import com.anharness.app.scheduled.ScheduledTask

/**
 * [T-scheduled-cli-update] Pure merge logic for `minis-scheduled update`.
 *
 * Problem: the CLI could only delete + recreate to change a task, which
 * lost the id (PendingIntent requestCode), createdAt, lastResult* and the
 * whole runHistory. The editor can update in place, the agent couldn't.
 *
 * Solution: field-level merge over the existing task — only flags the
 * caller actually passed change anything; identity and run history are
 * never touched. Kept pure (no Context/Manager) so the merge table is
 * JVM-testable; the handler owns parsing + persistence.
 *
 * Validation mirrors the editor's canSave rule: a non-rerun task must
 * keep a non-blank prompt, and target switches must carry their required
 * session/message ids. Violations throw [IllegalArgumentException],
 * which the handler maps to exit code 2.
 */
internal object ScheduledTaskMerge {

    /**
     * @param old the persisted task (identity + history source of truth)
     * @param args parsed CLI flags; absent flags keep the old value
     * @param parseTime HH:MM validator (handler's parseTime)
     * @param parseRepeat repeat-mode validator (handler's parseRepeat)
     * @param parseDays custom-days validator (handler's parseDays)
     * @param parseDate YYYY-MM-DD validator (handler's parseDate)
     */
    fun merge(
        old: ScheduledTask,
        args: OffloadArgs,
        parseTime: (String) -> Pair<Int, Int>,
        parseRepeat: (String?) -> ScheduledRepeatMode,
        parseDays: (String) -> Set<Int>,
        parseDate: (String) -> Long,
    ): ScheduledTask {
        require(!args.hasFlag("enabled") || !args.hasFlag("disabled")) {
            "--enabled and --disabled are mutually exclusive"
        }

        val time = args.get("time", "t")?.let(parseTime)
        val repeat = args.get("repeat", "r")?.let(parseRepeat)
        // --days only meaningful with CUSTOM; passing it implies custom when
        // --repeat is absent (matches create's --repeat custom --days pairing).
        val days = args.get("days")?.let(parseDays)
        val effectiveRepeat = repeat ?: if (days != null) ScheduledRepeatMode.CUSTOM else old.repeatMode
        if (effectiveRepeat == ScheduledRepeatMode.CUSTOM) {
            val effectiveDays = days ?: old.customDays
            require(effectiveDays.isNotEmpty()) { "--days required for custom repeat" }
        }

        val target = parseTargetPatch(old.targetMode, args)
        val prompt = args.get("prompt", "p") ?: old.prompt
        if (target !is ScheduledTargetMode.RerunMessage) {
            require(prompt.isNotBlank()) { "--prompt must not be blank (except for rerun target)" }
        }

        val enabled = when {
            args.hasFlag("enabled") -> true
            args.hasFlag("disabled") -> false
            else -> old.enabled
        }

        return old.copy(
            label = args.get("label", "l") ?: old.label,
            timeOfDayHour = time?.first ?: old.timeOfDayHour,
            timeOfDayMinute = time?.second ?: old.timeOfDayMinute,
            repeatMode = effectiveRepeat,
            customDays = if (effectiveRepeat == ScheduledRepeatMode.CUSTOM) {
                days ?: old.customDays
            } else emptySet(),
            prompt = prompt,
            targetMode = target,
            modelId = args.get("model", "m") ?: old.modelId,
            enabled = enabled,
            startDateMs = args.get("start")?.let(parseDate) ?: old.startDateMs,
            endDateMs = args.get("end")?.let(parseDate) ?: old.endDateMs,
            // id / createdAt / modelBinding / lastFiredAt / lastResult* /
            // runHistory intentionally untouched — copy() keeps them.
        )
    }

    /**
     * Target switch semantics: no --target keeps the old mode verbatim
     * (including its session/message ids). An explicit --target rebuilds
     * the mode and demands its required ids, same as create — silently
     * reusing the old session for a different mode would be surprising.
     */
    private fun parseTargetPatch(old: ScheduledTargetMode, args: OffloadArgs): ScheduledTargetMode {
        val raw = args.get("target")?.lowercase() ?: return old
        return when (raw) {
            "new" -> ScheduledTargetMode.NewSession
            "follow-up", "followup", "follow" -> {
                val sid = args.get("session", "s")
                    ?: throw IllegalArgumentException("--session required for follow-up target")
                ScheduledTargetMode.AppendToSession(sid)
            }
            "rerun", "re-run", "retry" -> {
                val sid = args.get("session", "s")
                    ?: throw IllegalArgumentException("--session required for rerun target")
                val mid = args.get("message")
                    ?: throw IllegalArgumentException("--message required for rerun target")
                ScheduledTargetMode.RerunMessage(sid, mid)
            }
            else -> throw IllegalArgumentException("--target must be new|follow-up|rerun")
        }
    }
}