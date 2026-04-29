import { createContext, useContext } from 'react'

export interface UndoActionsContextValue {
  handleUndo: () => void
  handleRedo: () => void
}

// Per-line keyboard handlers (cmd/ctrl+z/y) must route through here, not the
// active controller directly — UnifiedUndoManager owns the cross-editor stack
// the toolbar's canUndo/canRedo are bound to, so a direct controller.undo()
// leaves the redo button stale.
export const UndoActionsContext = createContext<UndoActionsContextValue | null>(null)

export function useUndoActions(): UndoActionsContextValue {
  const ctx = useContext(UndoActionsContext)
  if (!ctx) throw new Error('useUndoActions must be used within an UndoActionsContext.Provider')
  return ctx
}
