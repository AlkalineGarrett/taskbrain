import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NoteStore } from '../../data/NoteStore'
import type { Note } from '../../data/Note'
import { note } from '../factories'

const noteA = note({ id: 'a', content: 'Alpha' })
const noteB = note({ id: 'b', content: 'Beta' })
const noteC = note({ id: 'c', content: 'Charlie' })

/** Helper: pre-populate a store by adding notes directly. */
function populateStore(store: NoteStore, notes: Note[]) {
  for (const n of notes) {
    store.addNote(n)
  }
}

describe('NoteStore', () => {
  let store: NoteStore

  beforeEach(() => {
    store = new NoteStore()
    populateStore(store, [noteA, noteB])
  })

  describe('getSnapshot and addNote', () => {
    it('returns added notes', () => {
      expect(store.getSnapshot()).toEqual([noteA, noteB])
    })

    it('appends new notes', () => {
      store.addNote(noteC)
      expect(store.getSnapshot()).toHaveLength(3)
      expect(store.getSnapshot()[2]).toEqual(noteC)
    })

    it('returns empty array initially', () => {
      const empty = new NoteStore()
      expect(empty.getSnapshot()).toEqual([])
    })
  })

  describe('subscribe', () => {
    it('notifies listeners on addNote', () => {
      const listener = vi.fn()
      store.subscribe(listener)
      store.addNote(noteC)
      expect(listener).toHaveBeenCalledTimes(1)
    })

    it('notifies listeners on updateNote', () => {
      const listener = vi.fn()
      store.subscribe(listener)
      store.updateNote('a', { ...noteA, content: 'Updated' })
      expect(listener).toHaveBeenCalledTimes(1)
    })

    it('unsubscribe stops notifications', () => {
      const listener = vi.fn()
      const unsub = store.subscribe(listener)
      unsub()
      store.updateNote('a', { ...noteA, content: 'Updated' })
      expect(listener).not.toHaveBeenCalled()
    })
  })

  describe('updateNote', () => {
    it('replaces the note with matching ID', () => {
      const updated = { ...noteA, content: 'New content' }
      store.updateNote('a', updated)
      expect(store.getSnapshot()[0]).toEqual(updated)
      expect(store.getSnapshot()[1]).toEqual(noteB)
    })

    it('returns a new array reference', () => {
      const before = store.getSnapshot()
      store.updateNote('a', { ...noteA, content: 'Changed' })
      expect(store.getSnapshot()).not.toBe(before)
    })

    it('does nothing for unknown ID', () => {
      const listener = vi.fn()
      store.subscribe(listener)
      store.updateNote('unknown', note({ id: 'unknown' }))
      expect(listener).not.toHaveBeenCalled()
    })
  })

  describe('removeNote', () => {
    it('removes the note with matching ID', () => {
      store.removeNote('a')
      expect(store.getSnapshot()).toEqual([noteB])
    })

    it('does nothing for unknown ID', () => {
      const listener = vi.fn()
      store.subscribe(listener)
      store.removeNote('unknown')
      expect(listener).not.toHaveBeenCalled()
    })
  })

  describe('getNoteById', () => {
    it('returns the note', () => {
      expect(store.getNoteById('b')).toEqual(noteB)
    })

    it('returns undefined for unknown ID', () => {
      expect(store.getNoteById('unknown')).toBeUndefined()
    })
  })

  describe('clear', () => {
    it('empties the store', () => {
      store.clear()
      expect(store.getSnapshot()).toEqual([])
    })

    it('notifies listeners', () => {
      const listener = vi.fn()
      store.subscribe(listener)
      store.clear()
      expect(listener).toHaveBeenCalledTimes(1)
    })
  })

  describe('trackSave and awaitPendingSave', () => {
    it('awaitPendingSave resolves immediately when no pending save', async () => {
      await store.awaitPendingSave('a')
    })

    it('awaitPendingSave waits for tracked save to complete', async () => {
      let resolveSave!: () => void
      const savePromise = new Promise<void>(r => { resolveSave = r })

      store.trackSave('a', savePromise)

      let awaited = false
      const awaitPromise = store.awaitPendingSave('a').then(() => { awaited = true })

      await Promise.resolve()
      expect(awaited).toBe(false)

      resolveSave()
      await awaitPromise
      expect(awaited).toBe(true)
    })

    it('cleans up after save completes', async () => {
      const savePromise = Promise.resolve()
      store.trackSave('a', savePromise)
      await savePromise
      await new Promise(r => setTimeout(r, 0))
      await store.awaitPendingSave('a')
    })

    it('latest save replaces previous', async () => {
      let resolveFirst!: () => void
      const first = new Promise<void>(r => { resolveFirst = r })
      let resolveSecond!: () => void
      const second = new Promise<void>(r => { resolveSecond = r })

      store.trackSave('a', first)
      store.trackSave('a', second)

      resolveFirst()
      let awaited = false
      const awaitPromise = store.awaitPendingSave('a').then(() => { awaited = true })
      await Promise.resolve()
      expect(awaited).toBe(false)

      resolveSecond()
      await awaitPromise
      expect(awaited).toBe(true)
    })
  })

  describe('snapshot stability', () => {
    it('returns same reference when no changes', () => {
      const snap1 = store.getSnapshot()
      const snap2 = store.getSnapshot()
      expect(snap1).toBe(snap2)
    })

    it('returns different reference after update', () => {
      const snap1 = store.getSnapshot()
      store.addNote(noteC)
      const snap2 = store.getSnapshot()
      expect(snap1).not.toBe(snap2)
    })
  })
})
