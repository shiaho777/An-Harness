package com.anharness.app.scheduled

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-scheduled-rrule] Parser + next-occurrence contract. Fixed UTC timezone
 * so day math is deterministic on any machine.
 */
class RruleTest {

    private val tz = TimeZone.getTimeZone("UTC")

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        Calendar.getInstance(tz).apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    private fun cal(msVal: Long) = Calendar.getInstance(tz).apply { timeInMillis = msVal }

    private fun expectParseError(raw: String): String {
        try {
            Rrule.parse(raw, tz)
            fail("expected parse error for: $raw")
            throw AssertionError()
        } catch (e: IllegalArgumentException) {
            return e.message ?: ""
        }
    }

    // ── parsing ──────────────────────────────────────────────────────────

    @Test
    fun `basic daily parses`() {
        val r = Rrule.parse("FREQ=DAILY", tz)
        assertEquals(Rrule.Freq.DAILY, r.freq)
        assertEquals(1, r.interval)
    }

    @Test
    fun `rrule prefix tolerated and parts combined`() {
        val r = Rrule.parse("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR", tz)
        assertEquals(Rrule.Freq.WEEKLY, r.freq)
        assertEquals(2, r.interval)
        assertEquals(setOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY), r.byDay)
    }

    @Test
    fun `unsupported parts rejected loudly`() {
        assertTrue(expectParseError("FREQ=HOURLY").contains("FREQ"))
        assertTrue(expectParseError("FREQ=WEEKLY;BYDAY=2TU").contains("BYDAY"))
        assertTrue(expectParseError("FREQ=DAILY;BYSETPOS=1").contains("BYSETPOS"))
        assertTrue(expectParseError("FREQ=DAILY;WKST=MO").contains("WKST"))
        expectParseError("INTERVAL=2")               // no FREQ
        expectParseError("FREQ=DAILY;INTERVAL=0")
        expectParseError("FREQ=DAILY;COUNT=3;UNTIL=20270101")
        expectParseError("FREQ=DAILY;BYMONTHDAY=5")  // wrong freq
        expectParseError("FREQ=MONTHLY;BYMONTHDAY=32")
        expectParseError("")
    }

    @Test
    fun `until date-only includes whole day`() {
        val r = Rrule.parse("FREQ=DAILY;UNTIL=20261120", tz)
        // 23:59:59 of that day (ms() builds :00 seconds, add 59s).
        assertEquals(ms(2026, 11, 20, 23, 59) + 59_000, r.untilMs)
        val c = cal(r.untilMs!!)
        assertEquals(20, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, c.get(Calendar.MINUTE))
        assertEquals(59, c.get(Calendar.SECOND))
    }

    // ── daily / interval ─────────────────────────────────────────────────

    @Test
    fun `daily next occurrence is today if time ahead else tomorrow`() {
        val r = Rrule.parse("FREQ=DAILY", tz)
        val anchor = ms(2026, 11, 17)
        // floor 08:00, task 09:00 → today 09:00
        val today = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 17, 8), anchor, tz)!!
        assertEquals(ms(2026, 11, 17, 9), today)
        // floor 10:00 → tomorrow 09:00
        val tomorrow = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 17, 10), anchor, tz)!!
        assertEquals(ms(2026, 11, 18, 9), tomorrow)
    }

    @Test
    fun `interval 2 daily skips alternate days from anchor`() {
        val r = Rrule.parse("FREQ=DAILY;INTERVAL=2", tz)
        val anchor = ms(2026, 11, 17)
        // floor on the 18th (odd offset) → next valid is the 19th
        val next = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 18, 10), anchor, tz)!!
        assertEquals(ms(2026, 11, 19, 9), next)
    }

    // ── weekly + byday ───────────────────────────────────────────────────

    @Test
    fun `weekly byday picks next matching weekday`() {
        val r = Rrule.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR", tz)
        // 2026-11-17 is a Tuesday. Next matching day: Wed 18th.
        val anchor = ms(2026, 11, 15) // Sunday
        val next = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 17, 10), anchor, tz)!!
        val c = cal(next)
        assertEquals(18, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.WEDNESDAY, c.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `biweekly respects week parity from anchor`() {
        val r = Rrule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", tz)
        // Anchor week: Mon 2026-11-16. Valid Mondays: 16th, 30th, ...
        val anchor = ms(2026, 11, 16)
        val next = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 17, 10), anchor, tz)!!
        assertEquals(ms(2026, 11, 30, 9), next)
    }

    // ── monthly ──────────────────────────────────────────────────────────

    @Test
    fun `monthly bymonthday picks the day`() {
        val r = Rrule.parse("FREQ=MONTHLY;BYMONTHDAY=1", tz)
        val anchor = ms(2026, 11, 17)
        val next = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 17, 10), anchor, tz)!!
        assertEquals(ms(2026, 12, 1, 9), next)
    }

    @Test
    fun `monthly skips months without the day`() {
        val r = Rrule.parse("FREQ=MONTHLY;BYMONTHDAY=31", tz)
        val anchor = ms(2026, 11, 1)
        // Nov has 30 days → next 31st is Dec 31.
        val next = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 2, 10), anchor, tz)!!
        assertEquals(ms(2026, 12, 31, 9), next)
    }

    // ── yearly ───────────────────────────────────────────────────────────

    @Test
    fun `yearly repeats anchor month-day`() {
        val r = Rrule.parse("FREQ=YEARLY", tz)
        val anchor = ms(2026, 11, 17)
        val next = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 17, 10), anchor, tz)!!
        assertEquals(ms(2027, 11, 17, 9), next)
    }

    // ── bounds ───────────────────────────────────────────────────────────

    @Test
    fun `count exhausts the series`() {
        val r = Rrule.parse("FREQ=DAILY;COUNT=3", tz)
        val anchor = ms(2026, 11, 17)
        // Occurrences: 17th, 18th, 19th. Floor on the 20th → exhausted.
        val next = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 20, 0), anchor, tz)
        assertNull(next)
        // Floor on the 18th → 2nd occurrence still valid.
        val second = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 18, 0), anchor, tz)
        assertEquals(ms(2026, 11, 18, 9), second)
    }

    @Test
    fun `until bounds the series`() {
        val r = Rrule.parse("FREQ=DAILY;UNTIL=20261119", tz)
        val anchor = ms(2026, 11, 17)
        val last = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 19, 0), anchor, tz)
        assertEquals(ms(2026, 11, 19, 9), last)
        val beyond = Rrule.nextOccurrence(r, 9, 0, ms(2026, 11, 20, 0), anchor, tz)
        assertNull(beyond)
    }

    // ── task integration ─────────────────────────────────────────────────

    @Test
    fun `task with rrule overrides repeat mode`() {
        val task = ScheduledTask(
            label = "t", timeOfDayHour = 9, timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.DAILY, prompt = "p",
            createdAt = ms(2026, 11, 15),
            rrule = "FREQ=WEEKLY;BYDAY=FR",
        )
        // Tuesday the 17th, 10:00 → next Friday the 20th at 09:00.
        val next = task.nextTriggerMs(now = ms(2026, 11, 17, 10))
        assertNotNull(next)
        val c = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(Calendar.FRIDAY, c.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `task with invalid rrule yields no trigger not wrong fire`() {
        val task = ScheduledTask(
            label = "t", timeOfDayHour = 9, timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.DAILY, prompt = "p",
            rrule = "FREQ=HOURLY",
        )
        assertNull(task.nextTriggerMs(now = ms(2026, 11, 17, 10)))
    }

    @Test
    fun `task without rrule keeps legacy behavior`() {
        val task = ScheduledTask(
            label = "t", timeOfDayHour = 9, timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.DAILY, prompt = "p",
        )
        assertNotNull(task.nextTriggerMs(now = ms(2026, 11, 17, 10)))
    }

    @Test
    fun `rrule survives json round-trip`() {
        val task = ScheduledTask(
            label = "t", timeOfDayHour = 9, timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.DAILY, prompt = "p",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
        )
        val back = ScheduledTask.fromJson(task.toJson())
        assertEquals("FREQ=WEEKLY;BYDAY=MO", back.rrule)
        val plain = ScheduledTask.fromJson(
            ScheduledTask(label = "x", timeOfDayHour = 9, timeOfDayMinute = 0,
                repeatMode = ScheduledRepeatMode.ONCE, prompt = "p").toJson(),
        )
        assertNull(plain.rrule)
    }
}