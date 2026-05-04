package org.alkaline.taskbrain.service

import com.google.firebase.Timestamp
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.AlarmStageType
import org.alkaline.taskbrain.data.AlarmType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

class AlarmTriggerHandlerTest {

    private lateinit var alarmRepo: AlarmRepository
    private lateinit var recurrenceScheduler: RecurrenceScheduler
    private lateinit var presenter: AlarmPresenter
    private lateinit var handler: AlarmTriggerHandler

    private val now = Date()
    private fun ts(offsetMs: Long = 0) = Timestamp(Date(now.time + offsetMs))

    private fun alarm(
        id: String = "alarm-1",
        status: AlarmStatus = AlarmStatus.PENDING,
        recurringAlarmId: String? = null,
        snoozedUntil: Timestamp? = null,
        notifiedStageType: AlarmStageType? = null
    ) = Alarm(
        id = id,
        noteId = "note-1",
        lineContent = "Test alarm",
        dueTime = ts(3600_000),
        status = status,
        recurringAlarmId = recurringAlarmId,
        snoozedUntil = snoozedUntil,
        notifiedStageType = notifiedStageType
    )

    /** Tracks presenter.present() calls in order. */
    private val presented = mutableListOf<Pair<String, AlarmType>>()

    private fun stubAlarm(alarm: Alarm?, id: String = "alarm-1") {
        coEvery { alarmRepo.getAlarmFromServer(id) } returns Result.success(alarm)
    }
    private fun stubAlarmFailure(t: Throwable, id: String = "alarm-1") {
        coEvery { alarmRepo.getAlarmFromServer(id) } returns Result.failure(t)
    }
    private fun stubAlarmSeries(results: List<Result<Alarm?>>, id: String = "alarm-1") {
        coEvery { alarmRepo.getAlarmFromServer(id) } returnsMany results
    }

    @Before
    fun setUp() {
        alarmRepo = mockk(relaxed = true)
        recurrenceScheduler = mockk(relaxed = true)
        presenter = mockk(relaxed = true)
        handler = AlarmTriggerHandler(alarmRepo, recurrenceScheduler, presenter)

        every { presenter.present(any(), any()) } answers {
            val alarm = firstArg<Alarm>()
            val type = secondArg<AlarmType>()
            presented.add(alarm.id to type)
        }

        mockkObject(AlarmUtils)
        every { AlarmUtils.shouldShowAlarm(any(), any()) } answers {
            val alarm = firstArg<Alarm>()
            alarm.status == AlarmStatus.PENDING &&
                (alarm.snoozedUntil == null || alarm.snoozedUntil.toDate().time <= System.currentTimeMillis())
        }
    }

    @After
    fun tearDown() {
        presented.clear()
        unmockkAll()
    }

    // -----------------------------------------------------------------------
    // Unit tests: single trigger
    // -----------------------------------------------------------------------

    @Test
    fun `pending alarm is presented`() = runTest {
        val alarm = alarm()
        stubAlarm(alarm)

        val result = handler.handle("alarm-1", AlarmType.NOTIFY)

        assertTrue(result is TriggerResult.Shown)
        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `cancelled alarm is suppressed`() = runTest {
        stubAlarm(alarm(status = AlarmStatus.CANCELLED))

        val result = handler.handle("alarm-1", AlarmType.NOTIFY)

        assertTrue(result is TriggerResult.Suppressed)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `done alarm is suppressed`() = runTest {
        stubAlarm(alarm(status = AlarmStatus.DONE))

        val result = handler.handle("alarm-1", AlarmType.NOTIFY)

        assertTrue(result is TriggerResult.Suppressed)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `deleted alarm returns not found`() = runTest {
        stubAlarm(null)

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.NotFound)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `fetch failure returns error`() = runTest {
        stubAlarmFailure(RuntimeException("network"))

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Error)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `snoozed alarm is suppressed`() = runTest {
        stubAlarm(alarm(snoozedUntil = ts(3600_000)))

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Suppressed)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `recurring alarm triggers recurrence scheduling`() = runTest {
        val alarm = alarm(recurringAlarmId = "rec-1")
        stubAlarm(alarm)

        handler.handle("alarm-1", AlarmType.NOTIFY)

        coVerify { recurrenceScheduler.onFixedInstanceTriggered(alarm) }
    }

    @Test
    fun `non-recurring alarm does not trigger recurrence scheduling`() = runTest {
        stubAlarm(alarm())

        handler.handle("alarm-1", AlarmType.NOTIFY)

        coVerify(exactly = 0) { recurrenceScheduler.onFixedInstanceTriggered(any()) }
    }

    @Test
    fun `alarm cancelled during recurrence processing is suppressed`() = runTest {
        val pending = alarm(recurringAlarmId = "rec-1")
        stubAlarmSeries(listOf(
            Result.success(pending),
            Result.success(pending.copy(status = AlarmStatus.CANCELLED)),
        ))

        val result = handler.handle("alarm-1", AlarmType.NOTIFY)

        assertTrue("Expected Suppressed but got $result", result is TriggerResult.Suppressed)
        coVerify { recurrenceScheduler.onFixedInstanceTriggered(pending) }
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `alarm still pending after recurrence processing is presented`() = runTest {
        val alarm = alarm(recurringAlarmId = "rec-1")
        stubAlarm(alarm)

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)
        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `recurrence scheduling failure returns error`() = runTest {
        stubAlarm(alarm(recurringAlarmId = "rec-1"))
        coEvery { recurrenceScheduler.onFixedInstanceTriggered(any()) } throws RuntimeException("fail")

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Error)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `re-fetch failure after recurrence falls back to original alarm`() = runTest {
        val alarm = alarm(recurringAlarmId = "rec-1")
        stubAlarmSeries(listOf(
            Result.success(alarm),
            Result.failure(RuntimeException("re-fetch failed")),
        ))

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)
        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `presenter receives correct alarm type`() = runTest {
        stubAlarm(alarm())

        handler.handle("alarm-1", AlarmType.URGENT)

        assertEquals(listOf("alarm-1" to AlarmType.URGENT), presented)
    }

    // -----------------------------------------------------------------------
    // Multi-stage scenarios: sequential triggers for the same alarm
    // -----------------------------------------------------------------------

    @Test
    fun `all three stages fire sequentially for a pending alarm`() = runTest {
        val alarm = alarm()
        stubAlarm(alarm)

        // Notification (2h before) → Lock screen (30min before) → Sound (at due)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Shown)
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Shown)

        assertEquals(
            listOf(
                "alarm-1" to AlarmType.NOTIFY,
                "alarm-1" to AlarmType.URGENT,
                "alarm-1" to AlarmType.ALARM
            ),
            presented
        )
    }

    @Test
    fun `user completes alarm after notification, lock screen is suppressed`() = runTest {
        val pending = alarm()
        val done = pending.copy(status = AlarmStatus.DONE)

        // Notification fires — alarm is PENDING
        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        // User marks done; lock screen fires — alarm is DONE
        stubAlarm(done)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)

        // Sound fires — still DONE
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Suppressed)

        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `user skips alarm after notification, remaining stages are suppressed`() = runTest {
        val pending = alarm()
        val cancelled = pending.copy(status = AlarmStatus.CANCELLED)

        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        stubAlarm(cancelled)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Suppressed)

        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `user snoozes after notification, lock screen during snooze is suppressed`() = runTest {
        val pending = alarm()
        val snoozed = pending.copy(snoozedUntil = ts(3600_000))

        // Notification fires
        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        // User snoozes; lock screen fires during snooze
        stubAlarm(snoozed)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)

        // Snooze expires; sound fires — alarm is pending again
        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Shown)

        assertEquals(
            listOf("alarm-1" to AlarmType.NOTIFY, "alarm-1" to AlarmType.ALARM),
            presented
        )
    }

    @Test
    fun `user completes between notification and lock screen for non-recurring`() = runTest {
        val pending = alarm(recurringAlarmId = null)
        val done = pending.copy(status = AlarmStatus.DONE)

        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        stubAlarm(done)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)

        // No recurrence scheduling should have happened
        coVerify(exactly = 0) { recurrenceScheduler.onFixedInstanceTriggered(any()) }
        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    // -----------------------------------------------------------------------
    // Recurring alarm multi-stage scenarios
    // -----------------------------------------------------------------------

    @Test
    fun `recurring alarm — all stages fire, recurrence triggered once per stage`() = runTest {
        val alarm = alarm(recurringAlarmId = "rec-1")
        stubAlarm(alarm)

        handler.handle("alarm-1", AlarmType.NOTIFY)
        handler.handle("alarm-1", AlarmType.URGENT)
        handler.handle("alarm-1", AlarmType.ALARM)

        // Each trigger calls onFixedInstanceTriggered (dedup is RecurrenceScheduler's job)
        coVerify(exactly = 3) { recurrenceScheduler.onFixedInstanceTriggered(alarm) }
        assertEquals(3, presented.size)
    }

    @Test
    fun `recurring alarm — orphan cleanup cancels during notification, notification is suppressed`() = runTest {
        // This is the exact bug scenario: notification fires, recurrence processing
        // cancels the triggering alarm, notification should NOT be shown.
        val pending = alarm(recurringAlarmId = "rec-1")
        val cancelled = pending.copy(status = AlarmStatus.CANCELLED)

        stubAlarmSeries(listOf(
            Result.success(pending),    // initial fetch: PENDING
            Result.success(cancelled),  // re-fetch after recurrence: CANCELLED
        ))

        val result = handler.handle("alarm-1", AlarmType.NOTIFY)

        assertTrue("Expected Suppressed but got $result", result is TriggerResult.Suppressed)
        coVerify { recurrenceScheduler.onFixedInstanceTriggered(pending) }
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `recurring alarm — notification shown, user completes, lock screen suppressed`() = runTest {
        val pending = alarm(recurringAlarmId = "rec-1")
        val done = pending.copy(status = AlarmStatus.DONE)

        // Notification fires — still PENDING after recurrence
        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        // User marks done; lock screen fires
        stubAlarm(done)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)

        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `recurring alarm — recurrence error prevents notification`() = runTest {
        val alarm = alarm(recurringAlarmId = "rec-1")
        stubAlarm(alarm)
        coEvery { recurrenceScheduler.onFixedInstanceTriggered(any()) } throws RuntimeException("DB error")

        val result = handler.handle("alarm-1", AlarmType.NOTIFY)

        assertTrue(result is TriggerResult.Error)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun `recurring alarm — recurrence succeeds but re-fetch fails, falls back to stale PENDING`() = runTest {
        val alarm = alarm(recurringAlarmId = "rec-1")
        stubAlarmSeries(listOf(
            Result.success(alarm),
            Result.failure(RuntimeException("network blip")),
        ))

        val result = handler.handle("alarm-1", AlarmType.NOTIFY)

        // Falls back to original PENDING alarm — still presented
        assertTrue(result is TriggerResult.Shown)
        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    // -----------------------------------------------------------------------
    // Multi-alarm independence
    // -----------------------------------------------------------------------

    @Test
    fun `two different alarms trigger independently`() = runTest {
        val alarm1 = alarm(id = "alarm-1")
        val alarm2 = alarm(id = "alarm-2")
        stubAlarm(alarm1)
        stubAlarm(alarm2, id = "alarm-2")

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)
        assertTrue(handler.handle("alarm-2", AlarmType.URGENT) is TriggerResult.Shown)

        assertEquals(
            listOf("alarm-1" to AlarmType.NOTIFY, "alarm-2" to AlarmType.URGENT),
            presented
        )
    }

    @Test
    fun `one alarm cancelled does not affect another`() = runTest {
        val alarm1 = alarm(id = "alarm-1", status = AlarmStatus.CANCELLED)
        val alarm2 = alarm(id = "alarm-2")
        stubAlarm(alarm1)
        stubAlarm(alarm2, id = "alarm-2")

        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Suppressed)
        assertTrue(handler.handle("alarm-2", AlarmType.NOTIFY) is TriggerResult.Shown)

        assertEquals(listOf("alarm-2" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `recurring and non-recurring alarms interleaved`() = runTest {
        val recurring = alarm(id = "alarm-r", recurringAlarmId = "rec-1")
        val oneShot = alarm(id = "alarm-o")
        stubAlarm(recurring, id = "alarm-r")
        stubAlarm(oneShot, id = "alarm-o")

        handler.handle("alarm-r", AlarmType.NOTIFY)
        handler.handle("alarm-o", AlarmType.NOTIFY)
        handler.handle("alarm-r", AlarmType.URGENT)

        coVerify(exactly = 2) { recurrenceScheduler.onFixedInstanceTriggered(recurring) }
        coVerify(exactly = 0) { recurrenceScheduler.onFixedInstanceTriggered(oneShot) }
        assertEquals(
            listOf(
                "alarm-r" to AlarmType.NOTIFY,
                "alarm-o" to AlarmType.NOTIFY,
                "alarm-r" to AlarmType.URGENT
            ),
            presented
        )
    }

    // -----------------------------------------------------------------------
    // Alarm deleted between stages
    // -----------------------------------------------------------------------

    @Test
    fun `alarm deleted after notification, lock screen returns not found`() = runTest {
        val pending = alarm()
        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        // Alarm deleted from DB
        stubAlarm(null)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.NotFound)

        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    @Test
    fun `recurring alarm deleted during recurrence, re-fetch returns null`() = runTest {
        val alarm = alarm(recurringAlarmId = "rec-1")
        stubAlarmSeries(listOf(
            Result.success(alarm),
            Result.success(null),   // deleted during recurrence processing
        ))

        // Null re-fetch falls back to stale copy (still PENDING) — should present
        val result = handler.handle("alarm-1", AlarmType.NOTIFY)
        assertTrue(result is TriggerResult.Shown)
    }

    // -----------------------------------------------------------------------
    // notifiedStageType recording
    // -----------------------------------------------------------------------

    @Test
    fun `handle marks notifiedStageType after presenting`() = runTest {
        val alarm = alarm()
        stubAlarm(alarm)

        handler.handle("alarm-1", AlarmType.NOTIFY)

        coVerify { alarmRepo.markNotifiedStage("alarm-1", AlarmStageType.NOTIFICATION) }
    }

    @Test
    fun `handle marks LOCK_SCREEN when presenting URGENT`() = runTest {
        val alarm = alarm()
        stubAlarm(alarm)

        handler.handle("alarm-1", AlarmType.URGENT)

        coVerify { alarmRepo.markNotifiedStage("alarm-1", AlarmStageType.LOCK_SCREEN) }
    }

    @Test
    fun `handle marks SOUND_ALARM when presenting ALARM`() = runTest {
        val alarm = alarm()
        stubAlarm(alarm)

        handler.handle("alarm-1", AlarmType.ALARM)

        coVerify { alarmRepo.markNotifiedStage("alarm-1", AlarmStageType.SOUND_ALARM) }
    }

    @Test
    fun `handle upgrades notifiedStageType on escalation`() = runTest {
        // Alarm already notified at NOTIFICATION level, now escalating to LOCK_SCREEN
        val alarm = alarm(notifiedStageType = AlarmStageType.NOTIFICATION)
        stubAlarm(alarm)

        handler.handle("alarm-1", AlarmType.URGENT)

        coVerify { alarmRepo.markNotifiedStage("alarm-1", AlarmStageType.LOCK_SCREEN) }
    }

    @Test
    fun `handle does not downgrade notifiedStageType`() = runTest {
        // Alarm already notified at LOCK_SCREEN, presenting NOTIFY should not downgrade
        val alarm = alarm(notifiedStageType = AlarmStageType.LOCK_SCREEN)
        stubAlarm(alarm)

        handler.handle("alarm-1", AlarmType.NOTIFY)

        coVerify(exactly = 0) { alarmRepo.markNotifiedStage(any(), any()) }
    }

    @Test
    fun `handle does not mark notifiedStageType when suppressed`() = runTest {
        stubAlarm(alarm(status = AlarmStatus.DONE))

        handler.handle("alarm-1", AlarmType.NOTIFY)

        coVerify(exactly = 0) { alarmRepo.markNotifiedStage(any(), any()) }
    }

    @Test
    fun `handle does not mark notifiedStageType when not found`() = runTest {
        stubAlarm(null)

        handler.handle("alarm-1", AlarmType.NOTIFY)

        coVerify(exactly = 0) { alarmRepo.markNotifiedStage(any(), any()) }
    }

    // -----------------------------------------------------------------------
    // Snooze edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `alarm snoozed then snooze expires before next stage`() = runTest {
        val pending = alarm()
        val snoozed = pending.copy(snoozedUntil = ts(1800_000)) // 30 min from now

        // Notification fires while pending
        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        // User snoozes; urgent fires during snooze
        stubAlarm(snoozed)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)

        // Snooze expired (snoozedUntil in the past)
        val unSnoozed = pending.copy(snoozedUntil = ts(-1000)) // 1 second ago
        stubAlarm(unSnoozed)
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Shown)

        assertEquals(
            listOf("alarm-1" to AlarmType.NOTIFY, "alarm-1" to AlarmType.ALARM),
            presented
        )
    }

    @Test
    fun `alarm snoozed then completed during snooze, sound stage suppressed`() = runTest {
        val pending = alarm()
        val snoozed = pending.copy(snoozedUntil = ts(3600_000))
        val done = pending.copy(status = AlarmStatus.DONE, snoozedUntil = null)

        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        stubAlarm(snoozed)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)

        // User completes during snooze; sound fires
        stubAlarm(done)
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Suppressed)

        assertEquals(listOf("alarm-1" to AlarmType.NOTIFY), presented)
    }

    // -----------------------------------------------------------------------
    // Reactivation scenarios
    // -----------------------------------------------------------------------

    @Test
    fun `alarm completed then reactivated, later stage fires and is presented`() = runTest {
        val pending = alarm()
        val done = pending.copy(status = AlarmStatus.DONE)
        val reactivated = pending.copy(status = AlarmStatus.PENDING)

        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Shown)

        stubAlarm(done)
        assertTrue(handler.handle("alarm-1", AlarmType.URGENT) is TriggerResult.Suppressed)

        // User reactivates; sound fires
        stubAlarm(reactivated)
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Shown)

        assertEquals(
            listOf("alarm-1" to AlarmType.NOTIFY, "alarm-1" to AlarmType.ALARM),
            presented
        )
    }

    @Test
    fun `alarm cancelled then reactivated before due time`() = runTest {
        val pending = alarm()
        val cancelled = pending.copy(status = AlarmStatus.CANCELLED)

        stubAlarm(cancelled)
        assertTrue(handler.handle("alarm-1", AlarmType.NOTIFY) is TriggerResult.Suppressed)

        // Reactivated — sound fires at due time
        stubAlarm(pending)
        assertTrue(handler.handle("alarm-1", AlarmType.ALARM) is TriggerResult.Shown)

        assertEquals(listOf("alarm-1" to AlarmType.ALARM), presented)
    }
}
