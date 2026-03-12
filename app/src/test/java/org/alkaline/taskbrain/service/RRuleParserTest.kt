package org.alkaline.taskbrain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Date

class RRuleParserTest {

    // --- Parse tests ---

    @Test
    fun `parse daily`() {
        val rule = RRuleParser.parse("FREQ=DAILY")
        assertEquals(RRuleParser.Frequency.DAILY, rule.freq)
        assertEquals(1, rule.interval)
        assertNull(rule.byDay)
    }

    @Test
    fun `parse weekly with interval`() {
        val rule = RRuleParser.parse("FREQ=WEEKLY;INTERVAL=2")
        assertEquals(RRuleParser.Frequency.WEEKLY, rule.freq)
        assertEquals(2, rule.interval)
    }

    @Test
    fun `parse weekly with days`() {
        val rule = RRuleParser.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals(RRuleParser.Frequency.WEEKLY, rule.freq)
        assertEquals(listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY), rule.byDay)
    }

    @Test
    fun `parse monthly`() {
        val rule = RRuleParser.parse("FREQ=MONTHLY")
        assertEquals(RRuleParser.Frequency.MONTHLY, rule.freq)
    }

    @Test
    fun `parse weekdays preset`() {
        val rule = RRuleParser.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        assertEquals(5, rule.byDay?.size)
    }

    // --- Build tests ---

    @Test
    fun `build daily`() {
        assertEquals("FREQ=DAILY", RRuleParser.build(RRuleParser.Frequency.DAILY))
    }

    @Test
    fun `build weekly with interval`() {
        assertEquals("FREQ=WEEKLY;INTERVAL=2", RRuleParser.build(RRuleParser.Frequency.WEEKLY, interval = 2))
    }

    @Test
    fun `build weekly with days`() {
        val rrule = RRuleParser.build(
            RRuleParser.Frequency.WEEKLY,
            byDay = listOf(Calendar.MONDAY, Calendar.FRIDAY)
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO,FR", rrule)
    }

    @Test
    fun `build roundtrips with parse`() {
        val original = "FREQ=WEEKLY;INTERVAL=3;BYDAY=TU,TH"
        val parsed = RRuleParser.parse(original)
        val rebuilt = RRuleParser.build(parsed.freq, parsed.interval, parsed.byDay)
        assertEquals(original, rebuilt)
    }

    // --- Next occurrence tests ---

    private fun calendar(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Date {
        return Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    private fun Calendar.assertDate(year: Int, month: Int, day: Int) {
        assertEquals(year, get(Calendar.YEAR))
        assertEquals(month - 1, get(Calendar.MONTH))
        assertEquals(day, get(Calendar.DAY_OF_MONTH))
    }

    private fun Date.toCal(): Calendar = Calendar.getInstance().apply { time = this@toCal }

    @Test
    fun `daily next occurrence`() {
        val after = calendar(2026, 3, 10) // Tuesday March 10
        val next = RRuleParser.nextOccurrence("FREQ=DAILY", after)!!.toCal()
        next.assertDate(2026, 3, 11)
        assertEquals(9, next.get(Calendar.HOUR_OF_DAY)) // preserves time
    }

    @Test
    fun `daily interval 3`() {
        val after = calendar(2026, 3, 10)
        val next = RRuleParser.nextOccurrence("FREQ=DAILY;INTERVAL=3", after)!!.toCal()
        next.assertDate(2026, 3, 13)
    }

    @Test
    fun `weekly next occurrence`() {
        val after = calendar(2026, 3, 10)
        val next = RRuleParser.nextOccurrence("FREQ=WEEKLY", after)!!.toCal()
        next.assertDate(2026, 3, 17)
    }

    @Test
    fun `monthly next occurrence`() {
        val after = calendar(2026, 3, 10)
        val next = RRuleParser.nextOccurrence("FREQ=MONTHLY", after)!!.toCal()
        next.assertDate(2026, 4, 10)
    }

    @Test
    fun `monthly clamps to last day`() {
        // Jan 31 -> next month should be Feb 28 (non-leap year 2026)
        val after = calendar(2026, 1, 31)
        val next = RRuleParser.nextOccurrence("FREQ=MONTHLY", after)!!.toCal()
        next.assertDate(2026, 2, 28)
    }

    @Test
    fun `weekly with byday next day this week`() {
        // March 10 2026 is a Tuesday. BYDAY=MO,WE,FR => next should be Wednesday March 11
        val after = calendar(2026, 3, 10)
        val next = RRuleParser.nextOccurrence("FREQ=WEEKLY;BYDAY=MO,WE,FR", after)!!.toCal()
        next.assertDate(2026, 3, 11) // Wednesday
    }

    @Test
    fun `weekly with byday wraps to next week`() {
        // March 13 2026 is a Friday. BYDAY=MO,WE => next should be Monday March 16
        val after = calendar(2026, 3, 13)
        val next = RRuleParser.nextOccurrence("FREQ=WEEKLY;BYDAY=MO,WE", after)!!.toCal()
        next.assertDate(2026, 3, 16) // Monday
    }
}
