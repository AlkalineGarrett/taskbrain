import { describe, it, expect } from 'vitest'
import type { Note } from '../../data/Note'
import { searchNotes } from '../../data/NoteSearchUtils'
import { note } from '../factories'

function ts(millis: number) {
  return { toMillis: () => millis } as Note['updatedAt']
}

describe('searchNotes', () => {
  // ── basic matching ──

  it('returns empty for empty query', () => {
    const notes = [note({ id: '1', content: 'Hello' })]
    const { active, deleted } = searchNotes(notes, '', true, true)
    expect(active).toEqual([])
    expect(deleted).toEqual([])
  })

  it('returns empty when both criteria disabled', () => {
    const notes = [note({ id: '1', content: 'Hello' })]
    const { active, deleted } = searchNotes(notes, 'Hello', false, false)
    expect(active).toEqual([])
    expect(deleted).toEqual([])
  })

  it('matches name only when searchByName is true', () => {
    const notes = [note({ id: '1', content: 'Hello\nWorld' })]
    const { active } = searchNotes(notes, 'Hello', true, false)
    expect(active).toHaveLength(1)
    expect(active[0]!.nameMatches.length).toBeGreaterThan(0)
    expect(active[0]!.contentSnippets).toHaveLength(0)
  })

  it('matches content only when searchByContent is true', () => {
    const notes = [note({ id: '1', content: 'Hello\nWorld' })]
    const { active } = searchNotes(notes, 'World', false, true)
    expect(active).toHaveLength(1)
    expect(active[0]!.nameMatches).toHaveLength(0)
    expect(active[0]!.contentSnippets.length).toBeGreaterThan(0)
  })

  it('does not match first line as content', () => {
    const notes = [note({ id: '1', content: 'Hello\nWorld' })]
    const { active } = searchNotes(notes, 'Hello', false, true)
    expect(active).toHaveLength(0)
  })

  it('skips child notes', () => {
    const notes = [note({ id: 'child', content: 'Match this', parentNoteId: 'parent' })]
    const { active } = searchNotes(notes, 'Match', true, true)
    expect(active).toHaveLength(0)
  })

  // ── case insensitivity ──

  it('is case insensitive', () => {
    const notes = [note({ id: '1', content: 'Hello World' })]
    const { active } = searchNotes(notes, 'hello', true, false)
    expect(active).toHaveLength(1)
  })

  it('reports correct match positions for mixed case', () => {
    const notes = [note({ id: '1', content: 'TaskBrain App' })]
    const { active } = searchNotes(notes, 'taskbrain', true, false)
    expect(active).toHaveLength(1)
    const match = active[0]!.nameMatches[0]!
    expect(match.matchStart).toBe(0)
    expect(match.matchEnd).toBe(9)
  })

  // ── deleted notes separation ──

  it('separates active and deleted results', () => {
    const notes = [
      note({ id: 'active', content: 'Find me' }),
      note({ id: 'deleted', content: 'Find me', state: 'deleted' }),
    ]
    const { active, deleted } = searchNotes(notes, 'Find', true, false)
    expect(active).toHaveLength(1)
    expect(active[0]!.note.id).toBe('active')
    expect(deleted).toHaveLength(1)
    expect(deleted[0]!.note.id).toBe('deleted')
  })

  // ── ordering ──

  it('sorts active results by updatedAt descending', () => {
    const notes = [
      note({ id: 'old', content: 'Find', updatedAt: ts(1000) }),
      note({ id: 'new', content: 'Find', updatedAt: ts(2000) }),
    ]
    const { active } = searchNotes(notes, 'Find', true, false)
    expect(active.map((r) => r.note.id)).toEqual(['new', 'old'])
  })

  it('sorts deleted results by updatedAt descending', () => {
    const notes = [
      note({ id: 'old', content: 'Find', state: 'deleted', updatedAt: ts(1000) }),
      note({ id: 'new', content: 'Find', state: 'deleted', updatedAt: ts(2000) }),
    ]
    const { deleted } = searchNotes(notes, 'Find', true, false)
    expect(deleted.map((r) => r.note.id)).toEqual(['new', 'old'])
  })

  // ── name match positions ──

  it('reports correct match position within name', () => {
    const notes = [note({ id: '1', content: 'Hello World' })]
    const { active } = searchNotes(notes, 'World', true, false)
    const match = active[0]!.nameMatches[0]!
    expect(match.lineIndex).toBe(0)
    expect(match.matchStart).toBe(6)
    expect(match.matchEnd).toBe(11)
  })

  it('finds multiple matches in same line', () => {
    const notes = [note({ id: '1', content: 'aba aba' })]
    const { active } = searchNotes(notes, 'aba', true, false)
    expect(active[0]!.nameMatches).toHaveLength(2)
    expect(active[0]!.nameMatches[0]!.matchStart).toBe(0)
    expect(active[0]!.nameMatches[1]!.matchStart).toBe(4)
  })

  // ── content snippets ──

  it('includes 2 lines of context above and below', () => {
    const lines = Array.from({ length: 11 }, (_, i) => `Line ${i}`)
    const notes = [note({ id: '1', content: lines.join('\n') })]
    // "Line 5" is at index 5
    const { active } = searchNotes(notes, 'Line 5', false, true)
    expect(active).toHaveLength(1)
    const snippet = active[0]!.contentSnippets[0]!
    // Context: lines 3,4,5,6,7
    expect(snippet.lines).toHaveLength(5)
    expect(snippet.lines[0]!.lineIndex).toBe(3)
    expect(snippet.lines[4]!.lineIndex).toBe(7)
  })

  it('clamps context at start of content (never includes title)', () => {
    const notes = [note({ id: '1', content: 'Title\nLine 1\nLine 2\nLine 3' })]
    const { active } = searchNotes(notes, 'Line 1', false, true)
    const snippet = active[0]!.contentSnippets[0]!
    expect(snippet.lines[0]!.lineIndex).toBe(1)
  })

  it('clamps context at end of content', () => {
    const notes = [note({ id: '1', content: 'Title\nLine 1\nLine 2\nLast' })]
    const { active } = searchNotes(notes, 'Last', false, true)
    const snippet = active[0]!.contentSnippets[0]!
    expect(snippet.lines[snippet.lines.length - 1]!.lineIndex).toBe(3)
  })

  it('limits to max 3 content matches per note', () => {
    const lines = ['Title', ...Array.from({ length: 10 }, () => 'match')]
    const notes = [note({ id: '1', content: lines.join('\n') })]
    const { active } = searchNotes(notes, 'match', false, true)
    const totalMatches = active[0]!.contentSnippets.reduce(
      (sum, s) => sum + s.matches.length,
      0,
    )
    expect(totalMatches).toBeLessThanOrEqual(3)
  })

  it('merges overlapping context ranges into single snippet', () => {
    const notes = [note({ id: '1', content: 'Title\nmatch1\nmatch2\nLine 3\nLine 4' })]
    const { active } = searchNotes(notes, 'match', false, true)
    expect(active[0]!.contentSnippets).toHaveLength(1)
    expect(active[0]!.contentSnippets[0]!.matches).toHaveLength(2)
  })

  it('produces separate snippets for distant matches', () => {
    const lines = ['Title', 'match_first']
    for (let i = 0; i < 10; i++) lines.push(`filler ${i}`)
    lines.push('match_second')
    const notes = [note({ id: '1', content: lines.join('\n') })]
    const { active } = searchNotes(notes, 'match_', false, true)
    expect(active[0]!.contentSnippets).toHaveLength(2)
  })

  // ── edge cases ──

  it('returns empty when no notes match', () => {
    const notes = [note({ id: '1', content: 'Hello' })]
    const { active, deleted } = searchNotes(notes, 'xyz', true, true)
    expect(active).toHaveLength(0)
    expect(deleted).toHaveLength(0)
  })

  it('returns empty for empty notes list', () => {
    const { active, deleted } = searchNotes([], 'test', true, true)
    expect(active).toHaveLength(0)
    expect(deleted).toHaveLength(0)
  })

  it('single-line note has no content matches', () => {
    const notes = [note({ id: '1', content: 'Only title' })]
    const { active } = searchNotes(notes, 'Only', false, true)
    expect(active).toHaveLength(0)
  })

  it('matches both name and content in same note', () => {
    const notes = [note({ id: '1', content: 'Find me\nAlso find me here' })]
    const { active } = searchNotes(notes, 'find', true, true)
    expect(active).toHaveLength(1)
    expect(active[0]!.nameMatches.length).toBeGreaterThan(0)
    expect(active[0]!.contentSnippets.length).toBeGreaterThan(0)
  })
})
