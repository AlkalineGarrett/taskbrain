import { describe, it, expect, beforeEach } from 'vitest'
import { NoteStore } from '../../data/NoteStore'

describe('NoteStore cut buffer (Phase 5)', () => {
  let store: NoteStore

  beforeEach(() => {
    store = new NoteStore()
  })

  it('records and reclaims a cut by content match', () => {
    store.recordCut('line-1', 'Buy milk')
    expect(store.tryReclaim('Buy milk')).toBe('line-1')
  })

  it('reclaim is one-shot — second match falls through', () => {
    // Same-content cuts must not double-claim a single buffered id; the second
    // paste falls through to sentinel allocation (fresh doc).
    store.recordCut('line-1', 'Buy milk')
    expect(store.tryReclaim('Buy milk')).toBe('line-1')
    expect(store.tryReclaim('Buy milk')).toBeNull()
  })

  it('reclaim returns null when no match', () => {
    store.recordCut('line-1', 'apples')
    expect(store.tryReclaim('oranges')).toBeNull()
    // Original entry still present for a future matching paste.
    expect(store.tryReclaim('apples')).toBe('line-1')
  })

  it('multiple cuts with distinct content coexist', () => {
    store.recordCut('a', 'alpha')
    store.recordCut('b', 'beta')
    store.recordCut('c', 'gamma')
    expect(store.tryReclaim('beta')).toBe('b')
    expect(store.tryReclaim('alpha')).toBe('a')
    expect(store.tryReclaim('gamma')).toBe('c')
  })

  it('getPendingCuts returns a snapshot — mutating it does not affect the buffer', () => {
    store.recordCut('a', 'alpha')
    const snap = store.getPendingCuts()
    snap.delete('a')
    expect(store.tryReclaim('alpha')).toBe('a')
  })

  it('clearPendingCut drops a single entry', () => {
    store.recordCut('a', 'alpha')
    store.clearPendingCut('a')
    expect(store.tryReclaim('alpha')).toBeNull()
  })

  it('recording the same id twice replaces the content', () => {
    // The line was edited between the two cuts (rare but possible — e.g.,
    // rapid undo/redo). The second cut's content wins so reclaim matches it.
    store.recordCut('a', 'old')
    store.recordCut('a', 'new')
    expect(store.tryReclaim('old')).toBeNull()
    expect(store.tryReclaim('new')).toBe('a')
  })
})
