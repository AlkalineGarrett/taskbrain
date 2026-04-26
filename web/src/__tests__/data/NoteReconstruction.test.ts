import { describe, it, expect } from 'vitest'
import type { Note } from '../../data/Note'
import {
  rebuildAllNotes,
  rebuildAffectedNotes,
  reconstructNoteContent,
  reconstructNoteLines,
  indexChildrenByParent,
} from '../../data/NoteReconstruction'

function note(overrides: Partial<Note> & { id: string }): Note {
  return {
    userId: '',
    parentNoteId: null,
    content: '',
    createdAt: null,
    updatedAt: null,
    tags: [],
    containedNotes: [],
    state: null,
    path: '',
    rootNoteId: null,
    showCompleted: true,
    onceCache: {},
    ...overrides,
  }
}

function toMap(notes: Note[]): Map<string, Note> {
  const map = new Map<string, Note>()
  for (const n of notes) map.set(n.id, n)
  return map
}

// Re-export the production helper with a shorter name for test readability.
const childrenByParent = indexChildrenByParent

describe('rebuildAllNotes', () => {
  it('single root with no children returns as-is', () => {
    const root = note({ id: 'r1', content: 'Hello' })
    const result = rebuildAllNotes(toMap([root]))
    expect(result.notes).toHaveLength(1)
    expect(result.notes[0]).toBe(root)
    expect(result.notesNeedingFix.size).toBe(0)
  })

  it('root with descendants reconstructs content from tree', () => {
    const root = note({ id: 'r1', content: 'Title', containedNotes: ['c1', 'c2'] })
    const c1 = note({ id: 'c1', content: 'Line one', parentNoteId: 'r1', rootNoteId: 'r1' })
    const c2 = note({ id: 'c2', content: 'Line two', parentNoteId: 'r1', rootNoteId: 'r1' })
    const result = rebuildAllNotes(toMap([root, c1, c2]))
    expect(result.notes[0]!.content).toBe('Title\nLine one\nLine two')
    expect(result.notesNeedingFix.size).toBe(0)
  })

  it('orphan refs in containedNotes dropped and mark needsFix', () => {
    const root = note({ id: 'r1', content: 'Root', containedNotes: ['c1', 'c2', 'c3'] })
    const c2 = note({ id: 'c2', content: 'Only child', parentNoteId: 'r1' })
    const result = rebuildAllNotes(toMap([root, c2]))
    expect(result.notes[0]!.content).toBe('Root\nOnly child')
    expect([...result.notesNeedingFix]).toEqual(['r1'])
  })

  it('stray child linked via parentNoteId appended at end and marks needsFix', () => {
    const root = note({ id: 'r1', content: 'Root', containedNotes: ['c1'] })
    const c1 = note({ id: 'c1', content: 'Known', parentNoteId: 'r1' })
    const c2 = note({ id: 'c2', content: 'Stray', parentNoteId: 'r1' })
    const result = rebuildAllNotes(toMap([root, c1, c2]))
    expect(result.notes[0]!.content).toBe('Root\nKnown\nStray')
    expect([...result.notesNeedingFix]).toEqual(['r1'])
  })

  it('all declared refs orphaned but strays present reconstructs from strays', () => {
    const root = note({ id: 'r1', content: 'Alarms', containedNotes: ['orphan1', 'orphan2'] })
    const real1 = note({ id: 'real1', content: 'Real 1', parentNoteId: 'r1' })
    const real2 = note({ id: 'real2', content: 'Real 2', parentNoteId: 'r1' })
    const result = rebuildAllNotes(toMap([root, real1, real2]))
    expect(result.notes[0]!.content).toBe('Alarms\nReal 1\nReal 2')
    expect([...result.notesNeedingFix]).toEqual(['r1'])
  })

  it('deleted child dropped and marks needsFix', () => {
    const root = note({ id: 'r1', content: 'Root', containedNotes: ['c1', 'c2'] })
    const c1 = note({ id: 'c1', content: 'Live', parentNoteId: 'r1' })
    const c2 = note({ id: 'c2', content: 'Deleted', parentNoteId: 'r1', state: 'deleted' })
    const result = rebuildAllNotes(toMap([root, c1, c2]))
    expect(result.notes[0]!.content).toBe('Root\nLive')
    expect([...result.notesNeedingFix]).toEqual(['r1'])
  })

  it('child with mismatched parentNoteId dropped from ref list', () => {
    const root = note({ id: 'r1', content: 'Root', containedNotes: ['c1'] })
    const c1 = note({ id: 'c1', content: 'Not mine', parentNoteId: 'someone-else' })
    const result = rebuildAllNotes(toMap([root, c1]))
    expect(result.notes[0]!.content).toBe('Root')
    expect([...result.notesNeedingFix]).toEqual(['r1'])
  })

  it('nested strays render at correct depth', () => {
    const root = note({ id: 'r1', content: 'Root', containedNotes: ['c1'] })
    const c1 = note({ id: 'c1', content: 'Known', parentNoteId: 'r1' })
    const grandchild = note({ id: 'gc1', content: 'Stray gc', parentNoteId: 'c1' })
    const result = rebuildAllNotes(toMap([root, c1, grandchild]))
    expect(result.notes[0]!.content).toBe('Root\nKnown\n\tStray gc')
    expect([...result.notesNeedingFix]).toEqual(['r1'])
  })

  it('multiple roots are each reconstructed independently', () => {
    const root1 = note({ id: 'r1', content: 'First' })
    const root2 = note({ id: 'r2', content: 'Second', containedNotes: ['c1'] })
    const child = note({ id: 'c1', content: 'Child', parentNoteId: 'r2' })
    const result = rebuildAllNotes(toMap([root1, root2, child]))
    expect(result.notes.find(n => n.id === 'r1')!.content).toBe('First')
    expect(result.notes.find(n => n.id === 'r2')!.content).toBe('Second\nChild')
  })

  it('orphaned child (rootNoteId points to non-existent root) is excluded from top-level', () => {
    const orphan = note({ id: 'o1', content: 'Orphan', parentNoteId: 'missing', rootNoteId: 'missing' })
    const result = rebuildAllNotes(toMap([orphan]))
    expect(result.notes).toHaveLength(0)
  })

  it('deleted root still returned as top-level', () => {
    const root = note({ id: 'r1', content: 'Deleted root', state: 'deleted' })
    const result = rebuildAllNotes(toMap([root]))
    expect(result.notes).toHaveLength(1)
  })
})

describe('rebuildAffectedNotes', () => {
  it('single root changed', () => {
    const existing = [note({ id: 'a', content: 'Old' }), note({ id: 'b', content: 'Beta' })]
    const rawNotes = toMap([note({ id: 'a', content: 'New' }), note({ id: 'b', content: 'Beta' })])
    const result = rebuildAffectedNotes(existing, new Set(['a']), rawNotes)
    expect(result.notes.find(n => n.id === 'a')!.content).toBe('New')
  })

  it('root deleted (removed from rawNotes)', () => {
    const existing = [note({ id: 'a' }), note({ id: 'b' })]
    const rawNotes = toMap([note({ id: 'b' })])
    const result = rebuildAffectedNotes(existing, new Set(['a']), rawNotes)
    expect(result.notes).toHaveLength(1)
    expect(result.notes[0]!.id).toBe('b')
  })

  it('new root added', () => {
    const existing = [note({ id: 'a' })]
    const rawNotes = toMap([note({ id: 'a' }), note({ id: 'b', content: 'new' })])
    const result = rebuildAffectedNotes(existing, new Set(['b']), rawNotes)
    expect(result.notes).toHaveLength(2)
  })

  it('unchanged roots preserved by reference', () => {
    const rootA = note({ id: 'a' })
    const rootB = note({ id: 'b' })
    const existing = [rootA, rootB]
    const rawNotes = toMap([note({ id: 'a', content: 'changed' }), rootB])
    const result = rebuildAffectedNotes(existing, new Set(['a']), rawNotes)
    expect(result.notes[1]).toBe(rootB)
  })

  it('returns same reference when no changes', () => {
    const rootA = note({ id: 'a' })
    const existing = [rootA]
    const rawNotes = toMap([note({ id: 'a' })])
    const result = rebuildAffectedNotes(existing, new Set(['x']), rawNotes)
    expect(result.notes).toBe(existing)
  })

  it('fix applied marks notesNeedingFix', () => {
    const existing = [note({ id: 'r', content: 'Old' })]
    const rawNotes = toMap([note({ id: 'r', content: 'New', containedNotes: ['orphan'] })])
    const result = rebuildAffectedNotes(existing, new Set(['r']), rawNotes)
    expect([...result.notesNeedingFix]).toEqual(['r'])
  })
})

describe('reconstructNoteContent', () => {
  it('no children or refs returns same instance', () => {
    const n = note({ id: 'a', content: 'Plain' })
    const raw = toMap([n])
    const [result, fixed] = reconstructNoteContent(n, raw, childrenByParent(raw))
    expect(result).toBe(n)
    expect(fixed).toBe(false)
  })

  it('declared children in order', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1', 'c2'] })
    const c1 = note({ id: 'c1', content: 'A', parentNoteId: 'r' })
    const c2 = note({ id: 'c2', content: 'B', parentNoteId: 'r' })
    const raw = toMap([root, c1, c2])
    const [result, fixed] = reconstructNoteContent(root, raw, childrenByParent(raw))
    expect(result.content).toBe('Root\nA\nB')
    expect(fixed).toBe(false)
  })

  it('legacy "" in containedNotes is dropped as an orphan (post-migration)', () => {
    // Pre-migration, "" was a spacer sentinel. Post-migration, all empty
    // lines are real docs — an "" entry is data corruption that the orphan-
    // drop path catches and flags via `fixed = true`.
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1', '', 'c2'] })
    const c1 = note({ id: 'c1', content: 'A', parentNoteId: 'r' })
    const c2 = note({ id: 'c2', content: 'B', parentNoteId: 'r' })
    const raw = toMap([root, c1, c2])
    const [result, fixed] = reconstructNoteContent(root, raw, childrenByParent(raw))
    expect(result.content).toBe('Root\nA\nB')
    expect(fixed).toBe(true)
  })

  it('orphan ref dropped sets fixed true', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['missing'] })
    const raw = toMap([root])
    const [, fixed] = reconstructNoteContent(root, raw, childrenByParent(raw))
    expect(fixed).toBe(true)
  })

  it('stray child appended sets fixed true', () => {
    const root = note({ id: 'r', content: 'Root' })
    const stray = note({ id: 's', content: 'Stray', parentNoteId: 'r' })
    const raw = toMap([root, stray])
    const [result, fixed] = reconstructNoteContent(root, raw, childrenByParent(raw))
    expect(result.content).toBe('Root\nStray')
    expect(fixed).toBe(true)
  })

  it('preserves non-content fields', () => {
    const root = note({
      id: 'r',
      content: 'Root',
      containedNotes: ['c1'],
      tags: ['important'],
      state: 'active',
      path: 'my-note',
    })
    const c1 = note({ id: 'c1', content: 'Child', parentNoteId: 'r' })
    const raw = toMap([root, c1])
    const [result] = reconstructNoteContent(root, raw, childrenByParent(raw))
    expect(result.tags).toEqual(['important'])
    expect(result.state).toBe('active')
    expect(result.path).toBe('my-note')
  })
})

describe('reconstructNoteLines', () => {
  it('returns per-line NoteLines with noteIds', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1', 'c2'] })
    const c1 = note({ id: 'c1', content: 'First', parentNoteId: 'r' })
    const c2 = note({ id: 'c2', content: 'Second', parentNoteId: 'r' })
    const raw = toMap([root, c1, c2])
    const [lines, fixed] = reconstructNoteLines(root, raw, childrenByParent(raw))
    expect(lines).toEqual([
      { content: 'Root', noteId: 'r' },
      { content: 'First', noteId: 'c1' },
      { content: 'Second', noteId: 'c2' },
    ])
    expect(fixed).toBe(false)
  })

  it('drops declared child whose doc is missing from rawNotes (partial-sync window)', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1', 'new_child', 'c2'] })
    const c1 = note({ id: 'c1', content: 'First', parentNoteId: 'r' })
    const c2 = note({ id: 'c2', content: 'Second', parentNoteId: 'r' })
    // new_child absent from raw map
    const raw = toMap([root, c1, c2])
    const [lines, fixed] = reconstructNoteLines(root, raw, childrenByParent(raw))
    expect(lines.map(l => l.content)).toEqual(['Root', 'First', 'Second'])
    expect(fixed).toBe(true)
  })

  it('appends stray (parentNoteId but not in containedNotes) at end', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1'] })
    const c1 = note({ id: 'c1', content: 'Known', parentNoteId: 'r' })
    const stray = note({ id: 's', content: 'Stray', parentNoteId: 'r' })
    const raw = toMap([root, c1, stray])
    const [lines, fixed] = reconstructNoteLines(root, raw, childrenByParent(raw))
    expect(lines.map(l => l.content)).toEqual(['Root', 'Known', 'Stray'])
    expect(lines.map(l => l.noteId)).toEqual(['r', 'c1', 's'])
    expect(fixed).toBe(true)
  })

  it('matches reconstructNoteContent output line-by-line (drift guard)', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1', 'missing_c', 'c2'] })
    const c1 = note({ id: 'c1', content: 'First', containedNotes: ['gc1'], parentNoteId: 'r' })
    const c2 = note({ id: 'c2', content: 'Second', parentNoteId: 'r' })
    const gc1 = note({ id: 'gc1', content: 'GC', parentNoteId: 'c1' })
    const stray = note({ id: 's', content: 'Stray', parentNoteId: 'r' })
    const raw = toMap([root, c1, c2, gc1, stray])
    const cbp = childrenByParent(raw)

    const [lines, linesFixed] = reconstructNoteLines(root, raw, cbp)
    const [reconstructed, contentFixed] = reconstructNoteContent(root, raw, cbp)

    expect(lines.map(l => l.content).join('\n')).toBe(reconstructed.content)
    expect(linesFixed).toBe(contentFixed)
  })

  it('duplicate containedNotes ref placed once and flagged', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1', 'c1'] })
    const c1 = note({ id: 'c1', content: 'Once', parentNoteId: 'r' })
    const raw = toMap([root, c1])
    const [lines, fixed] = reconstructNoteLines(root, raw, childrenByParent(raw))
    expect(lines.map(l => l.content)).toEqual(['Root', 'Once'])
    expect(fixed).toBe(true)
  })

  it('cycle via containedNotes does not loop', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1'] })
    const c1 = note({ id: 'c1', content: 'First', containedNotes: ['r'], parentNoteId: 'r' })
    const raw = toMap([root, c1])
    const [lines, fixed] = reconstructNoteLines(root, raw, childrenByParent(raw))
    expect(lines.map(l => l.content)).toEqual(['Root', 'First'])
    expect(fixed).toBe(true)
  })

  it('orphan ref and stray child in same walk both applied', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['c1', 'missing'] })
    const c1 = note({ id: 'c1', content: 'Declared', parentNoteId: 'r' })
    const stray = note({ id: 's', content: 'Stray', parentNoteId: 'r' })
    const raw = toMap([root, c1, stray])
    const [lines, fixed] = reconstructNoteLines(root, raw, childrenByParent(raw))
    expect(lines.map(l => l.content)).toEqual(['Root', 'Declared', 'Stray'])
    expect(fixed).toBe(true)
  })

  it('deeply nested stray renders at correct depth', () => {
    const root = note({ id: 'r', content: 'Root', containedNotes: ['a'] })
    const a = note({ id: 'a', content: 'A', containedNotes: ['b'], parentNoteId: 'r' })
    const b = note({ id: 'b', content: 'B', containedNotes: ['c'], parentNoteId: 'a' })
    const c = note({ id: 'c', content: 'C', parentNoteId: 'b' })
    const stray = note({ id: 's', content: 'Stray', parentNoteId: 'c' })
    const raw = toMap([root, a, b, c, stray])
    const [lines, fixed] = reconstructNoteLines(root, raw, childrenByParent(raw))
    expect(lines.map(l => l.content)).toEqual(['Root', 'A', '\tB', '\t\tC', '\t\t\tStray'])
    expect(fixed).toBe(true)
  })
})
