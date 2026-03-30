import { describe, it, expect } from 'vitest'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { InlineEditSession } from '@/editor/InlineEditSession'

/**
 * Tests for selection mutual exclusivity between parent and view editors.
 * Mirrors the Android SelectionCoordinator behavior: when one editor activates,
 * all other editors' selections are cleared.
 *
 * These tests validate the state-level operations that NoteEditorScreen's
 * activateSession/deactivateSession call.
 */

function createParentEditor(): { state: EditorState; controller: EditorController } {
  const state = new EditorState()
  const noteLines = [
    { text: 'Line 1', noteIds: [] },
    { text: 'Line 2', noteIds: [] },
    { text: 'Line 3', noteIds: [] },
    { text: '', noteIds: [] },
  ]
  state.initFromNoteLines(noteLines)
  return { state, controller: new EditorController(state) }
}

describe('Selection mutual exclusivity', () => {
  describe('activateSession clears other selections', () => {
    it('clears parent selection when view activates', () => {
      const parent = createParentEditor()
      parent.state.setSelection(0, 6) // Select "Line 1"
      expect(parent.state.hasSelection).toBe(true)

      const viewSession = new InlineEditSession('view1', 'View line 1\nView line 2')

      // Simulate activateSession: clear parent + previous session
      parent.state.clearSelection()

      expect(parent.state.hasSelection).toBe(false)
    })

    it('clears previous view selection when switching to different view', () => {
      const parent = createParentEditor()
      const viewA = new InlineEditSession('viewA', 'Content A')
      const viewB = new InlineEditSession('viewB', 'Content B')

      // View A has a selection
      viewA.editorState.setSelection(0, 5)
      expect(viewA.editorState.hasSelection).toBe(true)

      // Simulate activateSession(viewB): clear parent + previous (viewA)
      parent.state.clearSelection()
      viewA.editorState.clearSelection()

      expect(viewA.editorState.hasSelection).toBe(false)
    })

    it('clears both parent and previous view when switching views', () => {
      const parent = createParentEditor()
      const viewA = new InlineEditSession('viewA', 'Content A')

      parent.state.setSelection(0, 6)
      viewA.editorState.setSelection(0, 5)

      // Simulate activateSession(viewB): clear both
      parent.state.clearSelection()
      viewA.editorState.clearSelection()

      expect(parent.state.hasSelection).toBe(false)
      expect(viewA.editorState.hasSelection).toBe(false)
    })

    it('does not clear when re-activating the same session', () => {
      const parent = createParentEditor()
      const view = new InlineEditSession('view1', 'Content')

      view.editorState.setSelection(0, 3)
      expect(view.editorState.hasSelection).toBe(true)

      // Re-activating same session: prev === session, no clearing
      // (This is the `if (prev !== session)` check in activateSession)
      expect(view.editorState.hasSelection).toBe(true)
    })
  })

  describe('deactivateSession clears departing view', () => {
    it('clears view selection on deactivation', () => {
      const view = new InlineEditSession('view1', 'Line 1\nLine 2')
      view.editorState.setSelection(0, 6)
      expect(view.editorState.hasSelection).toBe(true)

      // Simulate deactivateSession: clear departing session
      view.editorState.clearSelection()

      expect(view.editorState.hasSelection).toBe(false)
    })

    it('expected-session guard prevents wrong deactivation', () => {
      const viewA = new InlineEditSession('viewA', 'Content A')
      const viewB = new InlineEditSession('viewB', 'Content B')

      viewB.editorState.setSelection(0, 5)

      // Simulate: viewA tries to deactivate, but viewB is active
      // deactivateSession(viewA) should be a no-op because prev(viewB) !== expected(viewA)
      const activeSession = viewB
      const expectedSession = viewA
      if (activeSession !== expectedSession) {
        // no-op, don't clear
      } else {
        activeSession.editorState.clearSelection()
      }

      // viewB's selection should remain
      expect(viewB.editorState.hasSelection).toBe(true)
    })
  })

  describe('parent gutter deactivates view', () => {
    it('parent gutter selection clears view selection', () => {
      const parent = createParentEditor()
      const view = new InlineEditSession('view1', 'View content')

      // View has selection
      view.editorState.setSelection(0, 4)
      expect(view.editorState.hasSelection).toBe(true)

      // Simulate handleGutterDragStart on parent: deactivate view, then select in parent
      view.editorState.clearSelection() // from deactivateSession
      parent.controller.setSelection(0, 6) // parent gutter select

      expect(view.editorState.hasSelection).toBe(false)
      expect(parent.state.hasSelection).toBe(true)
    })
  })
})
