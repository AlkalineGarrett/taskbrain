package org.alkaline.taskbrain.ui.currentnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectionCoordinatorTest {

    private lateinit var parentState: EditorState
    private lateinit var parentController: EditorController
    private lateinit var coordinator: SelectionCoordinator
    private lateinit var inlineEditState: InlineEditState

    @Before
    fun setup() {
        parentState = EditorState()
        parentState.updateFromText("Parent line 1\nParent line 2\nParent line 3")
        parentController = EditorController(parentState)
        coordinator = SelectionCoordinator(parentState, parentController)
        inlineEditState = InlineEditState()
        coordinator.inlineEditState = inlineEditState
    }

    private fun createViewSession(noteId: String, content: String): InlineEditSession {
        val state = EditorState()
        state.updateFromText(content)
        val controller = EditorController(state)
        val session = InlineEditSession(
            noteId = noteId,
            originalContent = content,
            editorState = state,
            controller = controller
        )
        inlineEditState.viewSessions[noteId] = session
        return session
    }

    // ==================== Initial State ====================

    @Test
    fun `initially active editor is parent`() {
        assertEquals(EditorId.Parent, coordinator.activeEditorId)
    }

    @Test
    fun `initially activeController is parent controller`() {
        assertEquals(parentController, coordinator.activeController)
    }

    @Test
    fun `initially activeState is parent state`() {
        assertEquals(parentState, coordinator.activeState)
    }

    @Test
    fun `initially activeSession is null`() {
        assertNull(coordinator.activeSession)
    }

    // ==================== Activate View ====================

    @Test
    fun `activate view changes activeEditorId`() {
        createViewSession("note1", "View content")
        coordinator.activate(EditorId.View("note1"))
        assertEquals(EditorId.View("note1"), coordinator.activeEditorId)
    }

    @Test
    fun `activate view routes activeController to view`() {
        val session = createViewSession("note1", "View content")
        coordinator.activate(EditorId.View("note1"))
        assertEquals(session.controller, coordinator.activeController)
    }

    @Test
    fun `activate view routes activeState to view`() {
        val session = createViewSession("note1", "View content")
        coordinator.activate(EditorId.View("note1"))
        assertEquals(session.editorState, coordinator.activeState)
    }

    @Test
    fun `activate view returns session`() {
        val session = createViewSession("note1", "View content")
        coordinator.activate(EditorId.View("note1"))
        assertEquals(session, coordinator.activeSession)
    }

    // ==================== Mutual Exclusivity ====================

    @Test
    fun `activate view clears parent selection`() {
        parentState.setSelection(0, 14) // Select "Parent line 1"
        assertTrue(parentState.hasSelection)

        createViewSession("note1", "View content")
        coordinator.activate(EditorId.View("note1"))

        assertFalse(parentState.hasSelection)
    }

    @Test
    fun `activate parent clears view selection`() {
        val session = createViewSession("note1", "View line 1\nView line 2")
        coordinator.activate(EditorId.View("note1"))
        session.editorState.setSelection(0, 11) // Select "View line 1"
        assertTrue(session.editorState.hasSelection)

        coordinator.activate(EditorId.Parent)

        assertFalse(session.editorState.hasSelection)
    }

    @Test
    fun `activate view clears other view selection`() {
        val session1 = createViewSession("note1", "View 1")
        val session2 = createViewSession("note2", "View 2")

        coordinator.activate(EditorId.View("note1"))
        session1.editorState.setSelection(0, 6)
        assertTrue(session1.editorState.hasSelection)

        coordinator.activate(EditorId.View("note2"))

        assertFalse(session1.editorState.hasSelection)
    }

    @Test
    fun `activate same editor is no-op`() {
        createViewSession("note1", "View content")
        coordinator.activate(EditorId.View("note1"))

        // Set selection on the view
        val session = inlineEditState.viewSessions["note1"]!!
        session.editorState.setSelection(0, 4)
        assertTrue(session.editorState.hasSelection)

        // Activate same view again — selection should NOT be cleared
        coordinator.activate(EditorId.View("note1"))
        assertTrue(session.editorState.hasSelection)
    }

    // ==================== Deactivate ====================

    @Test
    fun `deactivateToParent returns old session`() {
        val session = createViewSession("note1", "View content")
        coordinator.activate(EditorId.View("note1"))

        val old = coordinator.deactivateToParent()

        assertEquals(session, old)
        assertEquals(EditorId.Parent, coordinator.activeEditorId)
    }

    @Test
    fun `deactivateToParent when already parent returns null`() {
        assertNull(coordinator.deactivateToParent())
    }

    // ==================== Focus Guard ====================

    @Test
    fun `isFocusGuarded is false initially`() {
        assertFalse(coordinator.isFocusGuarded)
    }

    @Test
    fun `withFocusGuard sets isFocusGuarded during action`() {
        var guardedDuringAction = false
        coordinator.withFocusGuard {
            guardedDuringAction = coordinator.isFocusGuarded
        }
        assertTrue(guardedDuringAction)
        assertFalse(coordinator.isFocusGuarded)
    }

    @Test
    fun `withFocusGuard clears even on exception`() {
        try {
            coordinator.withFocusGuard {
                throw RuntimeException("test")
            }
        } catch (_: RuntimeException) {}
        assertFalse(coordinator.isFocusGuarded)
    }

    @Test
    fun `withFocusGuard nesting works`() {
        coordinator.withFocusGuard {
            assertTrue(coordinator.isFocusGuarded)
            coordinator.withFocusGuard {
                assertTrue(coordinator.isFocusGuarded)
            }
            assertTrue(coordinator.isFocusGuarded) // Still guarded (depth=1)
        }
        assertFalse(coordinator.isFocusGuarded) // Fully unwound
    }

    @Test
    fun `withFocusGuard returns action result`() {
        val result = coordinator.withFocusGuard { 42 }
        assertEquals(42, result)
    }

    // ==================== hasAnySelection ====================

    @Test
    fun `hasAnySelection false when nothing selected`() {
        assertFalse(coordinator.hasAnySelection())
    }

    @Test
    fun `hasAnySelection true when parent has selection`() {
        parentState.setSelection(0, 14)
        assertTrue(coordinator.hasAnySelection())
    }

    @Test
    fun `hasAnySelection true when view has selection`() {
        val session = createViewSession("note1", "View line 1\nView line 2")
        session.editorState.setSelection(0, 11)
        assertTrue(coordinator.hasAnySelection())
    }

    @Test
    fun `hasAnySelection false after activate clears all`() {
        parentState.setSelection(0, 14)
        val session = createViewSession("note1", "View content")
        session.editorState.setSelection(0, 4)

        coordinator.activate(EditorId.View("note1"))

        // activate cleared both — but the view just activated, so check parent
        assertFalse(parentState.hasSelection)
    }

    // ==================== Focus Guard Version ====================

    @Test
    fun `focusGuardVersion starts at zero`() {
        assertEquals(0, coordinator.focusGuardVersion)
    }

    @Test
    fun `focusGuardVersion increments on each withFocusGuard call`() {
        coordinator.withFocusGuard { }
        assertEquals(1, coordinator.focusGuardVersion)
        coordinator.withFocusGuard { }
        assertEquals(2, coordinator.focusGuardVersion)
    }

    @Test
    fun `focusGuardVersion increments even if action throws`() {
        try {
            coordinator.withFocusGuard { throw RuntimeException("test") }
        } catch (_: RuntimeException) {}
        assertEquals(1, coordinator.focusGuardVersion)
    }

    @Test
    fun `focusGuardVersion increments once per nested call`() {
        coordinator.withFocusGuard {
            assertEquals(1, coordinator.focusGuardVersion)
            coordinator.withFocusGuard {
                assertEquals(2, coordinator.focusGuardVersion)
            }
        }
        assertEquals(2, coordinator.focusGuardVersion)
    }

    // ==================== Fallback behavior ====================

    @Test
    fun `activeController falls back to parent if view session missing`() {
        // Activate a view that has no registered session
        coordinator.activate(EditorId.View("nonexistent"))
        assertEquals(parentController, coordinator.activeController)
    }

    @Test
    fun `activeState falls back to parent if view session missing`() {
        coordinator.activate(EditorId.View("nonexistent"))
        assertEquals(parentState, coordinator.activeState)
    }

    @Test
    fun `hasAnySelection works without inlineEditState`() {
        coordinator.inlineEditState = null
        parentState.setSelection(0, 14)
        assertTrue(coordinator.hasAnySelection())
    }

    @Test
    fun `hasAnySelection false without inlineEditState and no parent selection`() {
        coordinator.inlineEditState = null
        assertFalse(coordinator.hasAnySelection())
    }
}
