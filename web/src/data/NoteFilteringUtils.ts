import type { Note } from './Note'
import type { NoteStats } from './NoteStats'

export type NoteSortMode = 'recent' | 'frequent' | 'consistent'

const VIEW_HALF_LIFE_MS = 14 * 24 * 60 * 60 * 1000
const MS_PER_DAY = 24 * 60 * 60 * 1000

function isTopLevel(note: Note): boolean {
  return note.parentNoteId == null
}

function isNotDeleted(note: Note): boolean {
  return note.state !== 'deleted'
}

function timestampMillis(ts: { toMillis(): number } | null | undefined): number {
  return ts?.toMillis() ?? 0
}

function parseLocalDateMs(yyyyMmDd: string): number | null {
  // Treat the YYYY-MM-DD as midnight in the device's local timezone.
  const parts = yyyyMmDd.split('-')
  if (parts.length !== 3) return null
  const y = Number(parts[0])
  const m = Number(parts[1])
  const d = Number(parts[2])
  if (!Number.isFinite(y) || !Number.isFinite(m) || !Number.isFinite(d)) return null
  return new Date(y, m - 1, d).getTime()
}

function decayedScore(stats: NoteStats | undefined, nowMs: number): number {
  if (!stats) return 0
  let sum = 0
  for (const day of Object.keys(stats.viewedDays)) {
    const dayMs = parseLocalDateMs(day)
    if (dayMs == null) continue
    const delta = Math.max(0, nowMs - dayMs)
    sum += Math.exp(-delta / VIEW_HALF_LIFE_MS)
  }
  return sum
}

function consistencyScore(stats: NoteStats | undefined, nowMs: number): number {
  if (!stats) return 0
  const days = Object.keys(stats.viewedDays)
  if (days.length === 0) return 0
  // viewedDays uses ISO YYYY-MM-DD; lexicographic min == earliest date.
  const earliest = days.reduce((a, b) => (a < b ? a : b))
  const firstMs = parseLocalDateMs(earliest)
  if (firstMs == null) return 0
  const span = Math.max(0, nowMs - firstMs) / MS_PER_DAY
  return days.length * Math.log(1 + span)
}

export function filterTopLevelNotes(notes: Note[]): Note[] {
  return notes.filter((n) => isTopLevel(n) && isNotDeleted(n))
}

export function sortByUpdatedAtDescending(notes: Note[]): Note[] {
  return [...notes].sort(
    (a, b) => timestampMillis(b.updatedAt) - timestampMillis(a.updatedAt),
  )
}

export function sortByLastAccessedAtDescending(notes: Note[], stats: Map<string, NoteStats>): Note[] {
  return [...notes].sort(
    (a, b) =>
      timestampMillis(stats.get(b.id)?.lastAccessedAt ?? b.updatedAt) -
      timestampMillis(stats.get(a.id)?.lastAccessedAt ?? a.updatedAt),
  )
}

export function filterAndSortNotes(notes: Note[]): Note[] {
  return sortByUpdatedAtDescending(filterTopLevelNotes(notes))
}

export function sortByDecayedScoreDescending(notes: Note[], stats: Map<string, NoteStats>, nowMs: number): Note[] {
  return sortByPrecomputedScore(notes, (n) => decayedScore(stats.get(n.id), nowMs))
}

export function sortByConsistencyDescending(notes: Note[], stats: Map<string, NoteStats>, nowMs: number): Note[] {
  return sortByPrecomputedScore(notes, (n) => consistencyScore(stats.get(n.id), nowMs))
}

function sortByPrecomputedScore(notes: Note[], score: (n: Note) => number): Note[] {
  return notes
    .map((n) => ({ note: n, score: score(n) }))
    .sort((a, b) => b.score - a.score)
    .map((e) => e.note)
}

export function filterAndSortNotesByMode(
  notes: Note[],
  stats: Map<string, NoteStats>,
  mode: NoteSortMode,
  nowMs: number,
): Note[] {
  const filtered = filterTopLevelNotes(notes)
  switch (mode) {
    case 'recent':
      return sortByLastAccessedAtDescending(filtered, stats)
    case 'frequent':
      return sortByDecayedScoreDescending(filtered, stats, nowMs)
    case 'consistent':
      return sortByConsistencyDescending(filtered, stats, nowMs)
  }
}

export function filterDeletedNotes(notes: Note[]): Note[] {
  return notes.filter((n) => isTopLevel(n) && n.state === 'deleted')
}

export function filterAndSortDeletedNotes(notes: Note[]): Note[] {
  return sortByUpdatedAtDescending(filterDeletedNotes(notes))
}
