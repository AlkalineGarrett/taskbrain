package org.alkaline.taskbrain.ui.currentnote

import android.util.Log
import androidx.compose.foundation.background
import org.alkaline.taskbrain.ui.currentnote.rendering.ButtonCallbacks
import org.alkaline.taskbrain.ui.currentnote.rendering.DirectiveCallbacks
import org.alkaline.taskbrain.ui.currentnote.util.AlarmSymbolUtils
import org.alkaline.taskbrain.ui.currentnote.util.SymbolTapInfo
import org.alkaline.taskbrain.ui.currentnote.util.TappableSymbol
import org.alkaline.taskbrain.ui.currentnote.util.ClipboardHtmlConverter
import org.alkaline.taskbrain.ui.currentnote.undo.UndoStatePersistence
import org.alkaline.taskbrain.ui.currentnote.util.TextLineUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.alkaline.taskbrain.ui.components.ErrorDialog
import org.alkaline.taskbrain.ui.currentnote.components.AgentCommandSection
import org.alkaline.taskbrain.ui.currentnote.components.AlarmConfigDialog
import org.alkaline.taskbrain.ui.currentnote.components.CommandBar
import org.alkaline.taskbrain.ui.currentnote.components.NoteTextField
import org.alkaline.taskbrain.ui.currentnote.components.StatusBar
import org.alkaline.taskbrain.dsl.runtime.MutationType
import org.alkaline.taskbrain.dsl.runtime.values.ButtonVal
import org.alkaline.taskbrain.dsl.ui.ButtonExecutionState

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
    val saveStatus by currentNoteViewModel.saveStatus.observeAsState()
    val loadStatus by currentNoteViewModel.loadStatus.observeAsState()
    val contentModified by currentNoteViewModel.contentModified.observeAsState(false)
    val isAgentProcessing by currentNoteViewModel.isAgentProcessing.observeAsState(false)
    val alarmCreated by currentNoteViewModel.alarmCreated.observeAsState()
    val alarmError by currentNoteViewModel.alarmError.observeAsState()
    val alarmPermissionWarning by currentNoteViewModel.alarmPermissionWarning.observeAsState(false)
    val notificationPermissionWarning by currentNoteViewModel.notificationPermissionWarning.observeAsState(false)
    val schedulingWarning by currentNoteViewModel.schedulingWarning.observeAsState()
    val isAlarmOperationPending by currentNoteViewModel.isAlarmOperationPending.observeAsState(false)
    val redoRollbackWarning by currentNoteViewModel.redoRollbackWarning.observeAsState()
    val isNoteDeletedFromVm by currentNoteViewModel.isNoteDeleted.observeAsState(false)
    // Observe UUID-keyed results and convert to position-keyed for UI display
    val directiveResultsRaw by currentNoteViewModel.directiveResults.observeAsState(emptyMap())
    val directiveResults = remember(directiveResultsRaw) {
        currentNoteViewModel.getResultsByPosition()
    }
    // Button execution states for directive buttons
    val buttonExecutionStates by currentNoteViewModel.buttonExecutionStates.observeAsState(emptyMap())
    // Button error messages - maps directive key to error message
    val buttonErrors by currentNoteViewModel.buttonErrors.observeAsState(emptyMap())
    // Log when directiveResults changes to trace timing
    LaunchedEffect(directiveResultsRaw) {
        val firstContent = directiveResultsRaw.values.firstOrNull()?.toValue()?.toDisplayString()?.take(60)?.replace("\n", "\\n")
        Log.d("InlineEditCache", ">>> directiveResultsRaw CHANGED: ${directiveResultsRaw.size} entries, firstContent='$firstContent...'")
    }
    val recentTabs by recentTabsViewModel.tabs.observeAsState(emptyList())
    val tabsError by recentTabsViewModel.error.observeAsState()

    // Internal note ID state - allows tab switching without navigation
    // Initialize from route parameter, but can be updated internally for tab switches
    var displayedNoteId by remember { mutableStateOf(noteId) }

    // Sync with route parameter when it changes (external navigation)
    LaunchedEffect(noteId) {
        if (noteId != displayedNoteId) {
            displayedNoteId = noteId
        }
    }

    // Get cached content for immediate initialization (prevents flashing on tab switch)
    val cachedContent = remember(displayedNoteId) {
        displayedNoteId?.let { recentTabsViewModel.getCachedContent(it) }
    }
    val initialContent = cachedContent?.noteLines?.joinToString("\n") { it.content } ?: ""
    val initialIsDeleted = cachedContent?.isDeleted ?: false

    // Track deleted state keyed on displayedNoteId, initialized from cache to prevent background flash
    // ViewModel will update this via effect when it loads
    var isNoteDeleted by remember(displayedNoteId) { mutableStateOf(initialIsDeleted) }

    // Sync with ViewModel when it updates (ViewModel is authoritative)
    LaunchedEffect(isNoteDeletedFromVm) {
        isNoteDeleted = isNoteDeletedFromVm
    }

    // Key states on displayedNoteId to reset when switching tabs, but initialize with cache
    var userContent by remember(displayedNoteId) { mutableStateOf(initialContent) }
    var textFieldValue by remember(displayedNoteId) {
        mutableStateOf(TextFieldValue(initialContent, TextRange(initialContent.length)))
    }
    var isSaved by remember(displayedNoteId) { mutableStateOf(true) }
    var agentCommand by remember { mutableStateOf("") }
    var isAgentSectionExpanded by remember { mutableStateOf(false) }

    val mainContentFocusRequester = remember { FocusRequester() }
    var isMainContentFocused by remember { mutableStateOf(false) }

    // Key editor state on displayedNoteId and initialize with cached content to prevent flashing
    val editorState = remember(displayedNoteId) {
        EditorState().apply {
            if (initialContent.isNotEmpty()) {
                updateFromText(initialContent)
            }
        }
    }
    val controller = rememberEditorController(editorState)

    // Inline editing state for view directives - allows editing viewed notes with full editor support
    val inlineEditState = rememberInlineEditState()

    // Wire up directive execution callbacks for inline editing
    LaunchedEffect(inlineEditState) {
        inlineEditState.onExecuteDirectives = { content, onResults ->
            currentNoteViewModel.executeDirectivesForContent(content, onResults)
        }
        inlineEditState.onExecuteSingleDirective = { sourceText, onResult ->
            currentNoteViewModel.executeSingleDirective(sourceText, onResult)
        }
        inlineEditState.onDirectiveEditConfirm = { lineIndex, directiveKey, oldSourceText, newSourceText ->
            // Save immediately when directive is edited (don't wait for focus loss)
            // But DON'T end the session - user should stay in edit mode after confirming a directive
            inlineEditState.activeSession?.let { session ->
                val noteId = session.noteId
                val newContent = session.currentContent
                currentNoteViewModel.saveInlineNoteContent(
                    noteId = noteId,
                    newContent = newContent,
                    onSuccess = {
                        // Invalidate tab cache so switching tabs shows fresh content
                        recentTabsViewModel.invalidateCache(noteId)
                        // Re-execute directives for the inline editor to show updated results
                        currentNoteViewModel.executeDirectivesForContent(newContent) { results ->
                            session.updateDirectiveResults(results)
                            // Refresh host note's directives so the view shows updated content
                            // But DON'T end the session - user stays in edit mode
                            currentNoteViewModel.forceRefreshAllDirectives(userContent) {
                                // Session stays active - user can continue editing or tap out
                            }
                        }
                    }
                )
            }
        }
    }

    // Route commands to the inline editor if one is active, otherwise to host note's controller
    val activeController = inlineEditState.activeController ?: controller

    // Context and coroutine scope for undo state persistence
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Track current note ID for undo state persistence (observed from ViewModel)
    val currentNoteId by currentNoteViewModel.currentNoteIdLiveData.observeAsState()

    // Sync displayedNoteId with ViewModel's currentNoteId on initial load
    // This handles the case where displayedNoteId is null and ViewModel loads a default note
    LaunchedEffect(currentNoteId) {
        if (displayedNoteId == null && currentNoteId != null) {
            displayedNoteId = currentNoteId
        }
    }

    // Track undo/redo button states - need to observe state versions to trigger recomposition
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
    var alarmDialogSymbolIndex by remember { mutableStateOf(0) }
    val lineAlarms by currentNoteViewModel.lineAlarms.observeAsState(emptyList())
    // Observe noteAlarms to trigger recomposition when alarm overlay states change
    val noteAlarmsForOverlay by currentNoteViewModel.noteAlarms.observeAsState(emptyMap())
    val alarmDialogExistingAlarm = lineAlarms
        .sortedBy { it.createdAt?.toDate()?.time ?: 0L }
        .getOrNull(alarmDialogSymbolIndex)
    val alarmDialogRecurrenceConfig by currentNoteViewModel.recurrenceConfig.observeAsState()

    // Auto-save when navigating away or app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentUserContent by rememberUpdatedState(userContent)
    val currentIsSaved by rememberUpdatedState(isSaved)
    val currentIsNoteDeleted by rememberUpdatedState(isNoteDeleted)

    // Track current note ID for persistence (using rememberUpdatedState for lifecycle access)
    val currentNoteIdForPersistence by rememberUpdatedState(currentNoteId)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Commit undo state before navigating away
                controller.commitUndoState()
                // Persist undo state for this note (blocking to ensure it completes)
                currentNoteIdForPersistence?.let { noteId ->
                    UndoStatePersistence.saveStateBlocking(context, noteId, controller.undoManager)
                }
                if (!currentIsSaved && currentUserContent.isNotEmpty()) {
                    currentNoteViewModel.saveContent(currentUserContent)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Also save when composable is removed from composition (e.g., bottom nav switch)
            // ON_STOP only fires when app goes to background, not for in-app navigation
            controller.commitUndoState()
            currentNoteIdForPersistence?.let { noteId ->
                UndoStatePersistence.saveStateBlocking(context, noteId, controller.undoManager)
            }
            if (!currentIsSaved && currentUserContent.isNotEmpty()) {
                // Update tracked lines and cache BEFORE save, so when we come back
                // the cache has the latest content (save is async and may complete after
                // the screen is already showing again)
                currentNoteViewModel.updateTrackedLines(currentUserContent)
                val trackedLines = currentNoteViewModel.getTrackedLines()
                recentTabsViewModel.cacheNoteContent(
                    currentNoteIdForPersistence!!,
                    trackedLines,
                    currentIsNoteDeleted
                )
                recentTabsViewModel.updateTabDisplayText(currentNoteIdForPersistence!!, currentUserContent)
                currentNoteViewModel.saveContent(currentUserContent)
            }
        }
    }

    // Handle data loading when displayed note changes (either from navigation or tab click)
    LaunchedEffect(displayedNoteId) {
        currentNoteViewModel.loadContent(displayedNoteId, recentTabsViewModel)
    }

    // Re-execute directives when notes cache is refreshed (e.g., after auto-save on tab switch)
    // This ensures view directives show fresh content after the async save completes
    LaunchedEffect(Unit) {
        currentNoteViewModel.notesCacheRefreshed.collect {
            if (userContent.isNotEmpty()) {
                currentNoteViewModel.executeDirectivesLive(userContent)
            }
        }
    }

    // Update content when loaded from VM
    // Skip redundant updates if we already initialized with identical cached content
    LaunchedEffect(loadStatus) {
        if (loadStatus is LoadStatus.Success) {
            val loadedContent = (loadStatus as LoadStatus.Success).content
            val needsContentUpdate = loadedContent != userContent

            if (needsContentUpdate) {
                userContent = loadedContent
                textFieldValue = TextFieldValue(loadedContent, TextRange(loadedContent.length))
                // CRITICAL: Update editorState BEFORE setBaseline() below!
                // Without this line, editorState is empty when baseline is captured,
                // which means undo can restore to empty state and LOSE USER DATA.
                // See UndoManagerTest: "CRITICAL - baseline must contain actual content"
                editorState.updateFromText(loadedContent)
            }

            // Always set up undo state (needed even for cached content on initial load)
            val noteIdForRestore = currentNoteViewModel.getCurrentNoteId()
            val restored = UndoStatePersistence.restoreState(context, noteIdForRestore, controller.undoManager)
            if (!restored) {
                controller.resetUndoHistory()
            }
            // Always ensure baseline is set - this is the "floor" for undo.
            // On fresh load (no restored state), this captures the loaded content.
            // On restore, this ensures baseline exists even if restored from old format.
            if (!controller.undoManager.hasBaseline) {
                controller.undoManager.setBaseline(editorState)
            }
            controller.undoManager.beginEditingLine(editorState, editorState.focusedLineIndex)
            // Trigger focus update now that content is loaded.
            // HangingIndentEditor waits for stateVersion > 0 before requesting focus
            // to avoid cursor jumping from position 0 to end of line on initial load.
            editorState.requestFocusUpdate()
        }
    }

    // Load tabs on initial composition
    LaunchedEffect(Unit) {
        recentTabsViewModel.loadTabs()
    }

    // Set up callback for directive mutations that affect the current note's content
    // When a directive like [.name: "new title"] runs, update the editor to show the change
    DisposableEffect(currentNoteViewModel, controller) {
        currentNoteViewModel.onEditorContentMutated = { noteId, newContent, mutationType, alreadyPersisted, appendedText ->
            if (noteId == currentNoteId) {
                when (mutationType) {
                    MutationType.CONTENT_CHANGED -> {
                        // For CONTENT_CHANGED (e.g., .name assignment), only update the first line
                        // because the editor shows combined content (parent + children) but
                        // Firestore stores children separately in containedNotes.
                        // The newContent here is just the note's content field (first line).
                        val currentLines = userContent.lines()
                        val updatedContent = if (currentLines.size > 1) {
                            // Preserve all lines after the first
                            (listOf(newContent) + currentLines.drop(1)).joinToString("\n")
                        } else {
                            newContent
                        }
                        editorState.updateFromText(updatedContent)
                        userContent = updatedContent
                        textFieldValue = TextFieldValue(updatedContent, TextRange(updatedContent.length))
                    }
                    MutationType.CONTENT_APPENDED -> {
                        // For CONTENT_APPENDED (e.g., .append()), we have the appended text
                        // Append it to the current editor content with a leading newline
                        if (appendedText != null) {
                            val updatedContent = userContent + "\n" + appendedText
                            editorState.updateFromText(updatedContent)
                            userContent = updatedContent
                            textFieldValue = TextFieldValue(updatedContent, TextRange(updatedContent.length))
                        }
                    }
                    MutationType.PATH_CHANGED -> {
                        // Path changes don't affect editor content - shouldn't reach here
                    }
                }
                // Only mark as unsaved if the mutation is NOT already persisted (e.g., live execution)
                // Mutations during save are already persisted to Firestore
                if (!alreadyPersisted) {
                    isSaved = false
                }
                // Clear undo history since the content was externally modified
                controller.resetUndoHistory()
            }
        }
        onDispose {
            currentNoteViewModel.onEditorContentMutated = null
        }
    }

    // Update tab when note is loaded
    LaunchedEffect(loadStatus, currentNoteId) {
        val noteId = currentNoteId
        if (loadStatus is LoadStatus.Success && noteId != null) {
            val content = (loadStatus as LoadStatus.Success).content
            recentTabsViewModel.onNoteOpened(noteId, content)
        }
    }

    // React to content modification signal (e.g. from Agent)
    // Clear undo history since externally modified content would have stale snapshots
    // Invalidate cache since content changed externally
    LaunchedEffect(contentModified) {
        if (contentModified) {
            isSaved = false
            controller.resetUndoHistory()
            // Invalidate cache - AI modified content is not yet saved
            currentNoteId?.let { noteId ->
                recentTabsViewModel.invalidateCache(noteId)
            }
        }
    }

    // Handle save status changes
    LaunchedEffect(saveStatus) {
        if (saveStatus is SaveStatus.Success) {
            isSaved = true
            currentNoteViewModel.markAsSaved()
            currentNoteId?.let { noteId ->
                // Update tab display text after save
                recentTabsViewModel.updateTabDisplayText(noteId, userContent)
                // Update cache with latest tracked lines (includes new note IDs)
                val trackedLines = currentNoteViewModel.getTrackedLines()
                recentTabsViewModel.cacheNoteContent(noteId, trackedLines, isNoteDeleted)
            }
        }
    }

    // Remove tab when note is deleted
    LaunchedEffect(isNoteDeleted, currentNoteId) {
        val noteId = currentNoteId
        if (isNoteDeleted && noteId != null) {
            recentTabsViewModel.onNoteDeleted(noteId)
        }
    }

    // Handle alarm creation - insert symbol at end of line, record for undo, and save
    LaunchedEffect(alarmCreated) {
        alarmCreated?.let { event ->
            controller.insertAtEndOfCurrentLine(AlarmSymbolUtils.ALARM_SYMBOL)
            userContent = editorState.text
            // Record alarm creation for undo/redo (snapshot is passed from ViewModel)
            event.alarmSnapshot?.let { snapshot ->
                controller.recordAlarmCreation(snapshot)
            }
            // Save the note with the alarm symbol included
            currentNoteViewModel.saveContent(editorState.text)
            currentNoteViewModel.clearAlarmCreatedEvent()
        }
    }

    // Show error dialogs
    if (saveStatus is SaveStatus.Error) {
        ErrorDialog(
            title = "Save Error",
            throwable = (saveStatus as SaveStatus.Error).throwable,
            onDismiss = { currentNoteViewModel.clearSaveError() }
        )
    }

    if (loadStatus is LoadStatus.Error) {
        ErrorDialog(
            title = "Load Error",
            throwable = (loadStatus as LoadStatus.Error).throwable,
            onDismiss = { currentNoteViewModel.clearLoadError() }
        )
    }

    tabsError?.let { error ->
        ErrorDialog(
            title = "Tabs Error",
            throwable = error.cause,
            onDismiss = { recentTabsViewModel.clearError() }
        )
    }

    alarmError?.let { throwable ->
        ErrorDialog(
            title = "Alarm Error",
            throwable = throwable,
            onDismiss = { currentNoteViewModel.clearAlarmError() }
        )
    }

    // Alarm permission warning dialog
    if (alarmPermissionWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { currentNoteViewModel.clearAlarmPermissionWarning() },
            title = { androidx.compose.material3.Text("Exact Alarms Disabled") },
            text = {
                androidx.compose.material3.Text(
                    "Exact alarm permission is not granted. Your alarm may not trigger at the exact time.\n\n" +
                    "To enable: Settings → Apps → TaskBrain → Alarms & reminders → Allow"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { currentNoteViewModel.clearAlarmPermissionWarning() }
                ) {
                    androidx.compose.material3.Text("OK")
                }
            }
        )
    }

    // Notification permission warning dialog
    if (notificationPermissionWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { currentNoteViewModel.clearNotificationPermissionWarning() },
            title = { androidx.compose.material3.Text("Notifications Disabled") },
            text = {
                androidx.compose.material3.Text(
                    "Notification permission is not granted. Your alarms will not show notifications.\n\n" +
                    "To enable: Settings → Apps → TaskBrain → Notifications → Allow"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { currentNoteViewModel.clearNotificationPermissionWarning() }
                ) {
                    androidx.compose.material3.Text("OK")
                }
            }
        )
    }

    // Scheduling warning dialog - shown when alarm couldn't be scheduled
    schedulingWarning?.let { warning ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { currentNoteViewModel.clearSchedulingWarning() },
            title = { androidx.compose.material3.Text("Alarm Scheduling Issue") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.material3.Text(
                        "$warning\n\nThe alarm was saved but may not trigger at the expected time."
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { currentNoteViewModel.clearSchedulingWarning() }
                ) {
                    androidx.compose.material3.Text("OK")
                }
            }
        )
    }

    // Redo rollback warning dialog - shown when alarm recreation fails during redo
    redoRollbackWarning?.let { warning ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { currentNoteViewModel.clearRedoRollbackWarning() },
            title = {
                androidx.compose.material3.Text(
                    if (warning.rollbackSucceeded) "Redo Failed" else "Redo Error"
                )
            },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.material3.Text(
                        if (warning.rollbackSucceeded) {
                            "Could not recreate the alarm: ${warning.errorMessage}\n\n" +
                            "The document has been automatically rolled back to its previous state."
                        } else {
                            "Could not recreate the alarm: ${warning.errorMessage}\n\n" +
                            "Warning: The document may be in an inconsistent state. " +
                            "The alarm symbol may be visible but no alarm exists. " +
                            "Consider saving and reloading the note."
                        }
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { currentNoteViewModel.clearRedoRollbackWarning() }
                ) {
                    androidx.compose.material3.Text("OK")
                }
            }
        )
    }

    // Alarm configuration dialog
    if (showAlarmDialog) {
        // Fetch recurrence config when dialog opens for an existing alarm
        LaunchedEffect(alarmDialogExistingAlarm?.id) {
            currentNoteViewModel.fetchRecurrenceConfig(alarmDialogExistingAlarm)
        }

        AlarmConfigDialog(
            lineContent = alarmDialogLineContent,
            existingAlarm = alarmDialogExistingAlarm,
            existingRecurrenceConfig = alarmDialogRecurrenceConfig,
            onSave = { upcomingTime, notifyTime, urgentTime, alarmTime ->
                val existing = alarmDialogExistingAlarm
                if (existing != null) {
                    // Update existing alarm
                    currentNoteViewModel.updateAlarm(
                        alarm = existing,
                        upcomingTime = upcomingTime,
                        notifyTime = notifyTime,
                        urgentTime = urgentTime,
                        alarmTime = alarmTime
                    )
                } else {
                    // Auto-save before creating alarm to ensure correct note IDs
                    currentNoteViewModel.saveAndCreateAlarm(
                        content = userContent,
                        lineContent = alarmDialogLineContent,
                        lineIndex = alarmDialogLineIndex,
                        upcomingTime = upcomingTime,
                        notifyTime = notifyTime,
                        urgentTime = urgentTime,
                        alarmTime = alarmTime
                    )
                }
            },
            onSaveRecurring = { upcomingTime, notifyTime, urgentTime, alarmTime, recurrenceConfig ->
                val existing = alarmDialogExistingAlarm
                if (existing != null) {
                    // Update existing recurring alarm
                    currentNoteViewModel.updateRecurringAlarm(
                        alarm = existing,
                        upcomingTime = upcomingTime,
                        notifyTime = notifyTime,
                        urgentTime = urgentTime,
                        alarmTime = alarmTime,
                        recurrenceConfig = recurrenceConfig
                    )
                } else {
                    // Create new recurring alarm
                    currentNoteViewModel.saveAndCreateRecurringAlarm(
                        content = userContent,
                        lineContent = alarmDialogLineContent,
                        lineIndex = alarmDialogLineIndex,
                        upcomingTime = upcomingTime,
                        notifyTime = notifyTime,
                        urgentTime = urgentTime,
                        alarmTime = alarmTime,
                        recurrenceConfig = recurrenceConfig
                    )
                }
            },
            onMarkDone = alarmDialogExistingAlarm?.let { alarm ->
                { currentNoteViewModel.markAlarmDone(alarm.id) }
            },
            onCancel = alarmDialogExistingAlarm?.let { alarm ->
                { currentNoteViewModel.cancelAlarm(alarm.id) }
            },
            onDelete = alarmDialogExistingAlarm?.let { alarm ->
                { currentNoteViewModel.deleteAlarmPermanently(alarm.id) }
            },
            onDismiss = {
                showAlarmDialog = false
                alarmDialogLineIndex = null
                currentNoteViewModel.fetchRecurrenceConfig(null) // clear
            }
        )
    }

    // Monitor clipboard and add HTML formatting for bullets/checkboxes
    ClipboardHtmlConverter()

    // Helper to update text field value and track changes
    val updateTextFieldValue: (TextFieldValue) -> Unit = { newValue ->
        val oldText = textFieldValue.text
        textFieldValue = newValue
        if (newValue.text != userContent) {
            userContent = newValue.text
            if (isSaved) isSaved = false
            // Execute directives only when a closing bracket is typed
            val oldBracketCount = oldText.count { it == ']' }
            val newBracketCount = newValue.text.count { it == ']' }
            if (newBracketCount > oldBracketCount) {
                currentNoteViewModel.executeDirectivesLive(newValue.text)
            }
        }
    }

    val deletedNoteBackground = Color(0xFFF0F0F0)
    val deletedNoteTextColor = Color(0xFF666666)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isNoteDeleted) deletedNoteBackground else Color.White)
    ) {
        RecentTabsBar(
            tabs = recentTabs,
            currentNoteId = displayedNoteId ?: "",
            onTabClick = { targetNoteId ->
                // Save current note before switching (if needed)
                if (!isSaved && userContent.isNotEmpty()) {
                    currentNoteViewModel.saveContent(userContent)
                }
                // Switch tabs internally - no navigation, no screen recreation
                displayedNoteId = targetNoteId
            },
            onTabClose = { targetNoteId ->
                val isClosingCurrentTab = targetNoteId == displayedNoteId
                recentTabsViewModel.closeTab(targetNoteId)
                if (isClosingCurrentTab) {
                    // Find the next tab to switch to
                    val currentIndex = recentTabs.indexOfFirst { it.noteId == targetNoteId }
                    val remainingTabs = recentTabs.filter { it.noteId != targetNoteId }
                    if (remainingTabs.isEmpty()) {
                        onNavigateBack()
                    } else {
                        // Switch to next tab, or previous if we closed the last one
                        val nextIndex = minOf(currentIndex, remainingTabs.size - 1)
                        displayedNoteId = remainingTabs[nextIndex].noteId
                    }
                }
            }
        )

        StatusBar(
            isSaved = isSaved,
            onSaveClick = {
                controller.commitUndoState(continueEditing = true)
                currentNoteViewModel.saveContent(userContent)
            },
            // Disable undo/redo during alarm operations to prevent race conditions
            canUndo = canUndo && !isAlarmOperationPending,
            canRedo = canRedo && !isAlarmOperationPending,
            onUndoClick = {
                // Capture expanded directive positions before undo
                val expandedPositions = currentNoteViewModel.getExpandedDirectivePositions(userContent)

                controller.commitUndoState()
                val snapshot = controller.undo()
                if (snapshot != null) {
                    userContent = editorState.text
                    isSaved = false

                    // Re-execute directives on restored content
                    currentNoteViewModel.executeDirectivesLive(userContent)

                    // Restore expanded state for directives that still exist at the same positions
                    currentNoteViewModel.restoreExpandedDirectivesByPosition(userContent, expandedPositions)

                    // If this snapshot created an alarm, delete it permanently
                    snapshot.createdAlarm?.let { alarm ->
                        currentNoteViewModel.deleteAlarmPermanently(alarm.id)
                    }
                }
            },
            onRedoClick = {
                // Capture expanded directive positions before redo
                val expandedPositions = currentNoteViewModel.getExpandedDirectivePositions(userContent)

                controller.commitUndoState()
                val snapshot = controller.redo()
                if (snapshot != null) {
                    userContent = editorState.text
                    isSaved = false

                    // Re-execute directives on restored content
                    currentNoteViewModel.executeDirectivesLive(userContent)

                    // Restore expanded state for directives that still exist at the same positions
                    currentNoteViewModel.restoreExpandedDirectivesByPosition(userContent, expandedPositions)

                    // If the undone snapshot had an alarm, recreate it
                    snapshot.createdAlarm?.let { alarm ->
                        currentNoteViewModel.recreateAlarm(
                            alarmSnapshot = alarm,
                            onAlarmCreated = { newId ->
                                controller.updateLastUndoAlarmId(newId)
                            },
                            onFailure = { errorMessage ->
                                // If alarm recreation fails, undo the redo to remove orphaned alarm symbol
                                val rollbackSnapshot = controller.undo()
                                val rollbackSucceeded = rollbackSnapshot != null
                                if (rollbackSucceeded) {
                                    userContent = editorState.text
                                }
                                // Show warning dialog explaining what happened
                                currentNoteViewModel.showRedoRollbackWarning(rollbackSucceeded, errorMessage)
                            }
                        )
                    }
                }
            },
            isDeleted = isNoteDeleted,
            onDeleteClick = {
                currentNoteViewModel.deleteCurrentNote(onSuccess = onNavigateBack)
            },
            onUndeleteClick = {
                currentNoteViewModel.undeleteCurrentNote(onSuccess = {})
            }
        )

        // Key on displayedNoteId to force full recreation of editor tree when switching tabs.
        // Without this, Compose reuses composables by position, causing stale state in
        // remember blocks (like interactionSource) that breaks touch handling.
        // Provide InlineEditState via CompositionLocal for view directive inline editing
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
                            alarmDialogSymbolIndex = tapInfo.symbolIndexOnLine
                            currentNoteViewModel.fetchAlarmsForLine(tapInfo.lineIndex) {
                                showAlarmDialog = true
                            }
                        }
                    }
                },
                textColor = if (isNoteDeleted) deletedNoteTextColor else Color.Black,
                directiveResults = directiveResults,
                directiveCallbacks = DirectiveCallbacks(
                    onDirectiveTap = { positionKey, sourceText ->
                        // Parse position key (lineIndex:startOffset) to get UUID
                        val parts = positionKey.split(":")
                        val lineIndex = parts.getOrNull(0)?.toIntOrNull()
                        val startOffset = parts.getOrNull(1)?.toIntOrNull()
                        val uuid = if (lineIndex != null && startOffset != null) {
                            currentNoteViewModel.getDirectiveUuid(lineIndex, startOffset)
                        } else null
                        if (uuid != null) {
                            currentNoteViewModel.toggleDirectiveCollapsed(uuid, sourceText)
                        }
                    },
                    onViewDirectiveConfirm = { lineIndex, positionKey, sourceText, newText ->
                        // Find directive position in line content before any modifications
                        val lineContent = editorState.lines.getOrNull(lineIndex)?.content ?: ""
                        val startOffset = lineContent.indexOf(sourceText)

                        // Get UUID before any modifications (position may change after edit)
                        val uuid = if (startOffset >= 0) {
                            currentNoteViewModel.getDirectiveUuid(lineIndex, startOffset)
                        } else null

                        if (sourceText != newText && startOffset >= 0) {
                            val endOffset = startOffset + sourceText.length

                            // Use controller for proper undo handling
                            controller.confirmDirectiveEdit(lineIndex, startOffset, endOffset, newText)

                            // Sync userContent with EditorState
                            userContent = editorState.text
                            isSaved = false

                            // Re-execute directives (this will assign UUIDs to the new content)
                            currentNoteViewModel.executeDirectivesLive(userContent)

                            // Position cursor at end of the new directive text
                            val cursorPos = startOffset + newText.length
                            val prefixLength = editorState.lines.getOrNull(lineIndex)?.prefix?.length ?: 0
                            controller.setCursor(lineIndex, prefixLength + cursorPos)

                            // Confirm the new directive (re-execute and collapse)
                            val newUuid = currentNoteViewModel.getDirectiveUuid(lineIndex, startOffset)
                            if (newUuid != null) {
                                currentNoteViewModel.confirmDirective(newUuid, newText)
                            }
                        } else if (startOffset >= 0 && uuid != null) {
                            // No text change - position cursor at end of directive
                            val cursorPos = startOffset + sourceText.length
                            val prefixLength = editorState.lines.getOrNull(lineIndex)?.prefix?.length ?: 0
                            controller.setCursor(lineIndex, prefixLength + cursorPos)

                            // Re-execute and collapse (important for dynamic directives like [now])
                            currentNoteViewModel.confirmDirective(uuid, sourceText)
                        }
                    },
                    onViewDirectiveCancel = { lineIndex, positionKey, sourceText ->
                        // Parse position key to get UUID
                        val parts = positionKey.split(":")
                        val lineIndexFromKey = parts.getOrNull(0)?.toIntOrNull()
                        val startOffset = parts.getOrNull(1)?.toIntOrNull()
                        val uuid = if (lineIndexFromKey != null && startOffset != null) {
                            currentNoteViewModel.getDirectiveUuid(lineIndexFromKey, startOffset)
                        } else null
                        if (uuid != null) {
                            currentNoteViewModel.toggleDirectiveCollapsed(uuid)
                        }

                        // Position cursor at end of the directive
                        val lineContent = editorState.lines.getOrNull(lineIndex)?.content ?: ""
                        val foundStartOffset = lineContent.indexOf(sourceText)
                        if (foundStartOffset >= 0) {
                            val cursorPos = foundStartOffset + sourceText.length
                            val prefixLength = editorState.lines.getOrNull(lineIndex)?.prefix?.length ?: 0
                            controller.setCursor(lineIndex, prefixLength + cursorPos)
                        }
                    },
                    onViewDirectiveRefresh = { lineIndex, positionKey, sourceText, newText ->
                        // Refresh updates the text and re-executes without closing the editor
                        val lineContent = editorState.lines.getOrNull(lineIndex)?.content ?: ""
                        val startOffset = lineContent.indexOf(sourceText)

                        if (sourceText != newText && startOffset >= 0) {
                            val endOffset = startOffset + sourceText.length

                            // Use controller for proper undo handling
                            controller.confirmDirectiveEdit(lineIndex, startOffset, endOffset, newText)

                            // Sync userContent with EditorState
                            userContent = editorState.text
                            isSaved = false

                            // Re-execute directives (this will assign UUIDs to the new content)
                            currentNoteViewModel.executeDirectivesLive(userContent)

                            // Keep the directive expanded by refreshing with the new UUID
                            val newUuid = currentNoteViewModel.getDirectiveUuid(lineIndex, startOffset)
                            if (newUuid != null) {
                                currentNoteViewModel.refreshDirective(newUuid, newText)
                            }
                        } else {
                            // No text change - just re-execute to refresh dynamic values like [now]
                            // Get current UUID and refresh to keep expanded
                            val parts = positionKey.split(":")
                            val lineIndexFromKey = parts.getOrNull(0)?.toIntOrNull()
                            val startOffsetFromKey = parts.getOrNull(1)?.toIntOrNull()
                            val uuid = if (lineIndexFromKey != null && startOffsetFromKey != null) {
                                currentNoteViewModel.getDirectiveUuid(lineIndexFromKey, startOffsetFromKey)
                            } else null

                            if (uuid != null) {
                                currentNoteViewModel.refreshDirective(uuid, sourceText)
                            } else {
                                currentNoteViewModel.executeDirectivesLive(userContent)
                            }
                        }
                    },
                    onViewNoteTap = { directiveKey, noteId, noteContent ->
                        // Save inline-edited note content
                        android.util.Log.d("CurrentNoteScreen", "onViewNoteTap: saving noteId=$noteId, content preview='${noteContent.take(40)}...'")
                        currentNoteViewModel.saveInlineNoteContent(
                            noteId = noteId,
                            newContent = noteContent,
                            onSuccess = {
                                android.util.Log.d("InlineEditCache", ">>> onViewNoteTap onSuccess CALLBACK ENTERED for noteId=$noteId")
                                // Invalidate the tab cache for this note so switching tabs shows fresh content
                                recentTabsViewModel.invalidateCache(noteId)
                                // Force re-execute ALL directives with fresh data from Firestore
                                // End session in the callback to ensure results are updated first
                                android.util.Log.d("InlineEditCache", ">>> onViewNoteTap: calling forceRefreshAllDirectives NOW")
                                currentNoteViewModel.forceRefreshAllDirectives(userContent) {
                                    android.util.Log.d("InlineEditCache", ">>> forceRefreshAllDirectives COMPLETE, ending sessions NOW")
                                    // End BOTH sessions: ViewModel session AND UI session
                                    currentNoteViewModel.endInlineEditSession()
                                    inlineEditState.endSession()
                                }
                                android.util.Log.d("InlineEditCache", ">>> onViewNoteTap: forceRefreshAllDirectives call RETURNED (async)")
                            }
                        )
                    },
                    onViewEditDirective = { directiveKey, sourceText ->
                        // Open directive editor overlay for the view
                        // Use same logic as onDirectiveTap to toggle expanded state
                        val parts = directiveKey.split(":")
                        val lineIndex = parts.getOrNull(0)?.toIntOrNull()
                        val startOffset = parts.getOrNull(1)?.toIntOrNull()
                        val uuid = if (lineIndex != null && startOffset != null) {
                            currentNoteViewModel.getDirectiveUuid(lineIndex, startOffset)
                        } else null
                        if (uuid != null) {
                            currentNoteViewModel.toggleDirectiveCollapsed(uuid, sourceText)
                        }
                    },
                ),
                buttonCallbacks = ButtonCallbacks(
                    onClick = { directiveKey, buttonVal, sourceText ->
                        currentNoteViewModel.executeButton(directiveKey, buttonVal, sourceText)
                    },
                    executionStates = buttonExecutionStates,
                    errors = buttonErrors,
                ),
                symbolOverlaysProvider = { lineIndex ->
                    currentNoteViewModel.getSymbolOverlays(lineIndex, noteAlarmsForOverlay)
                },
                modifier = Modifier.weight(1f)
            )
            }
        }

        CommandBar(
            onToggleBullet = { activeController.toggleBullet() },
            onToggleCheckbox = { activeController.toggleCheckbox() },
            onIndent = { activeController.indent() },
            onUnindent = { activeController.unindent() },
            onMoveUp = {
                // Mark move in progress to prevent focus loss from exiting inline edit
                // Flag is cleared when focus is regained (in InlineNoteEditor)
                inlineEditState.activeSession?.let { it.isMoveInProgress = true }
                if (activeController.moveUp()) {
                    if (!inlineEditState.isActive) {
                        // Host note - update content
                        userContent = editorState.text
                        isSaved = false
                    }
                    // Inline editing: content tracked by session, isDirty computed automatically
                }
            },
            onMoveDown = {
                // Mark move in progress to prevent focus loss from exiting inline edit
                // Flag is cleared when focus is regained (in InlineNoteEditor)
                inlineEditState.activeSession?.let { it.isMoveInProgress = true }
                if (activeController.moveDown()) {
                    if (!inlineEditState.isActive) {
                        // Host note - update content
                        userContent = editorState.text
                        isSaved = false
                    }
                    // Inline editing: content tracked by session, isDirty computed automatically
                }
            },
            moveUpState = activeController.getMoveUpState(),
            moveDownState = activeController.getMoveDownState(),
            onPaste = { clipText -> activeController.paste(clipText) },
            isPasteEnabled = (isMainContentFocused || inlineEditState.isActive) &&
                !(inlineEditState.activeSession?.editorState?.hasSelection ?: editorState.hasSelection),
            onAddAlarm = {
                // Alarm only applies to host note
                if (!inlineEditState.isActive) {
                    controller.commitUndoState(continueEditing = true)
                    val lineContent = editorState.currentLine?.text ?: ""
                    alarmDialogLineContent = TextLineUtils.trimLineForAlarm(lineContent)
                    alarmDialogLineIndex = editorState.focusedLineIndex
                    alarmDialogSymbolIndex = -1 // No existing symbol — forces new alarm dialog
                    showAlarmDialog = true
                }
            },
            isAlarmEnabled = isMainContentFocused && !editorState.hasSelection && !inlineEditState.isActive
        )

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

@Preview(showBackground = true)
@Composable
fun CurrentNoteScreenPreview() {
    CurrentNoteScreen()
}
