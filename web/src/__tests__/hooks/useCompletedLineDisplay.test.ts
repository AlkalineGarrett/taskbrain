import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { useCompletedLineDisplay } from '@/hooks/useCompletedLineDisplay'

function makeEditor() {
  const state = new EditorState()
  // Index 0 (title) is never hidden — keep open lines at 0. The hidden-line
  // detector matches a leading checked-checkbox glyph (☑) after tabs.
  state.initFromNoteLines([
    { text: 'Title', noteIds: [] },
    { text: '☑ done one', noteIds: [] },
    { text: 'open one', noteIds: [] },
    { text: '☑ done two', noteIds: [] },
    { text: 'open two', noteIds: [] },
  ])
  const controller = new EditorController(state)
  return { state, controller }
}

describe('useCompletedLineDisplay', () => {
  it('passes through hidden indices to the controller and assigns effectiveHidden', () => {
    const { state, controller } = makeEditor()
    renderHook(() => useCompletedLineDisplay(state, controller, false))
    // showCompleted=false → completed lines (indices 1 and 3) should be hidden.
    expect(controller.hiddenIndices.has(1)).toBe(true)
    expect(controller.hiddenIndices.has(3)).toBe(true)
    expect(controller.hiddenIndices.has(0)).toBe(false)
    expect(controller.hiddenIndices.has(2)).toBe(false)
    expect(controller.hiddenIndices.has(4)).toBe(false)
  })

  it('with showCompleted=true, no lines are hidden', () => {
    const { state, controller } = makeEditor()
    renderHook(() => useCompletedLineDisplay(state, controller, true))
    expect(controller.hiddenIndices.size).toBe(0)
  })

  it('produces displayItems that collapse hidden lines into placeholders', () => {
    const { state, controller } = makeEditor()
    const { result } = renderHook(() => useCompletedLineDisplay(state, controller, false))
    const items = result.current.displayItems
    // Two hidden completed lines (1 and 3) interleaved with three visible ones
    // (0, 2, 4) → 2 placeholders + 3 visible items.
    const placeholders = items.filter(i => i.type === 'placeholder')
    const visible = items.filter(i => i.type !== 'placeholder')
    expect(placeholders.length).toBe(2)
    expect(visible.length).toBe(3)
  })

  it('snaps focus to nearest visible line when toggling completed off', () => {
    const { state, controller } = makeEditor()
    // Place focus on a completed line (index 1) while showCompleted is on.
    controller.setCursor(1, 0)
    expect(state.focusedLineIndex).toBe(1)

    const { rerender } = renderHook(
      ({ show }) => useCompletedLineDisplay(state, controller, show),
      { initialProps: { show: true } },
    )

    // Toggle off — the hook should move the cursor to a visible line.
    rerender({ show: false })

    // Focus must not be on a hidden line.
    expect(controller.hiddenIndices.has(state.focusedLineIndex)).toBe(false)
  })

  it('clears selection when toggling completed off', () => {
    const { state, controller } = makeEditor()
    // Selection that spans the first completed line.
    state.setSelection(0, 20)
    expect(state.hasSelection).toBe(true)

    const { rerender } = renderHook(
      ({ show }) => useCompletedLineDisplay(state, controller, show),
      { initialProps: { show: true } },
    )
    rerender({ show: false })

    expect(state.hasSelection).toBe(false)
  })

  it('does not snap focus when toggling completed on', () => {
    const { state, controller } = makeEditor()
    // Start with showCompleted=false and focus on an open line (index 2).
    controller.setCursor(2, 0)

    const { rerender } = renderHook(
      ({ show }) => useCompletedLineDisplay(state, controller, show),
      { initialProps: { show: false } },
    )
    rerender({ show: true })

    expect(state.focusedLineIndex).toBe(2)
  })
})
