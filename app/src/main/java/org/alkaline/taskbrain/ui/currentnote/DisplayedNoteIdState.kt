package org.alkaline.taskbrain.ui.currentnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Owns [displayedNoteId] — the note currently shown in the editor — and the two
 * effects that keep it in sync with navigation and the ViewModel:
 *
 *  - When the navigation argument [initialNoteId] changes, adopt it (skipping
 *    null transitions to avoid flashing empty content during navigation).
 *  - When the route did not pass a noteId, follow [currentNoteId] from the
 *    ViewModel so a stale-prefs fallback can redirect to a fresh note.
 */
@Composable
fun rememberDisplayedNoteId(
    initialNoteId: String?,
    currentNoteId: String?,
): MutableState<String?> {
    val displayedNoteId = remember { mutableStateOf(initialNoteId) }

    LaunchedEffect(initialNoteId) {
        if (initialNoteId != null && initialNoteId != displayedNoteId.value) {
            displayedNoteId.value = initialNoteId
        }
    }

    LaunchedEffect(currentNoteId) {
        if (initialNoteId == null && currentNoteId != null) {
            displayedNoteId.value = currentNoteId
        }
    }

    return displayedNoteId
}

/**
 * Pushes the previous tab's editor text to NoteStore the moment [displayedNoteId]
 * changes, so the next composition reads the user's latest edits rather than the
 * stale rawNotes that NoteStore would otherwise return.
 *
 * The [SideEffect] writes [currentContent]/[displayedNoteId] into the prev refs
 * AFTER each composition. The displayedNoteId-keyed [remember] runs BEFORE the
 * SideEffect on a tab switch, so the refs still hold the previous values when
 * the push decision is made.
 */
@Composable
fun PushPreviousTabContentOnSwitch(
    displayedNoteId: String?,
    currentContent: String,
    onPush: (noteId: String, content: String) -> Unit,
) {
    val prevContentRef = remember { mutableStateOf("") }
    val prevNoteIdRef = remember { mutableStateOf<String?>(null) }
    SideEffect {
        prevContentRef.value = currentContent
        prevNoteIdRef.value = displayedNoteId
    }
    remember(displayedNoteId) {
        val prevId = prevNoteIdRef.value
        val prevContent = prevContentRef.value
        if (prevId != null && prevId != displayedNoteId && prevContent.isNotEmpty()) {
            onPush(prevId, prevContent)
        }
        Unit
    }
}
