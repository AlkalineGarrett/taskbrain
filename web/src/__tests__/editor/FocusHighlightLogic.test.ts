import { describe, it, expect } from 'vitest'
import { computeFocusHighlight } from '../../editor/FocusHighlight'

/**
 * Tests for the focus highlight and auto-focus gating logic used in EditorLine.
 *
 * In EditorLine:
 *   isFocusedInState = lineIndex === editorState.focusedLineIndex
 *   { highlight, autoFocus } = computeFocusHighlight(isFocusedInState, allowAutoFocus, showFocusHighlight)
 *   isFocused = highlight       // visual highlight (CSS .focused class)
 *   autoFocusAllowed = autoFocus // focus effect gate
 */

describe('computeFocusHighlight', () => {
  describe('main editor (no allowAutoFocus, showFocusHighlight controls highlight)', () => {
    it('shows highlight when no view is active', () => {
      // showFocusHighlight = !activeSession = true
      const { highlight, autoFocus } = computeFocusHighlight(true, undefined, true)
      expect(highlight).toBe(true)
      expect(autoFocus).toBe(true)
    })

    it('hides highlight when a view is active', () => {
      // showFocusHighlight = !activeSession = false
      const { highlight, autoFocus } = computeFocusHighlight(true, undefined, false)
      expect(highlight).toBe(false)
      // Focus effect still allowed — main editor must always be able to grab focus
      expect(autoFocus).toBe(true)
    })

    it('no highlight on non-focused line regardless', () => {
      const { highlight } = computeFocusHighlight(false, undefined, true)
      expect(highlight).toBe(false)
    })
  })

  describe('view editor (allowAutoFocus = isActiveHere, no showFocusHighlight)', () => {
    it('shows highlight and allows focus when active', () => {
      // allowAutoFocus = isActiveHere = true
      const { highlight, autoFocus } = computeFocusHighlight(true, true, undefined)
      expect(highlight).toBe(true)
      expect(autoFocus).toBe(true)
    })

    it('hides highlight and blocks focus when inactive', () => {
      // allowAutoFocus = isActiveHere = false
      const { highlight, autoFocus } = computeFocusHighlight(true, false, undefined)
      expect(highlight).toBe(false)
      expect(autoFocus).toBe(false)
    })

    it('no highlight on non-focused line even when active', () => {
      const { highlight } = computeFocusHighlight(false, true, undefined)
      expect(highlight).toBe(false)
    })
  })

  describe('showFocusHighlight overrides allowAutoFocus for visual', () => {
    it('showFocusHighlight=true with allowAutoFocus=false: highlight shown, focus blocked', () => {
      const { highlight, autoFocus } = computeFocusHighlight(true, false, true)
      expect(highlight).toBe(true)
      expect(autoFocus).toBe(false)
    })

    it('showFocusHighlight=false with allowAutoFocus=true: highlight hidden, focus allowed', () => {
      const { highlight, autoFocus } = computeFocusHighlight(true, true, false)
      expect(highlight).toBe(false)
      expect(autoFocus).toBe(true)
    })
  })
})
