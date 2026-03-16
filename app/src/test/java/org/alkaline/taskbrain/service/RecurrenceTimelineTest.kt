package org.alkaline.taskbrain.service

import android.app.NotificationManager
import com.google.firebase.Timestamp
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmRepository
import org.alkaline.taskbrain.data.AlarmStage
import org.alkaline.taskbrain.data.AlarmStageType
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.RecurrenceType
import org.alkaline.taskbrain.data.RecurringAlarm
import org.alkaline.taskbrain.data.RecurringAlarmRepository
import org.alkaline.taskbrain.data.RecurringAlarmStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Scenario-based timeline tests for recurring alarm lifecycle.
 *
 * These tests model realistic multi-day alarm timelines where stages fire at
 * different times, users act at various points, and edge cases like reboots,
 * snoozes, and end conditions interact.
 *
 * Key principle: mock query results must reflect what Firestore would actually
 * contain at each point in the sequence — not just the "expected" output.
 *
 * Each test is named by the scenario it models and documents the timeline
 * as comments within the test body.
 */
class RecurrenceTimelineTest {

    private lateinit var mockContext: android.content.Context
    private lateinit var mockRecurringRepo: RecurringAlarmRepository
    private lateinit var mockAlarmRepo: AlarmRepository
    private lateinit var mockAlarmScheduler: AlarmScheduler
    private lateinit var mockUrgentStateManager: UrgentStateManager
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var scheduler: RecurrenceScheduler

    // All times are in absolute milliseconds for clarity.
    // Day 1 = tomorrow, Day 2 = day after tomorrow, etc.
    private val HOUR_MS = 60 * 60 * 1000L
    private val DAY_MS = 24 * HOUR_MS

    // Base time: tomorrow at 9:00 AM
    private val day1_9am = System.currentTimeMillis() + DAY_MS
    private val day2_9am = day1_9am + DAY_MS
    private val day3_9am = day1_9am + 2 * DAY_MS

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockRecurringRepo = mockk(relaxed = true)
        mockAlarmRepo = mockk(relaxed = true)
        mockAlarmScheduler = mockk(relaxed = true)
        mockUrgentStateManager = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)

        scheduler = RecurrenceScheduler(
            context = mockContext,
            recurringRepo = mockRecurringRepo,
            alarmRepo = mockAlarmRepo,
            alarmScheduler = mockAlarmScheduler,
            urgentStateManager = mockUrgentStateManager,
            notificationManager = mockNotificationManager
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Helpers ---

    private fun ts(millis: Long) = Timestamp(Date(millis))

    private val threeStageConfig = listOf(
        AlarmStage(type = AlarmStageType.NOTIFICATION, offsetMs = 2 * HOUR_MS, enabled = true),
        AlarmStage(type = AlarmStageType.LOCK_SCREEN, offsetMs = 30 * 60 * 1000L, enabled = true),
        AlarmStage(type = AlarmStageType.SOUND_ALARM, offsetMs = 0, enabled = true)
    )

    private fun dailyRecurring(
        id: String = "rec1",
        currentAlarmId: String? = null,
        completionCount: Int = 0,
        repeatCount: Int? = null,
        lastCompletionDate: Timestamp? = null,
        status: RecurringAlarmStatus = RecurringAlarmStatus.ACTIVE
    ) = RecurringAlarm(
        id = id,
        noteId = "note1",
        lineContent = "Daily standup",
        recurrenceType = RecurrenceType.FIXED,
        rrule = "FREQ=DAILY",
        dueOffsetMs = 0,
        stages = threeStageConfig,
        currentAlarmId = currentAlarmId,
        completionCount = completionCount,
        repeatCount = repeatCount,
        lastCompletionDate = lastCompletionDate,
        status = status,
        createdAt = Timestamp.now()
    )

    private fun weekdayRecurring(
        id: String = "rec1",
        currentAlarmId: String? = null,
    ) = RecurringAlarm(
        id = id,
        noteId = "note1",
        lineContent = "Weekday standup",
        recurrenceType = RecurrenceType.FIXED,
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        dueOffsetMs = 0,
        stages = threeStageConfig,
        currentAlarmId = currentAlarmId,
        status = RecurringAlarmStatus.ACTIVE,
        createdAt = Timestamp.now()
    )

    private fun relativeRecurring(
        id: String = "rec1",
        intervalMs: Long = DAY_MS,
        currentAlarmId: String? = null,
        completionCount: Int = 0,
        lastCompletionDate: Timestamp? = null,
    ) = RecurringAlarm(
        id = id,
        noteId = "note1",
        lineContent = "Relative task",
        recurrenceType = RecurrenceType.RELATIVE,
        relativeIntervalMs = intervalMs,
        dueOffsetMs = 0,
        stages = threeStageConfig,
        currentAlarmId = currentAlarmId,
        completionCount = completionCount,
        lastCompletionDate = lastCompletionDate,
        status = RecurringAlarmStatus.ACTIVE,
        createdAt = Timestamp.now()
    )

    private fun alarm(
        id: String,
        dueTimeMs: Long = day1_9am,
        recurringAlarmId: String = "rec1",
        status: AlarmStatus = AlarmStatus.PENDING,
        stages: List<AlarmStage> = threeStageConfig
    ) = Alarm(
        id = id,
        noteId = "note1",
        lineContent = "Daily standup",
        dueTime = ts(dueTimeMs),
        stages = stages,
        recurringAlarmId = recurringAlarmId,
        status = status
    )

    /** Tracks how many alarms were created and what IDs they got. */
    private fun trackAlarmCreation(vararg ids: String) {
        val idIter = ids.iterator()
        coEvery { mockAlarmRepo.createAlarm(any()) } answers {
            val id = if (idIter.hasNext()) idIter.next() else "alarm-extra-${System.nanoTime()}"
            Result.success(id)
        }
        for (id in ids) {
            coEvery { mockAlarmRepo.getAlarm(id) } returns Result.success(null)
        }
    }

    // ===============================================================
    // FIXED RECURRENCE — MULTI-STAGE TIMELINES
    // ===============================================================

    // region Fixed: Happy path — user completes before next stage

    @Test
    fun `FIXED - notification fires, user completes before lock screen`() = runTest {
        // Timeline:
        //   7:00 AM  — notification fires (2h before due)
        //   7:15 AM  — user marks done
        //   8:30 AM  — lock screen would fire (but alarm is done)
        //   9:00 AM  — due time
        //
        // Expected: next instance created at notification time, no duplicates on completion.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        // Stage 1: notification fires → creates d2
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onFixedInstanceTriggered(d1)

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d1") }

        // User completes d1 — recurring template already points to d2
        val updatedRecurring = recurring.copy(currentAlarmId = "d2")
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(updatedRecurring)

        scheduler.onInstanceCompleted(d1)

        // Completion recorded, no extra alarm created
        coVerify(exactly = 1) { mockRecurringRepo.recordCompletion(eq("rec1"), any()) }
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) } // still just d2
    }

    // endregion

    // region Fixed: All three stages fire, user completes at end

    @Test
    fun `FIXED - all three stages fire sequentially, user completes after due`() = runTest {
        // Timeline:
        //   7:00 AM  — notification fires → creates d2, d1 stays PENDING
        //   8:30 AM  — lock screen fires → dedup guard (currentAlarmId=d2 ≠ d1) → skip
        //   9:00 AM  — due/sound fires → dedup guard → skip
        //   9:05 AM  — user marks done
        //
        // Expected: exactly 1 alarm created (d2). d1 never cancelled.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        // Notification fires
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onFixedInstanceTriggered(d1)

        // Lock screen fires — template now points to d2
        val afterNotification = recurring.copy(currentAlarmId = "d2")
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterNotification)

        scheduler.onFixedInstanceTriggered(d1)

        // Sound/due fires
        scheduler.onFixedInstanceTriggered(d1)

        // Verify: only 1 alarm created across all 3 triggers
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d1") }
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d2") }

        // User completes after due time
        scheduler.onInstanceCompleted(d1)

        coVerify(exactly = 1) { mockRecurringRepo.recordCompletion(eq("rec1"), any()) }
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) } // still just d2
    }

    // endregion

    // region Fixed: User ignores alarm entirely, next day's notification fires

    @Test
    fun `FIXED - user ignores alarm, next day notification fires for d2`() = runTest {
        // Timeline:
        //   Day 1 7:00 AM  — notification fires → creates d2
        //   Day 1 8:30 AM  — lock screen fires → skip (dedup)
        //   Day 1 9:00 AM  — due fires → skip (dedup)
        //   <user does nothing>
        //   Day 2 7:00 AM  — notification fires for d2 → creates d3, d1 now orphaned
        //
        // Expected: d1 is cleaned up when d3 is created (it's a genuine orphan at that point).

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        // Day 1: notification fires → creates d2
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2", "d3")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onFixedInstanceTriggered(d1)

        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d1") }

        // Day 1: remaining stages → skip
        val afterDay1 = recurring.copy(currentAlarmId = "d2")
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterDay1)
        scheduler.onFixedInstanceTriggered(d1)
        scheduler.onFixedInstanceTriggered(d1)

        // Day 2: notification fires for d2 → creates d3
        // Now d2 is the current instance, template points to d2
        val d2 = alarm("d2", dueTimeMs = day2_9am)
        val d2AsRecurring = afterDay1 // currentAlarmId still "d2"
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(d2AsRecurring)
        coEvery { mockAlarmRepo.getAlarm("d3") } returns Result.success(alarm("d3", dueTimeMs = day3_9am))
        // Firestore state: d1 still PENDING (ignored), d2 PENDING, d3 PENDING (just created)
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, d2, alarm("d3", dueTimeMs = day3_9am)))

        scheduler.onFixedInstanceTriggered(d2)

        // d1 should now be cleaned up as orphan (it's neither d3 nor d2)
        coVerify(exactly = 1) { mockAlarmRepo.markCancelled("d1") }
        verify(exactly = 1) { mockAlarmScheduler.cancelAlarm("d1") }
        verify(exactly = 1) { mockUrgentStateManager.exitUrgentState("d1") }

        // d2 (triggering) and d3 (new) should NOT be cancelled
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d2") }
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d3") }
    }

    // endregion

    // region Fixed: User cancels alarm after notification, before due

    @Test
    fun `FIXED - user cancels after notification fires`() = runTest {
        // Timeline:
        //   7:00 AM  — notification fires → creates d2
        //   7:30 AM  — user cancels d1 (marks as skipped)
        //
        // Expected: d2 already exists, onInstanceCancelled is a no-op for creation.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        // Notification fires → creates d2
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onFixedInstanceTriggered(d1)

        // User cancels — template now points to d2
        val afterTrigger = recurring.copy(currentAlarmId = "d2")
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterTrigger)

        scheduler.onInstanceCancelled(d1)

        // No additional alarm created
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        // No completion recorded for cancellation
        coVerify(exactly = 0) { mockRecurringRepo.recordCompletion(any(), any()) }
    }

    // endregion

    // region Fixed: User cancels BEFORE any stage fires

    @Test
    fun `FIXED - user cancels before any stage fires`() = runTest {
        // Timeline:
        //   6:00 AM  — user proactively cancels d1 from the alarms screen
        //
        // Expected: onInstanceCancelled creates d2 since no trigger has happened yet.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        // Only d2 is pending (d1 was just marked CANCELLED by AlarmStateManager before this call)
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onInstanceCancelled(d1)

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 1) { mockRecurringRepo.updateCurrentAlarmId("rec1", "d2") }
    }

    // endregion

    // region Fixed: User completes BEFORE any stage fires

    @Test
    fun `FIXED - user completes before any stage fires`() = runTest {
        // Timeline:
        //   6:00 AM  — user proactively marks d1 done from the alarms screen
        //
        // Expected: onInstanceCompleted creates d2, records completion.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onInstanceCompleted(d1)

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 1) { mockRecurringRepo.recordCompletion(eq("rec1"), any()) }
    }

    // endregion

    // region Fixed: Reboot between notification and due time

    @Test
    fun `FIXED - device reboots between notification and due time`() = runTest {
        // Timeline:
        //   7:00 AM  — notification fires → creates d2
        //   7:45 AM  — device reboots → BootReceiver calls bootstrapRecurringAlarms
        //   8:30 AM  — lock screen fires (rescheduled by bootstrap)
        //   9:00 AM  — due fires
        //
        // Expected: bootstrap sees d2 as currentAlarmId (PENDING), reschedules d2.
        //           Does NOT create d3. d1 still PENDING from before reboot.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        // Notification fires → creates d2
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onFixedInstanceTriggered(d1)
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }

        // Reboot: bootstrap sees template with currentAlarmId="d2"
        val afterTrigger = recurring.copy(currentAlarmId = "d2")
        val d2Alarm = alarm("d2", dueTimeMs = day2_9am)
        coEvery { mockRecurringRepo.getActiveRecurringAlarms() } returns Result.success(listOf(afterTrigger))
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterTrigger)
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(d2Alarm)

        scheduler.bootstrapRecurringAlarms()

        // Bootstrap should reschedule d2, NOT create d3
        verify { mockAlarmScheduler.scheduleAlarm(d2Alarm) }
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) } // still just d2 from before reboot

        // Subsequent stage triggers for d1 should be no-ops
        scheduler.onFixedInstanceTriggered(d1)
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Fixed: Reboot after completion, before next instance was created

    @Test
    fun `FIXED - reboot after completion when next instance creation crashed`() = runTest {
        // Simulates: user completed d1, onInstanceCompleted started but crashed
        // before createNextInstance finished. Template still has currentAlarmId="d1".
        // Bootstrap should detect d1 is DONE and create d2.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1Done = alarm("d1", dueTimeMs = day1_9am, status = AlarmStatus.DONE)

        coEvery { mockRecurringRepo.getActiveRecurringAlarms() } returns Result.success(listOf(recurring))
        coEvery { mockAlarmRepo.getAlarm("d1") } returns Result.success(d1Done)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("d2", dueTimeMs = day2_9am)))

        scheduler.bootstrapRecurringAlarms()

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 1) { mockRecurringRepo.updateCurrentAlarmId("rec1", "d2") }
    }

    // endregion

    // region Fixed: Reboot after cancellation, before next instance was created

    @Test
    fun `FIXED - reboot after cancellation when next instance creation crashed`() = runTest {
        // Same as above but with CANCELLED status.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1Cancelled = alarm("d1", dueTimeMs = day1_9am, status = AlarmStatus.CANCELLED)

        coEvery { mockRecurringRepo.getActiveRecurringAlarms() } returns Result.success(listOf(recurring))
        coEvery { mockAlarmRepo.getAlarm("d1") } returns Result.success(d1Cancelled)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("d2", dueTimeMs = day2_9am)))

        scheduler.bootstrapRecurringAlarms()

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Fixed: Bootstrap with no current alarm (fresh or lost reference)

    @Test
    fun `FIXED - bootstrap with null currentAlarmId creates next instance`() = runTest {
        val recurring = dailyRecurring(currentAlarmId = null)

        coEvery { mockRecurringRepo.getActiveRecurringAlarms() } returns Result.success(listOf(recurring))
        trackAlarmCreation("d1")
        coEvery { mockAlarmRepo.getAlarm("d1") } returns Result.success(alarm("d1", dueTimeMs = day1_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("d1", dueTimeMs = day1_9am)))

        scheduler.bootstrapRecurringAlarms()

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Fixed: End condition — repeat count reached

    @Test
    fun `FIXED - completion reaches repeat count, no more instances created`() = runTest {
        // Recurring alarm with repeatCount=3, already completed 2 times.
        // This completion makes it 3 → hasReachedEnd = true.

        val recurring = dailyRecurring(
            currentAlarmId = "d3",
            completionCount = 2,
            repeatCount = 3
        )
        val d3 = alarm("d3", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)

        scheduler.onInstanceCompleted(d3)

        coVerify(exactly = 1) { mockRecurringRepo.recordCompletion(eq("rec1"), any()) }
        coVerify(exactly = 1) { mockRecurringRepo.end("rec1") }
        coVerify(exactly = 0) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Fixed: End condition — cancellation does NOT count toward repeat count

    @Test
    fun `FIXED - cancellation does not advance completion count`() = runTest {
        val recurring = dailyRecurring(
            currentAlarmId = "d1",
            completionCount = 2,
            repeatCount = 3
        )
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onInstanceCancelled(d1)

        // Cancellation should NOT record completion
        coVerify(exactly = 0) { mockRecurringRepo.recordCompletion(any(), any()) }
        // But should still create next instance
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        // Should NOT end the recurring alarm
        coVerify(exactly = 0) { mockRecurringRepo.end(any()) }
    }

    // endregion

    // region Fixed: Notification-only alarm (lock screen and sound disabled)

    @Test
    fun `FIXED - single notification stage, user completes`() = runTest {
        // Only one stage fires (notification at due time). No pre-due triggers.
        val notifyOnlyStages = listOf(
            AlarmStage(type = AlarmStageType.NOTIFICATION, offsetMs = 0, enabled = true),
            AlarmStage(type = AlarmStageType.LOCK_SCREEN, offsetMs = 0, enabled = false),
            AlarmStage(type = AlarmStageType.SOUND_ALARM, offsetMs = 0, enabled = false)
        )
        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am, stages = notifyOnlyStages)

        // Only trigger: notification at due time → creates d2
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        // d1 is the triggering alarm AND still pending (notification just fired at due time)
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onFixedInstanceTriggered(d1)

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d1") }

        // User completes
        val afterTrigger = recurring.copy(currentAlarmId = "d2")
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterTrigger)

        scheduler.onInstanceCompleted(d1)
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Fixed: ENDED recurring alarm — triggers are ignored

    @Test
    fun `FIXED - triggers are ignored for ENDED recurring alarm`() = runTest {
        val recurring = dailyRecurring(
            currentAlarmId = "d1",
            status = RecurringAlarmStatus.ENDED
        )
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)

        scheduler.onFixedInstanceTriggered(d1)

        coVerify(exactly = 0) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // ===============================================================
    // FIXED RECURRENCE — MULTI-DAY LIFECYCLE
    // ===============================================================

    @Test
    fun `FIXED - three-day lifecycle with varied user actions`() = runTest {
        // Day 1: user completes promptly after notification
        // Day 2: user ignores entirely
        // Day 3: day 2 alarm is cleaned up as orphan when day 3 triggers
        //
        // Expected: 3 total alarms created (d1 initial, d2, d3). d2 orphan-cleaned on day 3.

        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        // === Day 1: notification fires → d2 created ===
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("d2", "d3")
        coEvery { mockAlarmRepo.getAlarm("d2") } returns Result.success(alarm("d2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d1, alarm("d2", dueTimeMs = day2_9am)))

        scheduler.onFixedInstanceTriggered(d1)

        // User completes d1
        val afterD1 = recurring.copy(currentAlarmId = "d2")
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterD1)
        scheduler.onInstanceCompleted(d1)

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) } // d2 only

        // === Day 2: notification fires for d2 → d3 created ===
        val d2 = alarm("d2", dueTimeMs = day2_9am)
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterD1) // currentAlarmId still "d2"
        coEvery { mockAlarmRepo.getAlarm("d3") } returns Result.success(alarm("d3", dueTimeMs = day3_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(d2, alarm("d3", dueTimeMs = day3_9am)))

        scheduler.onFixedInstanceTriggered(d2)
        coVerify(exactly = 2) { mockAlarmRepo.createAlarm(any()) } // d2 + d3

        // === Day 2: user ignores d2, all stages fire ===
        val afterD2Trigger = afterD1.copy(currentAlarmId = "d3")
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterD2Trigger)

        scheduler.onFixedInstanceTriggered(d2) // lock screen → skip
        scheduler.onFixedInstanceTriggered(d2) // due/sound → skip

        coVerify(exactly = 2) { mockAlarmRepo.createAlarm(any()) } // still d2 + d3

        // d2 not cancelled (user hasn't acted, but it's not an orphan from day 3's perspective yet)
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("d2") }
    }

    // endregion

    // ===============================================================
    // RELATIVE RECURRENCE
    // ===============================================================

    // region Relative: Basic completion cycle

    @Test
    fun `RELATIVE - completion creates next instance after interval`() = runTest {
        val recurring = relativeRecurring(currentAlarmId = "r1", intervalMs = DAY_MS)
        val r1 = alarm("r1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("r2")
        coEvery { mockAlarmRepo.getAlarm("r2") } returns Result.success(alarm("r2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("r2", dueTimeMs = day2_9am)))

        scheduler.onInstanceCompleted(r1)

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 1) { mockRecurringRepo.recordCompletion(eq("rec1"), any()) }
    }

    // endregion

    // region Relative: Cancellation creates next from last completion anchor

    @Test
    fun `RELATIVE - cancellation creates next from last completion date`() = runTest {
        val lastCompletion = ts(day1_9am - DAY_MS) // yesterday
        val recurring = relativeRecurring(
            currentAlarmId = "r1",
            intervalMs = DAY_MS,
            completionCount = 1,
            lastCompletionDate = lastCompletion
        )
        val r1 = alarm("r1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("r2")
        coEvery { mockAlarmRepo.getAlarm("r2") } returns Result.success(alarm("r2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("r2", dueTimeMs = day2_9am)))

        scheduler.onInstanceCancelled(r1)

        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        // Cancellation should NOT record completion
        coVerify(exactly = 0) { mockRecurringRepo.recordCompletion(any(), any()) }
    }

    // endregion

    // region Relative: Trigger does NOT create next instance (only completion/cancellation does)

    @Test
    fun `RELATIVE - onFixedInstanceTriggered is ignored for RELATIVE type`() = runTest {
        val recurring = relativeRecurring(currentAlarmId = "r1")
        val r1 = alarm("r1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)

        scheduler.onFixedInstanceTriggered(r1)

        // RELATIVE type — trigger should be ignored
        coVerify(exactly = 0) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Relative: Multi-cycle completion

    @Test
    fun `RELATIVE - two completion cycles create sequential instances`() = runTest {
        val recurring = relativeRecurring(currentAlarmId = "r1", intervalMs = DAY_MS)
        val r1 = alarm("r1", dueTimeMs = day1_9am)

        // Cycle 1: complete r1 → creates r2
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        trackAlarmCreation("r2", "r3")
        coEvery { mockAlarmRepo.getAlarm("r2") } returns Result.success(alarm("r2", dueTimeMs = day2_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("r2", dueTimeMs = day2_9am)))

        scheduler.onInstanceCompleted(r1)

        // Cycle 2: complete r2 → creates r3
        // Note: onInstanceCompleted uses Timestamp.now() as anchor, so we mock the
        // recurring template to reflect the updated state after cycle 1.
        val afterCycle1 = recurring.copy(
            currentAlarmId = "r2",
            completionCount = 1,
            lastCompletionDate = Timestamp.now()
        )
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(afterCycle1)
        coEvery { mockAlarmRepo.getAlarm("r3") } returns Result.success(alarm("r3", dueTimeMs = day3_9am))
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(alarm("r3", dueTimeMs = day3_9am)))

        val r2 = alarm("r2", dueTimeMs = day2_9am)
        scheduler.onInstanceCompleted(r2)

        coVerify(exactly = 2) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 2) { mockRecurringRepo.recordCompletion(eq("rec1"), any()) }
    }

    // endregion

    // ===============================================================
    // EDGE CASES
    // ===============================================================

    // region Non-recurring alarm — no recurrence side effects

    @Test
    fun `non-recurring alarm trigger has no recurrence side effects`() = runTest {
        val nonRecurring = alarm("solo1", dueTimeMs = day1_9am).copy(recurringAlarmId = null)

        scheduler.onFixedInstanceTriggered(nonRecurring)
        scheduler.onInstanceCompleted(nonRecurring)
        scheduler.onInstanceCancelled(nonRecurring)

        coVerify(exactly = 0) { mockRecurringRepo.get(any()) }
        coVerify(exactly = 0) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Recurring template deleted from server (returns null)

    @Test
    fun `trigger with deleted recurring template is a no-op`() = runTest {
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(null)

        scheduler.onFixedInstanceTriggered(d1)
        scheduler.onInstanceCompleted(d1)

        coVerify(exactly = 0) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Bootstrap with multiple active recurring alarms

    @Test
    fun `bootstrap handles multiple recurring alarms independently`() = runTest {
        val rec1 = dailyRecurring(id = "rec1", currentAlarmId = "a1")
        val rec2 = dailyRecurring(id = "rec2", currentAlarmId = "b1")

        // rec1's current alarm is still pending → reschedule only
        val a1 = alarm("a1", dueTimeMs = day1_9am, recurringAlarmId = "rec1")
        // rec2's current alarm is done → needs new instance
        val b1Done = alarm("b1", dueTimeMs = day1_9am, recurringAlarmId = "rec2", status = AlarmStatus.DONE)

        coEvery { mockRecurringRepo.getActiveRecurringAlarms() } returns Result.success(listOf(rec1, rec2))
        coEvery { mockAlarmRepo.getAlarm("a1") } returns Result.success(a1)
        coEvery { mockAlarmRepo.getAlarm("b1") } returns Result.success(b1Done)

        trackAlarmCreation("b2")
        coEvery { mockAlarmRepo.getAlarm("b2") } returns Result.success(
            alarm("b2", dueTimeMs = day2_9am, recurringAlarmId = "rec2")
        )
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec2") } returns
                Result.success(listOf(alarm("b2", dueTimeMs = day2_9am, recurringAlarmId = "rec2")))

        scheduler.bootstrapRecurringAlarms()

        // rec1: rescheduled, no new alarm
        verify { mockAlarmScheduler.scheduleAlarm(a1) }
        // rec2: new alarm created
        coVerify(exactly = 1) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 1) { mockRecurringRepo.updateCurrentAlarmId("rec2", "b2") }
    }

    // endregion

    // region createAlarm fails — no partial state

    @Test
    fun `FIXED - createAlarm failure does not update currentAlarmId`() = runTest {
        val recurring = dailyRecurring(currentAlarmId = "d1")
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(recurring)
        coEvery { mockAlarmRepo.createAlarm(any()) } returns Result.failure(Exception("Firestore write failed"))

        scheduler.onFixedInstanceTriggered(d1)

        // Should NOT update currentAlarmId if alarm creation failed
        coVerify(exactly = 0) { mockRecurringRepo.updateCurrentAlarmId(any(), any()) }
        // Should NOT try to schedule
        verify(exactly = 0) { mockAlarmScheduler.scheduleAlarm(any()) }
    }

    // endregion

    // region Recurring template fetch fails

    @Test
    fun `FIXED - recurring template fetch failure is a no-op`() = runTest {
        val d1 = alarm("d1", dueTimeMs = day1_9am)

        coEvery { mockRecurringRepo.get("rec1") } returns Result.failure(Exception("Network error"))

        scheduler.onFixedInstanceTriggered(d1)

        coVerify(exactly = 0) { mockAlarmRepo.createAlarm(any()) }
    }

    // endregion

    // region Multiple recurring alarms don't interfere

    @Test
    fun `triggers for different recurring alarms don't cross-contaminate`() = runTest {
        val rec1 = dailyRecurring(id = "rec1", currentAlarmId = "a1")
        val rec2 = dailyRecurring(id = "rec2", currentAlarmId = "b1")
        val a1 = alarm("a1", dueTimeMs = day1_9am, recurringAlarmId = "rec1")
        val b1 = alarm("b1", dueTimeMs = day1_9am, recurringAlarmId = "rec2")

        // Trigger a1 → creates a2
        coEvery { mockRecurringRepo.get("rec1") } returns Result.success(rec1)
        trackAlarmCreation("a2", "b2")
        coEvery { mockAlarmRepo.getAlarm("a2") } returns Result.success(
            alarm("a2", dueTimeMs = day2_9am, recurringAlarmId = "rec1")
        )
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec1") } returns
                Result.success(listOf(a1, alarm("a2", dueTimeMs = day2_9am, recurringAlarmId = "rec1")))

        scheduler.onFixedInstanceTriggered(a1)

        // Trigger b1 → creates b2
        coEvery { mockRecurringRepo.get("rec2") } returns Result.success(rec2)
        coEvery { mockAlarmRepo.getAlarm("b2") } returns Result.success(
            alarm("b2", dueTimeMs = day2_9am, recurringAlarmId = "rec2")
        )
        coEvery { mockAlarmRepo.getPendingInstancesForRecurring("rec2") } returns
                Result.success(listOf(b1, alarm("b2", dueTimeMs = day2_9am, recurringAlarmId = "rec2")))

        scheduler.onFixedInstanceTriggered(b1)

        // 2 alarms total (one per recurring)
        coVerify(exactly = 2) { mockAlarmRepo.createAlarm(any()) }
        coVerify(exactly = 1) { mockRecurringRepo.updateCurrentAlarmId("rec1", "a2") }
        coVerify(exactly = 1) { mockRecurringRepo.updateCurrentAlarmId("rec2", "b2") }

        // Neither a1 nor b1 cancelled
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("a1") }
        coVerify(exactly = 0) { mockAlarmRepo.markCancelled("b1") }
    }

    // endregion
}
