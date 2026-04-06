import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import { Executor } from '../../../dsl/runtime/Executor'
import { Environment } from '../../../dsl/runtime/Environment'
import type { DslValue, ListVal, ViewVal, NoteVal } from '../../../dsl/runtime/DslValue'
import {
  toDisplayString,
  noteVal,
  listVal,
  viewVal,
  serializeValue,
  deserializeValue,
} from '../../../dsl/runtime/DslValue'
import type { Note } from '../../../data/Note'
import { Timestamp } from 'firebase/firestore'

function execute(source: string, env?: Environment): DslValue {
  const tokens = new Lexer(source).tokenize()
  const directive = new Parser(tokens, source).parseDirective()
  return new Executor().execute(directive, env ?? Environment.create())
}

function display(source: string, env?: Environment): string {
  return toDisplayString(execute(source, env))
}

function makeNote(overrides: Partial<Note> & { id: string }): Note {
  return {
    userId: 'user1',
    parentNoteId: null,
    content: overrides.content ?? '',
    createdAt: overrides.createdAt ?? null,
    updatedAt: overrides.updatedAt ?? null,
    lastAccessedAt: overrides.lastAccessedAt ?? null,
    tags: [],
    containedNotes: [],
    state: null,
    path: overrides.path ?? '',
    rootNoteId: null,
    showCompleted: true,
    ...overrides,
  }
}

describe('NoteFunctions', () => {
  describe('find', () => {
    it('should return empty list when no notes available', () => {
      const result = execute('[find()]')
      expect(result.kind).toBe('ListVal')
      expect((result as ListVal).items).toHaveLength(0)
    })

    it('should return empty list when notes array is empty', () => {
      const env = Environment.withNotes([])
      const result = execute('[find()]', env)
      expect(result.kind).toBe('ListVal')
      expect((result as ListVal).items).toHaveLength(0)
    })

    it('should find note by exact path', () => {
      const note1 = makeNote({ id: 'n1', path: 'tasks/daily', content: 'Daily Tasks' })
      const note2 = makeNote({ id: 'n2', path: 'tasks/weekly', content: 'Weekly Tasks' })
      const env = Environment.withNotes([note1, note2])
      const result = execute('[find(path: "tasks/daily")]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(1)
      expect((result.items[0] as NoteVal).note.id).toBe('n1')
    })

    it('should find notes by pattern match on path', () => {
      const note1 = makeNote({ id: 'n1', path: 'tasks/daily', content: 'Daily' })
      const note2 = makeNote({ id: 'n2', path: 'tasks/weekly', content: 'Weekly' })
      const note3 = makeNote({ id: 'n3', path: 'notes/misc', content: 'Misc' })
      const env = Environment.withNotes([note1, note2, note3])
      const result = execute('[find(path: pattern("tasks/" any*any))]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(2)
    })

    it('should find notes by name filter', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'Daily Tasks' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'Weekly Tasks' })
      const env = Environment.withNotes([note1, note2])
      const result = execute('[find(name: "Daily Tasks")]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(1)
      expect((result.items[0] as NoteVal).note.id).toBe('n1')
    })

    it('should combine path and name filters', () => {
      const note1 = makeNote({ id: 'n1', path: 'tasks/a', content: 'Alpha' })
      const note2 = makeNote({ id: 'n2', path: 'tasks/b', content: 'Beta' })
      const note3 = makeNote({ id: 'n3', path: 'other/c', content: 'Alpha' })
      const env = Environment.withNotes([note1, note2, note3])
      const result = execute('[find(path: pattern("tasks/" any*any), name: "Alpha")]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(1)
      expect((result.items[0] as NoteVal).note.id).toBe('n1')
    })

    it('should find all notes without path filter', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'One' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'Two' })
      const env = Environment.withNotes([note1, note2])
      const result = execute('[find()]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(2)
    })

    it('should exclude soft-deleted notes from results', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'Active Note' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'Deleted Note', state: 'deleted' })
      const note3 = makeNote({ id: 'n3', path: 'c', content: 'Another Active' })
      const env = Environment.withNotes([note1, note2, note3])
      const result = execute('[find()]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(2)
      const ids = result.items.map((item) => (item as NoteVal).note.id)
      expect(ids).toContain('n1')
      expect(ids).toContain('n3')
      expect(ids).not.toContain('n2')
    })

    it('should exclude soft-deleted notes even when matching by name pattern', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'examples of things' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'examples deleted', state: 'deleted' })
      const env = Environment.withNotes([note1, note2])
      const result = execute('[find(name: pattern("examples" any*any))]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(1)
      expect((result.items[0] as NoteVal).note.id).toBe('n1')
    })

    it('should exclude current note from results', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'One' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'Two' })
      const env = Environment.withNotesAndCurrentNote([note1, note2], note1)
      const result = execute('[find()]', env) as ListVal
      expect(result.kind).toBe('ListVal')
      expect(result.items).toHaveLength(1)
      expect((result.items[0] as NoteVal).note.id).toBe('n2')
    })
  })

  describe('NoteVal display strings', () => {
    it('should display note with path', () => {
      const note = makeNote({ id: 'n1', path: 'tasks/daily', content: 'Daily Tasks' })
      const nv = noteVal(note)
      expect(toDisplayString(nv)).toBe('tasks/daily')
    })

    it('should display note without path using first content line', () => {
      const note = makeNote({ id: 'n1', path: '', content: 'My Note\nLine 2' })
      const nv = noteVal(note)
      expect(toDisplayString(nv)).toBe('My Note')
    })

    it('should display note with empty path and empty content using id', () => {
      const note = makeNote({ id: 'n1', path: '', content: '' })
      const nv = noteVal(note)
      expect(toDisplayString(nv)).toBe('n1')
    })
  })

  describe('ListVal display strings', () => {
    it('should display empty list', () => {
      expect(toDisplayString(listVal([]))).toBe('[]')
    })

    it('should display list with items', () => {
      const list = listVal([noteVal(makeNote({ id: 'n1', path: 'a' })), noteVal(makeNote({ id: 'n2', path: 'b' }))])
      expect(toDisplayString(list)).toBe('[a, b]')
    })
  })

  describe('NoteVal serialization', () => {
    it('should serialize and deserialize note', () => {
      const ts = Timestamp.fromDate(new Date(2025, 0, 15))
      const note = makeNote({ id: 'n1', path: 'tasks/daily', content: 'Daily', createdAt: ts, updatedAt: ts })
      const nv = noteVal(note)
      const serialized = serializeValue(nv)
      const deserialized = deserializeValue(serialized)
      expect(deserialized.kind).toBe('NoteVal')
      expect((deserialized as NoteVal).note.id).toBe('n1')
      expect((deserialized as NoteVal).note.path).toBe('tasks/daily')
      expect((deserialized as NoteVal).note.content).toBe('Daily')
    })
  })

  describe('view', () => {
    it('should create empty view', () => {
      const result = execute('[view(list())]')
      expect(result.kind).toBe('ViewVal')
      expect((result as ViewVal).notes).toHaveLength(0)
    })

    it('should create view with single note', () => {
      const note = makeNote({ id: 'n1', path: 'test', content: 'Hello World' })
      const env = Environment.withNotes([note])
      const result = execute('[view(find())]', env) as ViewVal
      expect(result.kind).toBe('ViewVal')
      expect(result.notes).toHaveLength(1)
      expect(result.notes[0]!.content).toBe('Hello World')
    })

    it('should create view with multiple notes', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'Note A' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'Note B' })
      const env = Environment.withNotes([note1, note2])
      const result = execute('[view(find())]', env) as ViewVal
      expect(result.kind).toBe('ViewVal')
      expect(result.notes).toHaveLength(2)
    })

    it('should create view with sorted notes', () => {
      const note1 = makeNote({ id: 'n1', path: 'b', content: 'Note B' })
      const note2 = makeNote({ id: 'n2', path: 'a', content: 'Note A' })
      const env = Environment.withNotes([note1, note2])
      const result = execute('[view(sort(find()))]', env) as ViewVal
      expect(result.kind).toBe('ViewVal')
      expect(result.notes).toHaveLength(2)
    })

    it('should display empty view', () => {
      expect(display('[view(list())]')).toBe('[empty view]')
    })

    it('should display single note view', () => {
      const note = makeNote({ id: 'n1', path: 'test', content: 'Hello' })
      const env = Environment.withNotes([note])
      expect(display('[view(find())]', env)).toBe('Hello')
    })

    it('should display multiple note view with separator', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'First' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'Second' })
      const env = Environment.withNotes([note1, note2])
      expect(display('[view(find())]', env)).toBe('First\n---\nSecond')
    })

    it('should exclude current note from view', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'First' })
      const note2 = makeNote({ id: 'n2', path: 'b', content: 'Second' })
      const env = Environment.withNotesAndCurrentNote([note1, note2], note1)
      const result = execute('[view(find())]', env) as ViewVal
      expect(result.notes).toHaveLength(1)
      expect(result.notes[0]!.id).toBe('n2')
    })
  })

  describe('ViewVal serialization', () => {
    it('should serialize and deserialize view', () => {
      const note = makeNote({ id: 'n1', path: 'test', content: 'Hello' })
      const vv = viewVal([note], ['Hello'])
      const serialized = serializeValue(vv)
      const deserialized = deserializeValue(serialized)
      expect(deserialized.kind).toBe('ViewVal')
      const dv = deserialized as ViewVal
      expect(dv.notes).toHaveLength(1)
      expect(dv.notes[0]!.id).toBe('n1')
      expect(dv.renderedContents).toEqual(['Hello'])
    })

    it('should serialize and deserialize empty view', () => {
      const vv = viewVal([], null)
      const serialized = serializeValue(vv)
      const deserialized = deserializeValue(serialized)
      expect(deserialized.kind).toBe('ViewVal')
      expect((deserialized as ViewVal).notes).toHaveLength(0)
    })
  })

  describe('circular dependency detection', () => {
    it('should detect circular view dependency', () => {
      const note1 = makeNote({ id: 'n1', path: 'a', content: 'Content A' })
      const env = Environment.withNotes([note1])
      // Push n1 onto the view stack, then try to view n1 again
      const envWithStack = env.pushViewStack('n1')
      expect(() => execute('[view(find())]', envWithStack)).toThrow(/[Cc]ircular/)
    })
  })

  describe('unknown named arguments', () => {
    it('should reject unknown named arg on view', () => {
      expect(() => execute('[view(find(), order: descending)]'))
        .toThrow("'view' does not accept named argument 'order'")
    })

    it('should reject unknown named arg on find', () => {
      expect(() => execute('[find(bogus: "x")]'))
        .toThrow("'find' does not accept named argument 'bogus'")
    })

    it('should accept known named args on find', () => {
      const result = execute('[find(path: "tasks")]')
      expect(result.kind).toBe('ListVal')
    })
  })
})
