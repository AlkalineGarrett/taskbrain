import { describe, it, expect } from 'vitest'
import {
  addOrUpdateTabState,
  updateDisplayTextState,
  removeTabState,
  extractDisplayText,
} from '../../data/TabState'
import type { RecentTab } from '../../data/RecentTabsRepository'

function tab(noteId: string, displayText = noteId): RecentTab {
  return { noteId, displayText, lastAccessedAt: null }
}

describe('addOrUpdateTabState', () => {
  it('adds a new tab to the front of an empty list', () => {
    const result = addOrUpdateTabState([], 'a', 'Note A')
    expect(result).toEqual([tab('a', 'Note A')])
  })

  it('adds a new tab to the front of an existing list', () => {
    const tabs = [tab('a'), tab('b')]
    const result = addOrUpdateTabState(tabs, 'c', 'Note C')
    expect(result.map((t) => t.noteId)).toEqual(['c', 'a', 'b'])
  })

  it('moves an existing tab to the front', () => {
    const tabs = [tab('a'), tab('b'), tab('c')]
    const result = addOrUpdateTabState(tabs, 'c', 'Note C')
    expect(result.map((t) => t.noteId)).toEqual(['c', 'a', 'b'])
  })

  it('updates display text of an existing tab', () => {
    const tabs = [tab('a', 'Old'), tab('b')]
    const result = addOrUpdateTabState(tabs, 'a', 'New')
    expect(result[0]!.displayText).toBe('New')
  })

  it('caps at 5 tabs when adding a new one', () => {
    const tabs = [tab('a'), tab('b'), tab('c'), tab('d'), tab('e')]
    const result = addOrUpdateTabState(tabs, 'f', 'Note F')
    expect(result).toHaveLength(5)
    expect(result[0]!.noteId).toBe('f')
    expect(result.map((t) => t.noteId)).not.toContain('e')
  })

  it('does not drop tabs when moving an existing tab to front', () => {
    const tabs = [tab('a'), tab('b'), tab('c'), tab('d'), tab('e')]
    const result = addOrUpdateTabState(tabs, 'e', 'Note E')
    expect(result).toHaveLength(5)
    expect(result[0]!.noteId).toBe('e')
  })

  it('preserves lastAccessedAt of existing tab', () => {
    const ts = { toDate: () => new Date() } as any
    const tabs: RecentTab[] = [{ noteId: 'a', displayText: 'old', lastAccessedAt: ts }]
    const result = addOrUpdateTabState(tabs, 'a', 'new')
    expect(result[0]!.lastAccessedAt).toBe(ts)
  })

  it('with tab already at front just updates text', () => {
    const tabs = [tab('a', 'Old'), tab('b'), tab('c')]
    const result = addOrUpdateTabState(tabs, 'a', 'New')
    expect(result.map((t) => t.noteId)).toEqual(['a', 'b', 'c'])
    expect(result[0]!.displayText).toBe('New')
  })
})

describe('updateDisplayTextState', () => {
  it('updates text without reordering', () => {
    const tabs = [tab('a', 'Old'), tab('b'), tab('c')]
    const result = updateDisplayTextState(tabs, 'b', 'Updated')
    expect(result.map((t) => t.noteId)).toEqual(['a', 'b', 'c'])
    expect(result[1]!.displayText).toBe('Updated')
  })

  it('with nonexistent noteId returns unchanged', () => {
    const tabs = [tab('a'), tab('b')]
    const result = updateDisplayTextState(tabs, 'z', 'X')
    expect(result).toEqual(tabs)
  })
})

describe('removeTabState', () => {
  it('removes a tab that is not current', () => {
    const tabs = [tab('a'), tab('b'), tab('c')]
    const result = removeTabState(tabs, 'b', 'a')
    expect(result.tabs.map((t) => t.noteId)).toEqual(['a', 'c'])
    expect(result.navigateTo).toBeUndefined()
  })

  it('navigates to next tab when closing current tab', () => {
    const tabs = [tab('a'), tab('b'), tab('c')]
    const result = removeTabState(tabs, 'b', 'b')
    expect(result.tabs.map((t) => t.noteId)).toEqual(['a', 'c'])
    expect(result.navigateTo).toBe('c')
  })

  it('navigates to previous tab when closing last current tab', () => {
    const tabs = [tab('a'), tab('b'), tab('c')]
    const result = removeTabState(tabs, 'c', 'c')
    expect(result.tabs.map((t) => t.noteId)).toEqual(['a', 'b'])
    expect(result.navigateTo).toBe('b')
  })

  it('navigates to first tab when closing current first tab', () => {
    const tabs = [tab('a'), tab('b'), tab('c')]
    const result = removeTabState(tabs, 'a', 'a')
    expect(result.tabs.map((t) => t.noteId)).toEqual(['b', 'c'])
    expect(result.navigateTo).toBe('b')
  })

  it('navigates home when closing the only tab', () => {
    const tabs = [tab('a')]
    const result = removeTabState(tabs, 'a', 'a')
    expect(result.tabs).toEqual([])
    expect(result.navigateTo).toBeNull()
  })

  it('does not navigate when closing non-current tab', () => {
    const tabs = [tab('a'), tab('b')]
    const result = removeTabState(tabs, 'b', 'a')
    expect(result.navigateTo).toBeUndefined()
  })

  it('handles removing a tab not in the list', () => {
    const tabs = [tab('a'), tab('b')]
    const result = removeTabState(tabs, 'z', 'a')
    expect(result.tabs.map((t) => t.noteId)).toEqual(['a', 'b'])
    expect(result.navigateTo).toBeUndefined()
  })

  it('handles undefined currentNoteId', () => {
    const tabs = [tab('a'), tab('b')]
    const result = removeTabState(tabs, 'a', undefined)
    expect(result.tabs.map((t) => t.noteId)).toEqual(['b'])
    expect(result.navigateTo).toBeUndefined()
  })

  it('removes from single-element list', () => {
    const result = removeTabState([tab('a')], 'a', 'b')
    expect(result.tabs).toEqual([])
    expect(result.navigateTo).toBeUndefined()
  })
})

describe('extractDisplayText', () => {
  it('uses first line', () => {
    expect(extractDisplayText('Hello\nWorld')).toBe('Hello')
  })

  it('removes alarm symbol', () => {
    expect(extractDisplayText('⏰ Task')).toBe('Task')
  })

  it('trims whitespace', () => {
    expect(extractDisplayText('  Hello  ')).toBe('Hello')
  })

  it('truncates long text', () => {
    const long = 'A'.repeat(20)
    const result = extractDisplayText(long)
    expect(result).toHaveLength(12)
    expect(result.endsWith('…')).toBe(true)
  })

  it('returns (empty) for empty content', () => {
    expect(extractDisplayText('')).toBe('(empty)')
  })

  it('returns (empty) for whitespace-only content', () => {
    expect(extractDisplayText('   ')).toBe('(empty)')
  })

  it('returns (empty) for alarm-symbol-only content', () => {
    expect(extractDisplayText('⏰')).toBe('(empty)')
  })

  it('removes alarm directive from display text', () => {
    expect(extractDisplayText('Task [alarm("abc123")]')).toBe('Task')
  })

  it('removes multiple alarm directives', () => {
    expect(extractDisplayText('[alarm("a1")] Task [alarm("a2")]')).toBe('Task')
  })

  it('returns (empty) for alarm-directive-only content', () => {
    expect(extractDisplayText('[alarm("abc")]')).toBe('(empty)')
  })

  it('removes both alarm symbol and alarm directive', () => {
    expect(extractDisplayText('⏰ Task [alarm("id1")]')).toBe('Task')
  })

  it('removes recurring alarm directive from display text', () => {
    expect(extractDisplayText('Task [recurringAlarm("rec123")]')).toBe('Task')
  })

  it('removes multiple recurring alarm directives', () => {
    expect(extractDisplayText('[recurringAlarm("r1")] Task [recurringAlarm("r2")]')).toBe('Task')
  })

  it('returns (empty) for recurringAlarm-directive-only content', () => {
    expect(extractDisplayText('[recurringAlarm("abc")]')).toBe('(empty)')
  })

  it('removes mixed alarm and recurringAlarm directives', () => {
    expect(extractDisplayText('[alarm("a1")] Task [recurringAlarm("r1")]')).toBe('Task')
  })
})
