package com.anharness.app.scheduled

import java.util.Calendar
import java.util.TimeZone

/**
 * [T-scheduled-rrule] RFC 5545 RRULE subset for scheduled tasks (P5,
 * dsh-Go rrule parity). Pure parser + next-occurrence engine, JVM-testable
 * with an injectable timezone — no Android dependencies.
 *
 * Supported subset (covers the practical task-scheduling surface):
 *   FREQ=DAILY|WEEKLY|MONTHLY|YEARLY
 *   INTERVAL=n            (default 1)
 *   BYDAY=MO,TU,WE,TH,FR,SA,SU   (WEEKLY: day filter; MONTHLY: only bare
 *                                 weekdays, no ordinal prefixes)
 *   BYMONTHDAY=1..31      (MONTHLY)
 *   COUNT=n | UNTIL=YYYYMMDD[THHMMSSZ]  (bounds)
 *
 * Unsupported parts (ordinal BYDAY like 2TU, BYSETPOS, WKST, sub-daily
 * frequencies) are REJECTED at parse time — a rule we cannot honor
 * exactly must not silently fire on the wrong day.
 *
 * Occurrence semantics: the task's time-of-day comes from the task row;
 * this engine finds the next matching DAY at/after a floor instant, in
 * the given timezone. COUNT counts occurrences from [anchorMs] inclusive.
 */
object Rrule {

    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    data class Rule(
        val freq: Freq,
        val interval: Int = 1,
        /** Calendar.DAY_OF_WEEK values; empty = no filter. */
        val byDay: Set<Int> = emptySet(),
        val byMonthDay: Set<Int> = emptySet(),
        val count: Int? = null,
        /** Epoch ms bound (inclusive), or null. */
        val untilMs: Long? = null,
    )

    private val dayCodes = mapOf(
        "SU" to Calendar.SUNDAY, "MO" to Calendar.MONDAY, "TU" to Calendar.TUESDAY,
        "WE" to Calendar.WEDNESDAY, "TH" to Calendar.THURSDAY, "FR" to Calendar.FRIDAY,
        "SA" to Calendar.SATURDAY,
    )

    /** Parse or throw IllegalArgumentException with a human-readable reason. */
    fun parse(raw: String, timeZone: TimeZone = TimeZone.getDefault()): Rule {
        val text = raw.trim().removePrefix("RRULE:")
        require(text.isNotBlank()) { "empty RRULE" }
        val parts = text.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        var freq: Freq? = null
        var interval = 1
        var byDay = emptySet<Int>()
        var byMonthDay = emptySet<Int>()
        var count: Int? = null
        var until: Long? = null
        for (part in parts) {
            val eq = part.indexOf('=')
            require(eq > 0) { "bad RRULE part: $part" }
            val key = part.substring(0, eq).uppercase()
            val value = part.substring(eq + 1)
            when (key) {
                "FREQ" -> freq = runCatching { Freq.valueOf(value.uppercase()) }.getOrElse {
                    throw IllegalArgumentException("unsupported FREQ=$value (DAILY/WEEKLY/MONTHLY/YEARLY only)")
                }
                "INTERVAL" -> {
                    interval = value.toIntOrNull() ?: throw IllegalArgumentException("bad INTERVAL")
                    require(interval >= 1) { "INTERVAL must be >= 1" }
                }
                "BYDAY" -> byDay = value.split(",").map { d ->
                    val code = d.trim().uppercase()
                    dayCodes[code] ?: throw IllegalArgumentException("unsupported BYDAY value '$d' (ordinal prefixes not supported)")
                }.toSet()
                "BYMONTHDAY" -> byMonthDay = value.split(",").map { d ->
                    val n = d.trim().toIntOrNull() ?: throw IllegalArgumentException("bad BYMONTHDAY")
                    require(n in 1..31) { "BYMONTHDAY must be 1..31" }
                    n
                }.toSet()
                "COUNT" -> {
                    count = value.toIntOrNull() ?: throw IllegalArgumentException("bad COUNT")
                    require(count >= 1) { "COUNT must be >= 1" }
                }
                "UNTIL" -> until = parseUntil(value, timeZone)
                else -> throw IllegalArgumentException("unsupported RRULE part '$key'")
            }
        }
        requireNotNull(freq) { "FREQ is required" }
        require(count == null || until == null) { "COUNT and UNTIL are mutually exclusive" }
        require(byDay.isEmpty() || freq == Freq.WEEKLY || freq == Freq.MONTHLY) {
            "BYDAY only supported with WEEKLY or MONTHLY"
        }
        require(byMonthDay.isEmpty() || freq == Freq.MONTHLY) { "BYMONTHDAY only with MONTHLY" }
        return Rule(freq!!, interval, byDay, byMonthDay, count, until)
    }

    private fun parseUntil(value: String, tz: TimeZone): Long {
        // YYYYMMDD or YYYYMMDDTHHMMSSZ (UTC per RFC).
        val datePart = value.substringBefore("T")
        require(datePart.length == 8 && datePart.all { it.isDigit() }) { "bad UNTIL date" }
        val cal = Calendar.getInstance(if (value.endsWith("Z")) TimeZone.getTimeZone("UTC") else tz)
        cal.clear()
        cal.set(
            datePart.substring(0, 4).toInt(),
            datePart.substring(4, 6).toInt() - 1,
            datePart.substring(6, 8).toInt(),
        )
        if (value.contains("T")) {
            val t = value.substringAfter("T").removeSuffix("Z")
            require(t.length == 6 && t.all { it.isDigit() }) { "bad UNTIL time" }
            cal.set(Calendar.HOUR_OF_DAY, t.substring(0, 2).toInt())
            cal.set(Calendar.MINUTE, t.substring(2, 4).toInt())
            cal.set(Calendar.SECOND, t.substring(4, 6).toInt())
        } else {
            // Date-only UNTIL includes the whole day.
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        }
        return cal.timeInMillis
    }

    /**
     * Next occurrence at [hour]:[minute] on a matching day at/after [floorMs].
     * [anchorMs] is the series start (for INTERVAL alignment and COUNT).
     * Returns null when the rule is exhausted (COUNT/UNTIL) or no match
     * exists within the search horizon.
     */
    fun nextOccurrence(
        rule: Rule,
        hour: Int,
        minute: Int,
        floorMs: Long,
        anchorMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        maxIterations: Int = 366 * 8,
    ): Long? {
        // COUNT counts occurrences from the SERIES START (anchor), not from
        // the scan floor — otherwise a late floor would resurrect an
        // exhausted series. Scan from the anchor when COUNT is set.
        val effectiveFloor = maxOf(floorMs, anchorMs)
        val scanFrom = if (rule.count != null) anchorMs else effectiveFloor
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = scanFrom
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis < scanFrom) cal.add(Calendar.DAY_OF_YEAR, 1)

        val anchor = Calendar.getInstance(timeZone).apply { timeInMillis = anchorMs }
        var emitted = 0
        var iterations = 0
        while (iterations++ < maxIterations) {
            val candidate = cal.timeInMillis
            if (rule.untilMs != null && candidate > rule.untilMs) return null
            if (matchesDay(rule, cal, anchor, timeZone)) {
                emitted++
                if (rule.count != null && emitted > rule.count) return null
                if (candidate >= effectiveFloor) return candidate
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun matchesDay(rule: Rule, day: Calendar, anchor: Calendar, tz: TimeZone): Boolean {
        return when (rule.freq) {
            Freq.DAILY -> daysBetween(anchor, day, tz) % rule.interval == 0L
            Freq.WEEKLY -> {
                if (rule.byDay.isNotEmpty() && day.get(Calendar.DAY_OF_WEEK) !in rule.byDay) return false
                weeksBetween(anchor, day, tz) % rule.interval == 0L
            }
            Freq.MONTHLY -> {
                val months = monthsBetween(anchor, day)
                if (months % rule.interval != 0) return false
                if (rule.byMonthDay.isNotEmpty() && day.get(Calendar.DAY_OF_MONTH) !in rule.byMonthDay) return false
                if (rule.byDay.isNotEmpty() && day.get(Calendar.DAY_OF_WEEK) !in rule.byDay) return false
                true
            }
            Freq.YEARLY -> {
                val years = day.get(Calendar.YEAR) - anchor.get(Calendar.YEAR)
                if (years % rule.interval != 0) return false
                day.get(Calendar.MONTH) == anchor.get(Calendar.MONTH) &&
                    day.get(Calendar.DAY_OF_MONTH) == anchor.get(Calendar.DAY_OF_MONTH)
            }
        }
    }

    private fun startOfDay(c: Calendar, tz: TimeZone): Calendar =
        (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

    private fun daysBetween(a: Calendar, b: Calendar, tz: TimeZone): Long {
        val d1 = startOfDay(a, tz).timeInMillis
        val d2 = startOfDay(b, tz).timeInMillis
        // Round to nearest day to absorb DST hour shifts.
        return Math.round((d2 - d1) / 86_400_000.0)
    }

    private fun weeksBetween(a: Calendar, b: Calendar, tz: TimeZone): Long {
        val a2 = startOfDay(a, tz); val b2 = startOfDay(b, tz)
        // Align to week starts (Sunday) so partial weeks count consistently.
        a2.add(Calendar.DAY_OF_YEAR, -(a2.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        b2.add(Calendar.DAY_OF_YEAR, -(b2.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        return Math.round((b2.timeInMillis - a2.timeInMillis) / (7 * 86_400_000.0))
    }

    private fun monthsBetween(a: Calendar, b: Calendar): Int =
        (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH))
}