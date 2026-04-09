import { describe, test, expect } from 'vitest'
import type { Note, NoteLine } from '../../data/Note'
import { flattenTreeToLines, buildTreeFromLines } from '../../data/NoteTree'

function note(
  id: string,
  content: string,
  containedNotes: string[] = [],
  rootNoteId: string | null = null,
): Note {
  return {
    id,
    content,
    containedNotes,
    rootNoteId,
    userId: 'user1',
    parentNoteId: null,
    createdAt: null,
    updatedAt: null,
    lastAccessedAt: null,
    tags: [],
    state: null,
    path: '',
    showCompleted: true,
  }
}

// --- flattenTreeToLines ---

describe('flattenTreeToLines', () => {
  test('empty note - single line no children', () => {
    const root = note('root', 'Hello')
    const result = flattenTreeToLines(root, [])

    expect(result).toHaveLength(1)
    expect(result[0]).toEqual({ content: 'Hello', noteId: 'root' })
  })

  test('flat note - direct children at depth 0', () => {
    const root = note('root', 'Parent', ['c1', 'c2', 'c3'])
    const descendants = [
      note('c1', 'Child 1', [], 'root'),
      note('c2', 'Child 2', [], 'root'),
      note('c3', 'Child 3', [], 'root'),
    ]

    const result = flattenTreeToLines(root, descendants)

    expect(result).toHaveLength(4)
    expect(result[0]).toEqual({ content: 'Parent', noteId: 'root' })
    expect(result[1]).toEqual({ content: 'Child 1', noteId: 'c1' })
    expect(result[2]).toEqual({ content: 'Child 2', noteId: 'c2' })
    expect(result[3]).toEqual({ content: 'Child 3', noteId: 'c3' })
  })

  test('nested 2 levels deep', () => {
    const root = note('root', 'Root', ['a'])
    const descendants = [
      note('a', 'A', ['b'], 'root'),
      note('b', 'B', [], 'root'),
    ]

    const result = flattenTreeToLines(root, descendants)

    expect(result).toHaveLength(3)
    expect(result[0]).toEqual({ content: 'Root', noteId: 'root' })
    expect(result[1]).toEqual({ content: 'A', noteId: 'a' })
    expect(result[2]).toEqual({ content: '\tB', noteId: 'b' })
  })

  test('nested 3 levels deep', () => {
    const root = note('root', 'Root', ['a'])
    const descendants = [
      note('a', 'A', ['b'], 'root'),
      note('b', 'B', ['c'], 'root'),
      note('c', 'C', [], 'root'),
    ]

    const result = flattenTreeToLines(root, descendants)

    expect(result).toHaveLength(4)
    expect(result[3]).toEqual({ content: '\t\tC', noteId: 'c' })
  })

  test('with spacers between root children', () => {
    const root = note('root', 'Root', ['a', '', 'b'])
    const descendants = [
      note('a', 'A', [], 'root'),
      note('b', 'B', [], 'root'),
    ]

    const result = flattenTreeToLines(root, descendants)

    expect(result).toHaveLength(4)
    expect(result[1]).toEqual({ content: 'A', noteId: 'a' })
    expect(result[2]).toEqual({ content: '', noteId: null })
    expect(result[3]).toEqual({ content: 'B', noteId: 'b' })
  })

  test('with spacers at nested levels', () => {
    const root = note('root', 'Root', ['a'])
    const descendants = [
      note('a', 'A', ['b', '', 'c'], 'root'),
      note('b', 'B', [], 'root'),
      note('c', 'C', [], 'root'),
    ]

    const result = flattenTreeToLines(root, descendants)

    expect(result).toHaveLength(5)
    expect(result[3]).toEqual({ content: '\t', noteId: null })
  })

  test('with bullet prefixes in content', () => {
    const root = note('root', 'Shopping', ['c1', 'c2'])
    const descendants = [
      note('c1', '• Milk', [], 'root'),
      note('c2', '☐ Bread', [], 'root'),
    ]

    const result = flattenTreeToLines(root, descendants)

    expect(result[1]).toEqual({ content: '• Milk', noteId: 'c1' })
    expect(result[2]).toEqual({ content: '☐ Bread', noteId: 'c2' })
  })

  test('skips orphaned references', () => {
    const root = note('root', 'Root', ['a', 'missing', 'b'])
    const descendants = [
      note('a', 'A', [], 'root'),
      note('b', 'B', [], 'root'),
    ]

    const result = flattenTreeToLines(root, descendants)

    expect(result).toHaveLength(3)
    expect(result[1]).toEqual({ content: 'A', noteId: 'a' })
    expect(result[2]).toEqual({ content: 'B', noteId: 'b' })
  })
})

// --- buildTreeFromLines ---

describe('buildTreeFromLines', () => {
  test('empty lines', () => {
    const result = buildTreeFromLines('root', [])

    expect(result.rootContent).toBe('')
    expect(result.rootContainedNoteIds).toEqual([])
    expect(result.nodes).toEqual([])
  })

  test('single line - root only', () => {
    const lines: NoteLine[] = [{ content: 'Hello', noteId: 'root' }]
    const result = buildTreeFromLines('root', lines)

    expect(result.rootContent).toBe('Hello')
    expect(result.rootContainedNoteIds).toEqual([])
    expect(result.nodes).toEqual([])
  })

  test('flat children at depth 0', () => {
    const lines: NoteLine[] = [
      { content: 'Parent', noteId: 'root' },
      { content: 'Child 1', noteId: 'c1' },
      { content: 'Child 2', noteId: 'c2' },
    ]
    const result = buildTreeFromLines('root', lines)

    expect(result.rootContent).toBe('Parent')
    expect(result.rootContainedNoteIds).toEqual(['c1', 'c2'])
    expect(result.nodes).toHaveLength(2)
    expect(result.nodes[0]).toMatchObject({ content: 'Child 1', parentNoteId: 'root' })
    expect(result.nodes[1]).toMatchObject({ content: 'Child 2', parentNoteId: 'root' })
  })

  test('nested children', () => {
    const lines: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: 'A', noteId: 'a' },
      { content: '\tB', noteId: 'b' },
    ]
    const result = buildTreeFromLines('root', lines)

    expect(result.rootContainedNoteIds).toEqual(['a'])
    expect(result.nodes[0]).toMatchObject({
      content: 'A',
      parentNoteId: 'root',
      containedNoteIds: ['b'],
    })
    expect(result.nodes[1]).toMatchObject({
      content: 'B',
      parentNoteId: 'a',
      containedNoteIds: [],
    })
  })

  test('3 levels deep', () => {
    const lines: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: 'A', noteId: 'a' },
      { content: '\tB', noteId: 'b' },
      { content: '\t\tC', noteId: 'c' },
    ]
    const result = buildTreeFromLines('root', lines)

    expect(result.nodes[0]).toMatchObject({ parentNoteId: 'root', containedNoteIds: ['b'] })
    expect(result.nodes[1]).toMatchObject({ parentNoteId: 'a', containedNoteIds: ['c'] })
    expect(result.nodes[2]).toMatchObject({ parentNoteId: 'b', containedNoteIds: [] })
  })

  test('with spacers between siblings at depth 0', () => {
    const lines: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: 'A', noteId: 'a' },
      { content: '', noteId: null },
      { content: 'B', noteId: 'b' },
    ]
    const result = buildTreeFromLines('root', lines)

    expect(result.rootContainedNoteIds).toEqual(['a', '', 'b'])
    expect(result.nodes).toHaveLength(2)
  })

  test('with new lines - null noteIds', () => {
    const lines: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: 'New child', noteId: null },
      { content: '\tNew grandchild', noteId: null },
    ]
    const result = buildTreeFromLines('root', lines)

    expect(result.rootContainedNoteIds).toEqual(['placeholder_1'])
    expect(result.nodes).toHaveLength(2)
    expect(result.nodes[0]).toMatchObject({
      noteId: null,
      content: 'New child',
      parentNoteId: 'root',
    })
    expect(result.nodes[1]).toMatchObject({
      noteId: null,
      content: 'New grandchild',
      parentNoteId: 'placeholder_1',
    })
  })

  test('unindent back to depth 0 after nesting', () => {
    const lines: NoteLine[] = [
      { content: 'Root', noteId: 'root' },
      { content: 'A', noteId: 'a' },
      { content: '\tB', noteId: 'b' },
      { content: 'C', noteId: 'c' },
    ]
    const result = buildTreeFromLines('root', lines)

    expect(result.rootContainedNoteIds).toEqual(['a', 'c'])
    expect(result.nodes[0]).toMatchObject({
      content: 'A',
      containedNoteIds: ['b'],
    })
    expect(result.nodes[2]).toMatchObject({
      content: 'C',
      parentNoteId: 'root',
    })
  })
})

// --- Round-trip tests ---

describe('round-trip', () => {
  test('flat note', () => {
    const root = note('root', 'Parent', ['c1', 'c2'])
    const descendants = [
      note('c1', 'Child 1', [], 'root'),
      note('c2', 'Child 2', [], 'root'),
    ]

    const flattened = flattenTreeToLines(root, descendants)
    const rebuilt = buildTreeFromLines('root', flattened)

    expect(rebuilt.rootContent).toBe('Parent')
    expect(rebuilt.rootContainedNoteIds).toEqual(['c1', 'c2'])
  })

  test('nested note', () => {
    const root = note('root', 'Root', ['a'])
    const descendants = [
      note('a', 'A', ['b'], 'root'),
      note('b', 'B', [], 'root'),
    ]

    const flattened = flattenTreeToLines(root, descendants)
    const rebuilt = buildTreeFromLines('root', flattened)

    expect(rebuilt.rootContent).toBe('Root')
    expect(rebuilt.rootContainedNoteIds).toEqual(['a'])
    expect(rebuilt.nodes[0]).toMatchObject({
      content: 'A',
      parentNoteId: 'root',
      containedNoteIds: ['b'],
    })
  })

  test('with spacers', () => {
    const root = note('root', 'Root', ['a', '', 'b'])
    const descendants = [
      note('a', 'A', [], 'root'),
      note('b', 'B', [], 'root'),
    ]

    const flattened = flattenTreeToLines(root, descendants)
    const rebuilt = buildTreeFromLines('root', flattened)

    expect(rebuilt.rootContainedNoteIds).toEqual(['a', '', 'b'])
  })

  test('complex tree', () => {
    const root = note('root', 'Root', ['a', 'd'])
    const descendants = [
      note('a', 'A', ['b', '', 'c'], 'root'),
      note('b', 'B', [], 'root'),
      note('c', 'C', [], 'root'),
      note('d', 'D', ['e'], 'root'),
      note('e', 'E', [], 'root'),
    ]

    const flattened = flattenTreeToLines(root, descendants)

    expect(flattened).toHaveLength(7)
    expect(flattened.map(l => l.content)).toEqual([
      'Root', 'A', '\tB', '\t', '\tC', 'D', '\tE',
    ])

    const rebuilt = buildTreeFromLines('root', flattened)

    expect(rebuilt.rootContent).toBe('Root')
    expect(rebuilt.rootContainedNoteIds).toEqual(['a', 'd'])
    expect(rebuilt.nodes[0]).toMatchObject({
      content: 'A',
      parentNoteId: 'root',
      containedNoteIds: ['b', '', 'c'],
    })
    expect(rebuilt.nodes[3]).toMatchObject({
      content: 'D',
      parentNoteId: 'root',
      containedNoteIds: ['e'],
    })
  })
})

