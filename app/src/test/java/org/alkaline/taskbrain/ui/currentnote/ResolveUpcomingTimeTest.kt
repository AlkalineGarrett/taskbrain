package org.alkaline.taskbrain.ui.currentnote

import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class ResolveUpcomingTimeTest {

    private fun ts(millis: Long) = Timestamp(Date(millis))

    @Test
    fun `returns explicit upcomingTime when provided`() {
        val upcoming = ts(1000)
        val result = CurrentNoteViewModel.resolveUpcomingTime(
            upcomingTime = upcoming,
            notifyTime = ts(500),
            urgentTime = ts(200),
            alarmTime = ts(100)
        )
        assertEquals(upcoming, result)
    }

    @Test
    fun `returns earliest of other times when upcomingTime is null`() {
        val notify = ts(3000)
        val urgent = ts(1000)
        val alarm = ts(2000)

        val result = CurrentNoteViewModel.resolveUpcomingTime(
            upcomingTime = null,
            notifyTime = notify,
            urgentTime = urgent,
            alarmTime = alarm
        )
        assertEquals(urgent, result)
    }

    @Test
    fun `returns sole time when upcomingTime is null and only one other set`() {
        val alarm = ts(5000)
        val result = CurrentNoteViewModel.resolveUpcomingTime(
            upcomingTime = null,
            notifyTime = null,
            urgentTime = null,
            alarmTime = alarm
        )
        assertEquals(alarm, result)
    }

    @Test
    fun `returns null when all times are null`() {
        val result = CurrentNoteViewModel.resolveUpcomingTime(
            upcomingTime = null,
            notifyTime = null,
            urgentTime = null,
            alarmTime = null
        )
        assertNull(result)
    }

    @Test
    fun `prefers explicit upcomingTime even when later than other times`() {
        val upcoming = ts(9999)
        val result = CurrentNoteViewModel.resolveUpcomingTime(
            upcomingTime = upcoming,
            notifyTime = ts(100),
            urgentTime = ts(200),
            alarmTime = ts(300)
        )
        assertEquals(upcoming, result)
    }

    @Test
    fun `picks earliest between two times when upcomingTime is null`() {
        val notify = ts(2000)
        val alarm = ts(3000)
        val result = CurrentNoteViewModel.resolveUpcomingTime(
            upcomingTime = null,
            notifyTime = notify,
            urgentTime = null,
            alarmTime = alarm
        )
        assertEquals(notify, result)
    }
}
