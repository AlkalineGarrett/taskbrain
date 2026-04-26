import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NoteRepository, matchLinesToIds, ContentDropAbortError } from '../../data/NoteRepository'
import { noteStore } from '../../data/NoteStore'
import type { Firestore, DocumentReference } from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import type { NoteLine } from '../../data/Note'

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

/** Mock getDocs to return empty results (no tree-format descendants). */
function mockEmptyTreeQuery() {
  vi.mocked(mockGetDocs).mockResolvedValue({ docs: [], empty: true } as any)
}

beforeEach(() => {
  vi.clearAllMocks()

  mockDb = {} as Firestore
  mockAuth = {
    currentUser: { uid: USER_ID },
  } as unknown as Auth

  vi.mocked(mockCollection).mockReturnValue('notesCollection' as any)

  // Default: no tree-format descendants
  mockEmptyTreeQuery()

  // Save/delete ops require NoteStore to be loaded; default to loaded for
  // tests that aren't exercising the load guard. Tests for the guard itself
  // override this.
  vi.spyOn(noteStore, 'isLoaded').mockReturnValue(true)

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

      const { lines } = await repository.loadNoteWithChildren('note_1')

      expect(lines).toEqual([{ content: '', noteId: 'note_1' }])
    })

    it('does not add trailing empty line when note is single empty line', async () => {
      vi.mocked(mockDoc).mockReturnValue({ id: 'note_1' } as any)
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: '', containedNotes: [] }),
        id: 'note_1',
      } as any)

      const { lines } = await repository.loadNoteWithChildren('note_1')

      expect(lines).toHaveLength(1)
      expect(lines[0]!.content).toBe('')
      expect(lines[0]!.noteId).toBe('note_1')
    })

    it('returns just parent content when no children', async () => {
      vi.mocked(mockDoc).mockReturnValue({ id: 'note_1' } as any)
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: 'Parent content', containedNotes: [] }),
        id: 'note_1',
      } as any)

      const { lines } = await repository.loadNoteWithChildren('note_1')

      expect(lines).toHaveLength(1)
      expect(lines[0]!.content).toBe('Parent content')
      expect(lines[0]!.noteId).toBe('note_1')
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
    it('reconstructs content from tree descendants', async () => {
      // getDocs returns ALL user notes — parent and children with rootNoteId
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          {
            id: 'parent_note',
            data: () => ({
              content: 'First line',
              containedNotes: ['child_1', 'child_2'],
            }),
          },
          {
            id: 'child_1',
            data: () => ({
              content: 'Second line',
              parentNoteId: 'parent_note',
              rootNoteId: 'parent_note',
            }),
          },
          {
            id: 'child_2',
            data: () => ({
              content: 'Third line',
              parentNoteId: 'parent_note',
              rootNoteId: 'parent_note',
            }),
          },
        ],
      } as any)

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
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) {
          return { id: 'new_child' } as any
        }
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
        { content: '\tNew child', noteId: null },
      ])

      expect(result.get(1)).toBe('new_child')
    })

    it('persists trailing empty lines as docs', async () => {
      let counter = 0
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: `new_${++counter}` } as any
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
        { content: '\tChild content', noteId: null },
        { content: '', noteId: null },
      ])

      expect(result.size).toBe(2)
      expect(result.get(1)).toBe('new_1')
      expect(result.get(2)).toBe('new_2')
    })

    it('persists multiple trailing empty lines as docs', async () => {
      let counter = 0
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: `new_${++counter}` } as any
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
        { content: '\tChild', noteId: null },
        { content: '', noteId: null },
        { content: '', noteId: null },
        { content: '', noteId: null },
      ])

      // Child + 3 trailing empties = 4 new docs.
      expect(result.size).toBe(4)
      expect(result.get(1)).toBe('new_1')
      expect(result.get(2)).toBe('new_2')
      expect(result.get(3)).toBe('new_3')
      expect(result.get(4)).toBe('new_4')
    })

    it('sentinel noteIds are written to a fresh ref via CREATE with userId', async () => {
      // Sentinels routed through the UPDATE (merge) path land on a nonexistent
      // doc with no userId — Firestore rejects as PERMISSION_DENIED.
      const freshRef = { id: 'fresh_id' } as any
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return freshRef
        return { id: args[args.length - 1] } as any
      })

      const setSpy = vi.fn()
      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({ exists: () => false, data: () => ({}) }),
          set: setSpy,
          update: vi.fn(),
        }
        return fn(transaction as any)
      })

      const sentinel = '@paste_abc123'
      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tPasted line', noteId: sentinel },
      ])

      expect(result.get(1)).toBe('fresh_id')

      const writeToFreshRef = setSpy.mock.calls.find((c) => c[0] === freshRef)
      expect(writeToFreshRef).toBeDefined()
      expect(writeToFreshRef![1].userId).toBe(USER_ID)
      expect(writeToFreshRef![1].content).toBe('Pasted line')
      // No merge option → CREATE path.
      expect(writeToFreshRef!.length).toBe(2)

      // And no write was addressed to the sentinel id itself.
      const writeToSentinel = setSpy.mock.calls.find((c) => c[0]?.id === sentinel)
      expect(writeToSentinel).toBeUndefined()
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
        { content: '\t   ', noteId: null }, // Tab + whitespace — whitespace is content
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

    it('allocates a doc for blank lines', async () => {
      const ids = ['parent_id', 'blank_id', 'child_id']
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

      // parent + blank-line doc + Line 3 child
      expect(batch.set).toHaveBeenCalledTimes(3)
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

  // region Tree-Format Load Tests

  describe('loadNoteWithChildren - tree format', () => {
    it('loads flat children via tree query', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        const id = args.length > 1 ? args[args.length - 1] : `auto`
        return { id } as DocumentReference
      })
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: 'Root', containedNotes: ['c1', 'c2'] }),
        id: 'root',
      } as any)
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          { id: 'c1', data: () => ({ content: 'Child 1', containedNotes: [], parentNoteId: 'root', rootNoteId: 'root' }) },
          { id: 'c2', data: () => ({ content: 'Child 2', containedNotes: [], parentNoteId: 'root', rootNoteId: 'root' }) },
        ],
        empty: false,
      } as any)

      const { lines } = await repository.loadNoteWithChildren('root')

      // No more auto-appended trailing empty — the persisted shape is what
      // comes back.
      expect(lines).toEqual([
        { content: 'Root', noteId: 'root' },
        { content: 'Child 1', noteId: 'c1' },
        { content: 'Child 2', noteId: 'c2' },
      ])
    })

    it('loads nested tree with tabs from depth', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        const id = args.length > 1 ? args[args.length - 1] : `auto`
        return { id } as DocumentReference
      })
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: 'Root', containedNotes: ['a'] }),
        id: 'root',
      } as any)
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          { id: 'a', data: () => ({ content: 'A', containedNotes: ['b'], parentNoteId: 'root', rootNoteId: 'root' }) },
          { id: 'b', data: () => ({ content: 'B', containedNotes: [], parentNoteId: 'a', rootNoteId: 'root' }) },
        ],
        empty: false,
      } as any)

      const { lines } = await repository.loadNoteWithChildren('root')

      expect(lines).toEqual([
        { content: 'Root', noteId: 'root' },
        { content: 'A', noteId: 'a' },
        { content: '\tB', noteId: 'b' },
      ])
    })

    it('filters deleted descendants in tree query', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        const id = args.length > 1 ? args[args.length - 1] : `auto`
        return { id } as DocumentReference
      })
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: 'Root', containedNotes: ['c1', 'c2'] }),
        id: 'root',
      } as any)
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          { id: 'c1', data: () => ({ content: 'Alive', containedNotes: [], parentNoteId: 'root', rootNoteId: 'root' }) },
          { id: 'c2', data: () => ({ content: 'Dead', containedNotes: [], parentNoteId: 'root', rootNoteId: 'root', state: 'deleted' }) },
        ],
        empty: false,
      } as any)

      const { lines } = await repository.loadNoteWithChildren('root')

      expect(lines).toHaveLength(2)
      expect(lines[0]).toEqual({ content: 'Root', noteId: 'root' })
      expect(lines[1]).toEqual({ content: 'Alive', noteId: 'c1' })
    })

    it('drops orphan ref in containedNotes and appends stray child linked by parentNoteId', () => {
      // Partial-sync / corrupted-data scenario: root's containedNotes names a
      // child that the descendants query didn't return, and a different child
      // is linked by parentNoteId but missing from containedNotes. The walk
      // drops the orphan and appends the stray at the end.
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        const id = args.length > 1 ? args[args.length - 1] : 'auto'
        return { id } as DocumentReference
      })
      vi.mocked(mockGetDoc).mockResolvedValue({
        exists: () => true,
        data: () => ({ content: 'Root', containedNotes: ['c1', 'missing'] }),
        id: 'root',
      } as any)
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          { id: 'c1', data: () => ({ content: 'Declared', containedNotes: [], parentNoteId: 'root', rootNoteId: 'root' }) },
          { id: 's', data: () => ({ content: 'Stray', containedNotes: [], parentNoteId: 'root', rootNoteId: 'root' }) },
        ],
        empty: false,
      } as any)

      return repository.loadNoteWithChildren('root').then(({ lines }) => {
        expect(lines.map(l => l.content)).toEqual(['Root', 'Declared', 'Stray'])
      })
    })
  })

  describe('loadNotesWithFullContent - tree format', () => {
    it('reconstructs content from tree descendants', async () => {
      vi.mocked(mockGetDocs).mockResolvedValue({
        docs: [
          {
            id: 'root',
            data: () => ({ content: 'Root', containedNotes: ['a'] }),
          },
          {
            id: 'a',
            data: () => ({ content: 'A', containedNotes: ['b'], parentNoteId: 'root', rootNoteId: 'root' }),
          },
          {
            id: 'b',
            data: () => ({ content: 'B', containedNotes: [], parentNoteId: 'a', rootNoteId: 'root' }),
          },
        ],
      } as any)

      const notes = await repository.loadNotesWithFullContent()

      expect(notes).toHaveLength(1)
      expect(notes[0]!.id).toBe('root')
      expect(notes[0]!.content).toBe('Root\nA\n\tB')
    })
  })

  // endregion

  // region Nested Save Tests

  describe('saveNoteWithChildren - nested', () => {
    it('saves nested tree structure', async () => {
      let refIdx = 0
      const childIds = ['child_a', 'child_b']
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: childIds[refIdx++] } as any
        return { id: args[args.length - 1] } as any
      })

      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({ exists: () => false, data: () => ({}) }),
          set: vi.fn(),
          update: vi.fn(),
        }
        return fn(transaction as any)
      })

      const result = await repository.saveNoteWithChildren('root', [
        { content: 'Root', noteId: 'root' },
        { content: '\tA', noteId: null },
        { content: '\t\tB', noteId: null },
      ])

      expect(result.get(1)).toBe('child_a')
      expect(result.get(2)).toBe('child_b')
    })

    it('returns empty map for empty lines', async () => {
      const result = await repository.saveNoteWithChildren('note_1', [])

      expect(result.size).toBe(0)
    })
  })

  // endregion

  // region Delete/Restore Tests

  describe('softDeleteNote', () => {
    it('deletes root and new-format descendants', async () => {
      const getDescendantIdsSpy = vi.spyOn(noteStore, 'getDescendantIds')
        .mockReturnValue(new Set(['child_1', 'child_2']))
      const batch = { update: vi.fn(), commit: vi.fn().mockResolvedValue(undefined) }
      vi.mocked(mockWriteBatch).mockReturnValue(batch as any)
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        return { id: args[args.length - 1] } as any
      })

      await repository.softDeleteNote('note_1')

      // root + 2 children
      expect(batch.update).toHaveBeenCalledTimes(3)
      getDescendantIdsSpy.mockRestore()
    })

    it('deletes only root when no descendants', async () => {
      const getDescendantIdsSpy = vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set())
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        return { id: args[args.length - 1] } as any
      })
      const batch = { update: vi.fn(), commit: vi.fn().mockResolvedValue(undefined) }
      vi.mocked(mockWriteBatch).mockReturnValue(batch as any)

      await repository.softDeleteNote('note_1')

      expect(batch.update).toHaveBeenCalledTimes(1)
      getDescendantIdsSpy.mockRestore()
    })
  })

  describe('undeleteNote', () => {
    it('restores root and new-format descendants', async () => {
      const getAllDescendantIdsSpy = vi.spyOn(noteStore, 'getAllDescendantIds')
        .mockReturnValue(new Set(['child_1', 'child_2']))
      const batch = { update: vi.fn(), commit: vi.fn().mockResolvedValue(undefined) }
      vi.mocked(mockWriteBatch).mockReturnValue(batch as any)
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        return { id: args[args.length - 1] } as any
      })

      await repository.undeleteNote('note_1')

      expect(batch.update).toHaveBeenCalledTimes(3)
      getAllDescendantIdsSpy.mockRestore()
    })

  })

  // endregion

  // region Content-drop guard

  describe('content-drop guard', () => {
    function setupTransaction() {
      vi.mocked(mockRunTransaction).mockImplementation(async (_db, fn) => {
        const transaction = {
          get: vi.fn().mockResolvedValue({ exists: () => false, data: () => ({}) }),
          set: vi.fn(),
          update: vi.fn(),
        }
        return fn(transaction as any)
      })
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        return { id: args[args.length - 1] ?? 'new' } as any
      })
    }

    it('aborts when save would soft-delete more than half of declared children', async () => {
      // Existing note has 4 declared children (containedNotes), all live.
      // Save sends only 1 child line — would delete 3 of 4 → trips the guard.
      vi.spyOn(noteStore, 'getNoteById').mockReturnValue({
        id: 'root',
        content: 'Root',
        containedNotes: ['c1', 'c2', 'c3', 'c4'],
      } as any)
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1', 'c2', 'c3', 'c4']))
      setupTransaction()

      await expect(
        repository.saveNoteWithChildren('root', [
          { content: 'Root', noteId: 'root' },
          { content: '\tonly survivor', noteId: 'c1' },
        ]),
      ).rejects.toBeInstanceOf(ContentDropAbortError)
    })

    it('passes when save deletes half or fewer of declared children', async () => {
      vi.spyOn(noteStore, 'getNoteById').mockReturnValue({
        id: 'root',
        content: 'Root',
        containedNotes: ['c1', 'c2', 'c3', 'c4'],
      } as any)
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1', 'c2', 'c3', 'c4']))
      setupTransaction()

      await expect(
        repository.saveNoteWithChildren('root', [
          { content: 'Root', noteId: 'root' },
          { content: '\tc1', noteId: 'c1' },
          { content: '\tc2', noteId: 'c2' },
        ]),
      ).resolves.toBeInstanceOf(Map)
    })

    it('does not trip when fewer than 3 declared children exist', async () => {
      vi.spyOn(noteStore, 'getNoteById').mockReturnValue({
        id: 'root',
        content: 'Root',
        containedNotes: ['c1', 'c2'],
      } as any)
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1', 'c2']))
      setupTransaction()

      await expect(
        repository.saveNoteWithChildren('root', [{ content: 'Root', noteId: 'root' }]),
      ).resolves.toBeInstanceOf(Map)
    })

    it('ignores orphan containedNotes refs (declared but not in NoteStore)', async () => {
      // 4 declared, but 3 are orphans (not in existingDescendants). Real
      // declared = ['c1']. Saving with c1 surviving → 0 of 1 to delete.
      vi.spyOn(noteStore, 'getNoteById').mockReturnValue({
        id: 'root',
        content: 'Root',
        containedNotes: ['c1', 'orphan_a', 'orphan_b', 'orphan_c'],
      } as any)
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1']))
      setupTransaction()

      await expect(
        repository.saveNoteWithChildren('root', [
          { content: 'Root', noteId: 'root' },
          { content: '\tc1', noteId: 'c1' },
        ]),
      ).resolves.toBeInstanceOf(Map)
    })
  })

  // endregion

  // region NoteStore-loaded invariant guard

  describe('NoteStore-loaded invariant', () => {
    it('saveNoteWithChildren throws NoteStoreNotLoadedError when NoteStore not loaded', async () => {
      vi.spyOn(noteStore, 'isLoaded').mockReturnValue(false)
      await expect(
        repository.saveNoteWithChildren('note_1', [{ content: 'Hello', noteId: 'note_1' }]),
      ).rejects.toMatchObject({
        name: 'NoteStoreNotLoadedError',
        operation: 'saveNoteWithChildren',
        noteId: 'note_1',
      })
    })

    it('softDeleteNote throws NoteStoreNotLoadedError when NoteStore not loaded', async () => {
      vi.spyOn(noteStore, 'isLoaded').mockReturnValue(false)
      await expect(repository.softDeleteNote('note_1')).rejects.toMatchObject({
        name: 'NoteStoreNotLoadedError',
        operation: 'softDeleteNote',
      })
    })

    it('undeleteNote throws NoteStoreNotLoadedError when NoteStore not loaded', async () => {
      vi.spyOn(noteStore, 'isLoaded').mockReturnValue(false)
      await expect(repository.undeleteNote('note_1')).rejects.toMatchObject({
        name: 'NoteStoreNotLoadedError',
        operation: 'undeleteNote',
      })
    })

    it('saveNoteWithFullContent throws NoteStoreNotLoadedError when NoteStore not loaded', async () => {
      vi.spyOn(noteStore, 'isLoaded').mockReturnValue(false)
      await expect(
        repository.saveNoteWithFullContent('note_1', 'Hello'),
      ).rejects.toMatchObject({
        name: 'NoteStoreNotLoadedError',
        operation: 'saveNoteWithFullContent',
      })
    })

    it('thrown error includes a stack trace pointing at the call site', async () => {
      vi.spyOn(noteStore, 'isLoaded').mockReturnValue(false)
      try {
        await repository.softDeleteNote('note_1')
        expect.fail('expected throw')
      } catch (e) {
        expect(e).toBeInstanceOf(Error)
        expect((e as Error).stack).toMatch(/NoteRepository/)
      }
    })
  })

  // endregion
})

// region matchLinesToIds Tests

describe('matchLinesToIds', () => {
  it('assigns parentNoteId to first line when no existing lines', () => {
    const result = matchLinesToIds('parent', [], ['Line 1', '\tLine 2'])

    expect(result).toEqual([
      { content: 'Line 1', noteId: 'parent' },
      { content: '\tLine 2', noteId: null },
    ])
  })

  it('matches lines by exact content first', () => {
    const existing: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: '\tChild A', noteId: 'a' },
      { content: '\tChild B', noteId: 'b' },
    ]

    const result = matchLinesToIds('root', existing, ['Root', '\tChild B', '\tChild A'])

    expect(result[0]!.noteId).toBe('root')
    expect(result[1]!.noteId).toBe('b')
    expect(result[2]!.noteId).toBe('a')
  })

  it('falls back to positional matching for modified lines', () => {
    const existing: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: '\tOld content', noteId: 'child' },
    ]

    const result = matchLinesToIds('root', existing, ['Root', '\tNew content'])

    expect(result[0]!.noteId).toBe('root')
    expect(result[1]!.noteId).toBe('child')
  })

  it('ensures first line always has parentNoteId', () => {
    const existing: NoteLine[] = [
      { content: 'Root', noteId: 'other_id' },
    ]

    const result = matchLinesToIds('parent', existing, ['Root'])

    expect(result[0]!.noteId).toBe('parent')
  })

  it('assigns null to new lines without matches', () => {
    const existing: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
    ]

    const result = matchLinesToIds('root', existing, ['Root', '\tBrand new line'])

    expect(result[0]!.noteId).toBe('root')
    expect(result[1]!.noteId).toBeNull()
  })

  it('uses editor noteIds for foreign notes (reparenting from another tree)', () => {
    const existing: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: '\tExisting child', noteId: 'child1' },
    ]

    const result = matchLinesToIds(
      'root',
      existing,
      ['Root', '\tExisting child', '\tPasted line'],
      ['root', 'child1', 'foreign-note'],
    )

    expect(result[0]!.noteId).toBe('root')
    expect(result[1]!.noteId).toBe('child1')
    expect(result[2]!.noteId).toBe('foreign-note')
  })

  it('prefers content matching over editor noteIds for notes already in tree', () => {
    const existing: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: '\tChild A', noteId: 'a' },
      { content: '\tChild B', noteId: 'b' },
    ]

    // Editor has noteIds in a different order due to reordering
    const result = matchLinesToIds(
      'root',
      existing,
      ['Root', '\tChild B', '\tChild A'],
      ['root', 'a', 'b'],
    )

    // Content matching should win for in-tree notes
    expect(result[1]!.noteId).toBe('b')
    expect(result[2]!.noteId).toBe('a')
  })

  it('handles editorNoteIds with no existing lines', () => {
    const result = matchLinesToIds(
      'root',
      [],
      ['Root', '\tPasted line'],
      ['root', 'foreign-note'],
    )

    expect(result[0]!.noteId).toBe('root')
    expect(result[1]!.noteId).toBe('foreign-note')
  })
})

// endregion
