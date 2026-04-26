import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Note } from '../../data/Note'
import { NoteStore } from '../../data/NoteStore'
import { note } from '../factories'

const noteA = note({ id: 'a', content: 'Alpha' })
const noteB = note({ id: 'b', content: 'Beta' })

function populateStore(store: NoteStore, notes: Note[]) {
  for (const n of notes) {
    store.addNote(n)
  }
}

describe('NoteStore.updateNoteSilently', () => {
  let store: NoteStore

  beforeEach(() => {
    store = new NoteStore()
    populateStore(store, [noteA, noteB])
  })

  it('updates the note visible via getSnapshot', () => {
    const updated = { ...noteA, content: 'Updated silently' }
    store.updateNoteSilently('a', updated)
    expect(store.getSnapshot()[0]).toEqual(updated)
  })

  it('does not notify subscribers', () => {
    const listener = vi.fn()
    store.subscribe(listener)
    store.updateNoteSilently('a', { ...noteA, content: 'Silent' })
    expect(listener).not.toHaveBeenCalled()
  })

  it('does nothing for unknown ID', () => {
    const before = store.getSnapshot()
    store.updateNoteSilently('unknown', note({ id: 'unknown' }))
    // Snapshot reference unchanged — no new array was created
    expect(store.getSnapshot()).toBe(before)
  })

  it('updated note is visible to getNoteById', () => {
    const updated = { ...noteA, content: 'Silent update' }
    store.updateNoteSilently('a', updated)
    expect(store.getNoteById('a')).toEqual(updated)
  })

  it('subsequent updateNote emits to listeners after silent update', () => {
    const listener = vi.fn()
    store.subscribe(listener)

    store.updateNoteSilently('a', { ...noteA, content: 'Silent' })
    expect(listener).not.toHaveBeenCalled()

    store.updateNote('b', { ...noteB, content: 'Loud' })
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('creates a new array reference', () => {
    const before = store.getSnapshot()
    store.updateNoteSilently('a', { ...noteA, content: 'Changed' })
    expect(store.getSnapshot()).not.toBe(before)
  })
})
