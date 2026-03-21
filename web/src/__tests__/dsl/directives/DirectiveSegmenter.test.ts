import { describe, it, expect } from 'vitest'
import { segmentLine, buildDisplayText, hasDirectives } from '../../../dsl/directives/DirectiveSegmenter'
import type { DirectiveResult } from '../../../dsl/directives/DirectiveResult'
import { directiveResultSuccess, directiveResultFailure } from '../../../dsl/directives/DirectiveResult'
import { directiveKey } from '../../../dsl/directives/DirectiveFinder'
import { numberVal, stringVal, alarmVal } from '../../../dsl/runtime/DslValue'

function resultsMap(entries: [string, DirectiveResult][]): Map<string, DirectiveResult> {
  return new Map(entries)
}

describe('segmentLine', () => {
  it('returns single text segment for plain text', () => {
    const segments = segmentLine('hello world', 0, new Map())
    expect(segments).toHaveLength(1)
    expect(segments[0]!.kind).toBe('Text')
    if (segments[0]!.kind === 'Text') {
      expect(segments[0]!.content).toBe('hello world')
      expect(segments[0]!.rangeStart).toBe(0)
      expect(segments[0]!.rangeEnd).toBe(11)
    }
  })

  it('returns empty array for empty content', () => {
    const segments = segmentLine('', 0, new Map())
    expect(segments).toHaveLength(0)
  })

  it('returns directive segment for directive-only content', () => {
    const segments = segmentLine('[test]', 0, new Map())
    expect(segments).toHaveLength(1)
    expect(segments[0]!.kind).toBe('Directive')
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.sourceText).toBe('[test]')
      expect(segments[0]!.key).toBe('0:0')
      expect(segments[0]!.result).toBeNull()
      expect(segments[0]!.isComputed).toBe(false)
    }
  })

  it('splits text and directive segments', () => {
    const segments = segmentLine('before [dir] after', 0, new Map())
    expect(segments).toHaveLength(3)
    expect(segments[0]!.kind).toBe('Text')
    expect(segments[1]!.kind).toBe('Directive')
    expect(segments[2]!.kind).toBe('Text')

    if (segments[0]!.kind === 'Text') expect(segments[0]!.content).toBe('before ')
    if (segments[1]!.kind === 'Directive') expect(segments[1]!.sourceText).toBe('[dir]')
    if (segments[2]!.kind === 'Text') expect(segments[2]!.content).toBe(' after')
  })

  it('uses line index for directive key', () => {
    const segments = segmentLine('[test]', 3, new Map())
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.key).toBe('3:0')
    }
  })

  it('attaches result when available', () => {
    const key = directiveKey(0, 0)
    const result = directiveResultSuccess(numberVal(42))
    const segments = segmentLine('[calc]', 0, resultsMap([[key, result]]))
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.result).not.toBeNull()
      expect(segments[0]!.isComputed).toBe(true)
      expect(segments[0]!.displayText).toBe('42')
    }
  })

  it('shows source text when no result', () => {
    const segments = segmentLine('[calc]', 0, new Map())
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      expect(segments[0]!.displayText).toBe('[calc]')
      expect(segments[0]!.isComputed).toBe(false)
    }
  })

  it('shows source text for error result', () => {
    const key = directiveKey(0, 0)
    const result = directiveResultFailure('bad')
    const segments = segmentLine('[calc]', 0, resultsMap([[key, result]]))
    expect(segments).toHaveLength(1)
    if (segments[0]!.kind === 'Directive') {
      // isComputed is false for errors, so displayText remains sourceText
      expect(segments[0]!.isComputed).toBe(false)
      expect(segments[0]!.displayText).toBe('[calc]')
    }
  })

  it('handles multiple directives', () => {
    const segments = segmentLine('[a] + [b]', 0, new Map())
    expect(segments).toHaveLength(3)
    expect(segments[0]!.kind).toBe('Directive')
    expect(segments[1]!.kind).toBe('Text')
    expect(segments[2]!.kind).toBe('Directive')
  })

  it('tracks correct ranges for segments', () => {
    const segments = segmentLine('hi [x] bye', 0, new Map())
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
    const result = buildDisplayText('hello', 0, new Map())
    expect(result.displayText).toBe('hello')
    expect(result.directiveDisplayRanges).toHaveLength(0)
  })

  it('returns empty for empty content', () => {
    const result = buildDisplayText('', 0, new Map())
    expect(result.displayText).toBe('')
    expect(result.segments).toHaveLength(0)
  })

  it('replaces directive source with computed result', () => {
    const key = directiveKey(0, 0)
    const dr = directiveResultSuccess(numberVal(42))
    const result = buildDisplayText('[calc]', 0, resultsMap([[key, dr]]))
    expect(result.displayText).toBe('42')
  })

  it('keeps directive source when no result', () => {
    const result = buildDisplayText('val=[calc]', 0, new Map())
    expect(result.displayText).toBe('val=[calc]')
  })

  it('builds display text with mixed text and directives', () => {
    const key = directiveKey(0, 4)
    const dr = directiveResultSuccess(stringVal('world'))
    const result = buildDisplayText('say [greet]!', 0, resultsMap([[key, dr]]))
    expect(result.displayText).toBe('say world!')
  })

  it('populates directive display ranges', () => {
    const key = directiveKey(0, 0)
    const dr = directiveResultSuccess(numberVal(7))
    const result = buildDisplayText('[x] end', 0, resultsMap([[key, dr]]))
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
    const key = directiveKey(0, 0)
    const dr = directiveResultFailure('bad')
    const result = buildDisplayText('[x]', 0, resultsMap([[key, dr]]))
    expect(result.directiveDisplayRanges).toHaveLength(1)
    expect(result.directiveDisplayRanges[0]!.hasError).toBe(true)
    expect(result.directiveDisplayRanges[0]!.isComputed).toBe(false)
  })

  it('handles multiple directives with different display lengths', () => {
    const key1 = directiveKey(0, 0)
    const key2 = directiveKey(0, 6)
    const dr1 = directiveResultSuccess(stringVal('AB'))
    const dr2 = directiveResultSuccess(stringVal('CDEF'))
    const result = buildDisplayText('[xxx] [yyy]', 0, resultsMap([[key1, dr1], [key2, dr2]]))
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
    const key = directiveKey(0, 0)
    const dr = directiveResultSuccess(alarmVal('alarm-123'))
    const result = buildDisplayText('[alarm("alarm-123")]', 0, resultsMap([[key, dr]]))

    expect(result.directiveDisplayRanges).toHaveLength(1)
    const range = result.directiveDisplayRanges[0]!
    expect(range.isAlarm).toBe(true)
    expect(range.alarmId).toBe('alarm-123')
    expect(range.isButton).toBe(false)
    expect(range.isView).toBe(false)
  })

  it('non-alarm directive has isAlarm false and no alarmId', () => {
    const key = directiveKey(0, 0)
    const dr = directiveResultSuccess(numberVal(42))
    const result = buildDisplayText('[calc]', 0, resultsMap([[key, dr]]))

    const range = result.directiveDisplayRanges[0]!
    expect(range.isAlarm).toBe(false)
    expect(range.alarmId).toBeUndefined()
  })

  it('alarm directive mixed with text preserves display ranges', () => {
    const key = directiveKey(0, 5)
    const dr = directiveResultSuccess(alarmVal('a1'))
    const result = buildDisplayText('Task [alarm("a1")] done', 0, resultsMap([[key, dr]]))

    expect(result.directiveDisplayRanges).toHaveLength(1)
    const range = result.directiveDisplayRanges[0]!
    expect(range.isAlarm).toBe(true)
    expect(range.alarmId).toBe('a1')
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
