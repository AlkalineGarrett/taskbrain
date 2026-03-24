import type { Note } from '@/data/Note'
import type { CachedDirectiveResult } from './CachedDirectiveResult'
import * as StalenessChecker from './StalenessChecker'

/**
 * Simple LRU cache using Map insertion order.
 */
class LruCache<V> {
  private readonly cache = new Map<string, V>()

  constructor(private readonly maxSize: number) {}

  get(key: string): V | undefined {
    const value = this.cache.get(key)
    if (value !== undefined) {
      // Move to end (most recently used)
      this.cache.delete(key)
      this.cache.set(key, value)
    }
    return value
  }

  put(key: string, value: V): void {
    this.cache.delete(key)
    if (this.cache.size >= this.maxSize) {
      // Evict oldest (first entry)
      const oldest = this.cache.keys().next().value
      if (oldest !== undefined) this.cache.delete(oldest)
    }
    this.cache.set(key, value)
  }

  remove(key: string): void {
    this.cache.delete(key)
  }

  clear(): void {
    this.cache.clear()
  }

  get size(): number {
    return this.cache.size
  }
}

const DEFAULT_GLOBAL_CACHE_SIZE = 1000
const DEFAULT_PER_NOTE_CACHE_SIZE = 100
const DEFAULT_MAX_NOTES = 500

/**
 * Global cache for self-less directives.
 */
class GlobalDirectiveCache {
  private readonly cache: LruCache<CachedDirectiveResult>

  constructor(maxSize: number = DEFAULT_GLOBAL_CACHE_SIZE) {
    this.cache = new LruCache(maxSize)
  }

  get(key: string): CachedDirectiveResult | undefined { return this.cache.get(key) }
  put(key: string, result: CachedDirectiveResult): void { this.cache.put(key, result) }
  remove(key: string): void { this.cache.remove(key) }
  clear(): void { this.cache.clear() }
}

/**
 * Per-note cache for self-referencing directives.
 */
class PerNoteDirectiveCache {
  private readonly noteCaches: LruCache<LruCache<CachedDirectiveResult>>

  constructor(
    private readonly maxEntriesPerNote: number = DEFAULT_PER_NOTE_CACHE_SIZE,
    maxTotalNotes: number = DEFAULT_MAX_NOTES,
  ) {
    this.noteCaches = new LruCache(maxTotalNotes)
  }

  get(noteId: string, directiveKey: string): CachedDirectiveResult | undefined {
    return this.noteCaches.get(noteId)?.get(directiveKey)
  }

  put(noteId: string, directiveKey: string, result: CachedDirectiveResult): void {
    let noteCache = this.noteCaches.get(noteId)
    if (!noteCache) {
      noteCache = new LruCache(this.maxEntriesPerNote)
      this.noteCaches.put(noteId, noteCache)
    }
    noteCache.put(directiveKey, result)
  }

  clearNote(noteId: string): void { this.noteCaches.remove(noteId) }
  clear(): void { this.noteCaches.clear() }
}

/**
 * Central manager for all directive caches.
 */
export class DirectiveCacheManager {
  private readonly globalCache: GlobalDirectiveCache
  private readonly perNoteCache: PerNoteDirectiveCache

  constructor() {
    this.globalCache = new GlobalDirectiveCache()
    this.perNoteCache = new PerNoteDirectiveCache()
  }

  get(directiveKey: string, noteId: string, _usesSelfAccess: boolean): CachedDirectiveResult | undefined {
    // Always use per-note cache: directive results are scoped to the parent note
    // that contains them, even for directives that don't reference the current note.
    return this.perNoteCache.get(noteId, directiveKey)
  }

  getIfValid(
    directiveKey: string,
    noteId: string,
    usesSelfAccess: boolean,
    currentNotes: Note[],
    currentNote: Note | null,
  ): CachedDirectiveResult | undefined {
    const cached = this.get(directiveKey, noteId, usesSelfAccess)
    if (!cached) return undefined
    if (StalenessChecker.shouldReExecute(cached, currentNotes, currentNote)) return undefined
    return cached
  }

  put(directiveKey: string, noteId: string, _usesSelfAccess: boolean, result: CachedDirectiveResult): void {
    this.perNoteCache.put(noteId, directiveKey, result)
  }

  clearNote(noteId: string): void {
    this.perNoteCache.clearNote(noteId)
  }

  clearAll(): void {
    this.globalCache.clear()
    this.perNoteCache.clear()
  }
}
