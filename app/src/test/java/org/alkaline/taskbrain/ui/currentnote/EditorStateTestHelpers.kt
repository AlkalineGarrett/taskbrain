package org.alkaline.taskbrain.ui.currentnote

/**
 * Test-only convenience: split [text] on `\n` and seed the editor with the
 * resulting lines, all with empty noteIds.
 *
 * Replaces the deprecated `EditorState.updateFromText`. Use this only when
 * the test doesn't care about noteId reconciliation — for tests that
 * exercise the reconciliation logic, call `reconcileLineNoteIds` directly
 * (see `LineReconciliationTest`) or seed via `initFromNoteLines` with the
 * exact noteIds the test needs.
 */
internal fun EditorState.initFromText(text: String) {
    initFromNoteLines(
        text.split("\n").map { it to emptyList<String>() },
        preserveCursor = false,
    )
}
