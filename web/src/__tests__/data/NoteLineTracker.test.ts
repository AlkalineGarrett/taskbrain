import { describe, it, expect } from 'vitest'
import { matchLinesToIds } from '../../data/NoteRepository'
import type { NoteLine } from '../../data/Note'
import { isSentinelNoteId } from '../../data/NoteIdSentinel'

const parentId = 'parent_123'

/** Test-fixture shape: `null` is a wildcard for "fresh allocation expected"
 *  used in [toEqual] checks. NoteLine itself disallows null at runtime;
 *  [unsentinelize] folds sentinel-shaped ids back to null for these
 *  structural assertions. */
type FixtureLine = { content: string; noteId: string | null }

function tracked(lines: FixtureLine[], newContent: string): NoteLine[] {
  // Test inputs only contain real ids; the FixtureLine `null` slot is just
  // for assertion-side wildcards.
  return matchLinesToIds(parentId, lines as NoteLine[], newContent.split('\n'))
}

function lines(...pairs: [string, string | null][]): FixtureLine[] {
  return pairs.map(([content, noteId]) => ({ content, noteId }))
}

function unsentinelize(actual: NoteLine[]): FixtureLine[] {
  return actual.map((l) => ({
    content: l.content,
    noteId: isSentinelNoteId(l.noteId) ? null : l.noteId,
  }))
}

describe('matchLinesToIds', () => {
  it('empty existing lines assigns parent ID to first line', () => {
    const result = matchLinesToIds(parentId, [], ['First line'])
    expect(unsentinelize(result)).toEqual(lines(['First line', parentId]))
  })

  it('empty content creates single empty line with parent ID', () => {
    const result = matchLinesToIds(parentId, [], [''])
    expect(unsentinelize(result)).toEqual(lines(['', parentId]))
  })

  it('single line gets parent ID', () => {
    const result = matchLinesToIds(parentId, [], ['First line'])
    expect(unsentinelize(result)).toEqual(lines(['First line', parentId]))
  })

  it('multiple lines - only first gets parent ID', () => {
    const result = matchLinesToIds(parentId, [], ['Line 1', 'Line 2', 'Line 3'])
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Line 2', null],
      ['Line 3', null],
    ))
  })

  it('exact match preserves all IDs', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Line 2', 'child_1'],
      ['Line 3', 'child_2'],
    )
    const result = tracked(existing, 'Line 1\nLine 2\nLine 3')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Line 2', 'child_1'],
      ['Line 3', 'child_2'],
    ))
  })

  it('insertion creates lines without IDs', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Line 3', 'child_1'],
    )
    const result = tracked(existing, 'Line 1\nLine 2\nLine 3')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Line 2', null],
      ['Line 3', 'child_1'],
    ))
  })

  it('deletion preserves remaining IDs', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Line 2', 'child_1'],
      ['Line 3', 'child_2'],
      ['Line 4', 'child_3'],
    )
    const result = tracked(existing, 'Line 1\nLine 3\nLine 4')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Line 3', 'child_2'],
      ['Line 4', 'child_3'],
    ))
  })

  it('modification preserves ID at same position', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Original', 'child_1'],
    )
    const result = tracked(existing, 'Line 1\nModified')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Modified', 'child_1'],
    ))
  })

  it('first line always keeps parent ID after modification', () => {
    const existing = lines(
      ['Original first', parentId],
      ['Line 2', 'child_1'],
    )
    const result = tracked(existing, 'Modified first\nLine 2')
    expect(result[0]!.noteId).toBe(parentId)
  })

  it('multiple insertions all get null IDs', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Line 4', 'child_1'],
    )
    const result = tracked(existing, 'Line 1\nLine 2\nLine 3\nLine 4')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Line 2', null],
      ['Line 3', null],
      ['Line 4', 'child_1'],
    ))
  })

  it('modification preserves ID at same position with multiple lines', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Line 2', 'child_1'],
      ['Line 3', 'child_2'],
      ['Line 4', 'child_3'],
    )
    const result = tracked(existing, 'Line 1\nModified Line 2\nLine 3\nLine 4')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Modified Line 2', 'child_1'],
      ['Line 3', 'child_2'],
      ['Line 4', 'child_3'],
    ))
  })

  it('swapping two lines preserves IDs with content', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Line 2', 'child_1'],
      ['Line 3', 'child_2'],
    )
    const result = tracked(existing, 'Line 1\nLine 3\nLine 2')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Line 3', 'child_2'],
      ['Line 2', 'child_1'],
    ))
  })

  it('reordering multiple lines preserves IDs with content', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['AAA', 'child_a'],
      ['BBB', 'child_b'],
      ['CCC', 'child_c'],
    )
    const result = tracked(existing, 'Line 1\nCCC\nAAA\nBBB')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['CCC', 'child_c'],
      ['AAA', 'child_a'],
      ['BBB', 'child_b'],
    ))
  })

  it('deleting first line promotes second line to parent', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Line 2', 'child_1'],
      ['Line 3', 'child_2'],
    )
    const result = tracked(existing, 'Line 2\nLine 3')
    expect(result[0]!.noteId).toBe(parentId)
    expect(result[1]!.noteId).toBe('child_2')
  })

  // Requirement: when a line is split, the fragment with more original content keeps the ID
  it('line split gives ID to fragment with more original content', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Hello world.', 'child_1'],
    )
    const result = tracked(existing, 'Line 1\nHello world\n.')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Hello world', 'child_1'],
      ['.', null],
    ))
  })

  it('line split at beginning gives ID to longer fragment', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Hello world.', 'child_1'],
    )
    const result = tracked(existing, 'Line 1\nH\nello world.')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['H', null],
      ['ello world.', 'child_1'],
    ))
  })

  it('line split with subsequent edits preserves ID on original content', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Hello world.', 'child_1'],
    )
    const result = tracked(existing, 'Line 1\nHello world\n. plus a whole lot of new content')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['Hello world', 'child_1'],
      ['. plus a whole lot of new content', null],
    ))
  })

  it('line split at beginning with subsequent edits gives ID to fragment with more original content', () => {
    const existing = lines(
      ['Line 1', parentId],
      ['Hello world.', 'child_1'],
    )
    const result = tracked(existing, 'Line 1\nH plus a very long addition\nello world.')
    expect(unsentinelize(result)).toEqual(lines(
      ['Line 1', parentId],
      ['H plus a very long addition', null],
      ['ello world.', 'child_1'],
    ))
  })

  it('whitespace-only line is NOT treated as empty', () => {
    const existing = lines(['Line 1', parentId])
    const result = tracked(existing, 'Line 1\n   \nLine 3')
    expect(result).toHaveLength(3)
    expect(result[1]!.content).toBe('   ')
    expect(unsentinelize(result)[1]!.noteId).toBeNull()
  })
})
