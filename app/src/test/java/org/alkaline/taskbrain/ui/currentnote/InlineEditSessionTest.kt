package org.alkaline.taskbrain.ui.currentnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineEditSessionTest {

    private fun createSession(noteId: String = "note1", content: String = "Line 1\nLine 2"): InlineEditSession {
        val editorState = EditorState()
        editorState.updateFromText(content)
        val controller = EditorController(editorState)
        return InlineEditSession(
            noteId = noteId,
            originalContent = content,
            editorState = editorState,
            controller = controller
        )
    }

    // --- isDirty ---

    @Test
    fun `new session is not dirty`() {
        val session = createSession()
        assertFalse(session.isDirty)
    }

    @Test
    fun `session is dirty after modifying editor content`() {
        val session = createSession(content = "Original")
        session.controller.updateLineContent(0, "Modified", 8)
        assertTrue(session.isDirty)
    }

    @Test
    fun `session is not dirty when editor content matches original`() {
        val session = createSession(content = "Hello")
        session.controller.updateLineContent(0, "Changed", 7)
        assertTrue(session.isDirty)
        // Restore original
        session.controller.updateLineContent(0, "Hello", 5)
        assertFalse(session.isDirty)
    }

    // --- markSaved ---

    @Test
    fun `markSaved resets dirty state`() {
        val session = createSession(content = "Original")
        session.controller.updateLineContent(0, "Edited", 6)
        assertTrue(session.isDirty)

        session.markSaved()
        assertFalse(session.isDirty)
    }

    @Test
    fun `markSaved updates originalContent to current text`() {
        val session = createSession(content = "Before")
        session.controller.updateLineContent(0, "After", 5)
        session.markSaved()

        assertEquals("After", session.originalContent)
    }

    @Test
    fun `editing after markSaved makes session dirty again`() {
        val session = createSession(content = "V1")
        session.controller.updateLineContent(0, "V2", 2)
        session.markSaved()
        assertFalse(session.isDirty)

        session.controller.updateLineContent(0, "V3", 2)
        assertTrue(session.isDirty)
    }

    // --- currentContent ---

    @Test
    fun `currentContent reflects editor state`() {
        val session = createSession(content = "Line 1\nLine 2")
        assertEquals("Line 1\nLine 2", session.currentContent)
    }

    @Test
    fun `currentContent updates after edit`() {
        val session = createSession(content = "Hello")
        session.controller.updateLineContent(0, "World", 5)
        assertEquals("World", session.currentContent)
    }

    // --- Directive expand/collapse ---

    @Test
    fun `no directive expanded initially`() {
        val session = createSession()
        assertNull(session.expandedDirectiveKey)
        assertNull(session.expandedDirectiveSourceText)
    }

    @Test
    fun `toggleDirectiveExpanded expands directive`() {
        val session = createSession()
        session.toggleDirectiveExpanded("0:5", "[find()]")
        assertEquals("0:5", session.expandedDirectiveKey)
        assertEquals("[find()]", session.expandedDirectiveSourceText)
    }

    @Test
    fun `toggleDirectiveExpanded collapses already expanded directive`() {
        val session = createSession()
        session.toggleDirectiveExpanded("0:5", "[find()]")
        session.toggleDirectiveExpanded("0:5", "[find()]")
        assertNull(session.expandedDirectiveKey)
    }

    @Test
    fun `toggleDirectiveExpanded switches to different directive`() {
        val session = createSession()
        session.toggleDirectiveExpanded("0:5", "[find()]")
        session.toggleDirectiveExpanded("1:0", "[view()]")
        assertEquals("1:0", session.expandedDirectiveKey)
        assertEquals("[view()]", session.expandedDirectiveSourceText)
    }

    @Test
    fun `isDirectiveExpanded returns correct state`() {
        val session = createSession()
        assertFalse(session.isDirectiveExpanded("0:5"))
        session.toggleDirectiveExpanded("0:5", "[find()]")
        assertTrue(session.isDirectiveExpanded("0:5"))
        assertFalse(session.isDirectiveExpanded("1:0"))
    }

    @Test
    fun `collapseDirective sets isCollapsingDirective flag`() {
        val session = createSession()
        session.toggleDirectiveExpanded("0:5", "[find()]")
        session.collapseDirective()
        assertTrue(session.isCollapsingDirective)
        assertNull(session.expandedDirectiveKey)
    }

    @Test
    fun `clearCollapsingFlag resets the flag`() {
        val session = createSession()
        session.collapseDirective()
        assertTrue(session.isCollapsingDirective)
        session.clearCollapsingFlag()
        assertFalse(session.isCollapsingDirective)
    }
}

class InlineEditStateTest {

    @Test
    fun `initially has no active session`() {
        val state = InlineEditState()
        assertFalse(state.isActive)
        assertNull(state.activeSession)
        assertNull(state.activeController)
    }

    @Test
    fun `startSession creates active session`() {
        val state = InlineEditState()
        val session = state.startSession("note1", "Content")
        assertTrue(state.isActive)
        assertEquals(session, state.activeSession)
        assertEquals("note1", session.noteId)
        assertEquals("Content", session.originalContent)
    }

    @Test
    fun `endSession returns and clears the session`() {
        val state = InlineEditState()
        val session = state.startSession("note1", "Content")
        val ended = state.endSession()
        assertEquals(session, ended)
        assertFalse(state.isActive)
        assertNull(state.activeSession)
    }

    @Test
    fun `endSession returns null when no session active`() {
        val state = InlineEditState()
        assertNull(state.endSession())
    }

    @Test
    fun `isEditingNote returns true for active note`() {
        val state = InlineEditState()
        state.startSession("note1", "Content")
        assertTrue(state.isEditingNote("note1"))
        assertFalse(state.isEditingNote("note2"))
    }

    @Test
    fun `startSession replaces previous session`() {
        val state = InlineEditState()
        state.startSession("note1", "First")
        val second = state.startSession("note2", "Second")
        assertEquals(second, state.activeSession)
        assertTrue(state.isEditingNote("note2"))
        assertFalse(state.isEditingNote("note1"))
    }

    @Test
    fun `activeController returns controller from active session`() {
        val state = InlineEditState()
        val session = state.startSession("note1", "Content")
        assertEquals(session.controller, state.activeController)
    }
}
