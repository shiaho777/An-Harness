package com.anharness.app.sandbox.offload

import com.anharness.app.scheduled.ScheduledRepeatMode
import com.anharness.app.scheduled.ScheduledRun
import com.anharness.app.scheduled.ScheduledTargetMode
import com.anharness.app.scheduled.ScheduledTask
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-scheduled-cli-update] Merge table for `minis-scheduled update`.
 *
 * The bug this exists for: changing a task via the agent required
 * delete+recreate, losing id / createdAt / runHistory. The merge must
 * therefore (a) touch ONLY passed flags and (b) never rebuild identity
 * or history. Pure JVM — parser validators are the same lambdas the
 * handler wires, reproduced here with identical rules.
 */
class ScheduledTaskMergeTest {

    private val history = listOf(
        ScheduledRun(firedAt = 1000L, sessionId = "s1", preview = "done", ok = true),
        ScheduledRun(firedAt = 2000L, sessionId = null, preview = null, ok = false),
    )

    private val old = ScheduledTask(
        id = "fixed-id",
        label = "morning report",
        timeOfDayHour = 9,
        timeOfDayMinute = 30,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "summarize news",
        targetMode = ScheduledTargetMode.AppendToSession("chat-1"),
        modelId = "model-a",
        modelBinding = """{"type":"entry","entryId":"e1"}""",
        enabled = true,
        createdAt = 42L,
        startDateMs = 100L,
        endDateMs = 200L,
        lastFiredAt = 2000L,
        lastResultPreview = "done",
        lastResultSessionId = "s1",
        runHistory = history,
    )

    private fun merge(vararg argv: String): ScheduledTask = ScheduledTaskMerge.merge(
        old = old,
        args = OffloadArgs(listOf("update", *argv)),
        parseTime = { s ->
            val parts = s.split(":")
            require(parts.size == 2) { "--time must be HH:MM" }
            val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: throw IllegalArgumentException("bad hour")
            val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: throw IllegalArgumentException("bad minute")
            h to m
        },
        parseRepeat = { s ->
            when (s?.lowercase()) {
                null, "once" -> ScheduledRepeatMode.ONCE
                "daily" -> ScheduledRepeatMode.DAILY
                "weekdays" -> ScheduledRepeatMode.WEEKDAYS
                "custom" -> ScheduledRepeatMode.CUSTOM
                else -> throw IllegalArgumentException("bad repeat")
            }
        },
        parseDays = { s ->
            val map = mapOf("sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY)
            s.split(",").mapNotNull { map[it.trim().lowercase().take(3)] }.toSet()
        },
        parseDate = { s ->
            val parts = s.split("-")
            require(parts.size == 3) { "bad date" }
            Calendar.getInstance().apply {
                clear(); set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            }.timeInMillis
        },
    )

    private fun expectIllegal(vararg argv: String): String {
        try {
            merge(*argv)
            fail("expected IllegalArgumentException for ${argv.toList()}")
            throw AssertionError("unreachable")
        } catch (e: IllegalArgumentException) {
            return e.message ?: ""
        }
    }

    // ── identity & history preservation ─────────────────────────────────

    @Test
    fun `no flags keeps everything including history`() {
        val merged = merge("--id", "fixed-id")
        assertEquals(old, merged)
    }

    @Test
    fun `label change keeps id createdAt history and lastResult`() {
        val merged = merge("--id", "fixed-id", "--label", "evening report")
        assertEquals("evening report", merged.label)
        assertEquals("fixed-id", merged.id)
        assertEquals(42L, merged.createdAt)
        assertEquals(history, merged.runHistory)
        assertEquals(2000L, merged.lastFiredAt)
        assertEquals("done", merged.lastResultPreview)
        assertEquals("s1", merged.lastResultSessionId)
        assertEquals(old.modelBinding, merged.modelBinding)
        // Untouched schedule fields stay.
        assertEquals(9, merged.timeOfDayHour)
        assertEquals(ScheduledRepeatMode.DAILY, merged.repeatMode)
        assertEquals(old.targetMode, merged.targetMode)
    }

    // ── field-level patches ─────────────────────────────────────────────

    @Test
    fun `time patch changes only time`() {
        val merged = merge("--id", "fixed-id", "--time", "07:05")
        assertEquals(7, merged.timeOfDayHour)
        assertEquals(5, merged.timeOfDayMinute)
        assertEquals(old.prompt, merged.prompt)
        assertEquals(history, merged.runHistory)
    }

    @Test
    fun `prompt patch keeps target and schedule`() {
        val merged = merge("--id", "fixed-id", "--prompt", "summarize mail")
        assertEquals("summarize mail", merged.prompt)
        assertEquals(old.targetMode, merged.targetMode)
        assertEquals(9, merged.timeOfDayHour)
    }

    @Test
    fun `enabled and disabled flags toggle state`() {
        assertFalse(merge("--id", "fixed-id", "--disabled").enabled)
        assertTrue(merge("--id", "fixed-id", "--enabled").enabled)
        expectIllegal("--id", "fixed-id", "--enabled", "--disabled")
    }

    @Test
    fun `repeat switch to custom requires days`() {
        val msg = expectIllegal("--id", "fixed-id", "--repeat", "custom")
        assertTrue(msg.contains("--days"))
        val merged = merge("--id", "fixed-id", "--repeat", "custom", "--days", "mon,tue")
        assertEquals(ScheduledRepeatMode.CUSTOM, merged.repeatMode)
        assertEquals(setOf(Calendar.MONDAY, Calendar.TUESDAY), merged.customDays)
    }

    @Test
    fun `days alone implies custom repeat`() {
        val merged = merge("--id", "fixed-id", "--days", "sun")
        assertEquals(ScheduledRepeatMode.CUSTOM, merged.repeatMode)
        assertEquals(setOf(Calendar.SUNDAY), merged.customDays)
    }

    @Test
    fun `switch away from custom clears days`() {
        val customOld = old.copy(repeatMode = ScheduledRepeatMode.CUSTOM, customDays = setOf(Calendar.MONDAY))
        val merged = ScheduledTaskMerge.merge(
            customOld, OffloadArgs(listOf("update", "--repeat", "daily")),
            parseTime = { error("unused") },
            parseRepeat = { ScheduledRepeatMode.DAILY },
            parseDays = { error("unused") },
            parseDate = { error("unused") },
        )
        assertEquals(ScheduledRepeatMode.DAILY, merged.repeatMode)
        assertTrue(merged.customDays.isEmpty())
    }

    // ── target semantics ────────────────────────────────────────────────

    @Test
    fun `target absent keeps old session binding`() {
        val merged = merge("--id", "fixed-id", "--prompt", "x")
        assertEquals(ScheduledTargetMode.AppendToSession("chat-1"), merged.targetMode)
    }

    @Test
    fun `explicit follow-up requires session`() {
        val msg = expectIllegal("--id", "fixed-id", "--target", "follow-up")
        assertTrue(msg.contains("--session"))
        val merged = merge("--id", "fixed-id", "--target", "follow-up", "--session", "chat-2")
        assertEquals(ScheduledTargetMode.AppendToSession("chat-2"), merged.targetMode)
    }

    @Test
    fun `rerun requires session and message and allows blank prompt`() {
        expectIllegal("--id", "fixed-id", "--target", "rerun", "--session", "s")
        val merged = merge(
            "--id", "fixed-id", "--target", "rerun",
            "--session", "s", "--message", "m", "--prompt", "",
        )
        assertEquals(ScheduledTargetMode.RerunMessage("s", "m"), merged.targetMode)
    }

    @Test
    fun `switch to new keeps prompt requirement`() {
        val msg = expectIllegal("--id", "fixed-id", "--target", "new", "--prompt", " ")
        assertTrue(msg.contains("--prompt"))
        val merged = merge("--id", "fixed-id", "--target", "new")
        assertEquals(ScheduledTargetMode.NewSession, merged.targetMode)
    }

    // ── validation ──────────────────────────────────────────────────────

    @Test
    fun `blank prompt rejected for non-rerun`() {
        expectIllegal("--id", "fixed-id", "--prompt", "   ")
    }

    @Test
    fun `bad time rejected`() {
        expectIllegal("--id", "fixed-id", "--time", "25:00")
        expectIllegal("--id", "fixed-id", "--time", "9:99")
        expectIllegal("--id", "fixed-id", "--time", "nine")
    }

    @Test
    fun `date window patch parses and keeps history`() {
        val merged = merge("--id", "fixed-id", "--start", "2026-12-01")
        assertTrue(merged.startDateMs!! > old.startDateMs!!)
        assertEquals(old.endDateMs, merged.endDateMs)
        assertEquals(history, merged.runHistory)
    }
}