import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NoteStore } from '../../data/NoteStore'
import { note } from '../factories'

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

  it('detach resets error state', () => {
    // We can't set error directly (it's set by snapshot listener error),
    // but detach() should reset any error state and notify error listeners.
    const errorListener = vi.fn()
    store.subscribeError(errorListener)
    store.detach()
    // detach() always emits to error listeners
    expect(errorListener).toHaveBeenCalledTimes(1)
    expect(store.getErrorSnapshot()).toBeNull()
  })

  it('unsubscribed error listener is not called on detach', () => {
    const listener = vi.fn()
    const unsub = store.subscribeError(listener)
    unsub()
    store.detach()
    expect(listener).not.toHaveBeenCalled()
  })

  it('detach resets both note and error listeners', () => {
    const noteListener = vi.fn()
    const errorListener = vi.fn()
    store.subscribe(noteListener)
    store.subscribeError(errorListener)

    store.addNote(note({ id: 'a', content: 'test' }))
    expect(noteListener).toHaveBeenCalledTimes(1)

    store.detach()
    expect(noteListener).toHaveBeenCalledTimes(2) // addNote + detach
    expect(errorListener).toHaveBeenCalledTimes(1) // detach
  })
})
