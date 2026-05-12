/**
 * Bucketed counters for Firestore reads and writes, segmented by operation
 * name and event type. Mirrors Android's `FirestoreUsage.kt` so reports look
 * the same on both platforms — keep the data model and formatters in sync.
 *
 * Persistence: buckets are stored in localStorage. Writes are throttled to
 * once per 30s to keep the per-call hot path cheap; the latest unsaved
 * increment is lost on a hard tab-close (acceptable — diagnostic only).
 */

export type ReadType =
  | 'DOC_GET'
  | 'GET_DOCS'
  | 'LISTENER_INITIAL_FRESH'
  | 'LISTENER_UPDATE_FRESH'
  | 'LISTENER_INITIAL_CACHED'
  | 'LISTENER_UPDATE_CACHED'
  | 'LISTENER_LOCAL_ECHO'
  // Delta pull: bills exactly the docs returned (changed since lastSync).
  | 'PULL_DELTA'
  // Full repair pull triggered by count() mismatch — drops the watermark and
  // re-reads every doc.
  | 'PULL_FULL_REPAIR'
  // count() aggregation query used by foreground detection (~1 read).
  | 'COUNT_AGGREGATION'
  // Hydration of rawNotes from Firestore's IndexedDB cache on attach. $0.
  | 'HYDRATE_CACHED'

export type WriteType =
  | 'SET'
  | 'UPDATE'
  | 'DELETE'
  | 'BATCH_COMMIT'
  | 'TRANSACTION'

const LOCAL_ONLY_READ_TYPES = new Set<string>([
  'LISTENER_INITIAL_CACHED',
  'LISTENER_UPDATE_CACHED',
  'LISTENER_LOCAL_ECHO',
  'HYDRATE_CACHED',
])

interface Counter {
  ops: number
  docs: number
}

interface Bucket {
  start: number
  reads: Record<string, Counter>
  writes: Record<string, Counter>
}

const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS
const MAX_HOURLY = 24
// Match the Firebase Console's monthly billing window so the report is
// directly comparable to the per-project usage shown in the console.
const MAX_DAILY = 30
const PERSIST_DEBOUNCE_MS = 30_000

const STORAGE_KEY_HOURLY = 'firestoreUsage:hourly'
const STORAGE_KEY_DAILY = 'firestoreUsage:daily'

class FirestoreUsage {
  private hourly: Bucket[] = []
  private daily: Bucket[] = []
  private persistTimer: ReturnType<typeof setTimeout> | null = null

  constructor() {
    this.load()
  }

  recordRead(operation: string, type: ReadType, docCount = 1): void {
    this.record(true, operation, type, docCount)
  }

  recordWrite(operation: string, type: WriteType, docCount = 1): void {
    this.record(false, operation, type, docCount)
  }

  private record(isRead: boolean, operation: string, type: string, docCount: number): void {
    const now = Date.now()
    const hour = this.currentBucket(this.hourly, hourStart(now), MAX_HOURLY)
    const day = this.currentBucket(this.daily, dayStart(now), MAX_DAILY)
    const key = `${operation}|${type}`
    incrementCounter(isRead ? hour.reads : hour.writes, key, docCount)
    incrementCounter(isRead ? day.reads : day.writes, key, docCount)
    this.schedulePersist()
  }

  private currentBucket(buckets: Bucket[], bucketStart: number, max: number): Bucket {
    const last = buckets[buckets.length - 1]
    if (last && last.start === bucketStart) return last
    const next: Bucket = { start: bucketStart, reads: {}, writes: {} }
    buckets.push(next)
    while (buckets.length > max) buckets.shift()
    return next
  }

  getReport(): string {
    const now = Date.now()
    const current = this.hourly[this.hourly.length - 1] ?? null
    const windows: [string, Bucket][] = [
      ['Last 24 hours', this.summarize(this.hourly.filter((b) => b.start >= now - 24 * HOUR_MS))],
      ['Last 7 days', this.summarize(this.daily.filter((b) => b.start >= now - 7 * DAY_MS))],
      ['Last 30 days (compare to Firebase console)', this.summarize(this.daily.filter((b) => b.start >= now - 30 * DAY_MS))],
    ]
    const lines: string[] = ['=== Firestore Usage Report ===']
    appendTimeSeries(lines, '== Billed ==', current, windows, formatBilledBucket)
    appendTimeSeries(lines, '== Local-only ==', current, windows, formatLocalBucket)
    lines.push('', '', '=== End ===')
    return lines.join('\n')
  }

  private summarize(buckets: Bucket[]): Bucket {
    const merged: Bucket = { start: 0, reads: {}, writes: {} }
    for (const b of buckets) {
      for (const [k, c] of Object.entries(b.reads)) accumulate(merged.reads, k, c)
      for (const [k, c] of Object.entries(b.writes)) accumulate(merged.writes, k, c)
    }
    return merged
  }

  reset(): void {
    this.hourly = []
    this.daily = []
    this.persist()
  }

  private load(): void {
    if (typeof localStorage === 'undefined') return
    try {
      const h = localStorage.getItem(STORAGE_KEY_HOURLY)
      const d = localStorage.getItem(STORAGE_KEY_DAILY)
      if (h) this.hourly = JSON.parse(h) as Bucket[]
      if (d) this.daily = JSON.parse(d) as Bucket[]
    } catch (e) {
      console.warn('FirestoreUsage: failed to load buckets from storage', e)
    }
  }

  private schedulePersist(): void {
    if (this.persistTimer != null) return
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null
      this.persist()
    }, PERSIST_DEBOUNCE_MS)
  }

  private persist(): void {
    if (typeof localStorage === 'undefined') return
    try {
      localStorage.setItem(STORAGE_KEY_HOURLY, JSON.stringify(this.hourly))
      localStorage.setItem(STORAGE_KEY_DAILY, JSON.stringify(this.daily))
    } catch (e) {
      console.warn('FirestoreUsage: failed to persist buckets', e)
    }
  }
}

function hourStart(t: number): number {
  const d = new Date(t)
  d.setMinutes(0, 0, 0)
  return d.getTime()
}

function dayStart(t: number): number {
  const d = new Date(t)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

function incrementCounter(map: Record<string, Counter>, key: string, docCount: number): void {
  const c = map[key]
  if (c) {
    c.ops += 1
    c.docs += docCount
  } else {
    map[key] = { ops: 1, docs: docCount }
  }
}

function accumulate(map: Record<string, Counter>, key: string, c: Counter): void {
  const existing = map[key]
  if (existing) {
    existing.ops += c.ops
    existing.docs += c.docs
  } else {
    map[key] = { ops: c.ops, docs: c.docs }
  }
}

const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

function formatHour(t: number): string {
  const d = new Date(t)
  const month = MONTH_ABBR[d.getMonth()]
  const day = d.getDate()
  const hour = String(d.getHours()).padStart(2, '0')
  return `${month} ${day} ${hour}:00`
}

function pluralize(noun: string, count: number): string {
  return count === 1 ? noun : `${noun}s`
}

function formatDocsOps(docs: number, ops: number): string {
  return `${docs} ${pluralize('doc', docs)} over ${ops} ${pluralize('op', ops)}`
}

function formatEntry(key: string, c: Counter): string {
  const sep = key.indexOf('|')
  const op = sep === -1 ? key : key.substring(0, sep)
  const rawType = sep === -1 ? '' : key.substring(sep + 1)
  const type = rawType.startsWith('LISTENER_') ? rawType.substring('LISTENER_'.length) : rawType
  return `${op} ${type}: ${formatDocsOps(c.docs, c.ops)}`
}

function isBilled(key: string): boolean {
  // Unknown types (including older lowercase keys still in localStorage)
  // default to billed so they surface in the report rather than disappearing.
  const type = key.substring(key.indexOf('|') + 1)
  return !LOCAL_ONLY_READ_TYPES.has(type)
}

function appendTimeSeries(
  lines: string[],
  sectionLabel: string,
  current: Bucket | null,
  windows: [string, Bucket][],
  formatter: (b: Bucket) => string,
): void {
  lines.push('', '', sectionLabel)
  if (current) {
    lines.push('', `Current hour (${formatHour(current.start)}):`, formatter(current))
  }
  for (const [label, bucket] of windows) {
    lines.push('', '', `${label}:`, formatter(bucket))
  }
}

function formatBilledBucket(b: Bucket): string {
  const billedReads = Object.entries(b.reads).filter(([key]) => isBilled(key))
  const totalReadOps = billedReads.reduce((sum, [, c]) => sum + c.ops, 0)
  const totalReadDocs = billedReads.reduce((sum, [, c]) => sum + c.docs, 0)
  const writeEntries = Object.entries(b.writes)
  const totalWriteOps = writeEntries.reduce((sum, [, c]) => sum + c.ops, 0)
  const totalWriteDocs = writeEntries.reduce((sum, [, c]) => sum + c.docs, 0)
  const ratioSuffix = totalWriteDocs > 0
    ? ` (R:W ${(totalReadDocs / totalWriteDocs).toFixed(1)}x)`
    : ''
  const lines: string[] = []
  lines.push(`  Reads: ${formatDocsOps(totalReadDocs, totalReadOps)}`)
  for (const [key, c] of billedReads.sort((a, b) => b[1].docs - a[1].docs)) {
    lines.push(`    ${formatEntry(key, c)}`)
  }
  lines.push(`  Writes: ${formatDocsOps(totalWriteDocs, totalWriteOps)}${ratioSuffix}`)
  for (const [key, c] of writeEntries.sort((a, b) => b[1].docs - a[1].docs)) {
    lines.push(`    ${formatEntry(key, c)}`)
  }
  return lines.join('\n')
}

function formatLocalBucket(b: Bucket): string {
  const localReads = Object.entries(b.reads).filter(([key]) => !isBilled(key))
  const totalDocs = localReads.reduce((sum, [, c]) => sum + c.docs, 0)
  const totalOps = localReads.reduce((sum, [, c]) => sum + c.ops, 0)
  const lines: string[] = []
  lines.push(`  Reads: ${formatDocsOps(totalDocs, totalOps)}`)
  for (const [key, c] of localReads.sort((a, b) => b[1].docs - a[1].docs)) {
    lines.push(`    ${formatEntry(key, c)}`)
  }
  return lines.join('\n')
}

export const firestoreUsage = new FirestoreUsage()

if (typeof window !== 'undefined') {
  ;(window as unknown as { firestoreUsage: FirestoreUsage }).firestoreUsage = firestoreUsage
}
