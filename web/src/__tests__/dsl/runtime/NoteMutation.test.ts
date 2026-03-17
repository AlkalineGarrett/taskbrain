import { describe, it, expect, vi } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import { Executor } from '../../../dsl/runtime/Executor'
import { Environment } from '../../../dsl/runtime/Environment'
import type { DslValue, NumberVal, StringVal, NoteVal } from '../../../dsl/runtime/DslValue'
import type { NoteOperations } from '../../../dsl/runtime/NoteOperations'
import type { Note } from '../../../data/Note'

function execute(source: string, env?: Environment): DslValue {
  const tokens = new Lexer(source).tokenize()
  const directive = new Parser(tokens, source).parseDirective()
  return new Executor().execute(directive, env ?? Environment.create())
}

/**
 * Execute a directive that contains async operations (new, maybe_new, append, property set).
 * The executor returns a Promise wrapped as a DslValue for async builtins.
 */
async function executeAsync(source: string, env: Environment): Promise<DslValue> {
  const result = execute(source, env)
  // If the result is a Promise (from async builtin), await it
  if (result && typeof (result as unknown as Promise<DslValue>).then === 'function') {
    return (result as unknown as Promise<DslValue>)
  }
  return result
}

function makeNote(overrides: Partial<Note> & { id: string }): Note {
  return {
    userId: 'user1',
    parentNoteId: null,
    content: overrides.content ?? '',
    createdAt: null,
    updatedAt: null,
    lastAccessedAt: null,
    tags: [],
    containedNotes: [],
    state: null,
    path: overrides.path ?? '',
    rootNoteId: null,
    showCompleted: true,
    ...overrides,
  }
}

function createMockNoteOperations(notes: Note[] = []): NoteOperations {
  const noteMap = new Map(notes.map((n) => [n.id, { ...n }]))

  return {
    createNote: vi.fn(async (path: string, content: string): Promise<Note> => {
      const note = makeNote({ id: `new-${Date.now()}`, path, content })
      noteMap.set(note.id, note)
      return note
    }),
    getNoteById: vi.fn(async (noteId: string): Promise<Note | null> => {
      return noteMap.get(noteId) ?? null
    }),
    findByPath: vi.fn(async (path: string): Promise<Note | null> => {
      for (const note of noteMap.values()) {
        if (note.path === path) return note
      }
      return null
    }),
    noteExistsAtPath: vi.fn(async (path: string): Promise<boolean> => {
      for (const note of noteMap.values()) {
        if (note.path === path) return true
      }
      return false
    }),
    updatePath: vi.fn(async (noteId: string, newPath: string): Promise<Note> => {
      const note = noteMap.get(noteId)
      if (!note) throw new Error(`Note not found: ${noteId}`)
      const updated = { ...note, path: newPath }
      noteMap.set(noteId, updated)
      return updated
    }),
    updateContent: vi.fn(async (noteId: string, newContent: string): Promise<Note> => {
      const note = noteMap.get(noteId)
      if (!note) throw new Error(`Note not found: ${noteId}`)
      const updated = { ...note, content: newContent }
      noteMap.set(noteId, updated)
      return updated
    }),
    appendToNote: vi.fn(async (noteId: string, text: string): Promise<Note> => {
      const note = noteMap.get(noteId)
      if (!note) throw new Error(`Note not found: ${noteId}`)
      const updated = { ...note, content: note.content + '\n' + text }
      noteMap.set(noteId, updated)
      return updated
    }),
  }
}

describe('NoteMutation', () => {
  describe('variable assignment', () => {
    it('should assign simple value', () => {
      const result = execute('[x: 42; x]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(42)
    })

    it('should assign string value', () => {
      const result = execute('[x: "hello"; x]')
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('hello')
    })

    it('should assign variable referencing another variable', () => {
      const result = execute('[x: 10; y: x; y]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(10)
    })

    it('should handle multiple variable assignments', () => {
      const result = execute('[a: 1; b: 2; add(a, b)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(3)
    })

    it('should throw on undefined variable', () => {
      expect(() => execute('[undefined_var]')).toThrow()
    })
  })

  describe('semicolons', () => {
    it('should execute statements separated by semicolons', () => {
      const result = execute('[x: 1; y: 2; add(x, y)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(3)
    })

    it('should return last statement value', () => {
      const result = execute('[1; 2; 3]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(3)
    })
  })

  describe('property assignment - path', () => {
    it('should assign path property', () => {
      const note = makeNote({ id: 'n1', path: 'old/path', content: 'Test' })
      const ops = createMockNoteOperations([note])
      const env = Environment.withAll([note], note, ops)

      // setProperty is async but the executor fires-and-forgets it;
      // we just verify the mock was called.
      execute('[.path: "new/path"]', env)

      expect(ops.updatePath).toHaveBeenCalledWith('n1', 'new/path')
    })
  })

  describe('property assignment - name', () => {
    it('should assign name property', () => {
      const note = makeNote({ id: 'n1', path: 'test', content: 'Old Name\nLine 2' })
      const ops = createMockNoteOperations([note])
      const env = Environment.withAll([note], note, ops)

      execute('[.name: "New Name"]', env)

      expect(ops.getNoteById).toHaveBeenCalledWith('n1')
    })
  })

  describe('method calls - append', () => {
    it('should call append method', () => {
      const note = makeNote({ id: 'n1', path: 'test', content: 'Initial' })
      const ops = createMockNoteOperations([note])
      const env = Environment.withAll([note], note, ops)

      execute('[.append("Added line")]', env)

      expect(ops.appendToNote).toHaveBeenCalledWith('n1', 'Added line')
    })
  })

  describe('new function', () => {
    it('should create new note', async () => {
      const ops = createMockNoteOperations([])
      const env = Environment.withNoteOperations(ops)

      // new() is async, so the executor returns a Promise as a DslValue
      await executeAsync('[new(path: "test/new")]', env)

      expect(ops.noteExistsAtPath).toHaveBeenCalledWith('test/new')
      expect(ops.createNote).toHaveBeenCalledWith('test/new', '')
    })

    it('should create new note with content', async () => {
      const ops = createMockNoteOperations([])
      const env = Environment.withNoteOperations(ops)

      await executeAsync('[new(path: "test/new", content: "Hello")]', env)

      expect(ops.createNote).toHaveBeenCalledWith('test/new', 'Hello')
    })

    it('should throw when note already exists', async () => {
      const existing = makeNote({ id: 'n1', path: 'test/existing', content: 'Existing' })
      const ops = createMockNoteOperations([existing])
      const env = Environment.withNoteOperations(ops)

      await expect(executeAsync('[new(path: "test/existing")]', env)).rejects.toThrow()
    })

    it('should reject without path argument', async () => {
      const ops = createMockNoteOperations([])
      const env = Environment.withNoteOperations(ops)

      // new() is async; missing path throws inside the async fn, resulting in rejection
      await expect(executeAsync('[new()]', env)).rejects.toThrow(/path/)
    })
  })

  describe('maybe_new function', () => {
    it('should create note when it does not exist', async () => {
      const ops = createMockNoteOperations([])
      const env = Environment.withNoteOperations(ops)

      await executeAsync('[maybe_new(path: "test/new")]', env)

      expect(ops.findByPath).toHaveBeenCalledWith('test/new')
      expect(ops.createNote).toHaveBeenCalledWith('test/new', '')
    })

    it('should return existing note when it exists', async () => {
      const existing = makeNote({ id: 'n1', path: 'test/existing', content: 'Existing' })
      const ops = createMockNoteOperations([existing])
      const env = Environment.withNoteOperations(ops)

      const result = await executeAsync('[maybe_new(path: "test/existing")]', env)

      expect(ops.findByPath).toHaveBeenCalledWith('test/existing')
      expect(result.kind).toBe('NoteVal')
      expect((result as NoteVal).note.id).toBe('n1')
    })

    it('should reject without path argument', async () => {
      const ops = createMockNoteOperations([])
      const env = Environment.withNoteOperations(ops)

      await expect(executeAsync('[maybe_new()]', env)).rejects.toThrow(/path/)
    })
  })

  describe('combined functionality', () => {
    it('should assign variable and use it in expression', () => {
      const result = execute('[x: 5; y: 3; mul(x, y)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(15)
    })

    it('should assign computed value to variable', () => {
      const result = execute('[x: add(2, 3); mul(x, x)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(25)
    })

    it('should chain variable assignments', () => {
      const result = execute('[a: 1; b: add(a, 1); c: add(b, 1); c]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(3)
    })

    it('should use note variables in expressions', () => {
      const note = makeNote({ id: 'n1', path: 'tasks/daily', content: 'My Task' })
      const env = Environment.withNotesAndCurrentNote([note], note)

      const result = execute('[n: .; n.path]', env)
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('tasks/daily')
    })

    it('should access note name via property', () => {
      const note = makeNote({ id: 'n1', path: 'test', content: 'Note Title\nBody' })
      const env = Environment.withNotesAndCurrentNote([note], note)

      const result = execute('[.name]', env)
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('Note Title')
    })

    it('should access note id via property', () => {
      const note = makeNote({ id: 'n1', path: 'test', content: 'Content' })
      const env = Environment.withNotesAndCurrentNote([note], note)

      const result = execute('[.id]', env)
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('n1')
    })
  })
})
