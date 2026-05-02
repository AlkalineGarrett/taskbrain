import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { NoteStore } from '../../data/NoteStore'

describe('NoteStore pending-op echo suppression', () => {
  let store: NoteStore

  beforeEach(() => {
    store = new NoteStore()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('isOurEcho returns true while a registered op is in-window', () => {
    const opId = 'op-1'
    store.registerPendingOp(opId)
    expect(store.isOurEcho(opId)).toBe(true)
  })

  it('isOurEcho returns false for null / undefined / unknown opId', () => {
    expect(store.isOurEcho(null)).toBe(false)
    expect(store.isOurEcho(undefined)).toBe(false)
    expect(store.isOurEcho('never-registered')).toBe(false)
  })

  it('expired opId is treated as not ours and purged on lookup', () => {
    const opId = 'op-stale'
    store.registerPendingOp(opId)
    expect(store.isOurEcho(opId)).toBe(true)

    // Past TTL — entry should be considered expired and cleared.
    vi.advanceTimersByTime(6000)
    expect(store.isOurEcho(opId)).toBe(false)
    // Subsequent calls don't re-resurrect it.
    expect(store.isOurEcho(opId)).toBe(false)
  })

  it('releasePendingOp removes the op after the TTL grace window', () => {
    const opId = 'op-released'
    store.registerPendingOp(opId)
    store.releasePendingOp(opId)

    // Inside the grace window the op is still suppressing echoes — covers
    // the case where the listener delivers a server echo immediately after
    // the commit promise resolves.
    expect(store.isOurEcho(opId)).toBe(true)

    vi.advanceTimersByTime(6000)
    expect(store.isOurEcho(opId)).toBe(false)
  })

  it('multiple opIds coexist independently', () => {
    store.registerPendingOp('op-a')
    store.registerPendingOp('op-b')
    expect(store.isOurEcho('op-a')).toBe(true)
    expect(store.isOurEcho('op-b')).toBe(true)
    expect(store.isOurEcho('op-c')).toBe(false)
  })
})
