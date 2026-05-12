import { describe, it, expect, vi, beforeEach } from 'vitest'

// Pin the contract that every notes-collection write path calls
// UserDocSignal.bump exactly once at the end. The bump is what tells other
// clients to delta-pull, so a write path that skips it makes the change
// invisible to peers until the next foreground count() detection.
vi.mock('../../data/UserDocSignal', () => {
  return {
    UserDocSignal: {
      bump: vi.fn(async () => undefined),
      ensureExists: vi.fn(async () => undefined),
    },
  }
})

vi.mock('firebase/firestore', () => {
  return {
    collection: vi.fn(() => 'notesCollection'),
    doc: vi.fn(() => ({ id: 'new-doc-id' })),
    getDoc: vi.fn(),
    getDocs: vi.fn(),
    query: vi.fn(),
    where: vi.fn(),
    addDoc: vi.fn(async () => ({ id: 'new-note-id' })),
    serverTimestamp: vi.fn(() => 'SERVER_TIMESTAMP'),
    writeBatch: vi.fn(() => ({
      set: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      commit: vi.fn(async () => undefined),
    })),
    updateDoc: vi.fn(async () => undefined),
  }
})

const { UserDocSignal } = await import('../../data/UserDocSignal')
const { NoteRepository } = await import('../../data/NoteRepository')
const fs = await import('firebase/firestore')

import type { Firestore } from 'firebase/firestore'
import type { Auth } from 'firebase/auth'

const USER_ID = 'uid-signal-bump'

let repo: InstanceType<typeof NoteRepository>

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(fs.getDocs).mockResolvedValue({ docs: [], empty: true, size: 0 } as never)
  const db = {} as Firestore
  const auth = { currentUser: { uid: USER_ID } } as unknown as Auth
  repo = new NoteRepository(db, auth)
})

describe('NoteRepository write paths fire UserDocSignal.bump', () => {
  it('createNote bumps after addDoc returns', async () => {
    await repo.createNote()
    expect(fs.addDoc).toHaveBeenCalled()
    expect(UserDocSignal.bump).toHaveBeenCalledTimes(1)
    expect(UserDocSignal.bump).toHaveBeenCalledWith(expect.anything(), USER_ID)
  })

  it('createMultiLineNote (single line) bumps once', async () => {
    await repo.createMultiLineNote('just one line')
    expect(UserDocSignal.bump).toHaveBeenCalledTimes(1)
  })

  it('createMultiLineNote (multi line via batch) bumps once per commit', async () => {
    await repo.createMultiLineNote('parent\n\tchild')
    // One bump, not one per doc — the batch is one logical save.
    expect(UserDocSignal.bump).toHaveBeenCalledTimes(1)
  })

  it('updateShowCompleted bumps after updateDoc', async () => {
    await repo.updateShowCompleted('note-1', true)
    expect(fs.updateDoc).toHaveBeenCalled()
    expect(UserDocSignal.bump).toHaveBeenCalledTimes(1)
  })
})
