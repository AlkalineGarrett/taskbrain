import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { NoteRepository, matchLinesToIds, ContentDropAbortError } from '../../data/NoteRepository'
import { noteStore } from '../../data/NoteStore'
import type { Firestore, DocumentReference } from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import type { Note, NoteLine } from '../../data/Note'
import { note } from '../factories'

// Mock firebase/firestore
vi.mock('firebase/firestore', () => {
  const mockCollection = vi.fn()
  const mockDoc = vi.fn()
  const mockGetDoc = vi.fn()
  const mockGetDocs = vi.fn()
  const mockQuery = vi.fn()
  const mockWhere = vi.fn()
  const mockAddDoc = vi.fn()
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
  writeBatch: mockWriteBatch,
  collection: mockCollection,
} = await import('firebase/firestore')

/** Mock getDocs to return empty results (no tree-format descendants). */
function mockEmptyTreeQuery() {
  vi.mocked(mockGetDocs).mockResolvedValue({ docs: [], empty: true } as any)
}

/**
 * Installs a writeBatch mock that records all set/update calls. Default for
 * save tests; helper tests use the returned spies to inspect individual writes.
 */
function setupSaveBatch(): {
  batch: { set: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn>; commit: ReturnType<typeof vi.fn> }
  setSpy: ReturnType<typeof vi.fn>
} {
  const setSpy = vi.fn()
  const batch = {
    set: setSpy,
    update: vi.fn(),
    commit: vi.fn().mockResolvedValue(undefined),
  }
  vi.mocked(mockWriteBatch).mockReturnValue(batch as any)
  return { batch, setSpy }
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

  // Default batch mock for save paths; tests that need to inspect writes
  // call setupSaveBatch() to grab spies.
  setupSaveBatch()

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
    it('saves parent content via batch commit', async () => {
      const { batch } = setupSaveBatch()
      vi.mocked(mockDoc).mockReturnValue({ id: 'note_1' } as any)

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Content', noteId: 'note_1' },
      ], null)

      expect(result).toBeInstanceOf(Map)
      expect(batch.commit).toHaveBeenCalled()
    })

    it('stamps the same lastWriterOpId on every write and registers it with NoteStore', async () => {
      // Echo-suppression contract: a save batch carries one clientOpId across
      // all docs (root + descendants + soft-deletes). NoteStore must register
      // that opId so the listener can drop the server echo when it arrives.
      const registerSpy = vi.spyOn(noteStore, 'registerPendingOp')
      const releaseSpy = vi.spyOn(noteStore, 'releasePendingOp')
      const { setSpy } = setupSaveBatch()
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: 'new_child' } as any
        return { id: args[args.length - 1] } as any
      })

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tNew child', noteId: null },
      ], null)

      expect(registerSpy).toHaveBeenCalledTimes(1)
      const opId = registerSpy.mock.calls[0]![0]
      expect(typeof opId).toBe('string')
      expect(opId.length).toBeGreaterThan(0)
      expect(releaseSpy).toHaveBeenCalledWith(opId)

      // Every write op carries the same opId.
      const opIds = setSpy.mock.calls.map((c: any[]) => (c[1] as { lastWriterOpId?: string }).lastWriterOpId)
      expect(opIds.every((id) => id === opId)).toBe(true)
      expect(opIds.length).toBeGreaterThan(0)
      registerSpy.mockRestore()
      releaseSpy.mockRestore()
    })

    it('stamps version=1 on a fresh descendant doc', async () => {
      const { setSpy } = setupSaveBatch()
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: 'fresh_id' } as any
        return { id: args[args.length - 1] } as any
      })

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tNew', noteId: null },
      ], null)

      // Find the CREATE call (2-arg, no merge option) and confirm version=1.
      const createCall = setSpy.mock.calls.find((c: any[]) => c.length === 2)
      expect(createCall).toBeDefined()
      expect(createCall![1].version).toBe(1)
    })

    it('bumps version on an existing descendant rewrite', async () => {
      // existingChild has version=4 in NoteStore — the merge write should land
      // version=5 so external listeners can detect a real change.
      const existingChild: Note = note({
        id: 'c1',
        userId: USER_ID,
        parentNoteId: 'note_1',
        rootNoteId: 'note_1',
        content: 'old',
        version: 4,
      })
      const rootNote: Note = note({ id: 'note_1', containedNotes: ['c1'], version: 2 })
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1']))
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_1' ? rootNote : id === 'c1' ? existingChild : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_1' ? rootNote : id === 'c1' ? existingChild : undefined,
      )
      const { setSpy } = setupSaveBatch()
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tedited', noteId: 'c1' },
      ], null)

      const c1Write = setSpy.mock.calls.find((c: any[]) => (c[0] as { id: string }).id === 'c1')
      expect(c1Write).toBeDefined()
      expect(c1Write![1].version).toBe(5)

      const rootWrite = setSpy.mock.calls.find((c: any[]) => (c[0] as { id: string }).id === 'note_1')
      expect(rootWrite).toBeDefined()
      expect(rootWrite![1].version).toBe(3)
    })

    it('creates new child notes and returns their IDs', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) {
          return { id: 'new_child' } as any
        }
        return { id: args[args.length - 1] } as any
      })

      setupSaveBatch()

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tNew child', noteId: null },
      ], null)

      expect(result.get(1)).toBe('new_child')
    })

    it('persists trailing empty lines as docs', async () => {
      let counter = 0
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: `new_${++counter}` } as any
        return { id: args[args.length - 1] } as any
      })

      setupSaveBatch()

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tChild content', noteId: null },
        { content: '', noteId: null },
      ], null)

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

      setupSaveBatch()

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tChild', noteId: null },
        { content: '', noteId: null },
        { content: '', noteId: null },
        { content: '', noteId: null },
      ], null)

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

      const { setSpy } = setupSaveBatch()

      const sentinel = '@paste_abc123'
      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tPasted line', noteId: sentinel },
      ], null)

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

    it('does not alias sentinel to an existing line with identical content', async () => {
      // A sentinel marks a brand-new line (typed/pasted/split). Even when its
      // content happens to match an existing sibling under the same parent,
      // the save must allocate a FRESH doc — never alias to the existing id.
      // Identity is preserved through proper line tracking, not through
      // content matching at save time. (Cut/paste identity preservation is
      // handled separately via the cut-delete state in Phase 8.)
      const existingChild: Note = note({
        id: 'c1',
        userId: USER_ID,
        parentNoteId: 'note_1',
        rootNoteId: 'note_1',
        content: '• Item A',
      })
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1']))
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'c1' ? existingChild : undefined,
      )
      vi.spyOn(noteStore, 'getLiveDescendantsByParent').mockReturnValue(
        new Map([['note_1', [existingChild]]]),
      )
      const raiseWarningSpy = vi.spyOn(noteStore, 'raiseWarning').mockImplementation(() => {})

      const freshRef = { id: 'fresh_id' } as any
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return freshRef
        return { id: args[args.length - 1] } as any
      })
      setupSaveBatch()

      const sentinel = '@paste_abc123'
      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '• Item A', noteId: sentinel },
      ], null)

      expect(result.get(1)).toBe('fresh_id')
      // Sentinels carry no null-id signal, so no user-facing warning is raised.
      expect(raiseWarningSpy).not.toHaveBeenCalled()
    })

    it('treats whitespace-only lines as content', async () => {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: 'new_child' } as any
        return { id: args[args.length - 1] } as any
      })

      setupSaveBatch()

      const result = await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\t   ', noteId: null }, // Tab + whitespace — whitespace is content
      ], null)

      expect(result.get(1)).toBe('new_child')
    })
  })

  // Mirror of the Android NoteRepositoryTest "Save diff/skip" region.
  describe('saveNoteWithChildren - diff/skip', () => {
    function setupStore(notes: Note[], descendantIdsByRoot: Record<string, string[]>) {
      const byId = new Map(notes.map((n) => [n.id, n]))
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id: string) => byId.get(id))
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id: string) => byId.get(id))
      vi.spyOn(noteStore, 'getDescendantIds').mockImplementation(
        (id: string) => new Set(descendantIdsByRoot[id] ?? []),
      )
    }

    function captureWrites(): { setSpy: ReturnType<typeof vi.fn> } {
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return { id: 'unexpected_new_doc' } as any
        return { id: args[args.length - 1] } as any
      })
      const { setSpy } = setupSaveBatch()
      return { setSpy }
    }

    function mergeCalls(setSpy: ReturnType<typeof vi.fn>) {
      // Merge writes use the 3-arg overload (ref, data, { merge: true }).
      // Soft-delete writes also go through merge with `state: 'deleted'` —
      // exclude them so the count reflects only re-writes of live docs.
      return setSpy.mock.calls.filter((c: unknown[]) =>
        c.length === 3 && (c[1] as { state?: string }).state !== 'deleted',
      )
    }

    it('skips all merge writes when nothing changed', async () => {
      const rootNote = note({ id: 'note_1', content: 'Parent', containedNotes: ['c1'] })
      const child = note({
        id: 'c1',
        content: 'Untouched',
        parentNoteId: 'note_1',
        rootNoteId: 'note_1',
      })
      setupStore([rootNote, child], { note_1: ['c1'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tUntouched', noteId: 'c1' },
      ], null)

      expect(mergeCalls(setSpy).length).toBe(0)
    })

    it('writes only the changed child when one of many is edited', async () => {
      const rootNote = note({
        id: 'note_1', content: 'Parent', containedNotes: ['c1', 'c2', 'c3'],
      })
      const c1 = note({ id: 'c1', content: 'First', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      const c2 = note({ id: 'c2', content: 'Second', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      const c3 = note({ id: 'c3', content: 'Third', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      setupStore([rootNote, c1, c2, c3], { note_1: ['c1', 'c2', 'c3'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tFirst', noteId: 'c1' },
        { content: '\tSecond EDITED', noteId: 'c2' },
        { content: '\tThird', noteId: 'c3' },
      ], null)

      // Root: containedNotes still [c1,c2,c3] → skip. c1, c3 unchanged → skip.
      // c2 content differs → exactly one merge write.
      expect(mergeCalls(setSpy).length).toBe(1)
    })

    it('writes root when content changes but skips unchanged child', async () => {
      const rootNote = note({ id: 'note_1', content: 'Old parent', containedNotes: ['c1'] })
      const c1 = note({
        id: 'c1', content: 'Untouched', parentNoteId: 'note_1', rootNoteId: 'note_1',
      })
      setupStore([rootNote, c1], { note_1: ['c1'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'New parent', noteId: 'note_1' },
        { content: '\tUntouched', noteId: 'c1' },
      ], null)

      expect(mergeCalls(setSpy).length).toBe(1)
    })

    it('writes root when containedNotes order changes', async () => {
      // Reordering siblings flips the parent's containedNotes — root must
      // write even when neither child's content changed.
      const rootNote = note({ id: 'note_1', content: 'Parent', containedNotes: ['a', 'b'] })
      const a = note({ id: 'a', content: 'A', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      const b = note({ id: 'b', content: 'B', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      setupStore([rootNote, a, b], { note_1: ['a', 'b'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tB', noteId: 'b' },
        { content: '\tA', noteId: 'a' },
      ], null)

      // Root flipped → write. Children unchanged → skip.
      expect(mergeCalls(setSpy).length).toBe(1)
    })

    it('writes parent and child when child is reparented', async () => {
      // Indenting B under A flips the root's containedNotes (drops B), A's
      // containedNotes (gains B), and B's parentNoteId. All three must
      // write; nothing else exists to skip.
      const rootNote = note({ id: 'note_1', content: 'Parent', containedNotes: ['a', 'b'] })
      const a = note({
        id: 'a', content: 'A', parentNoteId: 'note_1', rootNoteId: 'note_1', containedNotes: [],
      })
      const b = note({ id: 'b', content: 'B', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      setupStore([rootNote, a, b], { note_1: ['a', 'b'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tA', noteId: 'a' },
        { content: '\t\tB', noteId: 'b' },
      ], null)

      expect(mergeCalls(setSpy).length).toBe(3)
    })

    it('writes child whose existing state is non-null', async () => {
      // A child whose existing doc carries state (e.g., a soft-deleted line
      // being restored) must always be written so the merge can reset state
      // to null. Without this guard the doc would stay marked deleted.
      const rootNote = note({ id: 'note_1', content: 'Parent', containedNotes: ['c1'] })
      const deletedChild = note({
        id: 'c1', content: 'Same', parentNoteId: 'note_1', rootNoteId: 'note_1',
        state: 'deleted',
      })
      setupStore([rootNote, deletedChild], { note_1: ['c1'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tSame', noteId: 'c1' },
      ], null)

      expect(mergeCalls(setSpy).length).toBe(1)
    })

    it('writes child whose existing parentNoteId differs', async () => {
      // Edge case: NoteStore says the child currently lives under a
      // different parent than the editor places it. Must write to reconcile.
      const rootNote = note({ id: 'note_1', content: 'Parent', containedNotes: ['c1'] })
      const orphan = note({
        id: 'c1', content: 'Same', parentNoteId: 'stranger', rootNoteId: 'note_1',
      })
      setupStore([rootNote, orphan], { note_1: ['c1'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
        { content: '\tSame', noteId: 'c1' },
      ], null)

      expect(mergeCalls(setSpy).length).toBe(1)
    })

    it('soft-deletes removed child without clearing parentNoteId/rootNoteId', async () => {
      // The deleted-section view distinguishes deleted parent notes (no
      // parentNoteId) from removed child lines (parentNoteId set). Clearing
      // those fields here would make the child indistinguishable from a
      // top-level deletion and surface it incorrectly.
      const rootNote = note({ id: 'note_1', content: 'Parent', containedNotes: ['c1'] })
      const child = note({
        id: 'c1', content: 'Doomed', parentNoteId: 'note_1', rootNoteId: 'note_1',
      })
      setupStore([rootNote, child], { note_1: ['c1'] })
      const { setSpy } = captureWrites()

      await repository.saveNoteWithChildren('note_1', [
        { content: 'Parent', noteId: 'note_1' },
      ], null)

      const deleteCall = setSpy.mock.calls.find(
        (c: any[]) => c[1]?.state === 'deleted',
      )
      expect(deleteCall).toBeDefined()
      const payload = deleteCall![1] as Record<string, unknown>
      expect(payload).not.toHaveProperty('parentNoteId')
      expect(payload).not.toHaveProperty('rootNoteId')
    })
  })

  // endregion

  // region Multi-note atomic batched save (Phase 2)

  describe('saveMultipleNotes', () => {
    function setupStore(notes: Note[], descendantIdsByRoot: Record<string, string[]>) {
      const byId = new Map(notes.map((n) => [n.id, n]))
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id: string) => byId.get(id))
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id: string) => byId.get(id))
      vi.spyOn(noteStore, 'getDescendantIds').mockImplementation(
        (id: string) => new Set(descendantIdsByRoot[id] ?? []),
      )
    }

    it('returns empty result for empty input without committing', async () => {
      const { batch } = setupSaveBatch()
      const result = await repository.saveMultipleNotes([])
      expect(result.size).toBe(0)
      expect(batch.commit).not.toHaveBeenCalled()
    })

    it('combines writes from multiple notes into a single batch commit', async () => {
      const root1 = note({ id: 'r1', content: 'Old r1', containedNotes: ['c1'] })
      const child1 = note({ id: 'c1', content: 'a', parentNoteId: 'r1', rootNoteId: 'r1' })
      const root2 = note({ id: 'r2', content: 'Old r2', containedNotes: ['c2'] })
      const child2 = note({ id: 'c2', content: 'b', parentNoteId: 'r2', rootNoteId: 'r2' })
      setupStore([root1, child1, root2, child2], { r1: ['c1'], r2: ['c2'] })
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { batch, setSpy } = setupSaveBatch()

      const result = await repository.saveMultipleNotes([
        { noteId: 'r1', localBase: null, trackedLines: [
          { content: 'New r1', noteId: 'r1' },
          { content: '\ta', noteId: 'c1' },
        ] },
        { noteId: 'r2', localBase: null, trackedLines: [
          { content: 'New r2', noteId: 'r2' },
          { content: '\tb', noteId: 'c2' },
        ] },
      ])

      expect(result.size).toBe(2)
      expect(result.has('r1')).toBe(true)
      expect(result.has('r2')).toBe(true)
      // Single batch commit covers both notes' root rewrites.
      expect(batch.commit).toHaveBeenCalledTimes(1)
      const refs = setSpy.mock.calls.map((c: any[]) => (c[0] as { id: string }).id)
      expect(refs).toContain('r1')
      expect(refs).toContain('r2')
    })

    it('rolls back all notes when one item trips the content-drop guard', async () => {
      const root1 = note({ id: 'r1', content: 'Old r1', containedNotes: ['a', 'b', 'c', 'd'] })
      const a = note({ id: 'a', content: 'A', parentNoteId: 'r1', rootNoteId: 'r1' })
      const b = note({ id: 'b', content: 'B', parentNoteId: 'r1', rootNoteId: 'r1' })
      const c = note({ id: 'c', content: 'C', parentNoteId: 'r1', rootNoteId: 'r1' })
      const d = note({ id: 'd', content: 'D', parentNoteId: 'r1', rootNoteId: 'r1' })
      const root2 = note({ id: 'r2', content: 'Old r2', containedNotes: [] })
      setupStore([root1, a, b, c, d, root2], { r1: ['a', 'b', 'c', 'd'], r2: [] })
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { batch } = setupSaveBatch()

      // r1 drops 3 of 4 children → trips guard. r2 is benign. The whole
      // batch must abort before commit so r2 isn't saved alone.
      await expect(
        repository.saveMultipleNotes([
          { noteId: 'r1', localBase: null, trackedLines: [
            { content: 'r1', noteId: 'r1' },
            { content: '\tA', noteId: 'a' },
          ] },
          { noteId: 'r2', localBase: null, trackedLines: [
            { content: 'New r2', noteId: 'r2' },
          ] },
        ]),
      ).rejects.toBeInstanceOf(ContentDropAbortError)

      expect(batch.commit).not.toHaveBeenCalled()
    })
  })

  // endregion

  // region 3-way containedNotes merge (Phase 4)

  describe('saveNoteWithChildren — 3-way merge of containedNotes', () => {
    it('integrates a concurrent root-level addition into the saved containedNotes', async () => {
      // Editor loaded with [c1] as containedNotes (localBase). Another client
      // added c2 since. The user adds c3 locally. Save should land [c1, c3, c2].
      const root = note({ id: 'note_1', containedNotes: ['c1', 'c2'] })
      const c1 = note({ id: 'c1', content: 'first', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      const c2 = note({ id: 'c2', content: 'concurrent', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : id === 'c2' ? c2 : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : id === 'c2' ? c2 : undefined,
      )
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1', 'c2']))
      const newRef = { id: 'c3' } as any
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => {
        if (args.length === 1) return newRef
        return { id: args[args.length - 1] } as any
      })
      const { setSpy } = setupSaveBatch()

      await repository.saveNoteWithChildren(
        'note_1',
        [
          { content: 'parent', noteId: 'note_1' },
          { content: '\tfirst', noteId: 'c1' },
          { content: '\tour-add', noteId: null },
        ],
        ['c1'], // localBase: editor saw [c1] only
      )

      const rootWrite = setSpy.mock.calls.find((c: any[]) => (c[0] as { id: string }).id === 'note_1')
      expect(rootWrite).toBeDefined()
      // local [c1, c3] + remote-only [c2] (in remote relative order) = [c1, c3, c2]
      expect(rootWrite![1].containedNotes).toEqual(['c1', 'c3', 'c2'])
      // containedNotesBase records what we merged from.
      expect(rootWrite![1].containedNotesBase).toEqual(['c1'])
    })

    it('does not soft-delete a concurrently-added subtree', async () => {
      // Editor loaded with localBase = [c1]. Remote added subtree rooted at c2,
      // with c2's child c2a. Without the survivor extension, our save would
      // soft-delete c2 + c2a since they're not in our trackedLines.
      const root = note({ id: 'note_1', containedNotes: ['c1', 'c2'] })
      const c1 = note({ id: 'c1', content: 'first', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      const c2 = note({
        id: 'c2', content: 'concurrent root',
        parentNoteId: 'note_1', rootNoteId: 'note_1',
        containedNotes: ['c2a'],
      })
      const c2a = note({
        id: 'c2a', content: 'concurrent child',
        parentNoteId: 'c2', rootNoteId: 'note_1',
      })
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : id === 'c2' ? c2 : id === 'c2a' ? c2a : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : id === 'c2' ? c2 : id === 'c2a' ? c2a : undefined,
      )
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1', 'c2', 'c2a']))
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { setSpy } = setupSaveBatch()

      await repository.saveNoteWithChildren(
        'note_1',
        [
          { content: 'parent', noteId: 'note_1' },
          { content: '\tfirst', noteId: 'c1' },
        ],
        ['c1'], // localBase
      )

      // Neither c2 nor c2a should be soft-deleted: each would carry state='deleted'.
      const deletes = setSpy.mock.calls.filter((c: any[]) => (c[1] as { state?: string }).state === 'deleted')
      const deleteIds = deletes.map((c: any[]) => (c[0] as { id: string }).id)
      expect(deleteIds).not.toContain('c2')
      expect(deleteIds).not.toContain('c2a')
    })

    it('respects a concurrent remote removal', async () => {
      // Editor loaded with [c1, c2]. Other client removed c1 (remote = [c2]).
      // We didn't change anything locally. Save should land containedNotes=[c2]
      // (their removal honored). Soft-delete of c1 follows naturally because
      // it isn't in survivingIds.
      const root = note({ id: 'note_1', containedNotes: ['c2'] })
      const c2 = note({ id: 'c2', content: 'kept', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c2' ? c2 : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c2' ? c2 : undefined,
      )
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c2']))
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { setSpy } = setupSaveBatch()

      await repository.saveNoteWithChildren(
        'note_1',
        [
          { content: 'parent', noteId: 'note_1' },
          { content: '\tc1-stale', noteId: 'c1' },
          { content: '\tkept', noteId: 'c2' },
        ],
        ['c1', 'c2'], // localBase: editor still believes c1 exists
      )

      const rootWrite = setSpy.mock.calls.find((c: any[]) => (c[0] as { id: string }).id === 'note_1')
      expect(rootWrite).toBeDefined()
      // Remote removed c1 → final containedNotes drops it.
      expect(rootWrite![1].containedNotes).toEqual(['c2'])
    })
  })

  // endregion

  // region Cross-note move via cut-delete (Phase 5)

  describe('saveNoteWithChildren — cut buffer integration', () => {
    afterEach(() => {
      // Singleton noteStore — clear the cut buffer so tests don't leak.
      for (const id of noteStore.getPendingCuts().keys()) noteStore.clearPendingCut(id)
    })

    it('skips soft-delete for a line in the cut buffer (cross-note move pending)', async () => {
      // Editor cut line c1 from note_1; pendingCuts has c1. The editor saves
      // note_1 (no longer has c1). Without Phase 5, c1 would be soft-deleted —
      // and the destination's revive would race the delete.
      const root = note({ id: 'note_1', containedNotes: ['c1'] })
      const c1 = note({ id: 'c1', content: 'cut me', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1']))
      noteStore.recordCut('c1', 'cut me')
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { setSpy } = setupSaveBatch()

      await repository.saveNoteWithChildren(
        'note_1',
        [{ content: 'parent', noteId: 'note_1' }],
        ['c1'],
      )

      const c1Writes = setSpy.mock.calls.filter((c: any[]) => (c[0] as { id: string }).id === 'c1')
      const softDelete = c1Writes.find((c: any[]) => (c[1] as { state?: string }).state === 'deleted')
      expect(softDelete).toBeUndefined()
      const cutDelete = c1Writes.find((c: any[]) => (c[1] as { state?: string }).state === 'cut-delete')
      expect(cutDelete).toBeDefined()
    })

    it('writes cut-delete for an unreclaimed cut whose source note is not in the batch', async () => {
      // c1 was cut but the user is saving an unrelated note (note_2). c1's
      // source-note save isn't part of this batch, so we still need to park
      // it via cut-delete so it doesn't reappear via reconstruction.
      const root2 = note({ id: 'note_2', containedNotes: [] })
      const c1 = note({ id: 'c1', content: 'cut me', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_2' ? root2 : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_2' ? root2 : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set())
      noteStore.recordCut('c1', 'cut me')
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { setSpy } = setupSaveBatch()

      await repository.saveNoteWithChildren(
        'note_2',
        [{ content: 'note 2', noteId: 'note_2' }],
        [],
      )

      const cutDelete = setSpy.mock.calls.find(
        (c: any[]) => (c[0] as { id: string }).id === 'c1' && (c[1] as { state?: string }).state === 'cut-delete',
      )
      expect(cutDelete).toBeDefined()
    })

    it('does NOT write cut-delete for a line revived in the same batch', async () => {
      // c1 cut from note_1 then pasted into note_2 (same session). Both notes
      // save in the atomic batch: source drops c1 (without soft-delete), dest
      // includes c1 in its tracked lines (revives state=null). buildCutDeleteOps
      // sees c1 in note_2's survivingIds and skips the cut-delete write.
      const root1 = note({ id: 'note_1', containedNotes: ['c1'] })
      const root2 = note({ id: 'note_2', containedNotes: [] })
      const c1 = note({ id: 'c1', content: 'moved', parentNoteId: 'note_1', rootNoteId: 'note_1' })
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_1' ? root1 : id === 'note_2' ? root2 : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_1' ? root1 : id === 'note_2' ? root2 : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getDescendantIds').mockImplementation((id: string) =>
        id === 'note_1' ? new Set(['c1']) : new Set(),
      )
      noteStore.recordCut('c1', 'moved')
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { setSpy } = setupSaveBatch()

      await repository.saveMultipleNotes([
        { noteId: 'note_1', localBase: ['c1'], trackedLines: [
          { content: 'note 1', noteId: 'note_1' },
        ] },
        { noteId: 'note_2', localBase: [], trackedLines: [
          { content: 'note 2', noteId: 'note_2' },
          { content: '\tmoved', noteId: 'c1' },
        ] },
      ])

      const c1Writes = setSpy.mock.calls.filter((c: any[]) => (c[0] as { id: string }).id === 'c1')
      // Should be revived (state=null via merge) — not soft-deleted, not cut-delete.
      const softDelete = c1Writes.find((c: any[]) => (c[1] as { state?: string }).state === 'deleted')
      const cutDelete = c1Writes.find((c: any[]) => (c[1] as { state?: string }).state === 'cut-delete')
      expect(softDelete).toBeUndefined()
      expect(cutDelete).toBeUndefined()
      // The revive write sets state=null and reparents.
      const revive = c1Writes.find((c: any[]) => (c[1] as { state?: string | null }).state === null)
      expect(revive).toBeDefined()
      expect(revive![1].parentNoteId).toBe('note_2')
    })

    it('clears pendingCuts after commit', async () => {
      const root = note({ id: 'note_1', containedNotes: [] })
      const c1 = note({ id: 'c1', content: 'orphan', parentNoteId: 'old_root', rootNoteId: 'old_root' })
      vi.spyOn(noteStore, 'getNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'note_1' ? root : id === 'c1' ? c1 : undefined,
      )
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set())
      noteStore.recordCut('c1', 'orphan')
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      setupSaveBatch()

      expect(noteStore.getPendingCuts().has('c1')).toBe(true)
      await repository.saveNoteWithChildren(
        'note_1',
        [{ content: 'note 1', noteId: 'note_1' }],
        [],
      )
      expect(noteStore.getPendingCuts().has('c1')).toBe(false)
    })
  })

  // endregion

  // region restoreCutDeletedNotes (Phase 6)

  describe('restoreCutDeletedNotes', () => {
    it('flips state=null for each id and stamps a fresh opId', async () => {
      const c1 = note({ id: 'c1', content: 'a', state: 'cut-delete', parentNoteId: 'r', rootNoteId: 'r', version: 3 })
      const c2 = note({ id: 'c2', content: 'b', state: 'cut-delete', parentNoteId: 'r', rootNoteId: 'r', version: 1 })
      vi.spyOn(noteStore, 'getRawNoteById').mockImplementation((id) =>
        id === 'c1' ? c1 : id === 'c2' ? c2 : undefined,
      )
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { batch, setSpy } = setupSaveBatch()

      await repository.restoreCutDeletedNotes(['c1', 'c2'])

      expect(batch.commit).toHaveBeenCalled()
      const writes = setSpy.mock.calls
      expect(writes.length).toBe(2)
      // Every write sets state=null and bumps version (3→4, 1→2).
      const c1Write = writes.find((c: any[]) => c[1].version === 4)
      const c2Write = writes.find((c: any[]) => c[1].version === 2)
      expect(c1Write).toBeDefined()
      expect(c2Write).toBeDefined()
      expect(c1Write![1].state).toBeNull()
      expect(c2Write![1].state).toBeNull()
      // Every write carries a single shared lastWriterOpId.
      const opIds = writes.map((c: any[]) => c[1].lastWriterOpId)
      expect(opIds.every((id) => id != null && id === opIds[0])).toBe(true)
    })

    it('skips ids that are not in NoteStore', async () => {
      // E.g., the doc was hard-deleted between the user opening Recover and
      // pressing Restore. Skip rather than write a phantom entry.
      vi.spyOn(noteStore, 'getRawNoteById').mockReturnValue(undefined)
      vi.mocked(mockDoc).mockImplementation((...args: any[]) => ({ id: args[args.length - 1] } as any))
      const { batch, setSpy } = setupSaveBatch()

      await repository.restoreCutDeletedNotes(['ghost-1', 'ghost-2'])

      expect(setSpy.mock.calls.length).toBe(0)
      expect(batch.commit).not.toHaveBeenCalled()
    })

    it('is a no-op for empty input', async () => {
      const { batch } = setupSaveBatch()
      await repository.restoreCutDeletedNotes([])
      expect(batch.commit).not.toHaveBeenCalled()
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

      const result = await repository.saveNoteWithChildren('root', [
        { content: 'Root', noteId: 'root' },
        { content: '\tA', noteId: null },
        { content: '\t\tB', noteId: null },
      ], null)

      expect(result.get(1)).toBe('child_a')
      expect(result.get(2)).toBe('child_b')
    })

    it('returns empty map for empty lines', async () => {
      const result = await repository.saveNoteWithChildren('note_1', [], null)

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
    function setupBatch() {
      setupSaveBatch()
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
      setupBatch()

      await expect(
        repository.saveNoteWithChildren('root', [
          { content: 'Root', noteId: 'root' },
          { content: '\tonly survivor', noteId: 'c1' },
        ], null),
      ).rejects.toBeInstanceOf(ContentDropAbortError)
    })

    it('passes when save deletes half or fewer of declared children', async () => {
      vi.spyOn(noteStore, 'getNoteById').mockReturnValue({
        id: 'root',
        content: 'Root',
        containedNotes: ['c1', 'c2', 'c3', 'c4'],
      } as any)
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1', 'c2', 'c3', 'c4']))
      setupBatch()

      await expect(
        repository.saveNoteWithChildren('root', [
          { content: 'Root', noteId: 'root' },
          { content: '\tc1', noteId: 'c1' },
          { content: '\tc2', noteId: 'c2' },
        ], null),
      ).resolves.toBeInstanceOf(Map)
    })

    it('does not trip when fewer than 3 declared children exist', async () => {
      vi.spyOn(noteStore, 'getNoteById').mockReturnValue({
        id: 'root',
        content: 'Root',
        containedNotes: ['c1', 'c2'],
      } as any)
      vi.spyOn(noteStore, 'getDescendantIds').mockReturnValue(new Set(['c1', 'c2']))
      setupBatch()

      await expect(
        repository.saveNoteWithChildren('root', [{ content: 'Root', noteId: 'root' }], null),
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
      setupBatch()

      await expect(
        repository.saveNoteWithChildren('root', [
          { content: 'Root', noteId: 'root' },
          { content: '\tc1', noteId: 'c1' },
        ], null),
      ).resolves.toBeInstanceOf(Map)
    })
  })

  // endregion

  // region NoteStore-loaded invariant guard

  describe('NoteStore-loaded invariant', () => {
    it('saveNoteWithChildren throws NoteStoreNotLoadedError when NoteStore not loaded', async () => {
      vi.spyOn(noteStore, 'isLoaded').mockReturnValue(false)
      await expect(
        repository.saveNoteWithChildren('note_1', [{ content: 'Hello', noteId: 'note_1' }], null),
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
