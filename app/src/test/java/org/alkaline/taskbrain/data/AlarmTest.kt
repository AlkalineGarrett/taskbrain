package org.alkaline.taskbrain.data

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class AlarmTest {

    // region SnoozeDuration tests

    @Test
    fun `SnoozeDuration TWO_MINUTES has correct minutes value`() {
        assertEquals(2, SnoozeDuration.TWO_MINUTES.minutes)
    }

    @Test
    fun `SnoozeDuration TEN_MINUTES has correct minutes value`() {
        assertEquals(10, SnoozeDuration.TEN_MINUTES.minutes)
    }

    @Test
    fun `SnoozeDuration ONE_HOUR has correct minutes value`() {
        assertEquals(60, SnoozeDuration.ONE_HOUR.minutes)
    }

    // endregion

    // region AlarmPriority tests

    @Test
    fun `AlarmPriority ordering is UPCOMING less than NOTIFY`() {
        assertTrue(AlarmPriority.UPCOMING < AlarmPriority.NOTIFY)
    }

    @Test
    fun `AlarmPriority ordering is NOTIFY less than URGENT`() {
        assertTrue(AlarmPriority.NOTIFY < AlarmPriority.URGENT)
    }

    @Test
    fun `AlarmPriority ordering is URGENT less than ALARM`() {
        assertTrue(AlarmPriority.URGENT < AlarmPriority.ALARM)
    }

    @Test
    fun `AlarmPriority ALARM is highest priority`() {
        val priorities = AlarmPriority.entries
        assertEquals(AlarmPriority.ALARM, priorities.maxOrNull())
    }

    // endregion

    // region AlarmStatus tests

    @Test
    fun `AlarmStatus has three values`() {
        assertEquals(3, AlarmStatus.entries.size)
    }

    @Test
    fun `AlarmStatus contains PENDING DONE CANCELLED`() {
        val statuses = AlarmStatus.entries.map { it.name }
        assertTrue(statuses.containsAll(listOf("PENDING", "DONE", "CANCELLED")))
    }

    // endregion

    // region AlarmType tests

    @Test
    fun `AlarmType has three values`() {
        assertEquals(3, AlarmType.entries.size)
    }

    @Test
    fun `AlarmType contains NOTIFY URGENT ALARM`() {
        val types = AlarmType.entries.map { it.name }
        assertTrue(types.containsAll(listOf("NOTIFY", "URGENT", "ALARM")))
    }

    // endregion

    // region Alarm data class tests

    @Test
    fun `Alarm default values are correct`() {
        val alarm = Alarm()

        assertEquals("", alarm.id)
        assertEquals("", alarm.userId)
        assertEquals("", alarm.noteId)
        assertEquals("", alarm.lineContent)
        assertNull(alarm.createdAt)
        assertNull(alarm.updatedAt)
        assertNull(alarm.dueTime)
        assertEquals(Alarm.DEFAULT_STAGES, alarm.stages)
        assertEquals(AlarmStatus.PENDING, alarm.status)
        assertNull(alarm.snoozedUntil)
    }

    @Test
    fun `Alarm copy preserves values`() {
        val now = Timestamp(Date())
        val alarm = Alarm(
            id = "alarm_1",
            userId = "user_1",
            noteId = "note_1",
            lineContent = "Test content",
            dueTime = now,
            status = AlarmStatus.DONE
        )

        val copy = alarm.copy(lineContent = "Updated content")

        assertEquals("alarm_1", copy.id)
        assertEquals("user_1", copy.userId)
        assertEquals("note_1", copy.noteId)
        assertEquals("Updated content", copy.lineContent)
        assertEquals(now, copy.dueTime)
        assertEquals(AlarmStatus.DONE, copy.status)
    }

    @Test
    fun `Alarm equality works correctly`() {
        val now = Timestamp(Date())
        val alarm1 = Alarm(id = "alarm_1", noteId = "note_1", dueTime = now)
        val alarm2 = Alarm(id = "alarm_1", noteId = "note_1", dueTime = now)

        assertEquals(alarm1, alarm2)
    }

    @Test
    fun `Alarm inequality works correctly`() {
        val alarm1 = Alarm(id = "alarm_1")
        val alarm2 = Alarm(id = "alarm_2")

        assertNotEquals(alarm1, alarm2)
    }

    // endregion

    // region latestThresholdTime tests

    @Test
    fun `latestThresholdTime returns null when dueTime not set`() {
        val alarm = Alarm(id = "test")
        assertNull(alarm.latestThresholdTime)
    }

    @Test
    fun `latestThresholdTime returns dueTime when set`() {
        val time = Timestamp(Date(5000))
        val alarm = Alarm(id = "test", dueTime = time)
        assertEquals(time, alarm.latestThresholdTime)
    }

    // endregion

    // region AlarmStage tests

    @Test
    fun `AlarmStage resolveTime returns dueTime minus offset`() {
        val dueTime = Timestamp(Date(10000000L))
        val stage = AlarmStage(AlarmStageType.NOTIFICATION, offsetMs = 3600000L)

        val resolved = stage.resolveTime(dueTime)

        assertEquals(10000000L - 3600000L, resolved.toDate().time)
    }

    @Test
    fun `AlarmStage resolveTime uses absoluteTimeOfDay on due date`() {
        // Due time: some date at 14:00
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, 2, 18, 14, 0, 0) // March 18, 2026 14:00
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dueTime = Timestamp(cal.time)

        // absoluteTimeOfDay: 05:00
        val stage = AlarmStage(
            AlarmStageType.NOTIFICATION,
            offsetMs = 3600000L,
            absoluteTimeOfDay = TimeOfDay(5, 0)
        )

        val resolved = stage.resolveTime(dueTime)

        // Should resolve to 05:00 on the same date as dueTime (March 18)
        val resolvedCal = java.util.Calendar.getInstance()
        resolvedCal.time = resolved.toDate()
        assertEquals(5, resolvedCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, resolvedCal.get(java.util.Calendar.MINUTE))
        assertEquals(18, resolvedCal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `AlarmStageType toAlarmType maps correctly`() {
        assertEquals(AlarmType.ALARM, AlarmStageType.SOUND_ALARM.toAlarmType())
        assertEquals(AlarmType.URGENT, AlarmStageType.LOCK_SCREEN.toAlarmType())
        assertEquals(AlarmType.NOTIFY, AlarmStageType.NOTIFICATION.toAlarmType())
    }

    @Test
    fun `enabledStages filters disabled stages`() {
        val stages = listOf(
            AlarmStage(AlarmStageType.SOUND_ALARM, enabled = true),
            AlarmStage(AlarmStageType.LOCK_SCREEN, enabled = false),
            AlarmStage(AlarmStageType.NOTIFICATION, enabled = true)
        )
        val alarm = Alarm(id = "test", stages = stages)

        assertEquals(2, alarm.enabledStages.size)
        assertTrue(alarm.enabledStages.all { it.enabled })
    }

    // endregion

    // region earliestThresholdTime tests

    @Test
    fun `earliestThresholdTime returns null when dueTime not set`() {
        val alarm = Alarm(id = "test", dueTime = null)
        assertNull(alarm.earliestThresholdTime)
    }

    @Test
    fun `earliestThresholdTime returns null when no stages enabled`() {
        val alarm = Alarm(
            id = "test",
            dueTime = Timestamp(Date(10000000L)),
            stages = listOf(
                AlarmStage(AlarmStageType.SOUND_ALARM, offsetMs = 0, enabled = false),
                AlarmStage(AlarmStageType.NOTIFICATION, offsetMs = 3600000L, enabled = false)
            )
        )
        assertNull(alarm.earliestThresholdTime)
    }

    @Test
    fun `earliestThresholdTime returns earliest enabled stage time`() {
        val dueTime = Timestamp(Date(10000000L))
        val alarm = Alarm(
            id = "test",
            dueTime = dueTime,
            stages = listOf(
                AlarmStage(AlarmStageType.SOUND_ALARM, offsetMs = 0, enabled = true),
                AlarmStage(AlarmStageType.LOCK_SCREEN, offsetMs = 1800000L, enabled = true),
                AlarmStage(AlarmStageType.NOTIFICATION, offsetMs = 7200000L, enabled = true)
            )
        )

        // Notification has the largest offset, so its resolved time is the earliest
        val expected = Timestamp(Date(10000000L - 7200000L))
        assertEquals(expected, alarm.earliestThresholdTime)
    }

    @Test
    fun `earliestThresholdTime skips disabled stages`() {
        val dueTime = Timestamp(Date(10000000L))
        val alarm = Alarm(
            id = "test",
            dueTime = dueTime,
            stages = listOf(
                AlarmStage(AlarmStageType.SOUND_ALARM, offsetMs = 0, enabled = true),
                AlarmStage(AlarmStageType.NOTIFICATION, offsetMs = 7200000L, enabled = false)
            )
        )

        // Only sound alarm is enabled (offset 0), so earliest = dueTime
        assertEquals(dueTime, alarm.earliestThresholdTime)
    }

    @Test
    fun `earliestThresholdTime uses absoluteTimeOfDay when set`() {
        // Due time: March 18 at 14:00
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, 2, 18, 14, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dueTime = Timestamp(cal.time)

        val alarm = Alarm(
            id = "test",
            dueTime = dueTime,
            stages = listOf(
                AlarmStage(AlarmStageType.SOUND_ALARM, offsetMs = 0, enabled = true),
                AlarmStage(AlarmStageType.NOTIFICATION, offsetMs = 0, enabled = true, absoluteTimeOfDay = TimeOfDay(5, 0))
            )
        )

        // 05:00 on March 18 is earlier than 14:00, so notification stage should be earliest
        val earliest = alarm.earliestThresholdTime!!
        val earliestCal = java.util.Calendar.getInstance()
        earliestCal.time = earliest.toDate()
        assertEquals(5, earliestCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, earliestCal.get(java.util.Calendar.MINUTE))
    }

    // endregion

    // region displayName tests

    @Test
    fun `displayName returns plain text unchanged`() {
        val alarm = Alarm(lineContent = "Buy groceries")
        assertEquals("Buy groceries", alarm.displayName)
    }

    @Test
    fun `displayName strips leading tabs`() {
        val alarm = Alarm(lineContent = "\t\tNested task")
        assertEquals("Nested task", alarm.displayName)
    }

    @Test
    fun `displayName strips bullet prefix`() {
        val alarm = Alarm(lineContent = "• Bullet item")
        assertEquals("Bullet item", alarm.displayName)
    }

    @Test
    fun `displayName strips unchecked checkbox prefix`() {
        val alarm = Alarm(lineContent = "☐ Todo item")
        assertEquals("Todo item", alarm.displayName)
    }

    @Test
    fun `displayName strips checked checkbox prefix`() {
        val alarm = Alarm(lineContent = "☑ Done item")
        assertEquals("Done item", alarm.displayName)
    }

    @Test
    fun `displayName strips trailing alarm symbol`() {
        val alarm = Alarm(lineContent = "Meeting ⏰")
        assertEquals("Meeting", alarm.displayName)
    }

    @Test
    fun `displayName strips both prefix and alarm symbol`() {
        val alarm = Alarm(lineContent = "\t• Important task ⏰")
        assertEquals("Important task", alarm.displayName)
    }

    @Test
    fun `displayName handles empty lineContent`() {
        val alarm = Alarm(lineContent = "")
        assertEquals("", alarm.displayName)
    }

    @Test
    fun `displayName handles alarm symbol only`() {
        val alarm = Alarm(lineContent = "⏰")
        assertEquals("", alarm.displayName)
    }

    @Test
    fun `displayName handles tab bullet and alarm symbol`() {
        val alarm = Alarm(lineContent = "\t☐ ⏰")
        assertEquals("", alarm.displayName)
    }

    @Test
    fun `displayName strips alarm directive`() {
        val alarm = Alarm(lineContent = "Buy groceries [alarm(\"abc123\")]")
        assertEquals("Buy groceries", alarm.displayName)
    }

    @Test
    fun `displayName strips alarm directive with tab and bullet`() {
        val alarm = Alarm(lineContent = "\t• Task [alarm(\"xyz\")]")
        assertEquals("Task", alarm.displayName)
    }

    @Test
    fun `displayName strips multiple alarm directives`() {
        val alarm = Alarm(lineContent = "Task [alarm(\"a\")] and [alarm(\"b\")]")
        assertEquals("Task  and", alarm.displayName)
    }

    @Test
    fun `displayName strips both plain symbol and directive`() {
        val alarm = Alarm(lineContent = "Task ⏰ [alarm(\"x\")]")
        assertEquals("Task", alarm.displayName)
    }

    @Test
    fun `displayName handles directive-only content`() {
        val alarm = Alarm(lineContent = "[alarm(\"abc\")]")
        assertEquals("", alarm.displayName)
    }

    // endregion

    // region TimeOfDay tests

    @Test
    fun `TimeOfDay accepts valid bounds`() {
        TimeOfDay(0, 0)     // midnight
        TimeOfDay(23, 59)   // last minute of day
        TimeOfDay(12, 30)   // noon-ish
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TimeOfDay rejects hour 24`() {
        TimeOfDay(24, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TimeOfDay rejects negative hour`() {
        TimeOfDay(-1, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TimeOfDay rejects minute 60`() {
        TimeOfDay(0, 60)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TimeOfDay rejects negative minute`() {
        TimeOfDay(0, -1)
    }

    @Test
    fun `TimeOfDay onSameDateAs preserves date and sets time`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.JULY, 15, 14, 30, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val referenceDate = cal.time

        val result = TimeOfDay(5, 45).onSameDateAs(referenceDate)
        val resultCal = Calendar.getInstance().apply { time = result.toDate() }

        assertEquals(2026, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, resultCal.get(Calendar.MONTH))
        assertEquals(15, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(5, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, resultCal.get(Calendar.MINUTE))
        assertEquals(0, resultCal.get(Calendar.SECOND))
        assertEquals(0, resultCal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `TimeOfDay onSameDateAs midnight edge case`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.MARCH, 18, 9, 0, 0)
        val result = TimeOfDay(0, 0).onSameDateAs(cal.time)
        val resultCal = Calendar.getInstance().apply { time = result.toDate() }

        assertEquals(18, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
    }

    @Test
    fun `TimeOfDay onSameDateAs handles DST spring-forward`() {
        // US DST spring forward: March 8, 2026, 2:00 AM → 3:00 AM
        val savedTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val cal = Calendar.getInstance()
            cal.set(2026, Calendar.MARCH, 8, 12, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)

            // 5:00 AM exists on DST transition day
            val result = TimeOfDay(5, 0).onSameDateAs(cal.time)
            val resultCal = Calendar.getInstance().apply { time = result.toDate() }

            assertEquals(8, resultCal.get(Calendar.DAY_OF_MONTH))
            assertEquals(5, resultCal.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, resultCal.get(Calendar.MINUTE))
        } finally {
            TimeZone.setDefault(savedTz)
        }
    }

    @Test
    fun `TimeOfDay onSameDateAs handles DST fall-back`() {
        // US DST fall back: November 1, 2026, 2:00 AM → 1:00 AM
        val savedTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val cal = Calendar.getInstance()
            cal.set(2026, Calendar.NOVEMBER, 1, 12, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)

            // 1:30 AM is ambiguous on fall-back day, but Calendar resolves it deterministically
            val result = TimeOfDay(1, 30).onSameDateAs(cal.time)
            val resultCal = Calendar.getInstance().apply { time = result.toDate() }

            assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
            assertEquals(1, resultCal.get(Calendar.HOUR_OF_DAY))
            assertEquals(30, resultCal.get(Calendar.MINUTE))
        } finally {
            TimeZone.setDefault(savedTz)
        }
    }

    @Test
    fun `TimeOfDay equality and data class behavior`() {
        assertEquals(TimeOfDay(5, 0), TimeOfDay(5, 0))
        assertNotEquals(TimeOfDay(5, 0), TimeOfDay(5, 1))
        assertNotEquals(TimeOfDay(5, 0), TimeOfDay(6, 0))
    }

    // endregion

    // region TimeOfDay.fromMap — Firestore parsing

    @Test
    fun `fromMap reads hour and minute fields`() {
        val map = mapOf<String, Any>(
            "absoluteTimeHour" to 5,
            "absoluteTimeMinute" to 30
        )
        assertEquals(TimeOfDay(5, 30), TimeOfDay.fromMap(map))
    }

    @Test
    fun `fromMap returns null when no time data`() {
        val map = mapOf<String, Any>("type" to "NOTIFICATION", "offsetMs" to 0L)
        assertNull(TimeOfDay.fromMap(map))
    }

    @Test
    fun `fromMap returns null when only hour present`() {
        val map = mapOf<String, Any>("absoluteTimeHour" to 5)
        assertNull(TimeOfDay.fromMap(map))
    }

    // endregion
}
