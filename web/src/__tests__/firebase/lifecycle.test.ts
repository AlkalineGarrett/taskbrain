import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { FirebaseApp } from 'firebase/app'
import type { Auth } from 'firebase/auth'
import type { Firestore } from 'firebase/firestore'

const { initializeFirestoreSpy, terminateSpy, connectFirestoreEmulatorSpy } = vi.hoisted(() => ({
  initializeFirestoreSpy: vi.fn<(...args: unknown[]) => Firestore>(),
  terminateSpy: vi.fn<() => Promise<void>>(),
  connectFirestoreEmulatorSpy: vi.fn(),
}))

// Mock the firestore module so we can drive the lifecycle without actually
// reaching the network or IndexedDB. Provide just enough surface for the
// lifecycle module to compile against the real type imports.
vi.mock('firebase/firestore', () => ({
  initializeFirestore: initializeFirestoreSpy,
  terminate: terminateSpy,
  connectFirestoreEmulator: connectFirestoreEmulatorSpy,
  memoryLocalCache: vi.fn(() => ({ kind: 'memory' })),
  persistentLocalCache: vi.fn(() => ({ kind: 'persistent' })),
  persistentMultipleTabManager: vi.fn(() => ({ kind: 'tabManager' })),
}))

// Each test gets a fresh module to reset the singleton.
async function loadLifecycle() {
  vi.resetModules()
  const mod = await import('@/firebase/lifecycle')
  return mod
}

describe('FirestoreLifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    let counter = 0
    initializeFirestoreSpy.mockImplementation(() => ({ id: ++counter } as unknown as Firestore))
    terminateSpy.mockResolvedValue(undefined)
  })

  it('configure must precede start', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    await expect(firestoreLifecycle.start()).rejects.toThrow(/configure/)
  })

  it('start runs all attach handlers with the new Firestore instance', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const attach1 = vi.fn()
    const detach1 = vi.fn()
    const attach2 = vi.fn()
    const detach2 = vi.fn()
    firestoreLifecycle.subscribe(attach1, detach1)
    firestoreLifecycle.subscribe(attach2, detach2)

    await firestoreLifecycle.start()

    expect(attach1).toHaveBeenCalledTimes(1)
    expect(attach2).toHaveBeenCalledTimes(1)
    expect(detach1).not.toHaveBeenCalled()
    expect(detach2).not.toHaveBeenCalled()
    expect(firestoreLifecycle.state).toBe('started')
  })

  it('subscribe after start invokes attach immediately with current context', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)
    await firestoreLifecycle.start()

    const attach = vi.fn()
    const detach = vi.fn()
    firestoreLifecycle.subscribe(attach, detach)

    expect(attach).toHaveBeenCalledTimes(1)
  })

  it('stop runs all detach handlers, then terminates', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const attach = vi.fn()
    const detach = vi.fn()
    firestoreLifecycle.subscribe(attach, detach)

    await firestoreLifecycle.start()
    await firestoreLifecycle.stop()

    expect(detach).toHaveBeenCalledTimes(1)
    expect(terminateSpy).toHaveBeenCalledTimes(1)
    // detach must be called before terminate so subscribers can drain
    // their listener registrations on the still-live Firestore instance.
    const detachOrder = detach.mock.invocationCallOrder[0]!
    const terminateOrder = terminateSpy.mock.invocationCallOrder[0]!
    expect(detachOrder).toBeLessThan(terminateOrder)
    expect(firestoreLifecycle.state).toBe('stopped')
  })

  it('start is idempotent — second call without intervening stop is a no-op', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const attach = vi.fn()
    const detach = vi.fn()
    firestoreLifecycle.subscribe(attach, detach)

    await firestoreLifecycle.start()
    await firestoreLifecycle.start()

    expect(attach).toHaveBeenCalledTimes(1)
    expect(initializeFirestoreSpy).toHaveBeenCalledTimes(1)
  })

  it('stop is idempotent — second call without intervening start is a no-op', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const detach = vi.fn()
    firestoreLifecycle.subscribe(vi.fn(), detach)

    await firestoreLifecycle.start()
    await firestoreLifecycle.stop()
    await firestoreLifecycle.stop()

    expect(detach).toHaveBeenCalledTimes(1)
    expect(terminateSpy).toHaveBeenCalledTimes(1)
  })

  it('start after stop creates a fresh Firestore and re-attaches subscribers', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const attach = vi.fn()
    const detach = vi.fn()
    firestoreLifecycle.subscribe(attach, detach)

    await firestoreLifecycle.start()
    await firestoreLifecycle.stop()
    await firestoreLifecycle.start()

    expect(attach).toHaveBeenCalledTimes(2)
    expect(detach).toHaveBeenCalledTimes(1)
    expect(initializeFirestoreSpy).toHaveBeenCalledTimes(2)
    // First and second attach receive different Firestore instances because
    // terminate invalidated the prior one — assert the second context's db
    // is not the first.
    const ctx1 = attach.mock.calls[0]![0]
    const ctx2 = attach.mock.calls[1]![0]
    expect(ctx2.db).not.toBe(ctx1.db)
  })

  it('generation increments on every start so consumers can key useMemo', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const initial = firestoreLifecycle.generation
    await firestoreLifecycle.start()
    const afterFirst = firestoreLifecycle.generation
    await firestoreLifecycle.stop()
    await firestoreLifecycle.start()
    const afterSecond = firestoreLifecycle.generation

    expect(afterFirst).toBe(initial + 1)
    expect(afterSecond).toBe(initial + 2)
  })

  it('overlapping start/stop calls are serialized', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const order: string[] = []
    const attach = vi.fn(() => { order.push('attach') })
    const detach = vi.fn(() => { order.push('detach') })
    firestoreLifecycle.subscribe(attach, detach)

    // Fire concurrently — internal serialization should run start, then stop,
    // then start, in order, regardless of microtask scheduling.
    const p1 = firestoreLifecycle.start()
    const p2 = firestoreLifecycle.stop()
    const p3 = firestoreLifecycle.start()
    await Promise.all([p1, p2, p3])

    expect(order).toEqual(['attach', 'detach', 'attach'])
    expect(firestoreLifecycle.state).toBe('started')
  })

  it('requireDb throws when stopped', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)
    expect(() => firestoreLifecycle.requireDb()).toThrow(/not started/)
    await firestoreLifecycle.start()
    expect(firestoreLifecycle.requireDb()).toBeDefined()
    await firestoreLifecycle.stop()
    expect(() => firestoreLifecycle.requireDb()).toThrow(/not started/)
  })

  it('subscribeState fires on each state transition (not synchronously on subscribe)', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, false)

    const observed: string[] = []
    const unsub = firestoreLifecycle.subscribeState(() => {
      observed.push(firestoreLifecycle.state)
    })

    await firestoreLifecycle.start()
    await firestoreLifecycle.stop()

    unsub()
    // No initial sync call (React's useSyncExternalStore expects subscribe
    // to NOT fire on mount). start: 'starting' -> 'started'.
    // stop: 'stopping' -> 'stopped'.
    expect(observed).toEqual(['starting', 'started', 'stopping', 'stopped'])
  })

  it('emulator wiring runs only on the first start', async () => {
    const { firestoreLifecycle } = await loadLifecycle()
    firestoreLifecycle.configure({} as FirebaseApp, {} as Auth, true)

    await firestoreLifecycle.start()
    await firestoreLifecycle.stop()
    await firestoreLifecycle.start()

    expect(connectFirestoreEmulatorSpy).toHaveBeenCalledTimes(1)
  })
})
