import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NoteStore } from '../../data/NoteStore'
import type { Note } from '../../data/Note'

function note(overrides: Partial<Note> & { id: string }): Note {
  return {
    userId: '',
    parentNoteId: null,
    content: '',
    createdAt: null,
    updatedAt: null,
    lastAccessedAt: null,
    tags: [],
    containedNotes: [],
    state: null,
    path: '',
    rootNoteId: null,
    showCompleted: true,
    ...overrides,
  }
}

describe('NoteStore error subscription', () => {
  let store: NoteStore

  beforeEach(() => {
    store = new NoteStore()
  })

  it('getErrorSnapshot returns null initially', () => {
    expect(store.getErrorSnapshot()).toBeNull()
  })

  it('clearError when no error does not notify listeners', () => {
    const listener = vi.fn()
    store.subscribeError(listener)
    store.clearError()
    expect(listener).not.toHaveBeenCalled()
  })

  it('subscribeError returns unsubscribe function', () => {
    const listener = vi.fn()
    const unsub = store.subscribeError(listener)
    expect(typeof unsub).toBe('function')
    unsub()
  })

  it('clear resets error state', () => {
    // We can't set error directly (it's set by snapshot listener error),
    // but clear() should reset any error state and notify error listeners.
    const errorListener = vi.fn()
    store.subscribeError(errorListener)
    store.clear()
    // clear() always emits to error listeners
    expect(errorListener).toHaveBeenCalledTimes(1)
    expect(store.getErrorSnapshot()).toBeNull()
  })

  it('unsubscribed error listener is not called on clear', () => {
    const listener = vi.fn()
    const unsub = store.subscribeError(listener)
    unsub()
    store.clear()
    expect(listener).not.toHaveBeenCalled()
  })

  it('clear resets both note and error listeners', () => {
    const noteListener = vi.fn()
    const errorListener = vi.fn()
    store.subscribe(noteListener)
    store.subscribeError(errorListener)

    store.addNote(note({ id: 'a', content: 'test' }))
    expect(noteListener).toHaveBeenCalledTimes(1)

    store.clear()
    expect(noteListener).toHaveBeenCalledTimes(2) // addNote + clear
    expect(errorListener).toHaveBeenCalledTimes(1) // clear
  })
})
