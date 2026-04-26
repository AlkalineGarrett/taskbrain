package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.mutableStateOf
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.ui.currentnote.components.AlarmDialogMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmDialogStateTest {

    private fun makeAlarm(id: String, recurringAlarmId: String? = null) = Alarm(
        id = id,
        userId = "u1",
        noteId = "n1",
        lineContent = "task",
        status = AlarmStatus.PENDING,
        dueTime = null,
        recurringAlarmId = recurringAlarmId,
    )

    private fun makeState(
        showDialog: Boolean = true,
        alarm: Alarm? = null,
        siblings: List<Alarm> = emptyList(),
    ) = AlarmDialogState(
        showDialogState = mutableStateOf(showDialog),
        lineContentState = mutableStateOf("line"),
        lineIndexState = mutableStateOf(2),
        inlineSessionState = mutableStateOf(null),
        inlineSessionNoteIdState = mutableStateOf("inline-note"),
        alarmState = mutableStateOf(alarm),
        alarmIdToRestoreState = mutableStateOf("a1"),
        recurringAlarmIdToRestoreState = mutableStateOf("r1"),
        initialModeState = mutableStateOf(AlarmDialogMode.RECURRENCE),
        tappedDirectiveTextState = mutableStateOf("[alarm(\"x\")]"),
        siblingsState = mutableStateOf(siblings),
    )

    @Test
    fun `dismiss resets every dialog field except siblings`() {
        val alarm = makeAlarm("a1")
        val state = makeState(alarm = alarm, siblings = listOf(alarm))

        state.dismiss()

        assertFalse(state.showDialog)
        assertNull(state.lineIndex)
        assertNull(state.inlineSession)
        assertNull(state.inlineSessionNoteId)
        assertNull(state.alarm)
        assertNull(state.alarmIdToRestore)
        assertNull(state.recurringAlarmIdToRestore)
        assertEquals(AlarmDialogMode.INSTANCE, state.initialMode)
        assertNull(state.tappedDirectiveText)
        // siblings is owned by the sibling-fetch effect; dismiss leaves it alone
        assertEquals(listOf(alarm), state.siblings)
    }

    @Test
    fun `navigatePrevious moves to earlier sibling and stops at start`() {
        val a = makeAlarm("a1", recurringAlarmId = "r1")
        val b = makeAlarm("a2", recurringAlarmId = "r1")
        val c = makeAlarm("a3", recurringAlarmId = "r1")
        val state = makeState(alarm = b, siblings = listOf(a, b, c))

        state.navigatePrevious()
        assertEquals(a, state.alarm)

        state.navigatePrevious()
        assertEquals(a, state.alarm)
    }

    @Test
    fun `navigateNext moves to later sibling and stops at end`() {
        val a = makeAlarm("a1", recurringAlarmId = "r1")
        val b = makeAlarm("a2", recurringAlarmId = "r1")
        val c = makeAlarm("a3", recurringAlarmId = "r1")
        val state = makeState(alarm = b, siblings = listOf(a, b, c))

        state.navigateNext()
        assertEquals(c, state.alarm)

        state.navigateNext()
        assertEquals(c, state.alarm)
    }

    @Test
    fun `hasPrevious and hasNext reflect current position in siblings`() {
        val a = makeAlarm("a1", recurringAlarmId = "r1")
        val b = makeAlarm("a2", recurringAlarmId = "r1")
        val c = makeAlarm("a3", recurringAlarmId = "r1")

        val firstSelected = makeState(alarm = a, siblings = listOf(a, b, c))
        assertFalse(firstSelected.hasPrevious)
        assertTrue(firstSelected.hasNext)

        val middleSelected = makeState(alarm = b, siblings = listOf(a, b, c))
        assertTrue(middleSelected.hasPrevious)
        assertTrue(middleSelected.hasNext)

        val lastSelected = makeState(alarm = c, siblings = listOf(a, b, c))
        assertTrue(lastSelected.hasPrevious)
        assertFalse(lastSelected.hasNext)
    }

    @Test
    fun `hasPrevious and hasNext are false when alarm is not in siblings`() {
        val a = makeAlarm("a1")
        val orphan = makeAlarm("a-orphan")
        val state = makeState(alarm = orphan, siblings = listOf(a))

        assertFalse(state.hasPrevious)
        assertFalse(state.hasNext)
    }

    @Test
    fun `recurringId is read from current alarm`() {
        val recurring = makeAlarm("a1", recurringAlarmId = "rec-42")
        val nonRecurring = makeAlarm("a2", recurringAlarmId = null)

        assertEquals("rec-42", makeState(alarm = recurring).recurringId)
        assertNull(makeState(alarm = nonRecurring).recurringId)
        assertNull(makeState(alarm = null).recurringId)
    }
}
