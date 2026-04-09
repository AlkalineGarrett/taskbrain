package org.alkaline.taskbrain.ui.currentnote.undo

import org.alkaline.taskbrain.ui.currentnote.EditorController
import org.alkaline.taskbrain.ui.currentnote.EditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnifiedUndoManagerTest {

    private lateinit var unified: UnifiedUndoManager

    @Before
    fun setUp() {
        unified = UnifiedUndoManager()
    }

    /** Creates an editor pair, registers it, sets baseline with initial text. */
    private fun createEditor(
        contextId: String,
        initialText: String = "initial"
    ): Pair<EditorState, EditorController> {
        val state = EditorState()
        val controller = EditorController(state)
        state.updateFromText(initialText)
        controller.undoManager.setBaseline(state)
        unified.registerEditor(contextId, controller)
        return state to controller
    }

    /** Simulates an edit: update text, mark changed, commit. */
    private fun edit(state: EditorState, controller: EditorController, newText: String) {
        controller.undoManager.beginEditingLine(state, 0)
        state.updateFromText(newText)
        controller.undoManager.markContentChanged()
        controller.commitUndoState()
    }

    // ==================== Register / Unregister ====================

    @Test
    fun `registerEditor hooks callbacks`() {
        val (state, controller) = createEditor("main")

        edit(state, controller, "edited")

        assertTrue(unified.canUndo)
    }

    @Test
    fun `unregisterEditor removes callbacks`() {
        val (state, controller) = createEditor("main")

        unified.unregisterEditor("main")

        // Edit after unregister should not add to unified stack
        edit(state, controller, "edited")

        assertFalse(unified.canUndo)
    }

    // ==================== Chronological Undo Order ====================

    @Test
    fun `undo follows chronological order across editors`() {
        val (stateMain, ctrlMain) = createEditor("main", "main-v0")
        val (stateA, ctrlA) = createEditor("inlineA", "a-v0")

        // Edit main first, then inline A
        edit(stateMain, ctrlMain, "main-v1")
        edit(stateA, ctrlA, "a-v1")

        val activations = mutableListOf<String>()

        // First undo should target inline A (most recent)
        val result1 = unified.undo("main") { activations.add(it) }
        assertNotNull(result1)
        assertEquals("inlineA", result1!!.contextId)
        assertEquals(listOf("a-v0"), result1.snapshot.lineContents)

        // Second undo should target main
        val result2 = unified.undo("inlineA") { activations.add(it) }
        assertNotNull(result2)
        assertEquals("main", result2!!.contextId)
        assertEquals(listOf("main-v0"), result2.snapshot.lineContents)
    }

    @Test
    fun `undo returns null when nothing to undo`() {
        createEditor("main", "text")

        val result = unified.undo("main") { }
        assertNull(result)
    }

    // ==================== Redo Order ====================

    @Test
    fun `redo restores in correct order after undo`() {
        val (stateMain, ctrlMain) = createEditor("main", "main-v0")
        val (stateA, ctrlA) = createEditor("inlineA", "a-v0")

        edit(stateMain, ctrlMain, "main-v1")
        edit(stateA, ctrlA, "a-v1")

        // Undo both
        val undo1 = unified.undo("inlineA") { }
        assertNotNull(undo1)
        // After undo, stateA still has "a-v1" text (undo returns snapshot but
        // UnifiedUndoManager delegates restore to caller). For redo to work,
        // simulate that the caller restored the state.
        stateA.updateFromText("a-v0")

        val undo2 = unified.undo("main") { }
        assertNotNull(undo2)
        stateMain.updateFromText("main-v0")

        // Redo should restore main first (it was undone last)
        val redo1 = unified.redo("main") { }
        assertNotNull(redo1)
        assertEquals("main", redo1!!.contextId)

        // Redo should restore inline A next
        stateMain.updateFromText("main-v1")
        val redo2 = unified.redo("main") { }
        assertNotNull(redo2)
        assertEquals("inlineA", redo2!!.contextId)
    }

    @Test
    fun `canRedo is false initially`() {
        createEditor("main")
        assertFalse(unified.canRedo)
    }

    @Test
    fun `canRedo is true after undo`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        unified.undo("main") { }

        assertTrue(unified.canRedo)
    }

    @Test
    fun `canRedo is false without undo`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        assertFalse(unified.canRedo)
    }

    // ==================== activateEditor Callback ====================

    @Test
    fun `activateEditor is called when undo targets different editor`() {
        val (stateMain, ctrlMain) = createEditor("main", "main-v0")
        val (stateA, ctrlA) = createEditor("inlineA", "a-v0")

        edit(stateA, ctrlA, "a-v1")

        val activations = mutableListOf<String>()

        // Active is "main" but undo target is "inlineA"
        unified.undo("main") { activations.add(it) }

        assertEquals(listOf("inlineA"), activations)
    }

    @Test
    fun `activateEditor is not called when undo targets same editor`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        val activations = mutableListOf<String>()

        unified.undo("main") { activations.add(it) }

        assertTrue(activations.isEmpty())
    }

    @Test
    fun `activateEditor is called on redo targeting different editor`() {
        val (stateMain, ctrlMain) = createEditor("main", "main-v0")
        val (stateA, ctrlA) = createEditor("inlineA", "a-v0")

        edit(stateA, ctrlA, "a-v1")

        // Undo from inlineA context (no activation needed)
        unified.undo("inlineA") { }
        stateA.updateFromText("a-v0")

        val activations = mutableListOf<String>()

        // Redo from main context - should activate inlineA
        unified.redo("main") { activations.add(it) }

        assertEquals(listOf("inlineA"), activations)
    }

    // ==================== abandonHistory ====================

    @Test
    fun `abandonHistory clears undo stack`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        assertTrue(unified.canUndo)

        unified.abandonHistory()

        assertFalse(unified.canUndo)
    }

    @Test
    fun `abandonHistory clears redo stack`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        unified.undo("main") { }
        assertTrue(unified.canRedo)

        unified.abandonHistory()

        assertFalse(unified.canRedo)
    }

    // ==================== canUndo with uncommitted changes ====================

    @Test
    fun `canUndo is true when editor has uncommitted changes`() {
        val (state, controller) = createEditor("main", "v0")

        // Start editing but don't commit
        controller.undoManager.beginEditingLine(state, 0)
        state.updateFromText("v1")
        controller.undoManager.markContentChanged()

        assertTrue(unified.canUndo)
    }

    @Test
    fun `canUndo is false when no editors have changes`() {
        createEditor("main", "text")
        createEditor("inlineA", "text")

        assertFalse(unified.canUndo)
    }

    // ==================== New edits clear redo ====================

    @Test
    fun `new edit clears unified redo stack`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        unified.undo("main") { }
        assertTrue(unified.canRedo)

        // Restore state after undo, then make a new edit
        state.updateFromText("v0")
        edit(state, controller, "v2")

        assertFalse(unified.canRedo)
    }

    // ==================== reset ====================

    @Test
    fun `reset clears everything and unregisters editors`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        unified.reset()

        assertFalse(unified.canUndo)
        assertFalse(unified.canRedo)

        // Callbacks should be disconnected - new edits should not affect unified manager
        edit(state, controller, "v2")
        assertFalse(unified.canUndo)
    }

    // ==================== stateVersion ====================

    @Test
    fun `stateVersion increments on undo`() {
        val (state, controller) = createEditor("main", "v0")
        edit(state, controller, "v1")

        val versionBefore = unified.stateVersion
        unified.undo("main") { }

        assertTrue(unified.stateVersion > versionBefore)
    }

    @Test
    fun `stateVersion increments on abandonHistory`() {
        val versionBefore = unified.stateVersion
        unified.abandonHistory()
        assertTrue(unified.stateVersion > versionBefore)
    }
}
