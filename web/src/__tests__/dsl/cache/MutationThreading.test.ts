import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { Note } from '../../../data/Note'
import type { NoteMutation } from '../../../dsl/runtime/NoteMutation'
import { MutationType } from '../../../dsl/runtime/NoteMutation'
import { Timestamp } from 'firebase/firestore'
import { numberVal, stringVal } from '../../../dsl/runtime/DslValue'
import { directiveResultSuccess } from '../../../dsl/directives/DirectiveResult'

// Mock executeDirectiveWithMutations to control mutation output
const mockExecute = vi.fn()
vi.mock('../../../dsl/directives/DirectiveExecutor', () => ({
  executeDirectiveWithMutations: (...args: unknown[]) => mockExecute(...args),
}))

// Import after mock so the mock is wired in
import { CachedDirectiveExecutor } from '../../../dsl/cache/CachedDirectiveExecutor'

// Valid DSL expressions (must include brackets for parseDirective)
const READ_DIRECTIVE = '["hello"]'
const MUTATING_DIRECTIVE = '[.path: "new/path"]'

function makeNote(id: string, content = 'Content', path = `path/${id}`): Note {
  return {
    id,
    userId: 'user1',
    parentNoteId: null,
    content,
    createdAt: Timestamp.fromMillis(1000),
    updatedAt: Timestamp.fromMillis(2000),
    tags: [],
    containedNotes: [],
    state: null,
    path,
    rootNoteId: null,
    showCompleted: true,
    onceCache: {},
  }
}

function makeMutation(noteId: string, note: Note): NoteMutation {
  return {
    noteId,
    updatedNote: note,
    mutationType: MutationType.CONTENT_CHANGED,
  }
}

describe('CachedDirectiveExecutor mutation threading', () => {
  let executor: CachedDirectiveExecutor
  const currentNote = makeNote('note1')
  const notes = [currentNote]

  beforeEach(() => {
    vi.clearAllMocks()
    executor = new CachedDirectiveExecutor()
  })

  describe('fresh execution returns mutations', () => {
    it('threads mutations from underlying executor on cache miss', () => {
      const mutation = makeMutation('note1', currentNote)
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(numberVal(42)),
        mutations: [mutation],
      })

      const result = executor.execute(READ_DIRECTIVE, notes, currentNote)

      expect(result.cacheHit).toBe(false)
      expect(result.mutations).toHaveLength(1)
      expect(result.mutations[0]!.noteId).toBe('note1')
      expect(result.mutations[0]!.mutationType).toBe(MutationType.CONTENT_CHANGED)
    })

    it('threads empty mutations when underlying executor produces none', () => {
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(numberVal(10)),
        mutations: [],
      })

      const result = executor.execute(READ_DIRECTIVE, notes, currentNote)

      expect(result.cacheHit).toBe(false)
      expect(result.mutations).toEqual([])
    })
  })

  describe('cache hit returns empty mutations', () => {
    it('returns empty mutations on second execution (cache hit)', () => {
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(numberVal(42)),
        mutations: [],
      })

      // First execution: cache miss
      const first = executor.execute(READ_DIRECTIVE, notes, currentNote)
      expect(first.cacheHit).toBe(false)

      // Second execution: cache hit
      const second = executor.execute(READ_DIRECTIVE, notes, currentNote)
      expect(second.cacheHit).toBe(true)
      expect(second.mutations).toEqual([])
    })

    it('does not replay original mutations on cache hit', () => {
      const mutation = makeMutation('note1', currentNote)
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(stringVal('hello')),
        mutations: [mutation],
      })

      // First execution returns the mutation
      const first = executor.execute(READ_DIRECTIVE, notes, currentNote)
      expect(first.cacheHit).toBe(false)
      expect(first.mutations).toHaveLength(1)

      // Second execution: cache hit with empty mutations
      const second = executor.execute(READ_DIRECTIVE, notes, currentNote)
      expect(second.cacheHit).toBe(true)
      expect(second.mutations).toEqual([])
    })
  })

  describe('mutating directive cache behavior', () => {
    it('caches result on first execution of mutating directive', () => {
      const updatedNote = makeNote('note1', 'Content', 'new/path')
      const mutation = makeMutation('note1', updatedNote)
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(stringVal('new/path')),
        mutations: [mutation],
      })

      const first = executor.execute(MUTATING_DIRECTIVE, notes, currentNote)
      expect(first.cacheHit).toBe(false)
      expect(first.mutations).toHaveLength(1)

      // Second execution hits cache
      const second = executor.execute(MUTATING_DIRECTIVE, notes, currentNote)
      expect(second.cacheHit).toBe(true)
    })

    it('returns empty mutations on cache hit for mutating directive', () => {
      const updatedNote = makeNote('note1', 'Content', 'new/path')
      const mutation = makeMutation('note1', updatedNote)
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(stringVal('new/path')),
        mutations: [mutation],
      })

      executor.execute(MUTATING_DIRECTIVE, notes, currentNote)
      const second = executor.execute(MUTATING_DIRECTIVE, notes, currentNote)

      expect(second.cacheHit).toBe(true)
      expect(second.mutations).toEqual([])
    })

    it('does not re-execute mutating directive even when notes change', () => {
      const updatedNote = makeNote('note1', 'Content', 'new/path')
      const mutation = makeMutation('note1', updatedNote)
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(stringVal('new/path')),
        mutations: [mutation],
      })

      executor.execute(MUTATING_DIRECTIVE, notes, currentNote)

      // Even with changed notes, mutating directive uses cache (Step 3 in execute())
      const changedNotes = [...notes, makeNote('note2')]
      const second = executor.execute(MUTATING_DIRECTIVE, changedNotes, currentNote)

      expect(second.cacheHit).toBe(true)
      expect(second.mutations).toEqual([])
      // The underlying executor should only have been called once
      expect(mockExecute).toHaveBeenCalledTimes(1)
    })
  })

  describe('CachedExecutionResult interface', () => {
    it('includes all required fields on cache miss', () => {
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(numberVal(5)),
        mutations: [],
      })

      const result = executor.execute(READ_DIRECTIVE, notes, currentNote)

      expect(result).toHaveProperty('result')
      expect(result).toHaveProperty('cacheHit')
      expect(result).toHaveProperty('dependencies')
      expect(result).toHaveProperty('mutations')
      expect(Array.isArray(result.mutations)).toBe(true)
    })

    it('includes all required fields on cache hit', () => {
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(numberVal(5)),
        mutations: [],
      })

      executor.execute(READ_DIRECTIVE, notes, currentNote)
      const result = executor.execute(READ_DIRECTIVE, notes, currentNote)

      expect(result).toHaveProperty('result')
      expect(result).toHaveProperty('cacheHit')
      expect(result).toHaveProperty('dependencies')
      expect(result).toHaveProperty('mutations')
      expect(Array.isArray(result.mutations)).toBe(true)
    })
  })

  describe('multiple mutations', () => {
    it('threads multiple mutations from a single execution', () => {
      const note2 = makeNote('note2')
      const mutations: NoteMutation[] = [
        makeMutation('note1', currentNote),
        { noteId: 'note2', updatedNote: note2, mutationType: MutationType.PATH_CHANGED },
      ]
      mockExecute.mockReturnValue({
        result: directiveResultSuccess(stringVal('done')),
        mutations,
      })

      const result = executor.execute(READ_DIRECTIVE, notes, currentNote)

      expect(result.mutations).toHaveLength(2)
      expect(result.mutations[0]!.mutationType).toBe(MutationType.CONTENT_CHANGED)
      expect(result.mutations[1]!.mutationType).toBe(MutationType.PATH_CHANGED)
    })
  })
})
