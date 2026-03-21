import { describe, it, expect } from 'vitest'
import { resolveNoteIds } from '@/editor/resolveNoteIds'

describe('resolveNoteIds', () => {
  it('passes through unique noteIds unchanged', () => {
    const result = resolveNoteIds(
      ['Line A', 'Line B', 'Line C'],
      [['id1'], ['id2'], ['id3']],
    )
    expect(result[0]!.noteId).toBe('id1')
    expect(result[1]!.noteId).toBe('id2')
    expect(result[2]!.noteId).toBe('id3')
  })

  it('gives null to lines without noteIds', () => {
    const result = resolveNoteIds(
      ['Line A', 'New line', 'Line C'],
      [['id1'], [], ['id3']],
    )
    expect(result[0]!.noteId).toBe('id1')
    expect(result[1]!.noteId).toBeNull()
    expect(result[2]!.noteId).toBe('id3')
  })

  it('resolves duplicate noteIds by longest content', () => {
    const result = resolveNoteIds(
      ['He', 'Hello World'],
      [['id1'], ['id1']],
    )
    expect(result[0]!.noteId).toBeNull()
    expect(result[1]!.noteId).toBe('id1')
  })

  it('excludes prefix when computing content length', () => {
    const result = resolveNoteIds(
      ['\tShort', 'Much longer content here'],
      [['id1'], ['id1']],
    )
    // "\tShort" content = "Short" (5 chars)
    // "Much longer content here" content = 24 chars
    expect(result[0]!.noteId).toBeNull()
    expect(result[1]!.noteId).toBe('id1')
  })

  it('uses first (primary) noteId from merges', () => {
    const result = resolveNoteIds(
      ['Merged content'],
      [['idA', 'idB']],
    )
    expect(result[0]!.noteId).toBe('idA')
  })

  it('deduplicates when merged primary appears on multiple lines', () => {
    const result = resolveNoteIds(
      ['Long merged content', 'Short'],
      [['idA', 'idB'], ['idA']],
    )
    expect(result[0]!.noteId).toBe('idA')
    expect(result[1]!.noteId).toBeNull()
  })

  it('returns empty for empty inputs', () => {
    const result = resolveNoteIds([], [])
    expect(result).toHaveLength(0)
  })

  it('handles lineNoteIds shorter than contentLines', () => {
    const result = resolveNoteIds(
      ['Line A', 'Line B', 'Line C'],
      [['id1']],
    )
    expect(result[0]!.noteId).toBe('id1')
    expect(result[1]!.noteId).toBeNull()
    expect(result[2]!.noteId).toBeNull()
  })

  it('preserves content in output', () => {
    const result = resolveNoteIds(
      ['\t- Item 1', 'Plain text'],
      [['id1'], ['id2']],
    )
    expect(result[0]!.content).toBe('\t- Item 1')
    expect(result[1]!.content).toBe('Plain text')
  })

  it('preserves noteIds through indentation changes', () => {
    // This is the key bug fix: indenting a line changes its text but
    // the editor preserves the noteId. The save should use the editor's
    // noteIds, not re-match by content.
    const result = resolveNoteIds(
      ['\t☐ Todo 2'],  // indented version
      [['id1']],        // noteId preserved by editor
    )
    expect(result[0]!.noteId).toBe('id1')
  })
})
