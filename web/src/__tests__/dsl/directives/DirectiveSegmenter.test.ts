import { describe, it, expect } from 'vitest'
import { segmentLine, buildDisplayText, hasDirectives, isViewSegment, type DirectiveSegmentType } from '../../../dsl/directives/DirectiveSegmenter'
import type { DirectiveResult } from '../../../dsl/directives/DirectiveResult'
import { directiveResultSuccess, directiveResultFailure } from '../../../dsl/directives/DirectiveResult'
import { directiveHash } from '../../../dsl/directives/DirectiveFinder'
import { numberVal, stringVal, alarmVal, viewVal } from '../../../dsl/runtime/DslValue'
import type { Note } from '../../../data/Note'

function resultsMap(entries: [string, DirectiveResult][]): Map<string, DirectiveResult> {
  return new Map(entries)
}

describe('segmentLine', () => {
  it('returns single text segment for plain text', () => {
    const segments = segmentLine('hello world', 'test-line', new Map())
    expect(segments).toHaveLength(1)
    expect(segments[0]!.kind).toBe('Text')
    if (segments[0]!.kind === 'Text') {
      expect(segments[0]!.content).toBe('hello world')
      expect(segments[0]!.rangeStart).toBe(0)
      expect(segments[0]!.rangeEnd).toBe(11)
    }
  })

  it('returns empty array for empty content', () => {
    const segments = segmentLine('', 'test-line', new Map())
    expect(segments).toHaveLength(0)
  })

  it('returns directive segment for directive-only content', () => {
    const segments = segmentLine('[test]', 'test-line', new Map())
    expect(segments).toHaveLength(1)
    expect(segments[0]!.kind).toBe('Directive')
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.sourceText).toBe('[test]')
      expect(segments[0]!.key).toBe(directiveHash('[test]'))
      expect(segments[0]!.result).toBeNull()
      expect(segments[0]!.isComputed).toBe(false)
    }
  })

  it('splits text and directive segments', () => {
    const segments = segmentLine('before [dir] after', 'test-line', new Map())
    expect(segments).toHaveLength(3)
    expect(segments[0]!.kind).toBe('Text')
    expect(segments[1]!.kind).toBe('Directive')
    expect(segments[2]!.kind).toBe('Text')

    if (segments[0]!.kind === 'Text') expect(segments[0]!.content).toBe('before ')
    if (segments[1]!.kind === 'Directive') expect(segments[1]!.sourceText).toBe('[dir]')
    if (segments[2]!.kind === 'Text') expect(segments[2]!.content).toBe(' after')
  })

  it('uses directive hash for key', () => {
    const segments = segmentLine('[test]', 'note-abc', new Map())
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.key).toBe(directiveHash('[test]'))
    }
  })

  it('attaches result when available', () => {
    const key = directiveHash('[calc]')
    const result = directiveResultSuccess(numberVal(42))
    const segments = segmentLine('[calc]', 'test-line', resultsMap([[key, result]]))
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.result).not.toBeNull()
      expect(segments[0]!.isComputed).toBe(true)
      expect(segments[0]!.displayText).toBe('42')
    }
  })

  it('shows source text when no result', () => {
    const segments = segmentLine('[calc]', 'test-line', new Map())
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.displayText).toBe('[calc]')
      expect(segments[0]!.isComputed).toBe(false)
    }
  })

  it('shows source text for error result', () => {
    const key = directiveHash('[calc]')
    const result = directiveResultFailure('bad')
    const segments = segmentLine('[calc]', 'test-line', resultsMap([[key, result]]))
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      // isComputed is false for errors, so displayText remains sourceText
      expect(segments[0]!.isComputed).toBe(false)
      expect(segments[0]!.displayText).toBe('[calc]')
    }
  })

  it('handles multiple directives', () => {
    const segments = segmentLine('[a] + [b]', 'test-line', new Map())
    expect(segments).toHaveLength(3)
    expect(segments[0]!.kind).toBe('Directive')
    expect(segments[1]!.kind).toBe('Text')
    expect(segments[2]!.kind).toBe('Directive')
  })

  it('tracks correct ranges for segments', () => {
    const segments = segmentLine('hi [x] bye', 'test-line', new Map())
    expect(segments).toHaveLength(3)

    // "hi " -> 0..3
    if (segments[0]!.kind === 'Text') {
      expect(segments[0]!.rangeStart).toBe(0)
      expect(segments[0]!.rangeEnd).toBe(3)
    }
    // "[x]" -> 3..6
    if (segments[1]!.kind === 'Directive') {
      expect(segments[1]!.rangeStart).toBe(3)
      expect(segments[1]!.rangeEnd).toBe(6)
    }
    // " bye" -> 6..10
    if (segments[2]!.kind === 'Text') {
      expect(segments[2]!.rangeStart).toBe(6)
      expect(segments[2]!.rangeEnd).toBe(10)
    }
  })
})

describe('buildDisplayText', () => {
  it('returns plain text unchanged', () => {
    const result = buildDisplayText('hello', 'test-line', new Map())
    expect(result.displayText).toBe('hello')
    expect(result.directiveDisplayRanges).toHaveLength(0)
  })

  it('returns empty for empty content', () => {
    const result = buildDisplayText('', 'test-line', new Map())
    expect(result.displayText).toBe('')
    expect(result.segments).toHaveLength(0)
  })

  it('replaces directive source with computed result', () => {
    const key = directiveHash('[calc]')
    const dr = directiveResultSuccess(numberVal(42))
    const result = buildDisplayText('[calc]', 'test-line', resultsMap([[key, dr]]))
    expect(result.displayText).toBe('42')
  })

  it('keeps directive source when no result', () => {
    const result = buildDisplayText('val=[calc]', 'test-line', new Map())
    expect(result.displayText).toBe('val=[calc]')
  })

  it('builds display text with mixed text and directives', () => {
    const key = directiveHash('[greet]')
    const dr = directiveResultSuccess(stringVal('world'))
    const result = buildDisplayText('say [greet]!', 'test-line', resultsMap([[key, dr]]))
    expect(result.displayText).toBe('say world!')
  })

  it('populates directive display ranges', () => {
    const key = directiveHash('[x]')
    const dr = directiveResultSuccess(numberVal(7))
    const result = buildDisplayText('[x] end', 'test-line', resultsMap([[key, dr]]))
    expect(result.directiveDisplayRanges).toHaveLength(1)

    const range = result.directiveDisplayRanges[0]!
    expect(range.key).toBe(key)
    expect(range.sourceText).toBe('[x]')
    expect(range.displayText).toBe('7')
    expect(range.isComputed).toBe(true)
    expect(range.hasError).toBe(false)
    expect(range.hasWarning).toBe(false)
    expect(range.sourceRangeStart).toBe(0)
    expect(range.sourceRangeEnd).toBe(3)
    expect(range.displayRangeStart).toBe(0)
    expect(range.displayRangeEnd).toBe(1) // "7" is 1 char
  })

  it('marks error in display range', () => {
    const key = directiveHash('[x]')
    const dr = directiveResultFailure('bad')
    const result = buildDisplayText('[x]', 'test-line', resultsMap([[key, dr]]))
    expect(result.directiveDisplayRanges).toHaveLength(1)
    expect(result.directiveDisplayRanges[0]!.hasError).toBe(true)
    expect(result.directiveDisplayRanges[0]!.isComputed).toBe(false)
  })

  it('handles multiple directives with different display lengths', () => {
    const key1 = directiveHash('[xxx]')
    const key2 = directiveHash('[yyy]')
    const dr1 = directiveResultSuccess(stringVal('AB'))
    const dr2 = directiveResultSuccess(stringVal('CDEF'))
    const result = buildDisplayText('[xxx] [yyy]', 'test-line', resultsMap([[key1, dr1], [key2, dr2]]))
    expect(result.displayText).toBe('AB CDEF')

    const ranges = result.directiveDisplayRanges
    expect(ranges).toHaveLength(2)
    // First: "AB" at display 0..2
    expect(ranges[0]!.displayRangeStart).toBe(0)
    expect(ranges[0]!.displayRangeEnd).toBe(2)
    // Second: "CDEF" at display 3..7 (after "AB ")
    expect(ranges[1]!.displayRangeStart).toBe(3)
    expect(ranges[1]!.displayRangeEnd).toBe(7)
  })
})

describe('buildDisplayText alarm directives', () => {
  it('marks alarm directive with isAlarm and alarmId', () => {
    const key = directiveHash('[alarm("alarm-123")]')
    const dr = directiveResultSuccess(alarmVal('alarm-123'))
    const result = buildDisplayText('[alarm("alarm-123")]', 'test-line', resultsMap([[key, dr]]))

    expect(result.directiveDisplayRanges).toHaveLength(1)
    const range = result.directiveDisplayRanges[0]!
    expect(range.isAlarm).toBe(true)
    expect(range.alarmId).toBe('alarm-123')
    expect(range.isButton).toBe(false)
    expect(range.isView).toBe(false)
  })

  it('non-alarm directive has isAlarm false and no alarmId', () => {
    const key = directiveHash('[calc]')
    const dr = directiveResultSuccess(numberVal(42))
    const result = buildDisplayText('[calc]', 'test-line', resultsMap([[key, dr]]))

    const range = result.directiveDisplayRanges[0]!
    expect(range.isAlarm).toBe(false)
    expect(range.alarmId).toBeUndefined()
  })

  it('alarm directive mixed with text preserves display ranges', () => {
    const key = directiveHash('[alarm("a1")]')
    const dr = directiveResultSuccess(alarmVal('a1'))
    const result = buildDisplayText('Task [alarm("a1")] done', 'test-line', resultsMap([[key, dr]]))

    expect(result.directiveDisplayRanges).toHaveLength(1)
    const range = result.directiveDisplayRanges[0]!
    expect(range.isAlarm).toBe(true)
    expect(range.alarmId).toBe('a1')
  })

  it('alarm directive displays as clock emoji', () => {
    const key = directiveHash('[alarm("a1")]')
    const dr = directiveResultSuccess(alarmVal('a1'))
    const result = buildDisplayText('[alarm("a1")]', 'test-line', resultsMap([[key, dr]]))
    expect(result.displayText).toBe('\u23F0')
  })

  it('alarm sourceRange covers entire directive text', () => {
    const key = directiveHash('[alarm("a1")]')
    const dr = directiveResultSuccess(alarmVal('a1'))
    const result = buildDisplayText('Task [alarm("a1")] done', 'test-line', resultsMap([[key, dr]]))

    const range = result.directiveDisplayRanges[0]!
    expect(range.sourceRangeStart).toBe(5)
    expect(range.sourceRangeEnd).toBe(18) // end of [alarm("a1")]
  })

  it('alarm displayRange is single emoji width', () => {
    const key = directiveHash('[alarm("a1")]')
    const dr = directiveResultSuccess(alarmVal('a1'))
    const result = buildDisplayText('[alarm("a1")]', 'test-line', resultsMap([[key, dr]]))

    const range = result.directiveDisplayRanges[0]!
    expect(range.displayRangeEnd - range.displayRangeStart).toBe(1) // "⏰" = 1 char
  })
})

describe('buildDisplayText recurringAlarm directives', () => {
  it('marks recurringAlarm directive with isAlarm and recurringAlarmId', () => {
    const key = directiveHash('[recurringAlarm("rec-123")]')
    const dr = directiveResultSuccess(alarmVal('rec-123'))
    const result = buildDisplayText('[recurringAlarm("rec-123")]', 'test-line', resultsMap([[key, dr]]))

    expect(result.directiveDisplayRanges).toHaveLength(1)
    const range = result.directiveDisplayRanges[0]!
    expect(range.isAlarm).toBe(true)
    expect(range.recurringAlarmId).toBe('rec-123')
    // alarmId is also set from the AlarmVal result value
    expect(range.alarmId).toBe('rec-123')
  })

  it('recurringAlarm directive displays as clock emoji', () => {
    const key = directiveHash('[recurringAlarm("rec1")]')
    const dr = directiveResultSuccess(alarmVal('rec1'))
    const result = buildDisplayText('[recurringAlarm("rec1")]', 'test-line', resultsMap([[key, dr]]))
    expect(result.displayText).toBe('\u23F0')
  })

  it('renders recurringAlarm as clock emoji even without computed result', () => {
    const result = buildDisplayText('[recurringAlarm("rec1")]', 'test-line', new Map())
    expect(result.displayText).toBe('\u23F0')
    expect(result.directiveDisplayRanges[0]!.isAlarm).toBe(true)
    expect(result.directiveDisplayRanges[0]!.recurringAlarmId).toBe('rec1')
  })

  it('mixed alarm and recurringAlarm on same line', () => {
    const key1 = directiveHash('[alarm("a1")]')
    const key2 = directiveHash('[recurringAlarm("rec1")]')
    const dr1 = directiveResultSuccess(alarmVal('a1'))
    const dr2 = directiveResultSuccess(alarmVal('rec1'))
    const result = buildDisplayText(
      '[alarm("a1")] [recurringAlarm("rec1")]', 'test-line',
      resultsMap([[key1, dr1], [key2, dr2]])
    )

    expect(result.directiveDisplayRanges).toHaveLength(2)
    expect(result.directiveDisplayRanges[0]!.alarmId).toBe('a1')
    expect(result.directiveDisplayRanges[0]!.recurringAlarmId).toBeUndefined()
    expect(result.directiveDisplayRanges[1]!.recurringAlarmId).toBe('rec1')
  })
})

function directiveSegment(sourceText: string, result: DirectiveResult | null): DirectiveSegmentType {
  return {
    kind: 'Directive',
    sourceText,
    key: directiveHash(sourceText),
    result,
    rangeStart: 0,
    rangeEnd: sourceText.length,
    displayText: sourceText,
    isComputed: false,
  }
}

const dummyNote: Note = {
  id: 'n1', userId: 'u1', parentNoteId: null, content: 'test',
  createdAt: null, updatedAt: null, lastAccessedAt: null,
  tags: [], containedNotes: [], state: null, path: '/test',
  rootNoteId: null, showCompleted: true,
}

describe('isViewSegment', () => {
  it('returns true for view directive with successful result', () => {
    const result = directiveResultSuccess(viewVal([dummyNote]))
    expect(isViewSegment(directiveSegment('[view(find())]', result))).toBe(true)
  })

  it('returns true for view directive with error result (source text fallback)', () => {
    const result = directiveResultFailure('parse error')
    expect(isViewSegment(directiveSegment('[view(bad syntax)]', result))).toBe(true)
  })

  it('returns true for view directive with no result', () => {
    expect(isViewSegment(directiveSegment('[view(find())]', null))).toBe(true)
  })

  it('returns false for non-view directive', () => {
    const result = directiveResultSuccess(numberVal(42))
    expect(isViewSegment(directiveSegment('[calc]', result))).toBe(false)
  })

  it('returns false for non-view directive with error', () => {
    const result = directiveResultFailure('bad')
    expect(isViewSegment(directiveSegment('[calc]', result))).toBe(false)
  })

  it('returns false for source text that merely contains view', () => {
    expect(isViewSegment(directiveSegment('[overview()]', null))).toBe(false)
  })
})

describe('buildDisplayText view directives', () => {
  it('marks view directive with isView when result is ViewVal', () => {
    const source = '[view(find())]'
    const key = directiveHash(source)
    const dr = directiveResultSuccess(viewVal([dummyNote]))
    const result = buildDisplayText(source, 'test-line', resultsMap([[key, dr]]))
    expect(result.directiveDisplayRanges[0]!.isView).toBe(true)
  })

  it('marks view directive with isView even on error (source text fallback)', () => {
    const source = '[view(bad)]'
    const key = directiveHash(source)
    const dr = directiveResultFailure('parse error')
    const result = buildDisplayText(source, 'test-line', resultsMap([[key, dr]]))
    expect(result.directiveDisplayRanges[0]!.isView).toBe(true)
  })

  it('marks view directive with isView when no result at all', () => {
    const source = '[view(find())]'
    const result = buildDisplayText(source, 'test-line', new Map())
    expect(result.directiveDisplayRanges[0]!.isView).toBe(true)
  })

  it('does not mark non-view directive as isView', () => {
    const source = '[calc]'
    const result = buildDisplayText(source, 'test-line', new Map())
    expect(result.directiveDisplayRanges[0]!.isView).toBe(false)
  })
})

describe('hasDirectives', () => {
  it('returns true for content with brackets', () => {
    expect(hasDirectives('[test]')).toBe(true)
    expect(hasDirectives('some [dir] here')).toBe(true)
  })

  it('returns false for plain text', () => {
    expect(hasDirectives('no directives')).toBe(false)
    expect(hasDirectives('')).toBe(false)
  })

  it('returns false for unclosed brackets', () => {
    expect(hasDirectives('[unclosed')).toBe(false)
  })
})
