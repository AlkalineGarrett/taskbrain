package org.alkaline.taskbrain.service

import android.app.NotificationManager
import com.google.firebase.Timestamp
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

class AlarmStateManagerTest {

    private lateinit var mockRepository: AlarmRepository
    private lateinit var mockScheduler: AlarmScheduler
    private lateinit var mockUrgentStateManager: UrgentStateManager
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var stateManager: AlarmStateManager

    @Before
    fun setUp() {
        mockRepository = mockk(relaxed = true)
        mockScheduler = mockk(relaxed = true)
        mockUrgentStateManager = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)

        stateManager = AlarmStateManager(
            repository = mockRepository,
            scheduler = mockScheduler,
            urgentStateManager = mockUrgentStateManager,
            notificationManager = mockNotificationManager
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createTestAlarm(
        id: String = "test_alarm",
        upcomingTime: Timestamp? = Timestamp(Date(System.currentTimeMillis() + 3600000)),
        notifyTime: Timestamp? = null,
        urgentTime: Timestamp? = null,
        alarmTime: Timestamp? = null
    ) = Alarm(
        id = id,
        noteId = "note1",
        lineContent = "Test alarm",
        upcomingTime = upcomingTime,
        notifyTime = notifyTime,
        urgentTime = urgentTime,
        alarmTime = alarmTime
    )

    // region deactivate

    @Test
    fun `deactivate cancels scheduled triggers`() {
        stateManager.deactivate("alarm1")

        verify { mockScheduler.cancelAlarm("alarm1") }
    }

    @Test
    fun `deactivate exits urgent state`() {
        stateManager.deactivate("alarm1")

        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
    }

    @Test
    fun `deactivate dismisses notification`() {
        val notificationId = AlarmUtils.getNotificationId("alarm1")

        stateManager.deactivate("alarm1")

        verify { mockNotificationManager.cancel(notificationId) }
    }

    @Test
    fun `deactivate handles null notification manager`() {
        val manager = AlarmStateManager(
            repository = mockRepository,
            scheduler = mockScheduler,
            urgentStateManager = mockUrgentStateManager,
            notificationManager = null
        )

        // Should not throw
        manager.deactivate("alarm1")

        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
    }

    // endregion

    // region markDone

    @Test
    fun `markDone deactivates and updates repository`() = runTest {
        coEvery { mockRepository.markDone("alarm1") } returns Result.success(Unit)

        val result = stateManager.markDone("alarm1")

        assertTrue(result.isSuccess)
        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
        verify { mockNotificationManager.cancel(any()) }
        coVerify { mockRepository.markDone("alarm1") }
    }

    @Test
    fun `markDone deactivates even when repository fails`() = runTest {
        coEvery { mockRepository.markDone("alarm1") } returns Result.failure(RuntimeException("fail"))

        val result = stateManager.markDone("alarm1")

        assertTrue(result.isFailure)
        // Deactivation still happens (cleanup before repo call)
        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
        verify { mockNotificationManager.cancel(any()) }
    }

    // endregion

    // region markCancelled

    @Test
    fun `markCancelled deactivates and updates repository`() = runTest {
        coEvery { mockRepository.markCancelled("alarm1") } returns Result.success(Unit)

        val result = stateManager.markCancelled("alarm1")

        assertTrue(result.isSuccess)
        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
        verify { mockNotificationManager.cancel(any()) }
        coVerify { mockRepository.markCancelled("alarm1") }
    }

    @Test
    fun `markCancelled deactivates even when repository fails`() = runTest {
        coEvery { mockRepository.markCancelled("alarm1") } returns Result.failure(RuntimeException("fail"))

        val result = stateManager.markCancelled("alarm1")

        assertTrue(result.isFailure)
        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
    }

    // endregion

    // region delete

    @Test
    fun `delete deactivates and removes from repository`() = runTest {
        coEvery { mockRepository.deleteAlarm("alarm1") } returns Result.success(Unit)

        val result = stateManager.delete("alarm1")

        assertTrue(result.isSuccess)
        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
        verify { mockNotificationManager.cancel(any()) }
        coVerify { mockRepository.deleteAlarm("alarm1") }
    }

    @Test
    fun `delete deactivates even when repository fails`() = runTest {
        coEvery { mockRepository.deleteAlarm("alarm1") } returns Result.failure(RuntimeException("fail"))

        val result = stateManager.delete("alarm1")

        assertTrue(result.isFailure)
        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
    }

    // endregion

    // region update

    @Test
    fun `update deactivates old state and reschedules`() = runTest {
        val alarm = createTestAlarm(id = "alarm1")
        val newUpcoming = Timestamp(Date(System.currentTimeMillis() + 7200000))
        val scheduleResult = AlarmScheduleResult(
            alarmId = "alarm1",
            scheduledTriggers = listOf(org.alkaline.taskbrain.data.AlarmType.NOTIFY),
            skippedPastTriggers = emptyList(),
            noTriggersConfigured = false,
            usedExactAlarm = true
        )

        coEvery { mockRepository.updateAlarm(any()) } returns Result.success(Unit)
        every { mockScheduler.scheduleAlarm(any()) } returns scheduleResult

        val result = stateManager.update(alarm, newUpcoming, null, null, null)

        assertTrue(result.isSuccess)
        // Old state is deactivated
        verify { mockScheduler.cancelAlarm("alarm1") }
        verify { mockUrgentStateManager.exitUrgentState("alarm1") }
        verify { mockNotificationManager.cancel(any()) }
        // New alarm is scheduled
        verify { mockScheduler.scheduleAlarm(any()) }
        coVerify { mockRepository.updateAlarm(any()) }
    }

    @Test
    fun `update does not schedule when repository fails`() = runTest {
        val alarm = createTestAlarm(id = "alarm1")
        coEvery { mockRepository.updateAlarm(any()) } returns Result.failure(RuntimeException("fail"))

        val result = stateManager.update(alarm, null, null, null, null)

        assertTrue(result.isFailure)
        // Should not deactivate or schedule since repo failed before that
        verify(exactly = 0) { mockScheduler.cancelAlarm(any()) }
        verify(exactly = 0) { mockScheduler.scheduleAlarm(any()) }
    }

    @Test
    fun `update returns the schedule result from scheduler`() = runTest {
        val alarm = createTestAlarm(id = "alarm1")
        val scheduleResult = AlarmScheduleResult(
            alarmId = "alarm1",
            scheduledTriggers = listOf(org.alkaline.taskbrain.data.AlarmType.ALARM),
            skippedPastTriggers = listOf(org.alkaline.taskbrain.data.AlarmType.URGENT),
            noTriggersConfigured = false,
            usedExactAlarm = true
        )

        coEvery { mockRepository.updateAlarm(any()) } returns Result.success(Unit)
        every { mockScheduler.scheduleAlarm(any()) } returns scheduleResult

        val result = stateManager.update(alarm, null, null, null, null)

        assertEquals(scheduleResult, result.getOrNull())
    }

    @Test
    fun `update passes updated alarm with new times to repository and scheduler`() = runTest {
        val alarm = createTestAlarm(id = "alarm1")
        val notifyTime = Timestamp(Date(1000))
        val urgentTime = Timestamp(Date(2000))
        val alarmTime = Timestamp(Date(3000))
        val scheduleResult = AlarmScheduleResult(
            alarmId = "alarm1",
            scheduledTriggers = emptyList(),
            skippedPastTriggers = emptyList(),
            noTriggersConfigured = false,
            usedExactAlarm = true
        )

        coEvery { mockRepository.updateAlarm(any()) } returns Result.success(Unit)
        every { mockScheduler.scheduleAlarm(any()) } returns scheduleResult

        stateManager.update(alarm, null, notifyTime, urgentTime, alarmTime)

        // Verify repository gets alarm with all updated fields
        coVerify {
            mockRepository.updateAlarm(match {
                it.notifyTime == notifyTime &&
                it.urgentTime == urgentTime &&
                it.alarmTime == alarmTime &&
                it.upcomingTime == notifyTime // resolved from earliest
            })
        }

        // Verify scheduler gets the same updated alarm (not the original)
        verify {
            mockScheduler.scheduleAlarm(match {
                it.notifyTime == notifyTime &&
                it.urgentTime == urgentTime &&
                it.alarmTime == alarmTime
            })
        }
    }

    @Test
    fun `update applies resolveUpcomingTime when upcomingTime is null`() = runTest {
        val alarm = createTestAlarm(id = "alarm1")
        val notifyTime = Timestamp(Date(System.currentTimeMillis() + 3600000))
        val alarmTime = Timestamp(Date(System.currentTimeMillis() + 7200000))
        val scheduleResult = AlarmScheduleResult(
            alarmId = "alarm1",
            scheduledTriggers = emptyList(),
            skippedPastTriggers = emptyList(),
            noTriggersConfigured = false,
            usedExactAlarm = true
        )

        coEvery { mockRepository.updateAlarm(any()) } returns Result.success(Unit)
        every { mockScheduler.scheduleAlarm(any()) } returns scheduleResult

        stateManager.update(alarm, null, notifyTime, null, alarmTime)

        // Verify the alarm passed to updateAlarm has upcomingTime = notifyTime (earliest)
        coVerify {
            mockRepository.updateAlarm(match { it.upcomingTime == notifyTime })
        }
    }

    // endregion

    // region reactivate

    @Test
    fun `reactivate updates repository and reschedules`() = runTest {
        val alarm = createTestAlarm(id = "alarm1")
        val scheduleResult = AlarmScheduleResult(
            alarmId = "alarm1",
            scheduledTriggers = listOf(org.alkaline.taskbrain.data.AlarmType.NOTIFY),
            skippedPastTriggers = emptyList(),
            noTriggersConfigured = false,
            usedExactAlarm = true
        )

        coEvery { mockRepository.reactivateAlarm("alarm1") } returns Result.success(Unit)
        coEvery { mockRepository.getAlarm("alarm1") } returns Result.success(alarm)
        every { mockScheduler.scheduleAlarm(alarm) } returns scheduleResult

        val result = stateManager.reactivate("alarm1")

        assertTrue(result.isSuccess)
        assertEquals(scheduleResult, result.getOrNull())
        coVerify { mockRepository.reactivateAlarm("alarm1") }
        coVerify { mockRepository.getAlarm("alarm1") }
        verify { mockScheduler.scheduleAlarm(alarm) }
    }

    @Test
    fun `reactivate returns null schedule result when alarm not found`() = runTest {
        coEvery { mockRepository.reactivateAlarm("alarm1") } returns Result.success(Unit)
        coEvery { mockRepository.getAlarm("alarm1") } returns Result.success(null)

        val result = stateManager.reactivate("alarm1")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        verify(exactly = 0) { mockScheduler.scheduleAlarm(any()) }
    }

    @Test
    fun `reactivate does not schedule when repository fails`() = runTest {
        coEvery { mockRepository.reactivateAlarm("alarm1") } returns Result.failure(RuntimeException("fail"))

        val result = stateManager.reactivate("alarm1")

        assertTrue(result.isFailure)
        verify(exactly = 0) { mockScheduler.scheduleAlarm(any()) }
    }

    @Test
    fun `reactivate returns success with null when getAlarm fails`() = runTest {
        coEvery { mockRepository.reactivateAlarm("alarm1") } returns Result.success(Unit)
        coEvery { mockRepository.getAlarm("alarm1") } returns Result.failure(RuntimeException("not found"))

        val result = stateManager.reactivate("alarm1")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        verify(exactly = 0) { mockScheduler.scheduleAlarm(any()) }
    }

    // endregion

    // region resolveUpcomingTime

    @Test
    fun `resolveUpcomingTime returns explicit upcomingTime when set`() {
        val upcoming = Timestamp(Date(1000))
        val notify = Timestamp(Date(500))

        val result = AlarmStateManager.resolveUpcomingTime(upcoming, notify, null, null)

        assertEquals(upcoming, result)
    }

    @Test
    fun `resolveUpcomingTime returns earliest threshold when upcomingTime is null`() {
        val notify = Timestamp(Date(3000))
        val urgent = Timestamp(Date(1000))
        val alarm = Timestamp(Date(2000))

        val result = AlarmStateManager.resolveUpcomingTime(null, notify, urgent, alarm)

        assertEquals(urgent, result)
    }

    @Test
    fun `resolveUpcomingTime returns null when all times are null`() {
        val result = AlarmStateManager.resolveUpcomingTime(null, null, null, null)

        assertNull(result)
    }

    @Test
    fun `resolveUpcomingTime returns single threshold when only one is set`() {
        val alarmTime = Timestamp(Date(5000))

        val result = AlarmStateManager.resolveUpcomingTime(null, null, null, alarmTime)

        assertEquals(alarmTime, result)
    }

    // endregion

    // region all transitions deactivate consistently

    @Test
    fun `all deactivating transitions call the same three side effects`() = runTest {
        coEvery { mockRepository.markDone(any()) } returns Result.success(Unit)
        coEvery { mockRepository.markCancelled(any()) } returns Result.success(Unit)
        coEvery { mockRepository.deleteAlarm(any()) } returns Result.success(Unit)

        val operations = listOf(
            "markDone" to suspend { stateManager.markDone("alarm1") },
            "markCancelled" to suspend { stateManager.markCancelled("alarm2") },
            "delete" to suspend { stateManager.delete("alarm3") }
        )

        for ((name, operation) in operations) {
            operation()
        }

        // Each operation should cancel, exit urgent, and dismiss notification
        verify(exactly = 1) { mockScheduler.cancelAlarm("alarm1") }
        verify(exactly = 1) { mockScheduler.cancelAlarm("alarm2") }
        verify(exactly = 1) { mockScheduler.cancelAlarm("alarm3") }
        verify(exactly = 1) { mockUrgentStateManager.exitUrgentState("alarm1") }
        verify(exactly = 1) { mockUrgentStateManager.exitUrgentState("alarm2") }
        verify(exactly = 1) { mockUrgentStateManager.exitUrgentState("alarm3") }
        verify(exactly = 3) { mockNotificationManager.cancel(any()) }
    }

    // endregion
}
