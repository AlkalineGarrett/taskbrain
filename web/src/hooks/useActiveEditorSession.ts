import { useCallback, useMemo, useRef, useState } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { InlineEditSession } from '@/editor/InlineEditSession'
import type { InlineSessionManager } from '@/editor/InlineSessionManager'
import type { ActiveEditorContextValue } from '@/editor/ActiveEditorContext'

/**
 * Owns the "active editor" — either the parent controller or one of the inline
 * edit sessions. Routes activation/deactivation, version-bumps to force command
 * bar re-evaluation, and supplies the context value consumed by ViewNoteSection.
 */
export function useActiveEditorSession(
  controller: EditorController,
  editorState: EditorState,
  sessionManager: InlineSessionManager,
) {
  // Both state and ref hold the active session — not redundant:
  //  - state drives renders so derived values (activeController, activeState,
  //    activeEditorCtx) update via React's normal flow
  //  - ref gives event handlers (keyboard shortcuts, gutter routing) a
  //    stale-closure-free read at fire time, so they don't have to re-register
  //    every time the active session changes
  const [activeSession, setActiveSession] = useState<InlineEditSession | null>(null)
  const activeSessionRef = useRef<InlineEditSession | null>(null)
  // Force a re-render when the active session's state changes (e.g. selection
  // change within the view). setActiveSession with the same session object
  // doesn't trigger one, leaving CommandBar's disabled states stale.
  const [, setActiveSessionVersion] = useState(0)

  const activateSession = useCallback((session: InlineEditSession) => {
    const prev = activeSessionRef.current
    if (prev !== session) {
      // Commit pending undo state on the outgoing editor so edits become undo entries
      const outgoingCtrl = prev?.controller ?? controller
      outgoingCtrl.commitUndoState()
      // Mutual exclusivity: clear selections in all other editors when switching
      editorState.clearSelection()
      if (prev) prev.editorState.clearSelection()
    }
    activeSessionRef.current = session
    setActiveSession(session)
    setActiveSessionVersion(v => v + 1)
  }, [editorState, controller])

  const deactivateSession = useCallback((expectedSession?: InlineEditSession): InlineEditSession | null => {
    const prev = activeSessionRef.current
    // If caller specifies which session it expects to deactivate, only proceed if it matches.
    // Prevents a blurring ViewNoteSection from deactivating a sibling that just activated.
    if (expectedSession && prev !== expectedSession) return null
    if (prev) prev.editorState.clearSelection()
    activeSessionRef.current = null
    setActiveSession(null)
    return prev
  }, [])

  const notifyActiveChange = useCallback(() => {
    setActiveSessionVersion(v => v + 1)
  }, [])

  const activeController = activeSession?.controller ?? controller
  const activeState = activeSession?.editorState ?? editorState

  const activeEditorCtx = useMemo<ActiveEditorContextValue>(() => ({
    activeController,
    activeState,
    activeSession,
    activateSession,
    deactivateSession,
    notifyActiveChange,
    sessionManager,
  }), [activeController, activeState, activeSession, activateSession, deactivateSession, notifyActiveChange, sessionManager])

  return {
    activeSession,
    activeSessionRef,
    activeController,
    activeState,
    activateSession,
    deactivateSession,
    activeEditorCtx,
    notifyActiveChange,
  }
}
