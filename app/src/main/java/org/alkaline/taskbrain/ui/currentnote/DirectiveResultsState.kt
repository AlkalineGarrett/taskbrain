package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.directives.DirectiveResult
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal
import org.alkaline.taskbrain.ui.currentnote.util.CompletedLineUtils

/**
 * Result of [rememberDirectiveResultsAndSessions]: directive output for the
 * current text, the list of notes referenced by view directives, and the inline
 * edit state that owns sessions for those notes.
 */
class DirectiveResultsState(
    val directiveResults: Map<String, DirectiveResult>,
    val viewNotes: List<Note>,
    val inlineEditState: InlineEditState,
)

/**
 * Computes directive results for the current note text, derives the list of view
 * notes referenced by `[view ...]` directives, ensures an inline edit session
 * exists for each view note, and wires the inline-edit-state callbacks.
 *
 * Re-runs `computeDirectiveResults` whenever [userContent], [displayedNoteId], or
 * [directiveCacheGeneration] changes — the generation bump triggers recomputation
 * after the directive cache fills asynchronously on cold start.
 *
 * Also keeps `controller.hiddenIndices` in sync with the show-completed toggle
 * because the move system reads from it.
 */
@Composable
fun rememberDirectiveResultsAndSessions(
    userContent: String,
    displayedNoteId: String?,
    showCompleted: Boolean,
    directiveCacheGeneration: Int,
    editorState: EditorState,
    controller: EditorController,
    directiveManager: NoteDirectiveManager,
    alarmManager: NoteAlarmManager,
    onInvalidateCache: (String) -> Unit,
): DirectiveResultsState {
    // userContent is the joined-text projection of editorState.lines (which mutates
    // in place — see CLAUDE.md). Every flow that changes a line's noteIds also
    // changes its text (line-tracker re-keys, directive replacements, save-assigned
    // ids → applied via the newlyAssignedNoteIds collector), so userContent is a
    // sufficient cache key. Don't add editorState.lines as a key — its array
    // reference is stable and would never invalidate.
    val lineNoteIds = remember(userContent) {
        editorState.lines.map { it.noteIds.firstOrNull() }
    }
    val directiveResults = remember(userContent, directiveCacheGeneration, displayedNoteId) {
        directiveManager.computeDirectiveResults(userContent, displayedNoteId, lineNoteIds)
    }

    val viewNotes = remember(directiveResults) {
        directiveResults.values
            .mapNotNull { (it.toValue() as? ViewVal)?.notes }
            .flatten()
    }

    LaunchedEffect(viewNotes) {
        val viewNoteContents = viewNotes.map { it.content }
        if (viewNoteContents.isNotEmpty()) {
            alarmManager.loadAlarmStatesForContent(viewNoteContents)
        }
    }

    // controller.hiddenIndices is read by the move system (a non-Composable surface).
    // SideEffect runs after each successful composition and is the right hook for
    // pushing memoized Compose state out to plain mutable fields.
    val hiddenIndices = remember(userContent, showCompleted) {
        CompletedLineUtils.computeHiddenIndices(editorState.lines.map { it.text }, showCompleted)
    }
    SideEffect { controller.hiddenIndices = hiddenIndices }

    val inlineEditState = rememberInlineEditState()

    // Eagerly create edit sessions for all embedded notes so clicking is instant.
    // Also execute directives for each new session so they render immediately.
    remember(viewNotes) {
        val newNoteIds = inlineEditState.ensureSessionsForNotes(viewNotes)
        val activeNoteIds = viewNotes.map { it.id }.toSet()
        inlineEditState.removeStaleSessionsExcept(activeNoteIds)
        for (noteId in newNoteIds) {
            val session = inlineEditState.viewSessions[noteId] ?: continue
            directiveManager.executeDirectivesForContent(session.currentContent) { results ->
                session.updateDirectiveResults(results)
            }
        }
    }

    LaunchedEffect(inlineEditState) {
        inlineEditState.onExecuteDirectives = { content, onResults ->
            directiveManager.executeDirectivesForContent(content, onResults)
        }
        inlineEditState.onExecuteSingleDirective = { sourceText, onResult ->
            directiveManager.executeSingleDirective(sourceText, onResult)
        }
        inlineEditState.onDirectiveEditConfirm = { _, _, _, _ ->
            inlineEditState.activeSession?.let { session ->
                directiveManager.saveInlineEditSession(
                    session = session,
                    onSuccess = {
                        session.markSaved()
                        onInvalidateCache(session.noteId)
                        directiveManager.executeDirectivesForContent(session.currentContent) { results ->
                            session.updateDirectiveResults(results)
                            directiveManager.forceRefreshAllDirectives {}
                        }
                    }
                )
            }
        }
    }

    return DirectiveResultsState(
        directiveResults = directiveResults,
        viewNotes = viewNotes,
        inlineEditState = inlineEditState,
    )
}
