import { describe, it, expect } from 'vitest'
import type { Note } from '../../data/Note'
import {
  rebuildAllNotes,
  rebuildAffectedNotes,
  reconstructNoteContent,
} from '../../data/NoteReconstruction'

function note(overrides: Partial<Note> & { id: string }): Note {
  return {
    userId: '',
    parentNoteId: null,
    content: '',
    createdAt: null,
    updatedAt: null,
    lastAccessedAt: null,
    tags: [],
    containedNotes: [],
    state: null,
    path: '',
    rootNoteId: null,
    showCompleted: true,
    ...overrides,
  }
}

function toMap(notes: Note[]): Map<string, Note> {
  const map = new Map<string, Note>()
  for (const n of notes) map.set(n.id, n)
  return map
}

describe('rebuildAllNotes', () => {
  it('single root with no children returns as-is', () => {
    const root = note({ id: 'r1', content: 'Hello' })
    const result = rebuildAllNotes(toMap([root]))
    expect(result).toHaveLength(1)
    expect(result[0]).toBe(root) // same reference — no reconstruction needed
  })

  it('root with descendants reconstructs content from tree', () => {
    const root = note({
      id: 'r1',
      content: 'Title',
      containedNotes: ['c1', 'c2'],
    })
    const child1 = note({
      id: 'c1',
      content: 'Line one',
      parentNoteId: 'r1',
      rootNoteId: 'r1',
      containedNotes: [],
    })
    const child2 = note({
      id: 'c2',
      content: 'Line two',
      parentNoteId: 'r1',
      rootNoteId: 'r1',
      containedNotes: [],
    })

    const result = rebuildAllNotes(toMap([root, child1, child2]))
    expect(result).toHaveLength(1)
    // flattenTreeToLines: root content (no tabs), then children at depth 0
    expect(result[0]!.content).toBe('Title\nLine one\nLine two')
  })

  it('multiple roots are each reconstructed independently', () => {
    const root1 = note({ id: 'r1', content: 'First' })
    const root2 = note({
      id: 'r2',
      content: 'Second',
      containedNotes: ['c1'],
    })
    const child = note({
      id: 'c1',
      content: 'Child',
      parentNoteId: 'r2',
      rootNoteId: 'r2',
    })

    const result = rebuildAllNotes(toMap([root1, root2, child]))
    expect(result).toHaveLength(2)

    const first = result.find(n => n.id === 'r1')!
    const second = result.find(n => n.id === 'r2')!
    expect(first.content).toBe('First')
    expect(second.content).toBe('Second\nChild')
  })

  it('orphaned child (rootNoteId points to non-existent root) is excluded', () => {
    const orphan = note({
      id: 'o1',
      content: 'Orphan',
      parentNoteId: 'missing',
      rootNoteId: 'missing',
    })
    const result = rebuildAllNotes(toMap([orphan]))
    expect(result).toHaveLength(0)
  })

  it('root with empty containedNotes returns as-is', () => {
    const root = note({ id: 'r1', content: 'Solo', containedNotes: [] })
    const result = rebuildAllNotes(toMap([root]))
    expect(result).toHaveLength(1)
    expect(result[0]).toBe(root)
  })

  it('deleted notes in rawNotes are still reconstructed', () => {
    const root = note({ id: 'r1', content: 'Deleted root', state: 'deleted' })
    const result = rebuildAllNotes(toMap([root]))
    expect(result).toHaveLength(1)
    expect(result[0]!.content).toBe('Deleted root')
    expect(result[0]!.state).toBe('deleted')
  })

  it('root with nested descendants reconstructs deeply', () => {
    const root = note({
      id: 'r1',
      content: 'Root',
      containedNotes: ['c1'],
    })
    const child = note({
      id: 'c1',
      content: 'Child',
      parentNoteId: 'r1',
      rootNoteId: 'r1',
      containedNotes: ['gc1'],
    })
    const grandchild = note({
      id: 'gc1',
      content: 'Grandchild',
      parentNoteId: 'c1',
      rootNoteId: 'r1',
    })

    const result = rebuildAllNotes(toMap([root, child, grandchild]))
    expect(result).toHaveLength(1)
    // flattenTreeToLines: root (no tabs), child at depth 0, grandchild at depth 1
    expect(result[0]!.content).toBe('Root\nChild\n\tGrandchild')
  })
})

describe('rebuildAffectedNotes', () => {
  it('single root changed — only that root reconstructed', () => {
    const rootA = note({ id: 'a', content: 'A' })
    const rootB = note({ id: 'b', content: 'B' })
    const current = [rootA, rootB]

    const rawNotes = toMap([
      note({ id: 'a', content: 'A updated' }),
      note({ id: 'b', content: 'B' }),
    ])

    const result = rebuildAffectedNotes(current, new Set(['a']), rawNotes)
    expect(result).toHaveLength(2)
    expect(result.find(n => n.id === 'a')!.content).toBe('A updated')
    // Unchanged root preserved by reference
    expect(result.find(n => n.id === 'b')).toBe(rootB)
  })

  it('root deleted (removed from rawNotes) — removed from result', () => {
    const rootA = note({ id: 'a', content: 'A' })
    const rootB = note({ id: 'b', content: 'B' })
    const current = [rootA, rootB]

    // 'a' is not in rawNotes (deleted)
    const rawNotes = toMap([note({ id: 'b', content: 'B' })])

    const result = rebuildAffectedNotes(current, new Set(['a']), rawNotes)
    expect(result).toHaveLength(1)
    expect(result[0]!.id).toBe('b')
  })

  it('new root added — added to result', () => {
    const rootA = note({ id: 'a', content: 'A' })
    const current = [rootA]

    const rawNotes = toMap([
      note({ id: 'a', content: 'A' }),
      note({ id: 'b', content: 'B new' }),
    ])

    const result = rebuildAffectedNotes(current, new Set(['b']), rawNotes)
    expect(result).toHaveLength(2)
    expect(result.find(n => n.id === 'b')!.content).toBe('B new')
  })

  it('unchanged roots preserved by reference (not reconstructed)', () => {
    const rootA = note({ id: 'a', content: 'A' })
    const rootB = note({ id: 'b', content: 'B' })
    const current = [rootA, rootB]

    const rawNotes = toMap([
      note({ id: 'a', content: 'A changed' }),
      note({ id: 'b', content: 'B' }),
    ])

    const result = rebuildAffectedNotes(current, new Set(['a']), rawNotes)
    // rootB should be the exact same object reference
    expect(result[1]).toBe(rootB)
  })

  it('multiple affected roots — all rebuilt, unaffected preserved', () => {
    const rootA = note({ id: 'a', content: 'A' })
    const rootB = note({ id: 'b', content: 'B' })
    const rootC = note({ id: 'c', content: 'C' })
    const current = [rootA, rootB, rootC]

    const rawNotes = toMap([
      note({ id: 'a', content: 'A v2' }),
      note({ id: 'b', content: 'B v2' }),
      note({ id: 'c', content: 'C' }),
    ])

    const result = rebuildAffectedNotes(current, new Set(['a', 'b']), rawNotes)
    expect(result).toHaveLength(3)
    expect(result.find(n => n.id === 'a')!.content).toBe('A v2')
    expect(result.find(n => n.id === 'b')!.content).toBe('B v2')
    expect(result.find(n => n.id === 'c')).toBe(rootC)
  })

  it('returns same reference when no changes detected', () => {
    const rootA = note({ id: 'a', content: 'A' })
    const current = [rootA]

    // Affected root 'x' doesn't exist in rawNotes or current — no change
    const rawNotes = toMap([note({ id: 'a', content: 'A' })])

    const result = rebuildAffectedNotes(current, new Set(['x']), rawNotes)
    expect(result).toBe(current)
  })
})

describe('reconstructNoteContent', () => {
  it('note with no containedNotes returns as-is', () => {
    const root = note({ id: 'r1', content: 'Plain', containedNotes: [] })
    const result = reconstructNoteContent(root, undefined, new Map())
    expect(result).toBe(root)
  })

  it('note with descendants (new format) uses flattenTreeToLines', () => {
    const root = note({
      id: 'r1',
      content: 'Title',
      containedNotes: ['c1'],
    })
    const child = note({
      id: 'c1',
      content: 'Body line',
      parentNoteId: 'r1',
      rootNoteId: 'r1',
    })

    const rawNotes = toMap([root, child])
    const result = reconstructNoteContent(root, [child], rawNotes)
    expect(result.content).toBe('Title\nBody line')
    expect(result.id).toBe('r1')
  })

  it('note with containedNotes but no descendants (old format) joins from rawNotes', () => {
    const child1 = note({ id: 'c1', content: 'Line A' })
    const child2 = note({ id: 'c2', content: 'Line B' })
    const root = note({
      id: 'r1',
      content: 'Header',
      containedNotes: ['c1', 'c2'],
    })

    const rawNotes = toMap([root, child1, child2])
    // No descendants (undefined) triggers old format fallback
    const result = reconstructNoteContent(root, undefined, rawNotes)
    expect(result.content).toBe('Header\nLine A\nLine B')
  })

  it('old format with missing child in rawNotes uses empty string', () => {
    const root = note({
      id: 'r1',
      content: 'Header',
      containedNotes: ['missing1', 'c1'],
    })
    const child = note({ id: 'c1', content: 'Exists' })

    const rawNotes = toMap([root, child])
    const result = reconstructNoteContent(root, undefined, rawNotes)
    expect(result.content).toBe('Header\n\nExists')
  })

  it('old format with empty string child IDs (spacers) inserts empty lines', () => {
    const child = note({ id: 'c1', content: 'After spacer' })
    const root = note({
      id: 'r1',
      content: 'Title',
      containedNotes: ['', 'c1', ''],
    })

    const rawNotes = toMap([root, child])
    const result = reconstructNoteContent(root, undefined, rawNotes)
    expect(result.content).toBe('Title\n\nAfter spacer\n')
  })

  it('old format with empty descendants array triggers old format fallback', () => {
    const child = note({ id: 'c1', content: 'Child' })
    const root = note({
      id: 'r1',
      content: 'Root',
      containedNotes: ['c1'],
    })

    const rawNotes = toMap([root, child])
    // Empty array (not undefined) should also trigger old format
    const result = reconstructNoteContent(root, [], rawNotes)
    expect(result.content).toBe('Root\nChild')
  })

  it('preserves all note fields except content', () => {
    const root = note({
      id: 'r1',
      content: 'Original',
      containedNotes: ['c1'],
      tags: ['important'],
      state: 'active',
      path: 'my-note',
    })
    const child = note({
      id: 'c1',
      content: 'Child',
      parentNoteId: 'r1',
      rootNoteId: 'r1',
    })

    const result = reconstructNoteContent(root, [child], toMap([root, child]))
    expect(result.tags).toEqual(['important'])
    expect(result.state).toBe('active')
    expect(result.path).toBe('my-note')
    expect(result.id).toBe('r1')
  })
})
