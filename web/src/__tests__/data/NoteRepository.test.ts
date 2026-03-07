import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NoteRepository } from '../../data/NoteRepository'
import type { Note, NoteLine } from '../../data/Note'
import type { Firestore, DocumentReference } from 'firebase/firestore'
import type { Auth } from 'firebase/auth'

// Mock firebase/firestore
vi.mock('firebase/firestore', () => {
  const mockCollection = vi.fn()
  const mockDoc = vi.fn()
  const mockGetDoc = vi.fn()
  const mockGetDocs = vi.fn()
  const mockQuery = vi.fn()
  const mockWhere = vi.fn()
  const mockAddDoc = vi.fn()
  const mockRunTransaction = vi.fn()
  const mockServerTimestamp = vi.fn(() => 'SERVER_TIMESTAMP')
  const mockWriteBatch = vi.fn()
  const mockUpdateDoc = vi.fn()

  return {
    collection: mockCollection,
    doc: mockDoc,
    getDoc: mockGetDoc,
    getDocs: mockGetDocs,
    query: mockQuery,
    where: mockWhere,
    addDoc: mockAddDoc,
    runTransaction: mockRunTransaction,
    serverTimestamp: mockServerTimestamp,
    writeBatch: mockWriteBatch,
    updateDoc: mockUpdateDoc,
  }
})

const USER_ID = 'test_user_id'

let mockDb: Firestore
let mockAuth: Auth
let repository: NoteRepository

// Access mocked firebase functions
const {
  doc: mockDoc,
  getDoc: mockGetDoc,
  getDocs: mockGetDocs,
  addDoc: mockAddDoc,
  runTransaction: mockRunTransaction,
  writeBatch: mockWriteBatch,
  collection: mockCollection,
} = await import('firebase/firestore')

function mockDocument(noteId: string, noteData: Record<string, unknown> | null) {
  const ref = { id: noteId } as DocumentReference
  vi.mocked(mockDoc).mockReturnValue(ref)

  vi.mocked(mockGetDoc).mockImplementation(async (docRef: unknown) => {
    const r = docRef as { id: string }
    if (r.id === noteId) {
      return {
        exists: () => noteData !== null,
        data: () => noteData,
        id: noteId,
      } as any
    }
    return { exists: () => false, data: () => null, id: r.id } as any
  })

  return ref
}

function mockDocumentMultiple(docs: Map<string, Record<string, unknown> | null>) {
  vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
    const id = args.length > 1 ? args[args.length - 1] : `auto_${Math.random()}`
    return { id } as DocumentReference
  })

  vi.mocked(mockGetDoc).mockImplementation(async (docRef: unknown) => {
    const r = docRef as { id: string }
    const data = docs.get(r.id)
    return {
      exists: () => data !== undefined && data !== null,
      data: () => data,
      id: r.id,
    } as any
  })
}

beforeEach(() => {
  vi.clearAllMocks()

  mockDb = {} as Firestore
  mockAuth = {
    currentUser: { uid: USER_ID },
  } as unknown as Auth

  vi.mocked(mockCollection).mockReturnValue('notesCollection' as any)

  repository = new NoteRepository(mockDb, mockAuth)
})

function signOut() {
  ;(mockAuth as any).currentUser = null
}

describe('NoteRepository', () => {
  // region Auth Tests

  describe('auth checks', () => {
    it('loadNoteWithChildren fails when user is not signed in', async () => {
      signOut()
      await expect(repository.loadNoteWithChildren('note_1')).rejects.toThrow('User not signed in')
    })

    it('createNote fails when user is not signed in', async () => {
      signOut()
      await expect(repository.createNote()).rejects.toThrow('User not signed in')
    })

    it('createMultiLineNote fails when user is not signed in', async () => {
      signOut()
      await expect(repository.createMultiLineNote('Line 1\nLine 2')).rejects.toThrow('User not signed in')
    })
  })

  // endregion

  // region Load Tests

  describe('loadNoteWithChildren', () => {
    it('returns empty line when document does not exist', async () => {
      vi.mocked(mockDoc).mockReturnValue({ id: 'note_1' } as any)
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => false,
        data: () => null,
        id: 'note_1',
      } as any)

      const lines = await repository.loadNoteWithChildren('note_1')

      expect(lines).toEqual([{ content: '', noteId: 'note_1' }])
    })

    it('does not add trailing empty line when note is single empty line', async () => {
      vi.mocked(mockDoc).mockReturnValue({ id: 'note_1' } as any)
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: '', containedNotes: [] }),
        id: 'note_1',
      } as any)

      const lines = await repository.loadNoteWithChildren('note_1')

      expect(lines).toHaveLength(1)
      expect(lines[0]!.content).toBe('')
      expect(lines[0]!.noteId).toBe('note_1')
    })

    it('returns parent content with trailing empty line', async () => {
      vi.mocked(mockDoc).mockReturnValue({ id: 'note_1' } as any)
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: 'Parent content', containedNotes: [] }),
        id: 'note_1',
      } as any)

      const lines = await repository.loadNoteWithChildren('note_1')

      expect(lines).toHaveLength(2)
      expect(lines[0]!.content).toBe('Parent content')
      expect(lines[0]!.noteId).toBe('note_1')
      expect(lines[1]!.content).toBe('')
      expect(lines[1]!.noteId).toBeNull()
    })

    it('returns parent and children in order with trailing empty line', async () => {
      const docs = new Map<string, Record<string, unknown>>([
        ['parent', { content: 'Parent', containedNotes: ['child_1', 'child_2'] }],
        ['child_1', { content: 'Child 1' }],
        ['child_2', { content: 'Child 2' }],
      ])
      mockDocumentMultiple(docs)

      const lines = await repository.loadNoteWithChildren('parent')

      expect(lines).toEqual([
        { content: 'Parent', noteId: 'parent' },
        { content: 'Child 1', noteId: 'child_1' },
        { content: 'Child 2', noteId: 'child_2' },
        { content: '', noteId: null },
      ])
    })

    it('treats empty child IDs as spacers', async () => {
      const docs = new Map<string, Record<string, unknown>>([
        ['parent', { content: 'Parent', containedNotes: ['', 'child_1', ''] }],
        ['child_1', { content: 'Child' }],
      ])
      mockDocumentMultiple(docs)

      const lines = await repository.loadNoteWithChildren('parent')

      expect(lines).toHaveLength(5) // parent + 3 children + trailing empty
      expect(lines[1]!.content).toBe('')
      expect(lines[1]!.noteId).toBeNull()
      expect(lines[2]!.content).toBe('Child')
      expect(lines[3]!.content).toBe('')
      expect(lines[4]!.content).toBe('')
      expect(lines[4]!.noteId).toBeNull()
    })
  })

  describe('loadUserNotes', () => {
    it('filters out children and deleted notes', async () => {
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          { id: 'note_1', data: () => ({ userId: USER_ID }) },
          { id: 'note_2', data: () => ({ userId: USER_ID, state: 'deleted' }) },
          { id: 'note_3', data: () => ({ userId: USER_ID, parentNoteId: 'parent' }) },
        ],
      } as any)

      const notes = await repository.loadUserNotes()

      expect(notes).toHaveLength(1)
      expect(notes[0]!.id).toBe('note_1')
    })
  })

  describe('loadNotesWithFullContent', () => {
    it('reconstructs content from children', async () => {
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          {
            id: 'parent_note',
            data: () => ({
              content: 'First line',
              containedNotes: ['child_1', 'child_2'],
            }),
          },
        ],
      } as any)

      const docs = new Map<string, Record<string, unknown>>([
        ['child_1', { content: 'Second line' }],
        ['child_2', { content: 'Third line' }],
      ])
      mockDocumentMultiple(docs)

      const notes = await repository.loadNotesWithFullContent()

      expect(notes).toHaveLength(1)
      expect(notes[0]!.id).toBe('parent_note')
      expect(notes[0]!.content).toBe('First line\nSecond line\nThird line')
    })

    it('returns notes without children as-is', async () => {
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          {
            id: 'simple_note',
            data: () => ({
              content: 'Single line content',
              containedNotes: [],
            }),
          },
        ],
      } as any)

      const notes = await repository.loadNotesWithFullContent()

      expect(notes).toHaveLength(1)
      expect(notes[0]!.id).toBe('simple_note')
      expect(notes[0]!.content).toBe('Single line content')
    })
  })

  // endregion

  // region Save Tests

  describe('saveNoteWithChildren', () => {
    it('saves parent content via transaction', async () => {
      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({
            exists: () => false,
            data: () => ({}),
          }),
          set: vi.fn(),
          update: vi.fn(),
        }
        return fn(transaction as any)
      })
      vi.mocked(mockDoc).mockReturnValue({ id: 'note_1' } as any)

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Content', noteId: 'note_1' },
      ])

      expect(result).toBeInstanceOf(Map)
      expect(mockRunTransaction).toHaveBeenCalled()
    })

    it('creates new child notes and returns their IDs', async () => {
      let docCallCount = 0
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) {
          // auto-generated ref for new child
          return { id: 'new_child' } as any
        }
        docCallCount++
        return { id: args[args.length - 1] } as any
      })

      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({
            exists: () => false,
            data: () => ({}),
          }),
          set: vi.fn(),
          update: vi.fn(),
        }
        return fn(transaction as any)
      })

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: 'New child', noteId: null },
      ])

      expect(result.get(1)).toBe('new_child')
    })

    it('drops trailing empty lines', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: 'child_1' } as any
        return { id: args[args.length - 1] } as any
      })

      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({
            exists: () => false,
            data: () => ({}),
          }),
          set: vi.fn(),
          update: vi.fn(),
        }
        return fn(transaction as any)
      })

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: 'Child content', noteId: null },
        { content: '', noteId: null }, // Trailing empty line should be dropped
      ])

      expect(result.size).toBe(1)
      expect(result.get(1)).toBe('child_1')
    })

    it('drops multiple trailing empty lines', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: 'child_1' } as any
        return { id: args[args.length - 1] } as any
      })

      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({
            exists: () => false,
            data: () => ({}),
          }),
          set: vi.fn(),
          update: vi.fn(),
        }
        return fn(transaction as any)
      })

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: 'Child', noteId: null },
        { content: '', noteId: null },
        { content: '', noteId: null },
        { content: '', noteId: null },
      ])

      expect(result.size).toBe(1)
      expect(result.get(1)).toBe('child_1')
    })

    it('treats whitespace-only lines as content', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: 'new_child' } as any
        return { id: args[args.length - 1] } as any
      })

      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({
            exists: () => false,
            data: () => ({}),
          }),
          set: vi.fn(),
          update: vi.fn(),
        }
        return fn(transaction as any)
      })

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '   ', noteId: null }, // Whitespace-only should create child
      ])

      expect(result.get(1)).toBe('new_child')
    })
  })

  // endregion

  // region Create Tests

  describe('createNote', () => {
    it('returns new note ID', async () => {
      vi.mocked(mockAddDoc).mockResolvedValue({ id: 'new_note_id' } as any)

      const noteId = await repository.createNote()

      expect(noteId).toBe('new_note_id')
    })
  })

  describe('createMultiLineNote', () => {
    it('creates parent with children', async () => {
      const ids = ['parent_id', 'child_1', 'child_2']
      let idIdx = 0
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) {
          return { id: ids[idIdx++] } as any
        }
        return { id: args[args.length - 1] } as any
      })

      const batch = { set: vi.fn(), commit: vi.fn().mockResolvedValue(undefined) }
      vi.mocked(mockWriteBatch).mockReturnValue(batch as any)

      const parentId = await repository.createMultiLineNote('Line 1\nLine 2\nLine 3')

      expect(parentId).toBe('parent_id')
      expect(batch.set).toHaveBeenCalledTimes(3) // parent + 2 children
    })

    it('treats blank lines as spacers', async () => {
      const ids = ['parent_id', 'child_id']
      let idIdx = 0
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) {
          return { id: ids[idIdx++] } as any
        }
        return { id: args[args.length - 1] } as any
      })

      const batch = { set: vi.fn(), commit: vi.fn().mockResolvedValue(undefined) }
      vi.mocked(mockWriteBatch).mockReturnValue(batch as any)

      await repository.createMultiLineNote('Line 1\n\nLine 3')

      // Only 2 sets: parent + Line 3 child (blank line is spacer, no set call)
      expect(batch.set).toHaveBeenCalledTimes(2)
    })

    it('treats whitespace-only lines as content, not spacers', async () => {
      const ids = ['parent_id', 'child_1', 'child_2']
      let idIdx = 0
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) {
          return { id: ids[idIdx++] } as any
        }
        return { id: args[args.length - 1] } as any
      })

      const batch = { set: vi.fn(), commit: vi.fn().mockResolvedValue(undefined) }
      vi.mocked(mockWriteBatch).mockReturnValue(batch as any)

      await repository.createMultiLineNote('Line 1\n   \nLine 3')

      // 3 sets: parent + whitespace child + Line 3 child
      expect(batch.set).toHaveBeenCalledTimes(3)
    })
  })

  // endregion
})
