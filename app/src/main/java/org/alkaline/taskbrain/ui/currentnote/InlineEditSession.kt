package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.data.NoteLine
import org.alkaline.taskbrain.data.NoteStore
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.directives.DirectiveResult

/**
 * Represents an active inline editing session for a note within a view directive.
 *
 * When a user taps on a note's content within a view, an InlineEditSession is created
 * to manage the editing state separately from the host note's editor.
 *
 * Key responsibilities:
 * - Hold EditorState and EditorController for the viewed note
 * - Track the note ID being edited
 * - Track whether content has been modified (dirty state)
 * - Provide the original content for comparison on save
 * - Hold directive results for rendering directives within the viewed note
 * - Track expanded directive state for inline directive editing
 */
@Stable
class InlineEditSession(
    val noteId: String,
    originalContent: String,
    val editorState: EditorState,
    val controller: EditorController
) {
    /**
     * Whether the content has been modified from the original.
     */
    var originalContent by mutableStateOf(originalContent)
        private set

    val isDirty: Boolean
        get() = editorState.text != originalContent

    /**
     * Mark the current content as saved, resetting the dirty state.
     * Called after a successful save so [isDirty] becomes false.
     */
    fun markSaved() {
        originalContent = editorState.text
    }

    /** Update the baseline content after an external change was applied to the EditorState. */
    fun syncOriginalContent(content: String) {
        originalContent = content
    }

    /**
     * Builds tracked lines (content + noteId) from editor state, ready for
     * saveNoteWithChildren. Mirrors the main editor's save path.
     */
    fun getTrackedLines(): List<NoteLine> {
        val lines = editorState.lines
        // Strip trailing empty lines to match save convention
        var count = lines.size
        while (count > 1 && lines[count - 1].text.isEmpty()) count--

        val contentLines = lines.subList(0, count).map { it.text }
        val lineNoteIds = lines.subList(0, count).map { it.noteIds }
        val tracked = resolveNoteIds(contentLines, lineNoteIds).toMutableList()

        // Ensure first line always maps to the parent noteId
        if (tracked.isNotEmpty() && tracked[0].noteId != noteId) {
            tracked[0] = tracked[0].copy(noteId = noteId)
        }
        return tracked
    }

    /**
     * The current content of the editor.
     */
    val currentContent: String
        get() = editorState.text

    /**
     * Directive results for the viewed note's content.
     * Keyed by "lineId:startOffset".
     * These are used to render directives within the inline editor.
     */
    var directiveResults: Map<String, DirectiveResult> by mutableStateOf(emptyMap())
        internal set

    /**
     * The key of the currently expanded directive (for showing DirectiveEditRow).
     * Format: "lineId:startOffset"
     * Null means no directive is expanded.
     */
    var expandedDirectiveKey: String? by mutableStateOf(null)
        private set

    /**
     * The source text of the currently expanded directive.
     */
    var expandedDirectiveSourceText: String? by mutableStateOf(null)
        private set

    /**
     * Flag indicating we're in the process of collapsing a directive.
     * When true, focus loss should not trigger edit mode exit.
     * This is set before collapsing and cleared when focus is regained.
     */
    var isCollapsingDirective: Boolean by mutableStateOf(false)


    /**
     * Update directive results (called after execution completes).
     */
    fun updateDirectiveResults(results: Map<String, DirectiveResult>) {
        directiveResults = results
    }

    /**
     * Toggle the expanded state of a directive.
     * If already expanded, collapses it. If collapsed, expands it (and collapses any other).
     */
    fun toggleDirectiveExpanded(directiveKey: String, sourceText: String) {
        if (expandedDirectiveKey == directiveKey) {
            // Already expanded - collapse it
            collapseDirective()
        } else {
            // Expand this directive (collapses any other)
            expandedDirectiveKey = directiveKey
            expandedDirectiveSourceText = sourceText
        }
    }

    /**
     * Collapse the currently expanded directive.
     * Sets isCollapsingDirective flag to prevent focus loss from exiting edit mode.
     */
    fun collapseDirective() {
        isCollapsingDirective = true
        expandedDirectiveKey = null
        expandedDirectiveSourceText = null
    }

    /**
     * Clear the collapsing flag. Call this when focus is regained after collapsing.
     */
    fun clearCollapsingFlag() {
        isCollapsingDirective = false
    }

    /**
     * Check if a specific directive is expanded.
     */
    fun isDirectiveExpanded(directiveKey: String): Boolean {
        return expandedDirectiveKey == directiveKey
    }

    /**
     * Update a directive's result after refresh/confirm.
     */
    fun updateDirectiveResult(directiveKey: String, result: DirectiveResult) {
        directiveResults = directiveResults.toMutableMap().apply {
            put(directiveKey, result)
        }
    }
}

/**
 * State holder for managing inline edit sessions.
 *
 * Only one inline edit session can be active at a time. When a new session is started,
 * the previous one should be saved (if dirty) and closed.
 */
@Stable
class InlineEditState {
    /**
     * The currently active inline edit session, or null if none.
     */
    var activeSession: InlineEditSession? by mutableStateOf(null)
        private set

    /**
     * Display-mode sessions for view lines, keyed by noteId.
     * Always populated so the gutter can render per-line boxes regardless of focus state.
     */
    internal val viewSessions = mutableMapOf<String, InlineEditSession>()

    /**
     * Line layouts for view notes, keyed by noteId.
     * Decoupled from the editing session so they survive session transitions.
     * Updated by InlineNoteEditor/ViewNoteDisplayLines via onGloballyPositioned.
     */
    internal val viewLineLayouts = mutableMapOf<String, List<org.alkaline.taskbrain.ui.currentnote.gestures.LineLayoutInfo>>()

    /**
     * Gutter selection states for view notes, keyed by noteId.
     * Decoupled from the editing session so they survive session transitions.
     */
    internal val viewGutterStates = mutableMapOf<String, org.alkaline.taskbrain.ui.currentnote.selection.GutterSelectionState>()

    /**
     * Eagerly create sessions for all notes that don't already have one.
     * Existing sessions (including dirty ones) are preserved.
     */
    fun ensureSessionsForNotes(notes: List<Note>) {
        for (note in notes) {
            if (viewSessions.containsKey(note.id)) continue
            val editorState = EditorState()
            val storeLines = NoteStore.getNoteLinesById(note.id)
            if (storeLines != null) {
                val noteLines = storeLines.map { nl ->
                    nl.content to (nl.noteId?.let { listOf(it) } ?: emptyList())
                }
                editorState.initFromNoteLines(noteLines)
            } else {
                editorState.updateFromText(note.content)
            }
            val session = InlineEditSession(
                noteId = note.id,
                originalContent = note.content,
                editorState = editorState,
                controller = EditorController(editorState)
            )
            viewSessions[note.id] = session
        }
    }

    /**
     * Remove sessions for notes no longer displayed in view directives.
     * Returns any dirty sessions that were removed (caller may want to save them).
     */
    fun removeStaleSessionsExcept(activeNoteIds: Set<String>): List<InlineEditSession> {
        val removed = mutableListOf<InlineEditSession>()
        val iterator = viewSessions.iterator()
        while (iterator.hasNext()) {
            val (noteId, session) = iterator.next()
            if (noteId !in activeNoteIds) {
                if (session.isDirty) removed.add(session)
                iterator.remove()
                // Also clean up associated layout/gutter state
                viewLineLayouts.remove(noteId)
                viewGutterStates.remove(noteId)
            }
        }
        return removed
    }

    /** Get all sessions that have unsaved changes. */
    fun getAllDirtySessions(): List<InlineEditSession> =
        viewSessions.values.filter { it.isDirty }

    /**
     * Register an existing session as the active session (no new EditorState created).
     * Selection clearing is handled by SelectionCoordinator.activate().
     */
    fun activateExistingSession(session: InlineEditSession) {
        activeSession = session
        // Execute directives for the content
        onExecuteDirectives?.invoke(session.currentContent) { results ->
            session.updateDirectiveResults(results)
        }
    }

    /** Clear selections on all view sessions (called when parent gutter is used). */
    /**
     * Whether an inline edit session is currently active.
     */
    val isActive: Boolean
        get() = activeSession != null

    /**
     * Callback to execute directives for content.
     * Set by the screen that provides the ViewModel.
     * Parameters: (content: String, onResults: (Map<String, DirectiveResult>) -> Unit)
     */
    var onExecuteDirectives: ((String, (Map<String, DirectiveResult>) -> Unit) -> Unit)? = null

    /**
     * Callback to execute a single directive and get the result.
     * Used for refresh/confirm actions.
     * Parameters: (sourceText: String, onResult: (DirectiveResult) -> Unit)
     */
    var onExecuteSingleDirective: ((String, (DirectiveResult) -> Unit) -> Unit)? = null

    /**
     * Callback when a directive edit is confirmed.
     * The directive source text may have been modified.
     * Parameters: (lineIndex: Int, directiveKey: String, oldSourceText: String, newSourceText: String)
     */
    var onDirectiveEditConfirm: ((Int, String, String, String) -> Unit)? = null

    /**
     * Start a new inline edit session for a note.
     * Automatically triggers directive execution for the content.
     *
     * @param noteId The ID of the note being edited
     * @param content The note's content to edit
     * @return The created session
     */
    fun startSession(noteId: String, content: String): InlineEditSession {
        val editorState = EditorState()
        editorState.updateFromText(content)
        val controller = EditorController(editorState)

        val session = InlineEditSession(
            noteId = noteId,
            originalContent = content,
            editorState = editorState,
            controller = controller
        )
        activeSession = session

        // Execute directives for the content
        onExecuteDirectives?.invoke(content) { results ->
            session.updateDirectiveResults(results)
        }

        return session
    }

    /**
     * End the current inline edit session.
     *
     * @return The session that was ended (for saving if dirty), or null if no session was active
     */
    fun endSession(): InlineEditSession? {
        val session = activeSession
        activeSession = null
        return session
    }

    /**
     * Check if we're currently editing a specific note.
     */
    fun isEditingNote(noteId: String): Boolean {
        return activeSession?.noteId == noteId
    }

    /**
     * Toggle expanded state for a directive in the active session.
     * Shows/hides the DirectiveEditRow for the directive.
     */
    fun toggleDirectiveExpanded(directiveKey: String, sourceText: String) {
        activeSession?.toggleDirectiveExpanded(directiveKey, sourceText)
    }

    /**
     * Collapse any expanded directive in the active session.
     */
    fun collapseDirective() {
        activeSession?.collapseDirective()
    }

    /**
     * Refresh a directive (re-execute and update result, keeping it expanded).
     */
    fun refreshDirective(directiveKey: String, sourceText: String) {
        onExecuteSingleDirective?.invoke(sourceText) { result ->
            activeSession?.updateDirectiveResult(directiveKey, result)
        }
    }

    /**
     * Confirm a directive edit - update the source text in the editor if changed.
     */
    fun confirmDirective(lineIndex: Int, directiveKey: String, oldSourceText: String, newSourceText: String) {
        // If text changed, update the editor content
        if (oldSourceText != newSourceText) {
            activeSession?.controller?.let { controller ->
                // Replace the old directive text with the new one
                val editorState = activeSession?.editorState ?: return@let
                val line = editorState.lines.getOrNull(lineIndex) ?: return@let
                val content = line.content
                val startOffset = DirectiveFinder.startOffsetFromKey(directiveKey) ?: return@let

                // Find and replace the directive text
                val newContent = content.replaceRange(
                    startOffset,
                    startOffset + oldSourceText.length,
                    newSourceText
                )
                // Update line content with cursor at end of new directive
                val newCursor = startOffset + newSourceText.length
                controller.updateLineContent(lineIndex, newContent, newCursor)
            }

            // Re-execute to get the new result
            onExecuteSingleDirective?.invoke(newSourceText) { result ->
                activeSession?.updateDirectiveResult(directiveKey, result)
            }
        }

        // Collapse the directive
        activeSession?.collapseDirective()

        // Notify callback
        onDirectiveEditConfirm?.invoke(lineIndex, directiveKey, oldSourceText, newSourceText)
    }
}

/**
 * Remember an InlineEditState for managing inline editing within views.
 */
@Composable
fun rememberInlineEditState(): InlineEditState {
    return remember { InlineEditState() }
}

/**
 * CompositionLocal for providing InlineEditState to nested composables.
 * This allows view directive components to access the inline edit state
 * without threading it through all intermediate layers.
 */
val LocalInlineEditState = compositionLocalOf<InlineEditState?> { null }

/**
 * Provides InlineEditState to the composition tree via CompositionLocal.
 */
@Composable
fun ProvideInlineEditState(
    inlineEditState: InlineEditState,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalInlineEditState provides inlineEditState) {
        content()
    }
}
