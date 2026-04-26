import { useEffect, type MutableRefObject } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import type { InlineEditSession } from '@/editor/InlineEditSession'

interface UseGlobalKeyboardShortcutsOptions {
  saveAll: () => Promise<void> | void
  controller: EditorController
  editorState: EditorState
  activeSessionRef: MutableRefObject<InlineEditSession | null>
  handleUndo: () => void
  handleRedo: () => void
}

/**
 * Installs global keyboard shortcuts:
 * - Ctrl/Cmd+S always saves (regardless of focus)
 * - Other shortcuts only fire when no textarea is focused (e.g. after gutter
 *   selection): undo/redo, select-all, cut/copy on selection, delete selection
 *
 * Routes through the active session's controller/state when one is active,
 * otherwise the parent editor's.
 */
export function useGlobalKeyboardShortcuts({
  saveAll,
  controller,
  editorState,
  activeSessionRef,
  handleUndo,
  handleRedo,
}: UseGlobalKeyboardShortcutsOptions): void {
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault()
        void saveAll()
        return
      }

      // Remaining shortcuts only apply when no textarea has focus
      const active = document.activeElement
      if (active && active.tagName === 'TEXTAREA') return

      const ctrl = activeSessionRef.current?.controller ?? controller
      const state = activeSessionRef.current?.editorState ?? editorState

      if (e.metaKey || e.ctrlKey) {
        if (e.key === 'z') {
          e.preventDefault()
          if (e.shiftKey) handleRedo()
          else handleUndo()
          return
        }
        if (e.key === 'y') {
          e.preventDefault()
          handleRedo()
          return
        }
        if (e.key === 'a') {
          e.preventDefault()
          state.selectAll()
          return
        }
        if (e.key === 'x' && state.hasSelection) {
          e.preventDefault()
          ctrl.cutSelection()
          return
        }
        if (e.key === 'c' && state.hasSelection) {
          e.preventDefault()
          ctrl.copySelection()
          return
        }
        return
      }

      if (state.hasSelection && (e.key === 'Backspace' || e.key === 'Delete')) {
        e.preventDefault()
        ctrl.deleteSelectionWithUndo()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [saveAll, editorState, controller, activeSessionRef, handleUndo, handleRedo])
}
