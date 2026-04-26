package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore

/**
 * Holds the editor surface and the content state derived from it: the parsed
 * text, the [TextFieldValue] mirroring it for IME/cursor management, and the
 * dirty/deleted/show-completed flags. Exposed mutators ([updateContent],
 * [markUnsaved], [setUserContent]) keep the dirty signal in sync with the VM.
 */
@Stable
class EditorContentState internal constructor(
    val editorState: EditorState,
    val controller: EditorController,
    val mainContentFocusRequester: FocusRequester,
    private val userContentState: MutableState<String>,
    private val textFieldValueState: MutableState<TextFieldValue>,
    private val isSavedState: MutableState<Boolean>,
    private val isNoteDeletedState: MutableState<Boolean>,
    private val showCompletedState: MutableState<Boolean>,
    private val isMainContentFocusedState: MutableState<Boolean>,
    private val onMarkDirty: () -> Unit,
) {
    var userContent: String by userContentState
    var textFieldValue: TextFieldValue by textFieldValueState
    var isSaved: Boolean by isSavedState
    var isNoteDeleted: Boolean by isNoteDeletedState
    var showCompleted: Boolean by showCompletedState
    var isMainContentFocused: Boolean by isMainContentFocusedState

    fun updateContent(newContent: String) {
        userContent = newContent
        onMarkDirty()
    }

    fun markUnsaved() {
        if (isSaved) isSaved = false
        onMarkDirty()
    }

    /** Update both [userContent] and [textFieldValue] in one call. Used by load paths. */
    fun applyLoadedContent(loadedContent: String) {
        userContent = loadedContent
        textFieldValue = TextFieldValue(loadedContent, TextRange(loadedContent.length))
    }
}

/**
 * Builds an [EditorContentState] keyed on [displayedNoteId]: when the displayed
 * note changes, the editor and all derived content state reset to the new note's
 * initial content.
 *
 * Initial values come from the in-memory tab cache (for unsaved edits) or
 * NoteStore (for clean notes). The [EditorState] is initialized via
 * `initFromNoteLines` so per-line noteIds and the parent noteId are populated on
 * first composition — never via the lossy `updateFromText` path.
 */
@Composable
fun rememberEditorAndContent(
    displayedNoteId: String?,
    isNoteDeletedFromVm: Boolean,
    showCompletedFromVm: Boolean,
    onMarkDirty: () -> Unit,
    getCachedNoteContent: (String) -> CachedNoteContent?,
): EditorContentState {
    val cachedContent = remember(displayedNoteId) {
        displayedNoteId?.let(getCachedNoteContent)?.takeIf { it.isDirty }
    }
    val storeContent = remember(displayedNoteId) {
        displayedNoteId?.let { NoteStore.getNoteById(it) }
    }
    val initialNoteLines: List<NoteLine> = remember(displayedNoteId) {
        cachedContent?.noteLines
            ?: displayedNoteId?.let { NoteStore.getNoteLinesById(it) }
            ?: emptyList()
    }
    val initialContent = initialNoteLines.joinToString("\n") { it.content }
    val initialIsDeleted = cachedContent?.isDeleted ?: (storeContent?.state == "deleted")
    val initialShowCompleted = storeContent?.showCompleted ?: true

    val isNoteDeletedState = remember(displayedNoteId) { mutableStateOf(initialIsDeleted) }
    LaunchedEffect(isNoteDeletedFromVm) { isNoteDeletedState.value = isNoteDeletedFromVm }

    val showCompletedState = remember(displayedNoteId) { mutableStateOf(initialShowCompleted) }
    LaunchedEffect(showCompletedFromVm) { showCompletedState.value = showCompletedFromVm }

    val userContentState = remember(displayedNoteId) { mutableStateOf(initialContent) }
    val textFieldValueState = remember(displayedNoteId) {
        mutableStateOf(TextFieldValue(initialContent, TextRange(initialContent.length)))
    }
    val isSavedState = remember(displayedNoteId) { mutableStateOf(true) }
    val isMainContentFocusedState = remember { mutableStateOf(false) }
    val mainContentFocusRequester = remember { FocusRequester() }

    val editorState = remember(displayedNoteId) {
        EditorState().apply {
            if (initialNoteLines.isNotEmpty()) {
                initFromNoteLines(initialNoteLines, preserveCursor = false)
            }
        }
    }
    val controller = rememberEditorController(editorState)

    return remember(editorState, controller) {
        EditorContentState(
            editorState = editorState,
            controller = controller,
            mainContentFocusRequester = mainContentFocusRequester,
            userContentState = userContentState,
            textFieldValueState = textFieldValueState,
            isSavedState = isSavedState,
            isNoteDeletedState = isNoteDeletedState,
            showCompletedState = showCompletedState,
            isMainContentFocusedState = isMainContentFocusedState,
            onMarkDirty = onMarkDirty,
        )
    }
}
