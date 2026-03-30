/**
 * Computes whether a line should show the focus highlight and whether
 * the focus effect should auto-focus the textarea.
 *
 * Used by EditorLine and tested directly in FocusHighlightLogic.test.ts.
 */
export function computeFocusHighlight(
  isFocusedInState: boolean,
  allowAutoFocus: boolean | undefined,
  showFocusHighlight: boolean | undefined,
): { highlight: boolean; autoFocus: boolean } {
  const highlightActive = showFocusHighlight ?? (allowAutoFocus !== false)
  return {
    highlight: isFocusedInState && highlightActive,
    autoFocus: allowAutoFocus !== false,
  }
}
