package org.alkaline.taskbrain.ui.currentnote

import org.alkaline.taskbrain.data.NoteIdSentinel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The post-save id-update must replace empty AND sentinel heads with
 * the freshly-allocated real id, and must not overwrite a head that's
 * already real.
 */
class ApplyNewlyAssignedNoteIdsTest {

    @Test
    fun `replaces sentinel head with real id`() {
        val state = EditorState()
        val sentinel = NoteIdSentinel.new(NoteIdSentinel.Origin.TYPED)
        state.initFromNoteLines(
            listOf(
                "root" to listOf("REAL_ROOT"),
                "• child" to listOf(sentinel),
            ),
            preserveCursor = false,
        )
        val controller = EditorController(state)

        controller.applyNewlyAssignedNoteIds(mapOf(1 to "REAL_CHILD_ID"))

        assertEquals(listOf("REAL_CHILD_ID"), state.lines[1].noteIds)
    }

    @Test
    fun `replaces empty noteIds with real id`() {
        val state = EditorState()
        state.initFromNoteLines(
            listOf(
                "root" to listOf("REAL_ROOT"),
                "• child" to emptyList(),
            ),
            preserveCursor = false,
        )
        val controller = EditorController(state)

        controller.applyNewlyAssignedNoteIds(mapOf(1 to "REAL_CHILD_ID"))

        assertEquals(listOf("REAL_CHILD_ID"), state.lines[1].noteIds)
    }

    @Test
    fun `leaves a real id alone`() {
        val state = EditorState()
        state.initFromNoteLines(
            listOf(
                "root" to listOf("REAL_ROOT"),
                "• child" to listOf("ALREADY_REAL"),
            ),
            preserveCursor = false,
        )
        val controller = EditorController(state)

        // Defensive: the save planner shouldn't have a createdIds entry
        // for a real-id line, but if one slips in, don't overwrite.
        controller.applyNewlyAssignedNoteIds(mapOf(1 to "WOULD_BE_NEW"))

        assertEquals(listOf("ALREADY_REAL"), state.lines[1].noteIds)
    }
}
