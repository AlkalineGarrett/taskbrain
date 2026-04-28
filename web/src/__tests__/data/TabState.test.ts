import { describe, it, expect } from 'vitest'
import {
  computeDisplayTabs,
  nextNoteIdAfterRemove,
  extractDisplayText,
  MAX_DISPLAYED,
} from '../../data/TabState'
import type { RecentTab } from '../../data/RecentTabsRepository'

function tab(noteId: string, displayText = noteId): RecentTab {
  return { noteId, displayText, lastAccessedAt: null }
}

describe('computeDisplayTabs', () => {
  it('pins currentTab in slot 0 and dedupes the shared list', () => {
    const result = computeDisplayTabs(tab('c'), [tab('a'), tab('b'), tab('c'), tab('d')])
    expect(result.map((t) => t.noteId)).toEqual(['c', 'a', 'b', 'd'])
  })

  it('caps the displayed list at MAX_DISPLAYED total', () => {
    const shared = [tab('a'), tab('b'), tab('c'), tab('d'), tab('e'), tab('f')]
    const result = computeDisplayTabs(tab('z'), shared)
    expect(result).toHaveLength(MAX_DISPLAYED)
    expect(result[0]!.noteId).toBe('z')
    expect(result.slice(1).map((t) => t.noteId)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('keeps current pinned even when not in the shared list', () => {
    // Other devices' writes can push this device's current past the buffer.
    const shared = [tab('a'), tab('b'), tab('c'), tab('d')]
    const result = computeDisplayTabs(tab('orphan'), shared)
    expect(result.map((t) => t.noteId)).toEqual(['orphan', 'a', 'b', 'c', 'd'])
  })

  it('returns the shared list as-is when no current tab', () => {
    const shared = [tab('a'), tab('b'), tab('c'), tab('d'), tab('e'), tab('f')]
    const result = computeDisplayTabs(null, shared)
    expect(result.map((t) => t.noteId)).toEqual(['a', 'b', 'c', 'd', 'e'])
  })

  it('uses currentTab displayText (not the shared entry) so an in-flight title edit shows immediately', () => {
    const result = computeDisplayTabs(
      tab('c', 'Fresh title'),
      [tab('c', 'Stale title'), tab('a')],
    )
    expect(result[0]!.displayText).toBe('Fresh title')
  })
})

describe('nextNoteIdAfterRemove', () => {
  it('returns the next-most-recent shared entry', () => {
    expect(nextNoteIdAfterRemove('a', [tab('a'), tab('b'), tab('c')])).toBe('b')
  })

  it('skips the removed id even if not at the front', () => {
    expect(nextNoteIdAfterRemove('b', [tab('a'), tab('b'), tab('c')])).toBe('a')
  })

  it('returns null when shared has nothing else', () => {
    expect(nextNoteIdAfterRemove('a', [tab('a')])).toBeNull()
  })

  it('returns null when shared is empty', () => {
    expect(nextNoteIdAfterRemove('a', [])).toBeNull()
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
