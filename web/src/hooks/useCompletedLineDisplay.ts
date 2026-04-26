import { useEffect, useMemo, useRef } from 'react'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import {
  computeDisplayItemsFromHidden,
  computeEffectiveHidden,
  computeFadedIndices,
  computeHiddenIndices,
  nearestVisibleLine,
} from '@/editor/CompletedLineUtils'

/**
 * Derives the visible display items, faded-line indices, and effective hidden
 * set when the show-completed toggle is off, accounting for recently-checked
 * lines that should remain visible at reduced opacity until the next toggle.
 *
 * Also installs the focus-snap effect: when toggling completed OFF, if the
 * focus is on a now-hidden line, jump to the nearest visible one.
 */
export function useCompletedLineDisplay(
  editorState: EditorState,
  controller: EditorController,
  showCompleted: boolean,
) {
  // editorState.lines is mutated in place — its array reference is stable
  // across edits, so it can't be used as a useMemo dep. Memos below key on
  // lineTextsKey (a derived string) instead, which changes when content does.
  const lineTexts = editorState.lines.map((l) => l.text)
  const lineTextsKey = lineTexts.join('\n')

  const hiddenIndices = useMemo(
    () => computeHiddenIndices(lineTexts, showCompleted),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [lineTextsKey, showCompleted],
  )

  // Snapshot the recently-checked Set into a stable string key — the Set is
  // mutated in place, so reference/size comparison alone won't trigger recompute.
  const recentlyChecked = controller.recentlyCheckedIndices
  const recentlyCheckedKey = [...recentlyChecked].join(',')

  const effectiveHidden = useMemo(
    () => computeEffectiveHidden(hiddenIndices, recentlyChecked, lineTexts),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [hiddenIndices, recentlyCheckedKey],
  )
  const fadedIndices = useMemo(
    () => computeFadedIndices(hiddenIndices, recentlyChecked, lineTexts),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [hiddenIndices, recentlyCheckedKey],
  )
  const displayItems = useMemo(
    () => computeDisplayItemsFromHidden(lineTexts, effectiveHidden),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [lineTextsKey, effectiveHidden],
  )

  controller.hiddenIndices = effectiveHidden

  const prevShowCompletedRef = useRef(showCompleted)
  useEffect(() => {
    if (prevShowCompletedRef.current && !showCompleted) {
      if (hiddenIndices.has(editorState.focusedLineIndex)) {
        const newFocus = nearestVisibleLine(lineTexts, editorState.focusedLineIndex, hiddenIndices)
        controller.setCursor(newFocus, editorState.lines[newFocus]?.cursorPosition ?? 0)
      }
      if (editorState.hasSelection) {
        editorState.clearSelection()
      }
    }
    prevShowCompletedRef.current = showCompleted
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showCompleted])

  return { displayItems, fadedIndices }
}
