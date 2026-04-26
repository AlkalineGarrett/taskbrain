import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook } from '@testing-library/react'

const { addOrUpdateTabSpy, updateTabDisplayTextSpy, extractDisplayTextSpy } = vi.hoisted(() => ({
  addOrUpdateTabSpy: vi.fn(),
  updateTabDisplayTextSpy: vi.fn(),
  extractDisplayTextSpy: vi.fn((text: string) => text),
}))

vi.mock('@/components/RecentTabsBar', () => ({
  addOrUpdateTab: addOrUpdateTabSpy,
  updateTabDisplayText: updateTabDisplayTextSpy,
}))

vi.mock('@/data/TabState', () => ({
  extractDisplayText: extractDisplayTextSpy,
}))

import { useTabSync } from '@/hooks/useTabSync'

describe('useTabSync', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('skips when noteId is null', () => {
    renderHook(() => useTabSync(null, false, 'whatever'))
    expect(addOrUpdateTabSpy).not.toHaveBeenCalled()
    expect(updateTabDisplayTextSpy).not.toHaveBeenCalled()
  })

  it('skips while loading', () => {
    renderHook(() => useTabSync('note-1', true, 'Title'))
    expect(addOrUpdateTabSpy).not.toHaveBeenCalled()
    expect(updateTabDisplayTextSpy).not.toHaveBeenCalled()
  })

  it('records the last note id and brings the tab to front when a note opens', () => {
    renderHook(() => useTabSync('note-1', false, 'Title'))
    expect(localStorage.getItem('lastNoteId')).toBe('note-1')
    expect(addOrUpdateTabSpy).toHaveBeenCalledWith('note-1', 'Title')
  })

  it('updates the display text without bringing the tab to front when only the title changes', () => {
    const { rerender } = renderHook(
      ({ text }: { text: string }) => useTabSync('note-1', false, text),
      { initialProps: { text: 'Old title' } },
    )
    addOrUpdateTabSpy.mockClear()
    updateTabDisplayTextSpy.mockClear()

    rerender({ text: 'New title' })

    expect(updateTabDisplayTextSpy).toHaveBeenCalledWith('note-1', 'New title')
    // The bring-to-front effect should NOT fire on title change.
    expect(addOrUpdateTabSpy).not.toHaveBeenCalled()
  })

  it('brings the tab to front when noteId changes', () => {
    const { rerender } = renderHook(
      ({ id }: { id: string }) => useTabSync(id, false, 'Title'),
      { initialProps: { id: 'note-1' } },
    )
    addOrUpdateTabSpy.mockClear()

    rerender({ id: 'note-2' })

    expect(addOrUpdateTabSpy).toHaveBeenCalledWith('note-2', 'Title')
  })
})
