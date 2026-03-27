package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.foundation.background
import org.alkaline.taskbrain.data.Alarm
import org.alkaline.taskbrain.data.AlarmStatus
import org.alkaline.taskbrain.data.CloseTabResult
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.data.TabState
import org.alkaline.taskbrain.ui.currentnote.rendering.ButtonCallbacks
import org.alkaline.taskbrain.ui.currentnote.util.AlarmSymbolUtils
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol
import org.alkaline.taskbrain.ui.currentnote.components.AlarmDialogMode
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardHtmlConverter
import org.alkaline.taskbrain.ui.currentnote.util.CompletedLineUtils
import org.alkaline.taskbrain.ui.currentnote.undo.UndoStatePersistence
import androidx.compose.ui.res.stringResource
import org.alkaline.taskbrain.R
import org.alkaline.taskbrain.ui.currentnote.util.TextLineUtils
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import org.alkaline.taskbrain.dsl.runtime.MutationType
import org.alkaline.taskbrain.BuildConfig
import org.alkaline.taskbrain.ui.currentnote.components.AgentCommandSection
import org.alkaline.taskbrain.ui.currentnote.components.CommandBar
import org.alkaline.taskbrain.ui.currentnote.components.NoteTextField
import org.alkaline.taskbrain.ui.currentnote.components.StatusBar
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import android.util.Log
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult

/**
 * Main screen for viewing and editing a note.
 * Coordinates the note text field, command bar, and agent command section.
 */
@Composable
fun CurrentNoteScreen(
    noteId: String? = null,
    isFingerDownFlow: StateFlow<Boolean>? = null,
    onNavigateBack: () -> Unit = {},
    onNavigateToNote: (String) -> Unit = {},
    currentNoteViewModel: CurrentNoteViewModel = viewModel(),
    recentTabsViewModel: RecentTabsViewModel = viewModel()
) {
    // --- ViewModel state observations ---
    val saveStatus by currentNoteViewModel.saveStatus.observeAsState()
    val loadStatus by currentNoteViewModel.loadStatus.observeAsState()
    val contentModified by currentNoteViewModel.contentModified.observeAsState(false)
    val isAgentProcessing = if (BuildConfig.AGENT_COMMAND_ENABLED) {
        currentNoteViewModel.isAgentProcessing.observeAsState(false).value
    } else false
    val alarmCreated by currentNoteViewModel.alarmCreated.observeAsState()
    val alarmError by currentNoteViewModel.alarmError.observeAsState()
    val alarmPermissionWarning by currentNoteViewModel.alarmPermissionWarning.observeAsState(false)
    val notificationPermissionWarning by currentNoteViewModel.notificationPermissionWarning.observeAsState(false)
    val schedulingWarning by currentNoteViewModel.schedulingWarning.observeAsState()
    val isAlarmOperationPending by currentNoteViewModel.isAlarmOperationPending.observeAsState(false)
    val redoRollbackWarning by currentNoteViewModel.redoRollbackWarning.observeAsState()
    val saveWarning by currentNoteViewModel.saveWarning.observeAsState()
    val isNoteDeletedFromVm by currentNoteViewModel.isNoteDeleted.observeAsState(false)
    val showCompleted by currentNoteViewModel.showCompleted.observeAsState(true)
    // Generation counter bumps after async cache fills, triggering recomposition
    val directiveCacheGeneration by currentNoteViewModel.directiveCacheGeneration.observeAsState(0)
    val buttonExecutionStates by currentNoteViewModel.buttonExecutionStates.observeAsState(emptyMap())
    val buttonErrors by currentNoteViewModel.buttonErrors.observeAsState(emptyMap())
    val recentTabs by recentTabsViewModel.tabs.observeAsState(emptyList())
    val tabsError by recentTabsViewModel.error.observeAsState()

    // --- Local state ---
    var displayedNoteId by remember { mutableStateOf(noteId) }
    LaunchedEffect(noteId) {
        // Skip null transitions (navigation intermediates) to avoid flashing empty content
        if (noteId != null && noteId != displayedNoteId) displayedNoteId = noteId
    }

    // Only use editor cache for dirty notes (unsaved edits); clean notes load from NoteStore
    val cachedContent = remember(displayedNoteId) {
        displayedNoteId?.let { recentTabsViewModel.getCachedContent(it) }?.takeIf { it.isDirty }
    }
    val storeContent = remember(displayedNoteId) {
        displayedNoteId?.let { NoteStore.getNoteById(it) }
    }
    val initialContent = cachedContent?.noteLines?.joinToString("\n") { it.content }
        ?: storeContent?.content
        ?: ""
    val initialIsDeleted = cachedContent?.isDeleted ?: (storeContent?.state == "deleted")

    var isNoteDeleted by remember(displayedNoteId) { mutableStateOf(initialIsDeleted) }
    LaunchedEffect(isNoteDeletedFromVm) { isNoteDeleted = isNoteDeletedFromVm }

    var userContent by remember(displayedNoteId) { mutableStateOf(initialContent) }
    var textFieldValue by remember(displayedNoteId) {
        mutableStateOf(TextFieldValue(initialContent, TextRange(initialContent.length)))
    }
    var isSaved by remember(displayedNoteId) { mutableStateOf(true) }
    var agentCommand by remember { mutableStateOf("") }
    var isAgentSectionExpanded by remember { mutableStateOf(false) }
    @Suppress("KotlinConstantConditions")
    val agentCommandEnabled = BuildConfig.AGENT_COMMAND_ENABLED

    // Wrapper for user edits. Updates userContent and marks the note hot
    // (so Firestore echo doesn't overwrite it).
    fun updateContent(newContent: String) {
        userContent = newContent
        displayedNoteId?.let { NoteStore.markHot(it) }
    }

    // Push editor content to NoteStore on tab switch only (not every keystroke —
    // StateFlow emissions reset the cursor). SideEffect captures the latest
    // content/noteId AFTER each composition. When displayedNoteId changes, the
    // remember block reads the refs (still holding PREVIOUS values) and pushes.
    val prevContentRef = remember { mutableStateOf("") }
    val prevNoteIdRef = remember { mutableStateOf<String?>(null) }
    SideEffect {
        prevContentRef.value = userContent
        prevNoteIdRef.value = displayedNoteId
    }
    remember(displayedNoteId) {
        val prevId = prevNoteIdRef.value
        val prevContent = prevContentRef.value
        if (prevId != null && prevId != displayedNoteId && prevContent.isNotEmpty()) {
            currentNoteViewModel.pushContentToNoteStore(prevId, prevContent)
        }
        Unit
    }

    val mainContentFocusRequester = remember { FocusRequester() }
    var isMainContentFocused by remember { mutableStateOf(false) }

    val editorState = remember(displayedNoteId) {
        EditorState().apply {
            if (initialContent.isNotEmpty()) updateFromText(initialContent)
        }
    }
    val controller = rememberEditorController(editorState)

    // Sync tracker noteIds into editor state, then map directive results using
    // the editor's effective IDs so key generation matches key lookup in rendering
    // Keep previous directive results until new ones are ready to avoid flash of raw source
    // Compute directive results synchronously — no async races.
    // The CachedDirectiveExecutor's L1 cache makes cache hits instant.
    // directiveCacheGeneration triggers recomposition after async cache fills (cold start).
    val directiveResults = remember(userContent, directiveCacheGeneration, displayedNoteId) {
        // NoteStore already has the latest content (pushed on every keystroke via updateContent).
        // No flush needed — just compute directives.
        currentNoteViewModel.computeDirectiveResults(userContent, displayedNoteId)
    }

    // Update hidden indices for move system when showCompleted or lines change
    controller.hiddenIndices = remember(userContent, showCompleted) {
        CompletedLineUtils.computeHiddenIndices(editorState.lines.map { it.text }, showCompleted)
    }

    // Inline editing state for view directives
    val inlineEditState = rememberInlineEditState()
    LaunchedEffect(inlineEditState) {
        inlineEditState.onExecuteDirectives = { content, onResults ->
            currentNoteViewModel.executeDirectivesForContent(content, onResults)
        }
        inlineEditState.onExecuteSingleDirective = { sourceText, onResult ->
            currentNoteViewModel.executeSingleDirective(sourceText, onResult)
        }
        inlineEditState.onDirectiveEditConfirm = { _, _, _, _ ->
            inlineEditState.activeSession?.let { session ->
                currentNoteViewModel.saveInlineNoteContent(
                    noteId = session.noteId,
                    newContent = session.currentContent,
                    onSuccess = {
                        session.markSaved()
                        recentTabsViewModel.invalidateCache(session.noteId)
                        currentNoteViewModel.executeDirectivesForContent(session.currentContent) { results ->
                            session.updateDirectiveResults(results)
                            currentNoteViewModel.forceRefreshAllDirectives {}
                        }
                    }
                )
            }
        }
    }

    val activeController = inlineEditState.activeController ?: controller
    val context = LocalContext.current
    val currentNoteId by currentNoteViewModel.currentNoteIdLiveData.observeAsState()

    // Sync displayedNoteId from ViewModel when the screen didn't specify a noteId via
    // navigation (default CurrentNote route). This allows the ViewModel to redirect to a
    // different note (e.g., when a stale SharedPreferences noteId causes permission denied
    // and loadContent falls back to creating a new note for the current user).
    LaunchedEffect(currentNoteId) {
        if (noteId == null && currentNoteId != null) displayedNoteId = currentNoteId
    }

    // Undo/redo state observation
    @Suppress("UNUSED_VARIABLE")
    val editorStateVersion = editorState.stateVersion
    @Suppress("UNUSED_VARIABLE")
    val undoStateVersion = controller.undoManager.stateVersion
    val canUndo = controller.canUndo
    val canRedo = controller.canRedo

    // Alarm dialog state
    var showAlarmDialog by remember { mutableStateOf(false) }
    var alarmDialogLineContent by remember { mutableStateOf("") }
    var alarmDialogLineIndex by remember { mutableStateOf<Int?>(null) }
    val alarmCacheForOverlay by currentNoteViewModel.alarmCache.observeAsState(emptyMap())
    val recurringAlarmCacheForOverlay by currentNoteViewModel.recurringAlarmCache.observeAsState(emptyMap())
    // The alarm being viewed/edited in the dialog (set when tapping a directive)
    var alarmDialogAlarm by remember { mutableStateOf<Alarm?>(null) }
    val alarmDialogExistingAlarm = alarmDialogAlarm
    var alarmDialogInitialMode by remember { mutableStateOf(AlarmDialogMode.INSTANCE) }
    // The directive text that was tapped to open the dialog (for directive switching after save)
    var tappedDirectiveText by remember { mutableStateOf<String?>(null) }
    val alarmDialogRecurrenceConfig by currentNoteViewModel.recurrenceConfig.observeAsState()
    val alarmDialogRecurringAlarm by currentNoteViewModel.recurringAlarm.observeAsState()

    // Sibling instances for recurring alarm navigation in the dialog
    var alarmDialogSiblings by remember { mutableStateOf<List<Alarm>>(emptyList()) }
    val alarmDialogRecurringId = alarmDialogExistingAlarm?.recurringAlarmId
    LaunchedEffect(alarmDialogRecurringId, showAlarmDialog) {
        alarmDialogSiblings = if (showAlarmDialog && alarmDialogRecurringId != null) {
            currentNoteViewModel.getInstancesForRecurring(alarmDialogRecurringId)
        } else {
            emptyList()
        }
    }

    // --- Effects ---
    LifecycleAutoSaveEffect(
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        userContent = userContent,
        isSaved = isSaved,
        isNoteDeleted = isNoteDeleted,
        currentNoteId = currentNoteId
    )

    DataLoadingEffects(
        displayedNoteId = displayedNoteId,
        loadStatus = loadStatus,
        tabsError = tabsError,
        userContent = userContent,
        isSaved = isSaved,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        inlineEditState = inlineEditState,
        onContentLoaded = { loadedContent ->
            userContent = loadedContent
            textFieldValue = TextFieldValue(loadedContent, TextRange(loadedContent.length))
        }
    )

    ContentSyncEffects(
        currentNoteId = currentNoteId,
        loadStatus = loadStatus,
        saveStatus = saveStatus,
        contentModified = contentModified,
        alarmCreated = alarmCreated,
        userContent = userContent,
        isNoteDeleted = isNoteDeleted,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        recentTabsViewModel = recentTabsViewModel,
        onContentChanged = { updateContent(it) },
        onSavedChanged = { isSaved = it }
    )


    DirectiveMutationEffect(
        currentNoteId = currentNoteId,
        userContent = userContent,
        editorState = editorState,
        controller = controller,
        currentNoteViewModel = currentNoteViewModel,
        onContentChanged = { content, tfv ->
            updateContent(content)
            textFieldValue = tfv
        },
        onMarkUnsaved = { isSaved = false }
    )

    // Switches the directive text in the editor if the directive type changed.
    // e.g., [alarm("id")] → [recurringAlarm("recId")] or vice versa.
    fun switchDirectiveIfNeeded(newDirective: String) {
        val tapped = tappedDirectiveText ?: return
        if (tapped == newDirective) return
        val updatedText = editorState.text.replace(tapped, newDirective)
        if (updatedText != editorState.text) {
            editorState.updateFromText(updatedText)
            updateContent(editorState.text)
            isSaved = false
            currentNoteViewModel.saveContent(editorState.text, editorState.lines.map { it.noteIds })
        }
    }

    // --- Dialogs ---
    NoteScreenDialogs(
        saveStatus = saveStatus,
        loadStatus = loadStatus,
        tabsError = tabsError,
        alarmError = alarmError,
        alarmPermissionWarning = alarmPermissionWarning,
        notificationPermissionWarning = notificationPermissionWarning,
        schedulingWarning = schedulingWarning,
        redoRollbackWarning = redoRollbackWarning,
        saveWarning = saveWarning,
        onClearSaveWarning = { currentNoteViewModel.clearSaveWarning() },
        onClearSaveError = { currentNoteViewModel.clearSaveError() },
        onClearLoadError = { currentNoteViewModel.clearLoadError() },
        onClearTabsError = { recentTabsViewModel.clearError() },
        onClearAlarmError = { currentNoteViewModel.clearAlarmError() },
        onClearAlarmPermissionWarning = { currentNoteViewModel.clearAlarmPermissionWarning() },
        onClearNotificationPermissionWarning = { currentNoteViewModel.clearNotificationPermissionWarning() },
        onClearSchedulingWarning = { currentNoteViewModel.clearSchedulingWarning() },
        onClearRedoRollbackWarning = { currentNoteViewModel.clearRedoRollbackWarning() },
        showAlarmDialog = showAlarmDialog,
        alarmDialogLineContent = alarmDialogLineContent,
        alarmDialogExistingAlarm = alarmDialogExistingAlarm,
        alarmDialogInitialMode = alarmDialogInitialMode,
        alarmDialogRecurrenceConfig = alarmDialogRecurrenceConfig,
        alarmDialogRecurringAlarm = alarmDialogRecurringAlarm,
        alarmDialogInstanceCount = alarmDialogSiblings.size,
        onAlarmSave = { dueTime, stages ->
            val existing = alarmDialogExistingAlarm
            if (existing != null) {
                currentNoteViewModel.updateAlarm(existing, dueTime, stages)
                // Non-recurring save: switch [recurringAlarm(...)] → [alarm(...)]
                switchDirectiveIfNeeded(AlarmSymbolUtils.alarmDirective(existing.id))
            } else {
                currentNoteViewModel.saveAndCreateAlarm(userContent, alarmDialogLineContent, alarmDialogLineIndex, dueTime, stages)
            }
        },
        onAlarmSaveRecurring = { dueTime, stages, recurrenceConfig ->
            val existing = alarmDialogExistingAlarm
            if (existing != null) {
                currentNoteViewModel.updateRecurringAlarm(existing, dueTime, stages, recurrenceConfig)
                // Recurring save: switch [alarm(...)] → [recurringAlarm(...)]
                val recurringId = existing.recurringAlarmId
                if (recurringId != null) {
                    switchDirectiveIfNeeded(AlarmSymbolUtils.recurringAlarmDirective(recurringId))
                }
            } else {
                currentNoteViewModel.saveAndCreateRecurringAlarm(userContent, alarmDialogLineContent, alarmDialogLineIndex, dueTime, stages, recurrenceConfig)
            }
        },
        onAlarmSaveInstance = alarmDialogRecurringAlarm?.let { recurring ->
            { alarm, dueTime, stages, alsoUpdateRecurrence ->
                currentNoteViewModel.updateInstanceTimes(alarm, dueTime, stages, alsoUpdateRecurrence)
                // Instance save on recurring: switch [alarm(...)] → [recurringAlarm(...)]
                switchDirectiveIfNeeded(AlarmSymbolUtils.recurringAlarmDirective(recurring.id))
            }
        },
        onAlarmSaveRecurrenceTemplate = alarmDialogRecurringAlarm?.let { recurring ->
            { recId, dueTime, stages, config, alsoUpdateInstances ->
                currentNoteViewModel.updateRecurrenceTemplate(recId, dueTime, stages, config, alsoUpdateInstances)
                // Recurrence template save: switch [alarm(...)] → [recurringAlarm(...)]
                switchDirectiveIfNeeded(AlarmSymbolUtils.recurringAlarmDirective(recurring.id))
            }
        },
        onAlarmMarkDone = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.markAlarmDone(alarm.id) } },
        onAlarmMarkCancelled = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.cancelAlarm(alarm.id) } },
        onAlarmReactivate = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.reactivateAlarm(alarm.id) } },
        onAlarmDelete = alarmDialogExistingAlarm?.let { alarm -> { currentNoteViewModel.deleteAlarmPermanently(alarm.id) } },
        onAlarmNavigatePrevious = if (alarmDialogRecurringId != null) {{
            val idx = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id }
            if (idx > 0) alarmDialogAlarm = alarmDialogSiblings[idx - 1]
        }} else null,
        onAlarmNavigateNext = if (alarmDialogRecurringId != null) {{
            val idx = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id }
            if (idx in 0 until alarmDialogSiblings.lastIndex) alarmDialogAlarm = alarmDialogSiblings[idx + 1]
        }} else null,
        alarmHasPrevious = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id } > 0,
        alarmHasNext = alarmDialogSiblings.indexOfFirst { it.id == alarmDialogExistingAlarm?.id }.let { it in 0 until alarmDialogSiblings.lastIndex },
        onAlarmDismiss = {
            showAlarmDialog = false
            alarmDialogLineIndex = null
            alarmDialogAlarm = null
            alarmDialogInitialMode = AlarmDialogMode.INSTANCE
            tappedDirectiveText = null
            currentNoteViewModel.fetchRecurrenceConfig(null)
        },
        onFetchRecurrenceConfig = { currentNoteViewModel.fetchRecurrenceConfig(it) }
    )

    // Clipboard HTML formatting
    ClipboardHtmlConverter()

    // Text field value change handler
    val updateTextFieldValue: (TextFieldValue) -> Unit = { newValue ->
        val oldText = textFieldValue.text
        textFieldValue = newValue
        if (newValue.text != userContent) {
            updateContent(newValue.text)
            if (isSaved) isSaved = false
            val oldBracketCount = oldText.count { it == ']' }
            val newBracketCount = newValue.text.count { it == ']' }
            if (newBracketCount > oldBracketCount) {
                currentNoteViewModel.bumpDirectiveCacheGeneration()
            }
        }
    }

    // --- UI Composition ---
    val deletedNoteBackground = colorResource(R.color.deleted_note_background)
    val deletedNoteTextColor = colorResource(R.color.deleted_note_text)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isNoteDeleted) deletedNoteBackground else Color.White)
    ) {
        RecentTabsBar(
            tabs = recentTabs,
            currentNoteId = displayedNoteId ?: "",
            onTabClick = { targetNoteId ->
                if (!isSaved && userContent.isNotEmpty()) {
                    currentNoteViewModel.saveContent(userContent, editorState.lines.map { it.noteIds })
                }
                displayedNoteId = targetNoteId
            },
            onTabClose = { targetNoteId ->
                recentTabsViewModel.closeTab(targetNoteId)
                when (val result = TabState.closeTabNavigationTarget(recentTabs, targetNoteId, displayedNoteId ?: "")) {
                    is CloseTabResult.StayOnCurrent -> { /* no-op */ }
                    is CloseTabResult.NavigateBack -> onNavigateBack()
                    is CloseTabResult.SwitchTo -> { displayedNoteId = result.noteId }
                }
            }
        )

        NoteStatusBar(
            isSaved = isSaved,
            canUndo = canUndo && !isAlarmOperationPending,
            canRedo = canRedo && !isAlarmOperationPending,
            isNoteDeleted = isNoteDeleted,
            userContent = userContent,
            editorState = editorState,
            controller = controller,
            currentNoteViewModel = currentNoteViewModel,
            onContentChanged = { updateContent(it) },
            onMarkUnsaved = { isSaved = false },
            showCompleted = showCompleted,
            onShowCompletedToggle = { currentNoteViewModel.toggleShowCompleted() },
            onDeleteNote = {
                currentNoteViewModel.deleteCurrentNote(onSuccess = {
                    recentTabsViewModel.closeTab(displayedNoteId ?: "")
                    when (val result = TabState.closeTabNavigationTarget(recentTabs, displayedNoteId ?: "", displayedNoteId ?: "")) {
                        is CloseTabResult.StayOnCurrent -> onNavigateBack()
                        is CloseTabResult.NavigateBack -> onNavigateBack()
                        is CloseTabResult.SwitchTo -> { displayedNoteId = result.noteId }
                    }
                })
            }
        )

        // Loading indicator
        val isInitialLoad = loadStatus is LoadStatus.Loading && userContent.isEmpty()
        if (isInitialLoad) {
            Box(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.loading_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        // Editor
        if (!isInitialLoad) {
            val directiveCallbacks = rememberDirectiveCallbacks(
                editorState = editorState,
                controller = controller,
                currentNoteViewModel = currentNoteViewModel,
                recentTabsViewModel = recentTabsViewModel,
                inlineEditState = inlineEditState,
                userContent = userContent,
                onContentChanged = { updateContent(it) },
                onMarkUnsaved = { isSaved = false }
            )
            val buttonCallbacks = rememberButtonCallbacks(
                currentNoteViewModel = currentNoteViewModel,
                executionStates = buttonExecutionStates,
                errors = buttonErrors
            )

            ProvideInlineEditState(inlineEditState) {
                key(displayedNoteId) {
                    NoteTextField(
                        textFieldValue = textFieldValue,
                        onTextFieldValueChange = updateTextFieldValue,
                        focusRequester = mainContentFocusRequester,
                        onFocusChanged = { isFocused -> isMainContentFocused = isFocused },
                        editorState = editorState,
                        controller = controller,
                        isFingerDownFlow = isFingerDownFlow,
                        onSymbolTap = { tapInfo ->
                            when (tapInfo.symbol) {
                                TappableSymbol.ALARM -> {
                                    val lineContent = editorState.lines.getOrNull(tapInfo.lineIndex)?.text ?: ""
                                    alarmDialogLineContent = TextLineUtils.trimLineForAlarm(lineContent)
                                    alarmDialogLineIndex = tapInfo.lineIndex

                                    if (tapInfo.recurringAlarmId != null) {
                                        tappedDirectiveText = AlarmSymbolUtils.recurringAlarmDirective(tapInfo.recurringAlarmId)
                                        alarmDialogInitialMode = AlarmDialogMode.RECURRENCE
                                        currentNoteViewModel.fetchRecurringAlarmInstance(tapInfo.recurringAlarmId) { alarm ->
                                            alarmDialogAlarm = alarm
                                            showAlarmDialog = true
                                        }
                                    } else if (tapInfo.alarmId != null) {
                                        tappedDirectiveText = AlarmSymbolUtils.alarmDirective(tapInfo.alarmId)
                                        alarmDialogInitialMode = AlarmDialogMode.INSTANCE
                                        currentNoteViewModel.fetchAlarmById(tapInfo.alarmId) { alarm ->
                                            alarmDialogAlarm = alarm
                                            showAlarmDialog = true
                                        }
                                    }
                                }
                            }
                        },
                        textColor = if (isNoteDeleted) deletedNoteTextColor else Color.Black,
                        directiveResults = directiveResults,
                        directiveCallbacks = directiveCallbacks,
                        buttonCallbacks = buttonCallbacks,
                        showCompleted = showCompleted,
                        symbolOverlaysProvider = { lineIndex ->
                            val lineContent = editorState.lines.getOrNull(lineIndex)?.content ?: ""
                            currentNoteViewModel.getSymbolOverlays(lineIndex, lineContent, alarmCacheForOverlay, recurringAlarmCacheForOverlay)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        CommandBar(
            onToggleBullet = { activeController.toggleBullet() },
            onToggleCheckbox = { activeController.toggleCheckbox() },
            onIndent = { activeController.indent() },
            onUnindent = { activeController.unindent() },
            onMoveUp = {
                inlineEditState.activeSession?.let { it.isMoveInProgress = true }
                if (activeController.moveUp()) {
                    if (!inlineEditState.isActive) {
                        updateContent(editorState.text)
                        isSaved = false
                    }
                }
            },
            onMoveDown = {
                inlineEditState.activeSession?.let { it.isMoveInProgress = true }
                if (activeController.moveDown()) {
                    if (!inlineEditState.isActive) {
                        updateContent(editorState.text)
                        isSaved = false
                    }
                }
            },
            moveUpState = activeController.getMoveUpState(),
            moveDownState = activeController.getMoveDownState(),
            onPaste = { clipText, html -> activeController.paste(clipText, html) },
            isPasteEnabled = (isMainContentFocused || inlineEditState.isActive) &&
                !(inlineEditState.activeSession?.editorState?.hasSelection ?: editorState.hasSelection),
            onAddAlarm = {
                if (!inlineEditState.isActive) {
                    controller.commitUndoState(continueEditing = true)
                    val lineContent = editorState.currentLine?.text ?: ""
                    alarmDialogLineContent = TextLineUtils.trimLineForAlarm(lineContent)
                    alarmDialogLineIndex = editorState.focusedLineIndex
                    alarmDialogAlarm = null
                    alarmDialogInitialMode = AlarmDialogMode.INSTANCE
                    tappedDirectiveText = null
                    showAlarmDialog = true
                }
            },
            isAlarmEnabled = isMainContentFocused && !editorState.hasSelection && !inlineEditState.isActive
        )

        if (agentCommandEnabled) {
            AgentCommandSection(
                isExpanded = isAgentSectionExpanded,
                onExpandedChange = { isAgentSectionExpanded = it },
                agentCommand = agentCommand,
                onAgentCommandChange = { agentCommand = it },
                isProcessing = isAgentProcessing,
                onSendCommand = {
                    currentNoteViewModel.processAgentCommand(userContent, agentCommand)
                    agentCommand = ""
                },
                mainContentFocusRequester = mainContentFocusRequester
            )
        }
    }
}

// --- Extracted effect composables ---

/**
 * Handles auto-save on lifecycle events (background, dispose) and undo state persistence.
 */
@Composable
private fun LifecycleAutoSaveEffect(
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    userContent: String,
    isSaved: Boolean,
    isNoteDeleted: Boolean,
    currentNoteId: String?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentUserContent by rememberUpdatedState(userContent)
    val currentIsSaved by rememberUpdatedState(isSaved)
    val currentIsNoteDeleted by rememberUpdatedState(isNoteDeleted)
    val currentNoteIdForPersistence by rememberUpdatedState(currentNoteId)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.commitUndoState()
                currentNoteIdForPersistence?.let { noteId ->
                    UndoStatePersistence.saveStateBlocking(context, noteId, controller.undoManager)
                }
                if (!currentIsSaved && currentUserContent.isNotEmpty()) {
                    currentNoteViewModel.saveContent(currentUserContent, controller.state.lines.map { it.noteIds })
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.commitUndoState()
            currentNoteIdForPersistence?.let { noteId ->
                UndoStatePersistence.saveStateBlocking(context, noteId, controller.undoManager)
            }
            if (!currentIsSaved && currentUserContent.isNotEmpty()) {
                currentNoteViewModel.updateTrackedLines(currentUserContent)
                val trackedLines = currentNoteViewModel.getTrackedLines()
                recentTabsViewModel.cacheNoteContent(
                    currentNoteIdForPersistence!!,
                    trackedLines,
                    currentIsNoteDeleted,
                    isDirty = true
                )
                recentTabsViewModel.updateTabDisplayText(currentNoteIdForPersistence!!, currentUserContent)
                currentNoteViewModel.saveContent(currentUserContent, controller.state.lines.map { it.noteIds })
            }
        }
    }
}

/**
 * Handles initial data loading: note content, undo restoration, tab loading, and retry logic.
 */
@Composable
private fun DataLoadingEffects(
    displayedNoteId: String?,
    loadStatus: LoadStatus?,
    tabsError: TabsError?,
    userContent: String,
    isSaved: Boolean,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    inlineEditState: InlineEditState?,
    onContentLoaded: (String) -> Unit,
) {
    val context = LocalContext.current

    // Load content when displayed note changes.
    // Flush any dirty inline edit to NoteStore before loading the new note.
    // Focus loss fires AFTER recomposition, so the inline edit's onSave hasn't
    // run yet — push the dirty content to NoteStore here so loadContent sees it.
    LaunchedEffect(displayedNoteId) {
        inlineEditState?.activeSession?.let { session ->
            if (session.isDirty) {
                // Only save if the session's content is actually NEWER than NoteStore.
                // The user may have edited the note directly (on another tab) after the
                // inline session was created — NoteStore would have the newer content.
                // Saving the stale session content would overwrite the direct edit.
                val storeContent = NoteStore.getNoteById(session.noteId)?.content
                val sessionContent = session.currentContent
                if (storeContent == null || storeContent == session.originalContent || sessionContent == storeContent) {
                    // NoteStore hasn't changed since the session started, or matches session — safe to save
                    if (storeContent != sessionContent) {
                        currentNoteViewModel.saveInlineNoteContent(
                            noteId = session.noteId,
                            newContent = sessionContent
                        )
                    }
                }
                // else: NoteStore has different content from a direct edit — don't overwrite
            }
        }
        currentNoteViewModel.loadContent(displayedNoteId, recentTabsViewModel)
    }

    // Detect external changes (e.g., from web app) via NoteStore's collection listener.
    // Only applies when the editor is clean and the change is truly external (not our own save).
    LaunchedEffect(displayedNoteId) {
        if (displayedNoteId == null) return@LaunchedEffect
        var isFirstEmission = true
        NoteStore.notes.collect {
            if (isFirstEmission) { isFirstEmission = false; return@collect }
            if (!isSaved) return@collect // local edits take priority
            // Skip if a save is in progress or just completed — the NoteStore change
            // is from our own save, not an external source. Without this, the save's
            // NoteStore update triggers editorState.updateFromText which resets the cursor.
            val currentSaveStatus = currentNoteViewModel.saveStatus.value
            if (currentSaveStatus is SaveStatus.Saving || currentSaveStatus is SaveStatus.Success) return@collect
            val storeNote = NoteStore.getNoteById(displayedNoteId) ?: return@collect
            if (storeNote.content != userContent) {
                onContentLoaded(storeNote.content)
                editorState.updateFromText(storeNote.content)
            }
        }
    }

    // Re-execute directives when notes cache is refreshed
    LaunchedEffect(Unit) {
        currentNoteViewModel.notesCacheRefreshed.collect {
            if (userContent.isNotEmpty()) {
                currentNoteViewModel.bumpDirectiveCacheGeneration()
            }
        }
    }

    // Update content when loaded from ViewModel
    LaunchedEffect(loadStatus) {
        if (loadStatus is LoadStatus.Success) {
            // Guard: only apply content if it's for the currently displayed note.
            // Stale results from a previous loadContent can arrive after a tab switch.
            if (loadStatus.noteId != displayedNoteId) return@LaunchedEffect
            val loadedContent = loadStatus.content
            if (loadedContent != userContent) {
                onContentLoaded(loadedContent)
                // CRITICAL: Update editorState BEFORE setBaseline() below!
                // Without this line, editorState is empty when baseline is captured,
                // which means undo can restore to empty state and LOSE USER DATA.
                if (loadStatus.lineNoteIds.isNotEmpty()) {
                    val noteLines = loadedContent.split("\n").zip(loadStatus.lineNoteIds)
                    editorState.initFromNoteLines(noteLines)
                } else {
                    editorState.updateFromText(loadedContent)
                }
            }

            // Always set up undo state (needed even for cached content on initial load)
            val noteIdForRestore = currentNoteViewModel.getCurrentNoteId()
            val restored = UndoStatePersistence.restoreState(context, noteIdForRestore, controller.undoManager)
            if (!restored) {
                controller.resetUndoHistory()
            }
            if (!controller.undoManager.hasBaseline) {
                controller.undoManager.setBaseline(editorState)
            }
            controller.undoManager.beginEditingLine(editorState, editorState.focusedLineIndex)
            editorState.requestFocusUpdate()
        }
    }

    // Update EditorState noteIds when save assigns new IDs to lines
    LaunchedEffect(Unit) {
        currentNoteViewModel.newlyAssignedNoteIds.collect { newIds ->
            for ((index, newId) in newIds) {
                editorState.lines.getOrNull(index)?.let { line ->
                    if (line.noteIds.isEmpty()) {
                        line.noteIds = listOf(newId)
                    }
                }
            }
        }
    }

    // Auto-retry on transient load failures
    var loadRetryCount by remember(displayedNoteId) { mutableIntStateOf(0) }
    LaunchedEffect(loadStatus, loadRetryCount) {
        if (loadStatus is LoadStatus.Error && loadRetryCount < 2) {
            delay(1500)
            loadRetryCount++
            currentNoteViewModel.loadContent(displayedNoteId, recentTabsViewModel)
        }
    }

    // Load tabs on initial composition, with retry on failure
    LaunchedEffect(Unit) {
        recentTabsViewModel.loadTabs()
    }
    val tabsError2 = tabsError
    var tabsRetryCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(tabsError2, tabsRetryCount) {
        if (tabsError2 != null && tabsRetryCount < 2) {
            delay(1500)
            tabsRetryCount++
            recentTabsViewModel.clearError()
            recentTabsViewModel.loadTabs()
        }
    }
}

/**
 * Handles content sync: tab updates, save status, content modification signals, alarm creation.
 */
@Composable
private fun ContentSyncEffects(
    currentNoteId: String?,
    loadStatus: LoadStatus?,
    saveStatus: SaveStatus?,
    contentModified: Boolean,
    alarmCreated: AlarmCreatedEvent?,
    userContent: String,
    isNoteDeleted: Boolean,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    recentTabsViewModel: RecentTabsViewModel,
    onContentChanged: (String) -> Unit,
    onSavedChanged: (Boolean) -> Unit,
) {
    // Update tab when note is loaded
    LaunchedEffect(loadStatus, currentNoteId) {
        if (loadStatus is LoadStatus.Success && currentNoteId != null) {
            recentTabsViewModel.onNoteOpened(currentNoteId, loadStatus.content)
        }
    }

    // React to content modification signal (e.g. from Agent)
    LaunchedEffect(contentModified) {
        if (contentModified) {
            onSavedChanged(false)
            controller.resetUndoHistory()
            currentNoteId?.let { recentTabsViewModel.invalidateCache(it) }
        }
    }

    // Handle save status changes
    LaunchedEffect(saveStatus) {
        if (saveStatus is SaveStatus.Success) {
            onSavedChanged(true)
            currentNoteViewModel.markAsSaved()
            // Guard: during note switches, userContent is reset to "" before currentNoteId
            // updates via LiveData. Skip tab/cache updates to avoid writing "New Note" for
            // the old note using the new note's empty content.
            if (userContent.isNotEmpty()) {
                currentNoteId?.let { noteId ->
                    recentTabsViewModel.updateTabDisplayText(noteId, userContent)
                }
            }
        }
    }

    // Note: Tab removal on delete is handled by the onDeleteNote callback in NoteStatusBar,
    // which closes the tab and navigates to the next one in a single step.

    // Handle alarm creation - insert alarm directive, record for undo, and save
    LaunchedEffect(alarmCreated) {
        alarmCreated?.let { event ->
            val directive = if (event.recurringAlarmId != null) {
                AlarmSymbolUtils.recurringAlarmDirective(event.recurringAlarmId)
            } else {
                AlarmSymbolUtils.alarmDirective(event.alarmId)
            }
            controller.insertAtEndOfCurrentLine(directive)
            onContentChanged(editorState.text)
            event.alarmSnapshot?.let { snapshot ->
                controller.recordAlarmCreation(snapshot)
            }
            currentNoteViewModel.saveContent(editorState.text, editorState.lines.map { it.noteIds })
            // Immediately render the new alarm icon (without waiting for snapshot reload)
            currentNoteViewModel.bumpDirectiveCacheGeneration()
            currentNoteViewModel.clearAlarmCreatedEvent()
        }
    }
}

/**
 * Handles directive mutations that affect the current note's editor content.
 */
@Composable
private fun DirectiveMutationEffect(
    currentNoteId: String?,
    userContent: String,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    onContentChanged: (String, TextFieldValue) -> Unit,
    onMarkUnsaved: () -> Unit,
) {
    DisposableEffect(currentNoteViewModel, controller) {
        currentNoteViewModel.onEditorContentMutated = { noteId, newContent, mutationType, alreadyPersisted, appendedText ->
            if (noteId == currentNoteId) {
                val updatedContent = when (mutationType) {
                    MutationType.CONTENT_CHANGED -> {
                        val currentLines = userContent.lines()
                        if (currentLines.size > 1) {
                            (listOf(newContent) + currentLines.drop(1)).joinToString("\n")
                        } else {
                            newContent
                        }
                    }
                    MutationType.CONTENT_APPENDED -> {
                        if (appendedText != null) userContent + "\n" + appendedText else null
                    }
                    MutationType.PATH_CHANGED -> null
                }

                updatedContent?.let {
                    editorState.updateFromText(it)
                    onContentChanged(it, TextFieldValue(it, TextRange(it.length)))
                }
                if (!alreadyPersisted) onMarkUnsaved()
                controller.resetUndoHistory()
            }
        }
        onDispose {
            currentNoteViewModel.onEditorContentMutated = null
        }
    }
}

/**
 * Status bar with undo/redo handling extracted from the main composable.
 */
@Composable
private fun NoteStatusBar(
    isSaved: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    isNoteDeleted: Boolean,
    userContent: String,
    editorState: EditorState,
    controller: EditorController,
    currentNoteViewModel: CurrentNoteViewModel,
    onContentChanged: (String) -> Unit,
    onMarkUnsaved: () -> Unit,
    showCompleted: Boolean,
    onShowCompletedToggle: () -> Unit,
    onDeleteNote: () -> Unit,
) {
    StatusBar(
        isSaved = isSaved,
        onSaveClick = {
            controller.sortCompletedToBottom()
            controller.commitUndoState(continueEditing = true)
            currentNoteViewModel.saveContent(editorState.text, editorState.lines.map { it.noteIds })
        },
        canUndo = canUndo,
        canRedo = canRedo,
        onUndoClick = {
            val expandedHashes = currentNoteViewModel.getExpandedDirectiveHashes()
            controller.commitUndoState()
            val snapshot = controller.undo()
            if (snapshot != null) {
                onContentChanged(editorState.text)
                onMarkUnsaved()
                currentNoteViewModel.restoreExpandedDirectiveHashes(expandedHashes)
                snapshot.createdAlarm?.let { alarm ->
                    currentNoteViewModel.deleteAlarmPermanently(alarm.id)
                }
            }
        },
        onRedoClick = {
            val expandedHashes = currentNoteViewModel.getExpandedDirectiveHashes()
            controller.commitUndoState()
            val snapshot = controller.redo()
            if (snapshot != null) {
                onContentChanged(editorState.text)
                onMarkUnsaved()
                currentNoteViewModel.restoreExpandedDirectiveHashes(expandedHashes)
                snapshot.createdAlarm?.let { alarm ->
                    currentNoteViewModel.recreateAlarm(
                        alarmSnapshot = alarm,
                        onAlarmCreated = { newId ->
                            controller.updateLastUndoAlarmId(newId)
                            // Update alarm directive in text with new ID
                            val oldDirective = AlarmSymbolUtils.alarmDirective(alarm.id)
                            val newDirective = AlarmSymbolUtils.alarmDirective(newId)
                            val updatedText = editorState.text.replace(oldDirective, newDirective)
                            if (updatedText != editorState.text) {
                                editorState.updateFromText(updatedText)
                                onContentChanged(editorState.text)
                                currentNoteViewModel.saveContent(editorState.text, editorState.lines.map { it.noteIds })
                            }
                        },
                        onFailure = { errorMessage ->
                            val rollbackSnapshot = controller.undo()
                            val rollbackSucceeded = rollbackSnapshot != null
                            if (rollbackSucceeded) onContentChanged(editorState.text)
                            currentNoteViewModel.showRedoRollbackWarning(rollbackSucceeded, errorMessage)
                        }
                    )
                }
            }
        },
        isDeleted = isNoteDeleted,
        onDeleteClick = onDeleteNote,
        onUndeleteClick = {
            currentNoteViewModel.undeleteCurrentNote(onSuccess = {})
        },
        showCompleted = showCompleted,
        onShowCompletedToggle = onShowCompletedToggle
    )
}

@Preview(showBackground = true)
@Composable
fun CurrentNoteScreenPreview() {
    CurrentNoteScreen()
}
