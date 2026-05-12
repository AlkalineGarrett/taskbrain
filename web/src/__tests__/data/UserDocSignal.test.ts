import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Firestore } from 'firebase/firestore'

// Mock firebase/firestore so we can capture setDoc calls and inject a
// failure path. The UserDocSignal module is imported AFTER the mock is set
// up so it picks up the mocked dependencies.
vi.mock('firebase/firestore', () => {
  return {
    doc: vi.fn((_db, ...path: string[]) => ({ path: path.join('/') })),
    serverTimestamp: vi.fn(() => 'SERVER_TIMESTAMP'),
    setDoc: vi.fn(),
  }
})

const { doc: mockDoc, setDoc: mockSetDoc } = await import('firebase/firestore')
const { UserDocSignal } = await import('../../data/UserDocSignal')

describe('UserDocSignal.bump', () => {
  let mockDb: Firestore

  beforeEach(() => {
    vi.clearAllMocks()
    mockDb = {} as Firestore
  })

  it('writes lastNoteChange via setDoc(merge=true) to /users/{uid}', async () => {
    vi.mocked(mockSetDoc).mockResolvedValue(undefined as never)
    await UserDocSignal.bump(mockDb, 'uid-1', 'NOTES')
    expect(mockDoc).toHaveBeenCalledWith(mockDb, 'users', 'uid-1')
    expect(mockSetDoc).toHaveBeenCalledTimes(1)
    const [, payload, options] = vi.mocked(mockSetDoc).mock.calls[0]!
    expect(payload).toEqual({ lastNoteChange: 'SERVER_TIMESTAMP' })
    expect(options).toEqual({ merge: true })
  })

  it('swallows errors so a failed bump does not propagate to the writer', async () => {
    vi.mocked(mockSetDoc).mockRejectedValue(new Error('network down'))
    // No throw — promise resolves even on rejection inside.
    await expect(UserDocSignal.bump(mockDb, 'uid-1', 'NOTES')).resolves.toBeUndefined()
  })
})

describe('UserDocSignal.ensureExists', () => {
  let mockDb: Firestore

  beforeEach(() => {
    vi.clearAllMocks()
    mockDb = {} as Firestore
  })

  it('writes a known-shape user doc via setDoc(merge=true)', async () => {
    vi.mocked(mockSetDoc).mockResolvedValue(undefined as never)
    await UserDocSignal.ensureExists(mockDb, 'uid-2')
    expect(mockDoc).toHaveBeenCalledWith(mockDb, 'users', 'uid-2')
    const [, payload, options] = vi.mocked(mockSetDoc).mock.calls[0]!
    expect(payload).toEqual({ uid: 'uid-2' })
    expect(options).toEqual({ merge: true })
  })

  it('swallows errors', async () => {
    vi.mocked(mockSetDoc).mockRejectedValue(new Error('rules denied'))
    await expect(UserDocSignal.ensureExists(mockDb, 'uid-2')).resolves.toBeUndefined()
  })
})
