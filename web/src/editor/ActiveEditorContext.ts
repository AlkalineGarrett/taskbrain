import { createContext, useContext } from 'react'
import type { EditorController } from './EditorController'
import type { EditorState } from './EditorState'
import type { InlineEditSession } from './InlineEditSession'
import type { InlineSessionManager } from './InlineSessionManager'
import type { UnifiedUndoManager } from './UnifiedUndoManager'

export interface ActiveEditorContextValue {
  /** The controller that command bar buttons should route to. */
  activeController: EditorController
  /** The state that command bar buttons should read from. */
  activeState: EditorState
  /** The currently active inline edit session, if any. */
  activeSession: InlineEditSession | null
  /** Called by a ViewNoteSection when it gains focus. */
  activateSession: (session: InlineEditSession) => void
  /** Called when focus leaves a view section. Returns the old session for saving.
   *  If expectedSession is provided, only deactivates if it matches the current active session
   *  (prevents a blurring section from deactivating a newly-activated sibling). */
  deactivateSession: (expectedSession?: InlineEditSession) => InlineEditSession | null
  /** Notify that the active session's state changed (triggers CommandBar re-render). */
  notifyActiveChange: () => void
  /** Centralized manager for inline edit sessions (eagerly created for all embedded notes). */
  sessionManager: InlineSessionManager
  /** Cross-editor undo coordinator. Cross-editor drag-drop wraps its source-delete
   *  + target-paste in `unifiedUndoManager.withGroup` so one undo reverts both. */
  unifiedUndoManager: UnifiedUndoManager
}

export const ActiveEditorContext = createContext<ActiveEditorContextValue | null>(null)

export function useActiveEditor(): ActiveEditorContextValue {
  const ctx = useContext(ActiveEditorContext)
  if (!ctx) throw new Error('useActiveEditor must be used within an ActiveEditorContext.Provider')
  return ctx
}
