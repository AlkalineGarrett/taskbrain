package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.ui.currentnote.components.AlarmDialogMode

/**
 * State holder for the alarm config dialog. Mixes saveable scalars (line content,
 * IDs, mode) with non-saveable transient references (the resolved Alarm and the
 * inline session) — recreation surrogates for the non-saveables are persisted as
 * IDs/noteIds and re-resolved by [rememberAlarmDialogState] after Activity recreation.
 */
@Stable
class AlarmDialogState internal constructor(
    showDialogState: MutableState<Boolean>,
    lineContentState: MutableState<String>,
    lineIndexState: MutableState<Int?>,
    inlineSessionState: MutableState<InlineEditSession?>,
    inlineSessionNoteIdState: MutableState<String?>,
    alarmState: MutableState<Alarm?>,
    alarmIdToRestoreState: MutableState<String?>,
    recurringAlarmIdToRestoreState: MutableState<String?>,
    initialModeState: MutableState<AlarmDialogMode>,
    tappedDirectiveTextState: MutableState<String?>,
    siblingsState: MutableState<List<Alarm>>,
) {
    var showDialog: Boolean by showDialogState
    var lineContent: String by lineContentState
    var lineIndex: Int? by lineIndexState
    var inlineSession: InlineEditSession? by inlineSessionState
    var inlineSessionNoteId: String? by inlineSessionNoteIdState
    var alarm: Alarm? by alarmState
    var alarmIdToRestore: String? by alarmIdToRestoreState
    var recurringAlarmIdToRestore: String? by recurringAlarmIdToRestoreState
    var initialMode: AlarmDialogMode by initialModeState
    var tappedDirectiveText: String? by tappedDirectiveTextState
    var siblings: List<Alarm> by siblingsState

    val recurringId: String? get() = alarm?.recurringAlarmId
    val hasPrevious: Boolean get() = currentIndex > 0
    val hasNext: Boolean get() = currentIndex in 0 until siblings.lastIndex

    private val currentIndex: Int get() = siblings.indexOfFirst { it.id == alarm?.id }

    fun navigatePrevious() {
        if (currentIndex > 0) alarm = siblings[currentIndex - 1]
    }

    fun navigateNext() {
        if (currentIndex in 0 until siblings.lastIndex) alarm = siblings[currentIndex + 1]
    }

    fun dismiss() {
        showDialog = false
        lineIndex = null
        clearTransients()
    }

    /**
     * Stage the dialog state for opening on a new alarm — the toolbar add-alarm path.
     * Sets [showDialog] synchronously since no async fetch is needed.
     */
    fun openForNewAlarm(lineContent: String, lineIndex: Int?, inlineSession: InlineEditSession?) {
        clearTransients()
        this.lineContent = lineContent
        this.lineIndex = lineIndex
        this.inlineSession = inlineSession
        this.inlineSessionNoteId = inlineSession?.noteId
        showDialog = true
    }

    /**
     * Stage the dialog for an existing single-instance alarm. Caller fires the
     * alarm fetch and then assigns [alarm] + sets [showDialog] when it returns.
     */
    fun beginOpenForTappedAlarm(lineContent: String, lineIndex: Int, alarmId: String, directiveText: String) {
        clearTransients()
        this.lineContent = lineContent
        this.lineIndex = lineIndex
        alarmIdToRestore = alarmId
        tappedDirectiveText = directiveText
    }

    /**
     * Stage the dialog for an existing recurring alarm. Same async-completion
     * contract as [beginOpenForTappedAlarm].
     */
    fun beginOpenForTappedRecurring(lineContent: String, lineIndex: Int, recurringId: String, directiveText: String) {
        clearTransients()
        this.lineContent = lineContent
        this.lineIndex = lineIndex
        initialMode = AlarmDialogMode.RECURRENCE
        recurringAlarmIdToRestore = recurringId
        tappedDirectiveText = directiveText
    }

    private fun clearTransients() {
        inlineSession = null
        inlineSessionNoteId = null
        alarm = null
        alarmIdToRestore = null
        recurringAlarmIdToRestore = null
        initialMode = AlarmDialogMode.INSTANCE
        tappedDirectiveText = null
    }
}

/**
 * Builds an [AlarmDialogState] with rememberSaveable-backed scalars for the fields
 * that survive Activity recreation, plain remember for transient references, and
 * installs the two effects that keep the dialog state consistent:
 *
 *  - Sibling-list refresh when the dialog opens for a recurring alarm.
 *  - Restore the transient Alarm and InlineEditSession references after recreation
 *    by re-fetching from the alarm manager / inline-edit-state by their saved IDs.
 */
@Composable
fun rememberAlarmDialogState(
    inlineEditState: InlineEditState,
    alarmManager: NoteAlarmManager,
): AlarmDialogState {
    val showDialogState = rememberSaveable { mutableStateOf(false) }
    val lineContentState = rememberSaveable { mutableStateOf("") }
    val lineIndexState = rememberSaveable { mutableStateOf<Int?>(null) }
    val inlineSessionState = remember { mutableStateOf<InlineEditSession?>(null) }
    val inlineSessionNoteIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val alarmState = remember { mutableStateOf<Alarm?>(null) }
    val alarmIdToRestoreState = rememberSaveable { mutableStateOf<String?>(null) }
    val recurringAlarmIdToRestoreState = rememberSaveable { mutableStateOf<String?>(null) }
    val initialModeState = rememberSaveable { mutableStateOf(AlarmDialogMode.INSTANCE) }
    val tappedDirectiveTextState = rememberSaveable { mutableStateOf<String?>(null) }
    val siblingsState = remember { mutableStateOf<List<Alarm>>(emptyList()) }

    val state = remember {
        AlarmDialogState(
            showDialogState,
            lineContentState,
            lineIndexState,
            inlineSessionState,
            inlineSessionNoteIdState,
            alarmState,
            alarmIdToRestoreState,
            recurringAlarmIdToRestoreState,
            initialModeState,
            tappedDirectiveTextState,
            siblingsState,
        )
    }

    LaunchedEffect(state.recurringId, state.showDialog) {
        state.siblings = if (state.showDialog && state.recurringId != null) {
            alarmManager.getInstancesForRecurring(state.recurringId!!)
        } else {
            emptyList()
        }
    }

    LaunchedEffect(
        state.showDialog,
        state.alarmIdToRestore,
        state.recurringAlarmIdToRestore,
        state.inlineSessionNoteId,
    ) {
        if (!state.showDialog) return@LaunchedEffect
        if (state.alarm == null) {
            val instanceId = state.alarmIdToRestore
            val recurringId = state.recurringAlarmIdToRestore
            when {
                instanceId != null -> alarmManager.fetchAlarmById(instanceId) { state.alarm = it }
                recurringId != null -> alarmManager.fetchRecurringAlarmInstance(recurringId) { state.alarm = it }
            }
        }
        if (state.inlineSession == null) {
            val sessionNoteId = state.inlineSessionNoteId
            if (sessionNoteId != null) {
                state.inlineSession = inlineEditState.viewSessions[sessionNoteId]
            }
        }
    }

    return state
}
