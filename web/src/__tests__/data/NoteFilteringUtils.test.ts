import { describe, it, expect } from 'vitest'
import {
  filterTopLevelNotes,
  sortByUpdatedAtDescending,
  filterAndSortNotes,
  filterAndSortDeletedNotes,
} from '../../data/NoteFilteringUtils'
import type { Note } from '../../data/Note'
import { note } from '../factories'

function ts(millis: number) {
  return { toMillis: () => millis } as Note['updatedAt']
}

describe('filterTopLevelNotes', () => {
  it('excludes child notes', () => {
    const notes = [
      note({ id: 'note_1' }),
      note({ id: 'note_2', parentNoteId: 'parent_1' }),
      note({ id: 'note_3' }),
    ]
    const filtered = filterTopLevelNotes(notes)
    expect(filtered.map((n) => n.id)).toEqual(['note_1', 'note_3'])
  })

  it('excludes deleted notes', () => {
    const notes = [
      note({ id: 'note_1' }),
      note({ id: 'note_2', state: 'deleted' }),
      note({ id: 'note_3' }),
    ]
    const filtered = filterTopLevelNotes(notes)
    expect(filtered.map((n) => n.id)).toEqual(['note_1', 'note_3'])
  })

  it('excludes both children and deleted', () => {
    const notes = [
      note({ id: 'note_1' }),
      note({ id: 'note_2', parentNoteId: 'parent_1' }),
      note({ id: 'note_3', state: 'deleted' }),
      note({ id: 'note_4' }),
    ]
    const filtered = filterTopLevelNotes(notes)
    expect(filtered.map((n) => n.id)).toEqual(['note_1', 'note_4'])
  })

  it('with empty list returns empty', () => {
    expect(filterTopLevelNotes([])).toEqual([])
  })
})

describe('sortByUpdatedAtDescending', () => {
  it('sorts most recent first', () => {
    const now = Date.now()
    const notes = [
      note({ id: 'oldest', updatedAt: ts(now - 10000) }),
      note({ id: 'middle', updatedAt: ts(now - 5000) }),
      note({ id: 'newest', updatedAt: ts(now) }),
    ]
    const sorted = sortByUpdatedAtDescending(notes)
    expect(sorted.map((n) => n.id)).toEqual(['newest', 'middle', 'oldest'])
  })

  it('handles null timestamps', () => {
    const notes = [
      note({ id: 'null_1', updatedAt: null }),
      note({ id: 'has_time', updatedAt: ts(Date.now()) }),
      note({ id: 'null_2', updatedAt: null }),
    ]
    const sorted = sortByUpdatedAtDescending(notes)
    expect(sorted[0]!.id).toBe('has_time')
  })
})

describe('filterAndSortNotes', () => {
  it('filters then sorts', () => {
    const now = Date.now()
    const notes = [
      note({ id: 'oldest', updatedAt: ts(now - 10000) }),
      note({ id: 'child', parentNoteId: 'parent', updatedAt: ts(now) }),
      note({ id: 'deleted', state: 'deleted', updatedAt: ts(now) }),
      note({ id: 'newest', updatedAt: ts(now) }),
      note({ id: 'middle', updatedAt: ts(now - 5000) }),
    ]
    const result = filterAndSortNotes(notes)
    expect(result.map((n) => n.id)).toEqual(['newest', 'middle', 'oldest'])
  })

  it('returns empty when all filtered', () => {
    const notes = [
      note({ id: 'child', parentNoteId: 'parent_1' }),
      note({ id: 'deleted', state: 'deleted' }),
    ]
    expect(filterAndSortNotes(notes)).toEqual([])
  })
})

describe('filterAndSortDeletedNotes', () => {
  it('includes deleted top-level notes', () => {
    const now = Date.now()
    const notes = [
      note({ id: 'live', updatedAt: ts(now) }),
      note({ id: 'gone', state: 'deleted', updatedAt: ts(now) }),
    ]
    expect(filterAndSortDeletedNotes(notes).map((n) => n.id)).toEqual(['gone'])
  })

  it('excludes deleted child lines (parentNoteId set)', () => {
    // Removed child lines keep parentNoteId on soft-delete so the deleted-section
    // view doesn't surface them — only deleted parent notes should appear.
    const notes = [
      note({ id: 'parent_gone', state: 'deleted' }),
      note({ id: 'child_gone', state: 'deleted', parentNoteId: 'some_parent' }),
    ]
    expect(filterAndSortDeletedNotes(notes).map((n) => n.id)).toEqual(['parent_gone'])
  })

  it('sorts most recent first', () => {
    const now = Date.now()
    const notes = [
      note({ id: 'older', state: 'deleted', updatedAt: ts(now - 5000) }),
      note({ id: 'newer', state: 'deleted', updatedAt: ts(now) }),
    ]
    expect(filterAndSortDeletedNotes(notes).map((n) => n.id)).toEqual(['newer', 'older'])
  })
})
