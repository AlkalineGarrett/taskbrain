import { findDirectives, directiveHash } from './DirectiveFinder'
import type { DirectiveResult } from './DirectiveResult'
import { directiveResultToValue, isComputed, isViewResult } from './DirectiveResult'
import { toDisplayString } from '../runtime/DslValue'

const ALARM_PATTERN = /^\[alarm\("([^"]+)"\)]$/
const RECURRING_ALARM_PATTERN = /^\[recurringAlarm\("([^"]+)"\)]$/
const ALARM_SYMBOL = '⏰'

/** Extracts alarm ID if source text is an alarm directive, undefined otherwise. */
function alarmIdFromSource(sourceText: string): string | undefined {
  return ALARM_PATTERN.exec(sourceText)?.[1]
}

/** Extracts recurring alarm ID if source text is a recurringAlarm directive, undefined otherwise. */
function recurringAlarmIdFromSource(sourceText: string): string | undefined {
  return RECURRING_ALARM_PATTERN.exec(sourceText)?.[1]
}

/** Returns true if this is any alarm-type directive (alarm or recurringAlarm). */
function isAlarmDirective(sourceText: string): boolean {
  return alarmIdFromSource(sourceText) != null || recurringAlarmIdFromSource(sourceText) != null
}

const VIEW_PATTERN = /^\[view\(/

/** Returns true if the source text is a view directive, regardless of parse/execution result. */
function isViewDirective(sourceText: string): boolean {
  return VIEW_PATTERN.test(sourceText)
}

/** Returns true if a directive segment is a view — checks result first, falls back to source text. */
export function isViewSegment(segment: DirectiveSegmentType): boolean {
  return isViewResult(segment.result) || isViewDirective(segment.sourceText)
}

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
  isAlarm: boolean
  alarmId?: string
  recurringAlarmId?: string
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
  _lineId: string,
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

    const key = directiveHash(directive.sourceText)
    const result = results.get(key) ?? null
    const computed = result ? isComputed(result) : false
    let displayText = directive.sourceText
    if (result && computed) {
      const val = directiveResultToValue(result)
      if (val) displayText = toDisplayString(val)
    } else {
      // Alarm directives are trivial pure functions — render the icon
      // even without a computed result to avoid flicker from key mismatches
      if (isAlarmDirective(directive.sourceText)) displayText = ALARM_SYMBOL
    }

    segments.push({
      kind: 'Directive',
      sourceText: directive.sourceText,
      key,
      result,
      rangeStart: directive.startOffset,
      rangeEnd: directive.endOffset,
      displayText,
      isComputed: computed || isAlarmDirective(directive.sourceText),
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
  lineId: string,
  results: Map<string, DirectiveResult>,
): DisplayTextResult {
  const segments = segmentLine(content, lineId, results)

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
      const synthesizedAlarmId = alarmIdFromSource(segment.sourceText)
      const synthesizedRecurringId = recurringAlarmIdFromSource(segment.sourceText)
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
        isView: resultValue?.kind === 'ViewVal' || isViewDirective(segment.sourceText),
        isButton: resultValue?.kind === 'ButtonVal',
        isAlarm: resultValue?.kind === 'AlarmVal' || synthesizedAlarmId != null || synthesizedRecurringId != null,
        // Always extract alarm ID from source text — immune to result cache
        // key mismatches caused by stale noteIds after line reordering.
        alarmId: synthesizedAlarmId ?? (resultValue?.kind === 'AlarmVal' ? resultValue.alarmId : undefined),
        recurringAlarmId: synthesizedRecurringId,
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
