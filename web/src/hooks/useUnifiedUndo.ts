import { useCallback, useEffect, useState, type MutableRefObject } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { InlineEditSession } from '@/editor/InlineEditSession'
import type { InlineSessionManager } from '@/editor/InlineSessionManager'
import { UnifiedUndoManager } from '@/editor/UnifiedUndoManager'

const MAIN_CONTEXT_ID = 'main'
const INLINE_CONTEXT_PREFIX = 'inline:'
const inlineContextId = (noteId: string) => `${INLINE_CONTEXT_PREFIX}${noteId}`

interface UseUnifiedUndoOptions {
  controller: EditorController
  sessionManager: InlineSessionManager
  activeSessionRef: MutableRefObject<InlineEditSession | null>
  activateSession: (session: InlineEditSession) => void
  deactivateSession: (expectedSession?: InlineEditSession) => InlineEditSession | null
  invalidateAndRecompute: () => void
}

/**
 * Wires the unified undo/redo manager to the main controller and all inline
 * edit sessions, and exposes activation-aware undo/redo entry points.
 */
export function useUnifiedUndo({
  controller,
  sessionManager,
  activeSessionRef,
  activateSession,
  deactivateSession,
  invalidateAndRecompute,
}: UseUnifiedUndoOptions) {
  const [unifiedUndoManager] = useState(() => new UnifiedUndoManager())

  useEffect(() => {
    unifiedUndoManager.registerEditor(MAIN_CONTEXT_ID, controller)
    return () => unifiedUndoManager.unregisterEditor(MAIN_CONTEXT_ID)
  }, [unifiedUndoManager, controller])

  // Re-register inline sessions when the set of sessions changes.
  const inlineSessionIds = sessionManager.getAllSessions().map(s => s.noteId).join(',')
  useEffect(() => {
    for (const session of sessionManager.getAllSessions()) {
      unifiedUndoManager.registerEditor(inlineContextId(session.noteId), session.controller)
    }
    return () => {
      for (const session of sessionManager.getAllSessions()) {
        unifiedUndoManager.unregisterEditor(inlineContextId(session.noteId))
      }
    }
  }, [inlineSessionIds, sessionManager, unifiedUndoManager])

  const getActiveContextId = useCallback(() => {
    const session = activeSessionRef.current
    return session ? inlineContextId(session.noteId) : MAIN_CONTEXT_ID
  }, [activeSessionRef])

  const activateEditorByContextId = useCallback((contextId: string) => {
    if (contextId === MAIN_CONTEXT_ID) {
      if (activeSessionRef.current) deactivateSession()
    } else {
      const targetNoteId = contextId.slice(INLINE_CONTEXT_PREFIX.length)
      const session = sessionManager.getSession(targetNoteId)
      if (session) activateSession(session)
    }
  }, [sessionManager, activateSession, deactivateSession, activeSessionRef])

  const handleUndo = useCallback(() => {
    unifiedUndoManager.undo(getActiveContextId(), activateEditorByContextId)
    invalidateAndRecompute()
  }, [unifiedUndoManager, getActiveContextId, activateEditorByContextId, invalidateAndRecompute])

  const handleRedo = useCallback(() => {
    unifiedUndoManager.redo(getActiveContextId(), activateEditorByContextId)
    invalidateAndRecompute()
  }, [unifiedUndoManager, getActiveContextId, activateEditorByContextId, invalidateAndRecompute])

  return { unifiedUndoManager, handleUndo, handleRedo }
}
