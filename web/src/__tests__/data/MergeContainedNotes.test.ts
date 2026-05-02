import { describe, it, expect } from 'vitest'
import { mergeContainedNotes } from '@/data/NoteRepository'

describe('mergeContainedNotes (Phase 4 3-way merge)', () => {
  it('returns local unchanged when base is null', () => {
    // Legacy callers pre-Phase-4 don't track base; treat as no concurrent edit.
    expect(mergeContainedNotes(['a', 'b'], null, ['x', 'y'])).toEqual(['a', 'b'])
  })

  it('returns local unchanged when base equals remote (no concurrent edit)', () => {
    expect(mergeContainedNotes(['a', 'b', 'c'], ['a', 'b', 'c'], ['a', 'b', 'c'])).toEqual(['a', 'b', 'c'])
  })

  it('appends concurrent additions from remote in their relative order', () => {
    // base = [a, b], local = [a, b, x] (we added x), remote = [a, b, y, z] (others added y, z).
    // Result should keep our additions and pick up theirs at the end.
    expect(mergeContainedNotes(['a', 'b', 'x'], ['a', 'b'], ['a', 'b', 'y', 'z'])).toEqual(['a', 'b', 'x', 'y', 'z'])
  })

  it('respects remote removals even when local kept the item', () => {
    // base = [a, b, c], local = [a, b, c] (no change), remote = [a, c] (removed b).
    // Other client removed b → drop from final.
    expect(mergeContainedNotes(['a', 'b', 'c'], ['a', 'b', 'c'], ['a', 'c'])).toEqual(['a', 'c'])
  })

  it('respects local removals even when remote kept the item', () => {
    // base = [a, b, c], local = [a, c] (we removed b), remote = [a, b, c] (no change).
    // Our removal stays; b doesn't reappear.
    expect(mergeContainedNotes(['a', 'c'], ['a', 'b', 'c'], ['a', 'b', 'c'])).toEqual(['a', 'c'])
  })

  it('handles concurrent add and concurrent remove together', () => {
    // base = [a, b], local = [a] (we removed b), remote = [a, b, c] (others added c).
    // Both effects honored: b gone, c appended.
    expect(mergeContainedNotes(['a'], ['a', 'b'], ['a', 'b', 'c'])).toEqual(['a', 'c'])
  })

  it('preserves local order for items present in both', () => {
    // local has reorder [b, a], remote unchanged [a, b]. Last-writer-wins on
    // ordering: keep local's view.
    expect(mergeContainedNotes(['b', 'a'], ['a', 'b'], ['a', 'b'])).toEqual(['b', 'a'])
  })

  it('does not duplicate items present in both local and remote_added', () => {
    // Edge case: an item we added (local-only vs base) was also added by
    // remote (remote-only vs base). Result should include it once.
    expect(mergeContainedNotes(['a', 'x'], ['a'], ['a', 'x'])).toEqual(['a', 'x'])
  })

  it('returns empty when both local and remote dropped the only item', () => {
    expect(mergeContainedNotes([], ['a'], [])).toEqual([])
  })
})
