import { describe, it, expect, beforeEach, vi } from 'vitest'
import { SearchHistoryRepository, type SearchHistoryEntry } from '../../data/SearchHistoryRepository'

// Mock Firebase modules
vi.mock('firebase/firestore', () => ({
  collection: vi.fn(),
  addDoc: vi.fn(() => Promise.resolve({ id: 'new_doc' })),
  getDocs: vi.fn(() => Promise.resolve({ docs: [], size: 0 })),
  query: vi.fn(),
  orderBy: vi.fn(),
  limit: vi.fn(),
}))

vi.mock('@/data/NoteStore', () => ({
  noteStore: { raiseWarning: vi.fn() },
}))

vi.mock('../../data/FirestoreUsage', () => ({
  firestoreUsage: { recordRead: vi.fn(), recordWrite: vi.fn() },
}))

function mockLocalStorage() {
  const store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => {
      store[key] = value
    }),
    removeItem: vi.fn((key: string) => {
      delete store[key]
    }),
    clear: vi.fn(() => {
      Object.keys(store).forEach((k) => delete store[k])
    }),
    get length() {
      return Object.keys(store).length
    },
    key: vi.fn(() => null),
  } satisfies Storage
}

function entry(query: string, timestamp: number, criteria?: Record<string, boolean>): SearchHistoryEntry {
  return {
    query,
    criteria: criteria ?? { name: true, content: true },
    timestamp,
  }
}

describe('SearchHistoryRepository', () => {
  let repo: SearchHistoryRepository
  let storage: ReturnType<typeof mockLocalStorage>

  beforeEach(() => {
    storage = mockLocalStorage()
    Object.defineProperty(globalThis, 'localStorage', { value: storage, writable: true })
    repo = new SearchHistoryRepository({} as never, { currentUser: { uid: 'user1' } } as never)
  })

  it('returns empty history when nothing saved', () => {
    expect(repo.getHistory()).toEqual([])
  })

  it('saves and retrieves a single entry', () => {
    repo.saveEntry(entry('test', 1000))
    const history = repo.getHistory()
    expect(history).toHaveLength(1)
    expect(history[0]!.query).toBe('test')
  })

  it('sorts history by timestamp descending', () => {
    repo.saveEntry(entry('old', 1000))
    repo.saveEntry(entry('new', 2000))
    const history = repo.getHistory()
    expect(history[0]!.query).toBe('new')
    expect(history[1]!.query).toBe('old')
  })

  it('deduplicates by query + criteria, keeping latest timestamp', () => {
    repo.saveEntry(entry('test', 1000, { name: true, content: true }))
    repo.saveEntry(entry('test', 2000, { name: true, content: true }))
    const history = repo.getHistory()
    expect(history).toHaveLength(1)
    expect(history[0]!.timestamp).toBe(2000)
  })

  it('does not deduplicate entries with different criteria', () => {
    repo.saveEntry(entry('test', 1000, { name: true, content: true }))
    repo.saveEntry(entry('test', 2000, { name: true, content: false }))
    const history = repo.getHistory()
    expect(history).toHaveLength(2)
  })

  it('limits to 10 entries', () => {
    for (let i = 0; i < 15; i++) {
      repo.saveEntry(entry(`query_${i}`, i * 1000))
    }
    const history = repo.getHistory()
    expect(history).toHaveLength(10)
    // Most recent entries are kept
    expect(history[0]!.query).toBe('query_14')
    expect(history[9]!.query).toBe('query_5')
  })

  it('persists to localStorage', () => {
    repo.saveEntry(entry('test', 1000))
    expect(storage.setItem).toHaveBeenCalledWith(
      'search_history',
      expect.any(String),
    )
    const stored = JSON.parse(storage.setItem.mock.calls[0]![1] as string) as SearchHistoryEntry[]
    expect(stored).toHaveLength(1)
    expect(stored[0]!.query).toBe('test')
  })

  it('reads from localStorage on getHistory', () => {
    const data: SearchHistoryEntry[] = [entry('saved', 5000)]
    storage.setItem('search_history', JSON.stringify(data))
    // Create fresh repo to read from storage
    const freshRepo = new SearchHistoryRepository({} as never, { currentUser: { uid: 'user1' } } as never)
    const history = freshRepo.getHistory()
    expect(history).toHaveLength(1)
    expect(history[0]!.query).toBe('saved')
  })

  it('handles corrupted localStorage gracefully', () => {
    storage.setItem('search_history', 'not valid json!!!')
    const freshRepo = new SearchHistoryRepository({} as never, { currentUser: { uid: 'user1' } } as never)
    expect(freshRepo.getHistory()).toEqual([])
  })

  it('preserves criteria map with arbitrary keys', () => {
    repo.saveEntry(entry('test', 1000, { name: true, content: false, tags: true }))
    const history = repo.getHistory()
    expect(history[0]!.criteria).toEqual({ name: true, content: false, tags: true })
  })
})
