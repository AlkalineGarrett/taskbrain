import { describe, it, expect, vi, beforeEach } from 'vitest'
import { RecentTabsRepository, type RecentTab } from '../../data/RecentTabsRepository'
import type { Firestore } from 'firebase/firestore'
import type { Auth } from 'firebase/auth'

vi.mock('@/firebase/config', () => ({
  // Avoid pulling in real Firebase init at test load time.
  getDb: () => ({}),
  auth: { currentUser: { uid: 'user_1' } },
}))

vi.mock('firebase/firestore', () => ({
  collection: vi.fn(() => 'tabsCollection'),
  doc: vi.fn((_col, noteId) => ({ id: noteId })),
  setDoc: vi.fn(() => Promise.resolve()),
  deleteDoc: vi.fn(() => Promise.resolve()),
  updateDoc: vi.fn(() => Promise.resolve()),
  query: vi.fn((..._args) => 'tabsQuery'),
  orderBy: vi.fn(),
  writeBatch: vi.fn(() => ({
    delete: vi.fn(),
    commit: vi.fn(() => Promise.resolve()),
  })),
  serverTimestamp: vi.fn(() => 'SERVER_TIMESTAMP'),
  onSnapshot: vi.fn(),
}))

const { onSnapshot: mockOnSnapshot, writeBatch: mockWriteBatch } = await import('firebase/firestore')

const USER_ID = 'user_1'
const mockDb = {} as Firestore
const mockAuth = { currentUser: { uid: USER_ID } } as unknown as Auth

/**
 * Build a fake `QuerySnapshot` for the listener callback. Each tab id becomes
 * a doc with a deterministic shape that matches what the repo parses.
 */
function fakeSnapshot(opts: {
  tabIds: string[]
  hasPendingWrites?: boolean
  fromCache?: boolean
}) {
  const docs = opts.tabIds.map((id, i) => ({
    id,
    data: () => ({
      noteId: id,
      displayText: `${id}-text`,
      lastAccessedAt: { toMillis: () => i },
    }),
  }))
  return {
    docs,
    docChanges: () => docs.map((d) => ({ doc: d })),
    metadata: {
      hasPendingWrites: opts.hasPendingWrites ?? false,
      fromCache: opts.fromCache ?? false,
    },
  } as any
}

describe('RecentTabsRepository', () => {
  let repo: RecentTabsRepository
  /** The listener function the repo registered with onSnapshot. */
  let registeredListener: ((snap: ReturnType<typeof fakeSnapshot>) => void) | null = null

  beforeEach(() => {
    vi.clearAllMocks()
    registeredListener = null
    vi.mocked(mockOnSnapshot).mockImplementation((..._args: any[]) => {
      // Signature: onSnapshot(query, onNext, onError). Capture onNext.
      registeredListener = _args[1] as any
      return (() => {}) as any
    })
    repo = new RecentTabsRepository()
    repo.attach(mockDb, mockAuth)
  })

  describe('subscribe + cache updates', () => {
    it('first snapshot populates the cache and fires subscribers', async () => {
      const subscriber = vi.fn()
      repo.subscribe(subscriber)
      void repo.getOpenTabs()
      // Let ensureListenerAttached register its listener.
      await Promise.resolve()
      expect(registeredListener).not.toBeNull()
      registeredListener!(fakeSnapshot({ tabIds: ['a', 'b'], fromCache: true }))
      expect(repo.getCachedTabs().map((t) => t.noteId)).toEqual(['a', 'b'])
      expect(subscriber).toHaveBeenCalledTimes(1)
    })

    it('processes local-echo snapshots so subscribers see this device’s own writes immediately', async () => {
      // Bug fix: the listener used to skip snapshots whose hasPendingWrites was
      // true after the first delivery, which delayed the cache by a server
      // roundtrip and let RecentTabsBar drop optimistically-added tabs on its
      // mount-time merge. Process them.
      void repo.getOpenTabs()
      await Promise.resolve()
      registeredListener!(fakeSnapshot({ tabIds: ['a'] }))

      const subscriber = vi.fn()
      repo.subscribe(subscriber)

      registeredListener!(fakeSnapshot({ tabIds: ['b', 'a'], hasPendingWrites: true }))
      expect(repo.getCachedTabs().map((t) => t.noteId)).toEqual(['b', 'a'])
      expect(subscriber).toHaveBeenCalledTimes(1)
    })

    it('unsubscribe stops further notifications', async () => {
      void repo.getOpenTabs()
      await Promise.resolve()
      registeredListener!(fakeSnapshot({ tabIds: ['a'] }))

      const subscriber = vi.fn()
      const unsubscribe = repo.subscribe(subscriber)
      unsubscribe()

      registeredListener!(fakeSnapshot({ tabIds: ['b'] }))
      expect(subscriber).not.toHaveBeenCalled()
    })

    it('detach drops cached tabs and listener but keeps React subscribers', async () => {
      void repo.getOpenTabs()
      await Promise.resolve()
      registeredListener!(fakeSnapshot({ tabIds: ['a', 'b'] }))
      const subscriber = vi.fn()
      repo.subscribe(subscriber)

      repo.detach()

      expect(repo.getCachedTabs()).toEqual([])
      // Subscribers persist across detach so that on the next lifecycle.start
      // reattach, the existing React components seamlessly receive updates
      // without re-subscribing — see firebase/lifecycle.ts rationale.
      vi.mocked(mockOnSnapshot).mockImplementation((..._args: any[]) => {
        registeredListener = _args[1] as any
        return (() => {}) as any
      })
      repo.attach(mockDb, mockAuth)
      void repo.getOpenTabs()
      await Promise.resolve()
      registeredListener!(fakeSnapshot({ tabIds: ['c'] }))
      // The pre-detach subscriber MUST receive post-reattach updates.
      expect(subscriber).toHaveBeenCalledTimes(1)
      expect(repo.getCachedTabs().map((t) => t.noteId)).toEqual(['c'])
    })
  })

  describe('enforceTabLimit', () => {
    it('trims excess entries past MAX_STORED after addOrUpdateTab', async () => {
      const batchDelete = vi.fn()
      const batchCommit = vi.fn(() => Promise.resolve())
      vi.mocked(mockWriteBatch).mockReturnValue({
        delete: batchDelete,
        commit: batchCommit,
      } as any)

      // addOrUpdateTab awaits enforceTabLimit, which awaits the first snapshot.
      // Kick the call off, then deliver an oversized snapshot so the awaiter
      // proceeds with the full set; let microtasks drain so the batch runs.
      const overflowing: RecentTab[] = Array.from({ length: 12 }, (_, i) => ({
        noteId: `n${i}`,
        displayText: `n${i}`,
        lastAccessedAt: null,
      }))
      const writePromise = repo.addOrUpdateTab('fresh', 'Fresh')
      await Promise.resolve()
      expect(registeredListener).not.toBeNull()
      registeredListener!(fakeSnapshot({ tabIds: overflowing.map((t) => t.noteId) }))
      await writePromise

      // 12 cached − 10 stored = 2 deletes.
      expect(batchDelete).toHaveBeenCalledTimes(2)
      expect(batchCommit).toHaveBeenCalledTimes(1)
    })
  })
})
