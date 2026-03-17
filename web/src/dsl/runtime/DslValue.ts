import type { Note } from '@/data/Note'
import { Timestamp } from 'firebase/firestore'
import type { Expression, PatternElement, CharClassType, Quantifier } from '../language/Expression'

// Forward reference types to avoid circular imports
export type { Environment } from './Environment'

/**
 * Runtime values in the DSL.
 * Each variant represents a different type of value that can result from evaluation.
 * Uses discriminated union (TypeScript equivalent of Kotlin sealed class).
 */
export type DslValue =
  | UndefinedVal
  | NumberVal
  | StringVal
  | BooleanVal
  | DateVal
  | TimeVal
  | DateTimeVal
  | PatternVal
  | NoteVal
  | ListVal
  | ViewVal
  | LambdaVal
  | ButtonVal
  | ScheduleVal

// ---- Primitive values ----

export interface UndefinedVal {
  readonly kind: 'UndefinedVal'
}

export const UNDEFINED: UndefinedVal = { kind: 'UndefinedVal' }

export interface NumberVal {
  readonly kind: 'NumberVal'
  readonly value: number
}

export function numberVal(value: number): NumberVal {
  return { kind: 'NumberVal', value }
}

export interface StringVal {
  readonly kind: 'StringVal'
  readonly value: string
}

export function stringVal(value: string): StringVal {
  return { kind: 'StringVal', value }
}

export interface BooleanVal {
  readonly kind: 'BooleanVal'
  readonly value: boolean
}

export function booleanVal(value: boolean): BooleanVal {
  return { kind: 'BooleanVal', value }
}

// ---- Temporal values ----

/**
 * Date value (year, month, day only).
 * Uses ISO 8601 string internally for simplicity (YYYY-MM-DD).
 */
export interface DateVal {
  readonly kind: 'DateVal'
  readonly value: string // ISO date: YYYY-MM-DD
}

export function dateVal(isoDate: string): DateVal {
  return { kind: 'DateVal', value: isoDate }
}

/**
 * Time value (hour, minute, second only).
 * Uses ISO 8601 string internally (HH:mm:ss).
 */
export interface TimeVal {
  readonly kind: 'TimeVal'
  readonly value: string // ISO time: HH:mm:ss
}

export function timeVal(isoTime: string): TimeVal {
  return { kind: 'TimeVal', value: isoTime }
}

/**
 * DateTime value (date + time).
 * Uses ISO 8601 string internally (YYYY-MM-DDTHH:mm:ss).
 */
export interface DateTimeVal {
  readonly kind: 'DateTimeVal'
  readonly value: string // ISO datetime: YYYY-MM-DDTHH:mm:ss
}

export function dateTimeVal(isoDateTime: string): DateTimeVal {
  return { kind: 'DateTimeVal', value: isoDateTime }
}

// ---- Complex values ----

export interface PatternVal {
  readonly kind: 'PatternVal'
  readonly elements: PatternElement[]
  readonly compiledRegex: RegExp
}

export function patternVal(elements: PatternElement[], compiledRegex: RegExp): PatternVal {
  return { kind: 'PatternVal', elements, compiledRegex }
}

export interface NoteVal {
  readonly kind: 'NoteVal'
  readonly note: Note
}

export function noteVal(note: Note): NoteVal {
  return { kind: 'NoteVal', note }
}

export interface ListVal {
  readonly kind: 'ListVal'
  readonly items: DslValue[]
}

export function listVal(items: DslValue[]): ListVal {
  return { kind: 'ListVal', items }
}

export interface ViewVal {
  readonly kind: 'ViewVal'
  readonly notes: Note[]
  readonly renderedContents: string[] | null
}

export function viewVal(notes: Note[], renderedContents: string[] | null = null): ViewVal {
  return { kind: 'ViewVal', notes, renderedContents }
}

export interface LambdaVal {
  readonly kind: 'LambdaVal'
  readonly params: string[]
  readonly body: Expression
  readonly capturedEnv: unknown // Environment (avoid circular import)
}

export function lambdaVal(params: string[], body: Expression, capturedEnv: unknown): LambdaVal {
  return { kind: 'LambdaVal', params, body, capturedEnv }
}

// ---- Action values ----

export enum ScheduleFrequency {
  DAILY = 'daily',
  HOURLY = 'hourly',
  WEEKLY = 'weekly',
  ONCE = 'once',
}

export function scheduleFrequencyFromId(id: string): ScheduleFrequency | null {
  const values = Object.values(ScheduleFrequency) as string[]
  return values.includes(id) ? (id as ScheduleFrequency) : null
}

export interface ButtonVal {
  readonly kind: 'ButtonVal'
  readonly label: string
  readonly action: LambdaVal
}

export function buttonVal(label: string, action: LambdaVal): ButtonVal {
  return { kind: 'ButtonVal', label, action }
}

export interface ScheduleVal {
  readonly kind: 'ScheduleVal'
  readonly frequency: ScheduleFrequency
  readonly action: LambdaVal
  readonly atTime: string | null
  readonly precise: boolean
}

export function scheduleVal(
  frequency: ScheduleFrequency,
  action: LambdaVal,
  atTime: string | null = null,
  precise: boolean = false,
): ScheduleVal {
  return { kind: 'ScheduleVal', frequency, action, atTime, precise }
}

// ---- Display ----

export function toDisplayString(val: DslValue): string {
  switch (val.kind) {
    case 'UndefinedVal':
      return 'undefined'
    case 'NumberVal':
      return Number.isInteger(val.value) ? val.value.toFixed(0) : String(val.value)
    case 'StringVal':
      return val.value
    case 'BooleanVal':
      return val.value ? 'true' : 'false'
    case 'DateVal':
      return val.value
    case 'TimeVal':
      return val.value
    case 'DateTimeVal': {
      // Display as "2026-01-25, 14:30:00" instead of "2026-01-25T14:30:00"
      const [date, time] = val.value.split('T')
      return `${date}, ${time}`
    }
    case 'PatternVal':
      return `pattern(${val.compiledRegex.source})`
    case 'NoteVal': {
      const note = val.note
      if (note.path.length > 0) return note.path
      if (note.content.length > 0) return note.content.split('\n')[0] ?? note.id
      return note.id
    }
    case 'ListVal': {
      if (val.items.length === 0) return '[]'
      return `[${val.items.map(toDisplayString).join(', ')}]`
    }
    case 'ViewVal': {
      const contents = val.renderedContents ?? val.notes.map((n) => n.content)
      if (contents.length === 0) return '[empty view]'
      if (contents.length === 1) return contents[0]!
      return contents.join('\n---\n')
    }
    case 'LambdaVal':
      return `<lambda(${val.params.join()})>`
    case 'ButtonVal':
      return `[Button: ${val.label}]`
    case 'ScheduleVal': {
      const timeStr = val.atTime ? ` at ${val.atTime}` : ''
      const preciseStr = val.precise ? ' (precise)' : ''
      return `[Schedule: ${val.frequency}${timeStr}${preciseStr}]`
    }
  }
}

// ---- Type name ----

export function typeName(val: DslValue): string {
  switch (val.kind) {
    case 'UndefinedVal': return 'undefined'
    case 'NumberVal': return 'number'
    case 'StringVal': return 'string'
    case 'BooleanVal': return 'boolean'
    case 'DateVal': return 'date'
    case 'TimeVal': return 'time'
    case 'DateTimeVal': return 'datetime'
    case 'PatternVal': return 'pattern'
    case 'NoteVal': return 'note'
    case 'ListVal': return 'list'
    case 'ViewVal': return 'view'
    case 'LambdaVal': return 'lambda'
    case 'ButtonVal': return 'button'
    case 'ScheduleVal': return 'schedule'
  }
}

// ---- Serialization ----

export function serializeValue(val: DslValue): Record<string, unknown> {
  return { type: typeName(val), value: serializeInner(val) }
}

function serializeInner(val: DslValue): unknown {
  switch (val.kind) {
    case 'UndefinedVal': return null
    case 'NumberVal': return val.value
    case 'StringVal': return val.value
    case 'BooleanVal': return val.value
    case 'DateVal': return val.value
    case 'TimeVal': return val.value
    case 'DateTimeVal': return val.value
    case 'PatternVal': return val.compiledRegex.source
    case 'NoteVal': return {
      id: val.note.id,
      userId: val.note.userId,
      path: val.note.path,
      content: val.note.content,
      createdAt: val.note.createdAt?.toDate().getTime() ?? null,
      updatedAt: val.note.updatedAt?.toDate().getTime() ?? null,
      lastAccessedAt: val.note.lastAccessedAt?.toDate().getTime() ?? null,
    }
    case 'ListVal': return val.items.map(serializeValue)
    case 'ViewVal': return {
      notes: val.notes.map((note) => ({
        id: note.id,
        userId: note.userId,
        path: note.path,
        content: note.content,
        createdAt: note.createdAt?.toDate().getTime() ?? null,
        updatedAt: note.updatedAt?.toDate().getTime() ?? null,
        lastAccessedAt: note.lastAccessedAt?.toDate().getTime() ?? null,
      })),
      renderedContents: val.renderedContents,
    }
    case 'LambdaVal':
      throw new Error('Lambdas cannot be serialized')
    case 'ButtonVal': return { label: val.label }
    case 'ScheduleVal': return {
      frequency: val.frequency,
      atTime: val.atTime,
      precise: val.precise,
    }
  }
}

export function deserializeValue(map: Record<string, unknown>): DslValue {
  const type = map.type as string
  if (!type) throw new Error("Missing 'type' field in serialized DslValue")
  const value = map.value

  switch (type) {
    case 'undefined': return UNDEFINED
    case 'number': return numberVal(value as number)
    case 'string': return stringVal(value as string)
    case 'boolean': return booleanVal(value as boolean)
    case 'date': return dateVal(value as string)
    case 'time': return timeVal(value as string)
    case 'datetime': return dateTimeVal(value as string)
    case 'pattern': return patternVal([], new RegExp(value as string))
    case 'note': return deserializeNoteVal(value as Record<string, unknown>)
    case 'list': return listVal((value as Record<string, unknown>[]).map(deserializeValue))
    case 'view': return deserializeViewVal(value as Record<string, unknown>)
    case 'button': return deserializeButtonVal(value as Record<string, unknown>)
    case 'schedule': return deserializeScheduleVal(value as Record<string, unknown>)
    default: throw new Error(`Unknown DslValue type: ${type}`)
  }
}

function deserializeNoteVal(map: Record<string, unknown>): NoteVal {
  // Timestamp imported at module level
  const note: Note = {
    id: (map.id as string) ?? '',
    userId: (map.userId as string) ?? '',
    parentNoteId: null,
    path: (map.path as string) ?? '',
    content: (map.content as string) ?? '',
    createdAt: map.createdAt != null ? Timestamp.fromMillis(map.createdAt as number) : null,
    updatedAt: map.updatedAt != null ? Timestamp.fromMillis(map.updatedAt as number) : null,
    lastAccessedAt: map.lastAccessedAt != null ? Timestamp.fromMillis(map.lastAccessedAt as number) : null,
    tags: [],
    containedNotes: [],
    state: null,
    rootNoteId: null,
    showCompleted: true,
  }
  return noteVal(note)
}

function deserializeViewVal(map: Record<string, unknown>): ViewVal {
  // Timestamp imported at module level
  const notesData = (map.notes as Record<string, unknown>[]) ?? []
  const notes = notesData.map((noteMap) => ({
    id: (noteMap.id as string) ?? '',
    userId: (noteMap.userId as string) ?? '',
    parentNoteId: null,
    path: (noteMap.path as string) ?? '',
    content: (noteMap.content as string) ?? '',
    createdAt: noteMap.createdAt != null ? Timestamp.fromMillis(noteMap.createdAt as number) : null,
    updatedAt: noteMap.updatedAt != null ? Timestamp.fromMillis(noteMap.updatedAt as number) : null,
    lastAccessedAt: noteMap.lastAccessedAt != null ? Timestamp.fromMillis(noteMap.lastAccessedAt as number) : null,
    tags: [] as string[],
    containedNotes: [] as string[],
    state: null,
    rootNoteId: null,
    showCompleted: true,
  }))
  const renderedContents = (map.renderedContents as string[]) ?? null
  return viewVal(notes, renderedContents)
}

function deserializeButtonVal(map: Record<string, unknown>): ButtonVal {
  const label = (map.label as string) ?? 'Button'
  const placeholder = lambdaVal([], { kind: 'StringLiteral', value: 'Placeholder', position: 0 }, null)
  return buttonVal(label, placeholder)
}

function deserializeScheduleVal(map: Record<string, unknown>): ScheduleVal {
  const frequencyId = (map.frequency as string) ?? 'daily'
  const frequency = scheduleFrequencyFromId(frequencyId) ?? ScheduleFrequency.DAILY
  const atTime = (map.atTime as string) ?? null
  const precise = (map.precise as boolean) ?? false
  const placeholder = lambdaVal([], { kind: 'StringLiteral', value: 'Placeholder', position: 0 }, null)
  return scheduleVal(frequency, placeholder, atTime, precise)
}

// ---- Pattern compilation ----

export function compilePattern(elements: PatternElement[]): PatternVal {
  const regexPattern = elements.map(elementToRegex).join('')
  return patternVal(elements, new RegExp(`^${regexPattern}$`))
}

function elementToRegex(element: PatternElement): string {
  switch (element.kind) {
    case 'CharClass': return charClassToRegex(element.type)
    case 'PatternLiteral': return escapeRegex(element.value)
    case 'Quantified': return elementToRegex(element.element) + quantifierToRegex(element.quantifier)
    default: throw new Error(`Unknown pattern element kind`)
  }
}

function charClassToRegex(type: CharClassType): string {
  switch (type) {
    case 'DIGIT': return '\\d'
    case 'LETTER': return '[a-zA-Z]'
    case 'SPACE': return '\\s'
    case 'PUNCT': return '[\\p{P}]'
    case 'ANY': return '.'
    default: throw new Error(`Unknown char class type: ${type}`)
  }
}

function quantifierToRegex(q: Quantifier): string {
  switch (q.kind) {
    case 'Exact': return `{${q.n}}`
    case 'Any': return '*'
    case 'Range': return q.max == null ? `{${q.min},}` : `{${q.min},${q.max}}`
    default: throw new Error(`Unknown quantifier kind`)
  }
}

function escapeRegex(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
