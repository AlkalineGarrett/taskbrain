import { findDirectives, directiveKey } from './DirectiveFinder'
import type { DirectiveResult } from './DirectiveResult'
import { directiveResultToValue, isComputed } from './DirectiveResult'
import { toDisplayString } from '../runtime/DslValue'

// ---- Segment types ----

export type DirectiveSegment = TextSegment | DirectiveSegmentType

export interface TextSegment {
  kind: 'Text'
  content: string
  rangeStart: number
  rangeEnd: number
}

export interface DirectiveSegmentType {
  kind: 'Directive'
  sourceText: string
  key: string
  result: DirectiveResult | null
  rangeStart: number
  rangeEnd: number
  displayText: string
  isComputed: boolean
}

export interface DirectiveDisplayRange {
  key: string
  sourceRangeStart: number
  sourceRangeEnd: number
  displayRangeStart: number
  displayRangeEnd: number
  sourceText: string
  displayText: string
  isComputed: boolean
  hasError: boolean
  hasWarning: boolean
  isView: boolean
  isButton: boolean
}

export interface DisplayTextResult {
  displayText: string
  segments: DirectiveSegment[]
  directiveDisplayRanges: DirectiveDisplayRange[]
}

/**
 * Split a line into segments of text and directives.
 */
export function segmentLine(
  content: string,
  lineIndex: number,
  results: Map<string, DirectiveResult>,
): DirectiveSegment[] {
  const directives = findDirectives(content)

  if (directives.length === 0) {
    return content.length === 0 ? [] : [{ kind: 'Text', content, rangeStart: 0, rangeEnd: content.length }]
  }

  const segments: DirectiveSegment[] = []
  let lastEnd = 0

  for (const directive of directives) {
    if (directive.startOffset > lastEnd) {
      segments.push({
        kind: 'Text',
        content: content.substring(lastEnd, directive.startOffset),
        rangeStart: lastEnd,
        rangeEnd: directive.startOffset,
      })
    }

    const key = directiveKey(lineIndex, directive.startOffset)
    const result = results.get(key) ?? null
    const computed = result ? isComputed(result) : false
    let displayText = directive.sourceText
    if (result && computed) {
      const val = directiveResultToValue(result)
      if (val) displayText = toDisplayString(val)
    }

    segments.push({
      kind: 'Directive',
      sourceText: directive.sourceText,
      key,
      result,
      rangeStart: directive.startOffset,
      rangeEnd: directive.endOffset,
      displayText,
      isComputed: computed,
    })

    lastEnd = directive.endOffset
  }

  if (lastEnd < content.length) {
    segments.push({ kind: 'Text', content: content.substring(lastEnd), rangeStart: lastEnd, rangeEnd: content.length })
  }

  return segments
}

/**
 * Build the display text for a line, replacing directive source with results.
 */
export function buildDisplayText(
  content: string,
  lineIndex: number,
  results: Map<string, DirectiveResult>,
): DisplayTextResult {
  const segments = segmentLine(content, lineIndex, results)

  if (segments.length === 0) {
    return { displayText: '', segments: [], directiveDisplayRanges: [] }
  }

  let displayText = ''
  const directiveDisplayRanges: DirectiveDisplayRange[] = []

  for (const segment of segments) {
    if (segment.kind === 'Text') {
      displayText += segment.content
    } else {
      const displayStart = displayText.length
      displayText += segment.displayText
      const displayEnd = displayText.length

      const resultValue = segment.result ? directiveResultToValue(segment.result) : null
      directiveDisplayRanges.push({
        key: segment.key,
        sourceRangeStart: segment.rangeStart,
        sourceRangeEnd: segment.rangeEnd,
        displayRangeStart: displayStart,
        displayRangeEnd: displayEnd,
        sourceText: segment.sourceText,
        displayText: segment.displayText,
        isComputed: segment.isComputed,
        hasError: segment.result?.error != null,
        hasWarning: segment.result?.warning != null,
        isView: resultValue?.kind === 'ViewVal',
        isButton: resultValue?.kind === 'ButtonVal',
      })
    }
  }

  return { displayText, segments, directiveDisplayRanges }
}

/**
 * Check if a line contains any directives.
 */
export function hasDirectives(content: string): boolean {
  return findDirectives(content).length > 0
}
