/**
 * Bucketed counters for Firestore reads and writes, segmented by operation
 * name and event type. Used to find hotspots — call
 * `firestoreUsage.recordRead(...)` / `recordWrite(...)` at every Firestore
 * call site, then tap the Usage button on NoteListScreen to view the report.
 *
 * Data model:
 * - Hourly buckets: last 24 hours, one bucket per local-clock hour.
 * - Daily buckets: last 7 days, one bucket per local-clock day.
 * - Each bucket holds (operation, type) → {ops, docs} maps for both reads
 *   and writes.
 *
 * Persistence: buckets are stored in localStorage and survive reloads, so
 * the user can tap "Usage" any time to see hotspots without having to leave
 * the app running. Writes are throttled to once per 30s to avoid hot-path
 * cost; the latest unsaved increment is lost on a hard tab-close (acceptable
 * — this is diagnostic data, not user data).
 */

export type ReadType =
  | 'doc.get'
  | 'getDocs'
  | 'listener.initial-fresh'
  | 'listener.initial-cached'
  | 'listener.update-fresh'
  | 'listener.update-cached'
  | 'listener.local-echo'

export type WriteType =
  | 'set'
  | 'update'
  | 'delete'
  | 'batch.commit'
  | 'transaction'

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
const MAX_DAILY = 7
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
    const lines: string[] = ['=== Firestore Usage Report ===']
    const now = Date.now()
    const lastHour = this.hourly[this.hourly.length - 1]
    if (lastHour) {
      lines.push('')
      lines.push(`Current hour (${formatHour(lastHour.start)}):`)
      lines.push(formatBucket(lastHour))
    }
    const last24 = this.summarize(this.hourly.filter((b) => b.start >= now - 24 * HOUR_MS))
    lines.push('')
    lines.push('Last 24 hours:')
    lines.push(formatBucket(last24))
    const last7 = this.summarize(this.daily.filter((b) => b.start >= now - 7 * DAY_MS))
    lines.push('')
    lines.push('Last 7 days:')
    lines.push(formatBucket(last7))
    lines.push('')
    lines.push('=== End ===')
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

function formatHour(t: number): string {
  const d = new Date(t)
  return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function formatBucket(b: Bucket): string {
  const lines: string[] = []
  let totalReadOps = 0, totalReadDocs = 0, totalWriteOps = 0, totalWriteDocs = 0
  const readEntries = Object.entries(b.reads).sort((a, b) => b[1].docs - a[1].docs)
  const writeEntries = Object.entries(b.writes).sort((a, b) => b[1].docs - a[1].docs)
  lines.push('  Reads:')
  for (const [key, c] of readEntries) {
    const [op, type] = key.split('|')
    lines.push(`    ${(op ?? '').padEnd(34)} ${(type ?? '').padEnd(28)} ${String(c.ops).padStart(5)} ops × ${String(c.docs).padStart(6)} docs`)
    totalReadOps += c.ops
    totalReadDocs += c.docs
  }
  lines.push(`    TOTAL: ${totalReadOps} ops × ${totalReadDocs} docs`)
  lines.push('  Writes:')
  for (const [key, c] of writeEntries) {
    const [op, type] = key.split('|')
    lines.push(`    ${(op ?? '').padEnd(34)} ${(type ?? '').padEnd(28)} ${String(c.ops).padStart(5)} ops × ${String(c.docs).padStart(6)} docs`)
    totalWriteOps += c.ops
    totalWriteDocs += c.docs
  }
  lines.push(`    TOTAL: ${totalWriteOps} ops × ${totalWriteDocs} docs`)
  if (totalWriteDocs > 0) {
    lines.push(`    R:W RATIO: ${(totalReadDocs / totalWriteDocs).toFixed(1)}x`)
  }
  return lines.join('\n')
}

export const firestoreUsage = new FirestoreUsage()

if (typeof window !== 'undefined') {
  ;(window as unknown as { firestoreUsage: FirestoreUsage }).firestoreUsage = firestoreUsage
}
