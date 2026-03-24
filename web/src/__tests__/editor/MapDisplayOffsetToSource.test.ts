import { describe, it, expect } from 'vitest'
import { mapDisplayOffsetToSource } from '../../editor/TextMeasure'
import { segmentLine } from '../../dsl/directives/DirectiveSegmenter'
import { directiveResultSuccess } from '../../dsl/directives/DirectiveResult'
import { directiveHash } from '../../dsl/directives/DirectiveFinder'
import { alarmVal, numberVal, stringVal, buttonVal } from '../../dsl/runtime/DslValue'

/**
 * Helper: build segments + results map for a line with directives.
 */
function buildSegments(content: string, lineId: string, resultEntries: [string, ReturnType<typeof directiveResultSuccess>][]) {
  const results = new Map(resultEntries)
  return segmentLine(content, lineId, results)
}

describe('mapDisplayOffsetToSource', () => {
  describe('text-only lines (no directives)', () => {
    it('maps offset directly when there are no directives', () => {
      const segments = buildSegments('hello world', 'L',[])
      expect(mapDisplayOffsetToSource(0, segments)).toBe(0)
      expect(mapDisplayOffsetToSource(5, segments)).toBe(5)
      expect(mapDisplayOffsetToSource(11, segments)).toBe(11)
    })

    it('returns 0 for empty segments', () => {
      expect(mapDisplayOffsetToSource(0, [])).toBe(0)
    })
  })

  describe('lines with alarm directives', () => {
    // Source: "Task [alarm(abc123)]"
    // Display: "Task ⏰"
    const content = 'Task [alarm(abc123)]'
    const alarmResult = directiveResultSuccess(alarmVal('abc123'))

    it('maps click on text before alarm to correct source position', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(abc123)]'), alarmResult]])
      // Display: "Task " = 5 chars, then "⏰"
      // Click at display offset 0 → source 0
      expect(mapDisplayOffsetToSource(0, segments)).toBe(0)
      // Click at display offset 3 → source 3 ("Tas|k")
      expect(mapDisplayOffsetToSource(3, segments)).toBe(3)
      // Click at display offset 5 → source 5 (end of "Task ", start of text segment boundary)
      expect(mapDisplayOffsetToSource(5, segments)).toBe(5)
    })

    it('maps click on alarm emoji to end of directive in source', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(abc123)]'), alarmResult]])
      // Display offset 6 = on/after the "⏰" emoji
      // Should map to source 20 (end of "[alarm(abc123)]")
      expect(mapDisplayOffsetToSource(6, segments)).toBe(20)
    })

    it('maps click past all content to end of source', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(abc123)]'), alarmResult]])
      expect(mapDisplayOffsetToSource(100, segments)).toBe(20)
    })
  })

  describe('lines with text after directive', () => {
    // Source: "A [alarm(x)] B"
    // Display: "A ⏰ B"
    const content = 'A [alarm(x)] B'
    const alarmResult = directiveResultSuccess(alarmVal('x'))

    it('maps click on text before directive', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(x)]'), alarmResult]])
      // "A " = 2 chars display
      expect(mapDisplayOffsetToSource(0, segments)).toBe(0)
      expect(mapDisplayOffsetToSource(1, segments)).toBe(1)
    })

    it('maps click on directive to end of directive source', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(x)]'), alarmResult]])
      // Display offset 2-3 is on the "⏰"
      expect(mapDisplayOffsetToSource(3, segments)).toBe(12) // after "[alarm(x)]"
    })

    it('maps click on text after directive correctly', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(x)]'), alarmResult]])
      // Display: "A ⏰ B"
      //           01 2 34
      // Display offset 3 = " " after emoji → source " " at position 12
      // Display offset 4 = "B" → source 13
      expect(mapDisplayOffsetToSource(4, segments)).toBe(13)
      expect(mapDisplayOffsetToSource(5, segments)).toBe(14)
    })
  })

  describe('lines with non-alarm computed directives', () => {
    // Source: "x=[1+2]"
    // Display: "x=3" (if result is number 3)
    const content = 'x=[1+2]'
    const numResult = directiveResultSuccess(numberVal(3))

    it('maps click on text before directive', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[1+2]'), numResult]])
      expect(mapDisplayOffsetToSource(0, segments)).toBe(0)
      expect(mapDisplayOffsetToSource(1, segments)).toBe(1)
    })

    it('maps click on directive display text to end of directive source', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[1+2]'), numResult]])
      // Display "3" is at offset 2, source directive ends at 7
      expect(mapDisplayOffsetToSource(3, segments)).toBe(7)
    })
  })

  describe('lines with string directives', () => {
    // Source: 'say ["hello"]'
    // Display: 'say hello' (string result displays without quotes)
    const content = 'say ["hello"]'
    const strResult = directiveResultSuccess(stringVal('hello'))

    it('maps click on displayed string to after directive source', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('["hello"]'), strResult]])
      // Display: "say hello"
      //           0123456789
      // "say " = 4 display chars, then "hello" = 5 display chars
      // Click at offset 7 ("say hel|lo") → should land after directive in source (position 13)
      expect(mapDisplayOffsetToSource(9, segments)).toBe(13)
    })

    it('maps click on text before directive correctly', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('["hello"]'), strResult]])
      expect(mapDisplayOffsetToSource(2, segments)).toBe(2)
    })
  })

  describe('lines with button directives', () => {
    // Source: '[run("Go")]'
    // Display: "▶ Go" (chip display for buttons)
    const content = '[run("Go")]'
    const btnResult = directiveResultSuccess(buttonVal('Go', { kind: 'LambdaVal', params: [], body: null as never, capturedEnv: null }))

    it('maps click on button chip to end of directive source', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[run("Go")]'), btnResult]])
      // "▶ Go" = 4 chars display, source is 11 chars
      expect(mapDisplayOffsetToSource(4, segments)).toBe(11)
    })
  })

  describe('multiple directives on one line', () => {
    // Source: "A [alarm(x)] B [alarm(y)] C"
    // Display: "A ⏰ B ⏰ C"
    const content = 'A [alarm(x)] B [alarm(y)] C'
    const alarm1 = directiveResultSuccess(alarmVal('x'))
    const alarm2 = directiveResultSuccess(alarmVal('y'))

    it('maps positions correctly across multiple directives', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(x)]'), alarm1], [directiveHash('[alarm(y)]'), alarm2]])
      // Display: "A ⏰ B ⏰ C"
      //           01 2 345 6 78

      // "A " → source 0-1
      expect(mapDisplayOffsetToSource(0, segments)).toBe(0)
      expect(mapDisplayOffsetToSource(1, segments)).toBe(1)

      // First alarm emoji → source 12 (end of [alarm(x)])
      expect(mapDisplayOffsetToSource(3, segments)).toBe(12)

      // " B " between alarms → source 12-14 (" B ")
      expect(mapDisplayOffsetToSource(4, segments)).toBe(13)
      expect(mapDisplayOffsetToSource(5, segments)).toBe(14)

      // Second alarm emoji → source 25 (end of [alarm(y)])
      expect(mapDisplayOffsetToSource(7, segments)).toBe(25)

      // " C" after second alarm → source 25-26
      expect(mapDisplayOffsetToSource(8, segments)).toBe(26)
    })
  })

  describe('directive at start of line', () => {
    // Source: "[alarm(z)] end"
    // Display: "⏰ end"
    const content = '[alarm(z)] end'
    const alarmResult = directiveResultSuccess(alarmVal('z'))

    it('maps click on leading directive to end of directive source', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(z)]'), alarmResult]])
      // Display offset 0-1 is on "⏰", maps to source 10 (after [alarm(z)])
      expect(mapDisplayOffsetToSource(1, segments)).toBe(10)
    })

    it('maps click on text after leading directive', () => {
      const segments = buildSegments(content, 'L',[[directiveHash('[alarm(z)]'), alarmResult]])
      // Display: "⏰ end"
      //           0 1234
      // Display offset 2 = " " → source 10 + offset-in-segment
      expect(mapDisplayOffsetToSource(2, segments)).toBe(11)
      expect(mapDisplayOffsetToSource(5, segments)).toBe(14)
    })
  })

  describe('uncomputed directive (no result)', () => {
    // Source: "x=[expr]"
    // No result → displayText = sourceText = "[expr]" (shown as chip with source text)
    const content = 'x=[expr]'

    it('maps text before directive correctly', () => {
      const segments = buildSegments(content, 'L',[])
      expect(mapDisplayOffsetToSource(0, segments)).toBe(0)
      expect(mapDisplayOffsetToSource(1, segments)).toBe(1)
    })

    it('maps click on uncomputed directive chip to end of directive', () => {
      const segments = buildSegments(content, 'L',[])
      // Display: "x=" (2 chars) + "[expr]" (6 chars, chip showing source text)
      // Click on chip at display offset 5 → maps to end of directive (source 8)
      expect(mapDisplayOffsetToSource(5, segments)).toBe(8)
    })
  })
})
