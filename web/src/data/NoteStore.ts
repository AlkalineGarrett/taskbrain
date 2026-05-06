import {
  collection,
  query,
  where,
  onSnapshot,
  type Firestore,
  type Unsubscribe,
} from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import { noteFromFirestore, type Note, type NoteLine } from './Note'
import { isLive } from './NoteState'
import { firestoreUsage } from './FirestoreUsage'
import {
  rebuildAllNotes,
  rebuildAffectedNotes,
  reconstructNoteLines,
  indexChildrenByParent,
  type RebuildResult,
} from './NoteReconstruction'

/** Cap for waiting on the listener to surface a specific note. Mirrors the
 *  Android NoteStore constant; also reused by NoteRepository.loadNoteWithChildren. */
const NOTE_STORE_AWAIT_MS = 1500

/**
 * Thrown when a code path that depends on a fully-loaded NoteStore runs before
 * the live listener has received its first snapshot. Save/delete operations
 * read the descendant set from NoteStore — running them before the snapshot
 * has arrived would silently miss soft-deleting removed descendants, leaving
 * orphaned documents in Firestore.
 *
 * The Error superclass auto-captures `this.stack`, so `console.error(e)` prints
 * the full call chain.
 */
export class NoteStoreNotLoadedError extends Error {
  readonly operation: string
  readonly noteId: string
  constructor(operation: string, noteId: string) {
    super(
      `[NoteStore not loaded] ${operation}(noteId=${noteId}) ran before the ` +
      `live note listener received its first snapshot. ` +
      `Save/delete operations read descendants from the in-memory NoteStore; ` +
      `running them now would silently miss soft-deleting removed descendants, ` +
      `leaving orphaned documents. Try again after the note list has loaded.`
    )
    this.name = 'NoteStoreNotLoadedError'
    this.operation = operation
    this.noteId = noteId
  }
}

/**
 * Singleton reactive store for all notes with reconstructed content.
 * Single source of truth for directive execution context.
 *
 * Uses a Firestore collection listener for real-time incremental updates.
 * React components subscribe via useSyncExternalStore (see useNoteStore.ts).
 *
 * Internal state:
 * - rawNotes: every Firestore doc (including descendants), indexed by ID
 * - reconstructedNotes: top-level notes with content rebuilt from their tree
 *   (what consumers see via getSnapshot())
 */
export class NoteStore {
  /** All notes from Firestore, indexed by ID (including descendants). */
  private rawNotes = new Map<string, Note>()
  /** Top-level notes with reconstructed multi-line content. */
  private reconstructedNotes: Note[] = []
  private loaded = false
  private loadResolve: (() => void) | null = null
  private loadPromise: Promise<void> | null = null
  private unsubscribe: Unsubscribe | null = null
  private listeners = new Set<() => void>()
  private changeListeners = new Set<(changedIds: Set<string>) => void>()
  private errorListeners = new Set<() => void>()
  private needsFixListeners = new Set<() => void>()
  private pendingSaves = new Map<string, Promise<unknown>>()

  // Tail of the global save queue. Every save is appended via `enqueueSave`,
  // so concurrent saves of different notes (e.g., A's autosave on tab switch
  // racing B's autosave after a cross-note paste) run sequentially rather
  // than overlapping on shared state — see `enqueueSave` for rationale.
  private saveQueueTail: Promise<unknown> = Promise.resolve()
  private _error: string | null = null
  /**
   * Top-level note IDs whose reconstruction had to auto-heal a discrepancy
   * (orphan ref dropped or stray child appended). The editor shows the healed
   * content; saving the note writes the fix back to Firestore and removes it
   * from this set on the next snapshot (or via `markNoteFixed`).
   */
  private _notesNeedingFix = new Set<string>()

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = (): Note[] => {
    return this.reconstructedNotes
  }

  /** Subscribe to changed note IDs on each incremental snapshot. */
  subscribeChangedNoteIds = (listener: (changedIds: Set<string>) => void): (() => void) => {
    this.changeListeners.add(listener)
    return () => this.changeListeners.delete(listener)
  }

  subscribeError = (listener: () => void): (() => void) => {
    this.errorListeners.add(listener)
    return () => this.errorListeners.delete(listener)
  }

  getErrorSnapshot = (): string | null => {
    return this._error
  }

  clearError = (): void => {
    if (this._error !== null) {
      this._error = null
      this.emitErrorChange()
    }
  }

  /**
   * Raise a user-visible warning from anywhere in the data layer. Routed to
   * the same save-warning dialog the reconstruction errors flowed through,
   * so the UI surface is consistent.
   */
  raiseWarning = (message: string): void => {
    this._error = message
    this.emitErrorChange()
  }

  subscribeNotesNeedingFix = (listener: () => void): (() => void) => {
    this.needsFixListeners.add(listener)
    return () => this.needsFixListeners.delete(listener)
  }

  getNotesNeedingFixSnapshot = (): Set<string> => {
    return this._notesNeedingFix
  }

  /** Clear a note from needsFix (e.g., after a successful save). */
  markNoteFixed = (noteId: string): void => {
    if (!this._notesNeedingFix.has(noteId)) return
    const next = new Set(this._notesNeedingFix)
    next.delete(noteId)
    this._notesNeedingFix = next
    this.emitNeedsFixChange()
  }

  /**
   * Start the Firestore collection listener. Idempotent — calling multiple
   * times is safe (subsequent calls are no-ops if already started).
   */
  start(db: Firestore, auth: Auth): void {
    if (this.unsubscribe) return
    const userId = auth.currentUser?.uid
    if (!userId) return
    this.surfacePersistentCacheError()

    // Create promise for ensureLoaded() callers to await
    if (!this.loadPromise) {
      this.loadPromise = new Promise<void>((resolve) => {
        this.loadResolve = resolve
      })
    }

    const notesRef = collection(db, 'notes')
    const q = query(notesRef, where('userId', '==', userId))

    this.unsubscribe = onSnapshot(q, (snapshot) => {
      // Skip local-echo snapshots, but always process the first snapshot
      // so ensureLoaded() can resolve even if there are pending writes.
      if (this.loaded && snapshot.metadata.hasPendingWrites) {
        firestoreUsage.recordRead('NoteStore.listener', 'LISTENER_LOCAL_ECHO', snapshot.docChanges().length)
        return
      }

      const isFirstSnapshot = !this.loaded

      if (isFirstSnapshot) {
        firestoreUsage.recordRead(
          'NoteStore.listener',
          snapshot.metadata.fromCache ? 'LISTENER_INITIAL_CACHED' : 'LISTENER_INITIAL_FRESH',
          snapshot.docs.length,
        )
      } else {
        firestoreUsage.recordRead(
          'NoteStore.listener',
          snapshot.metadata.fromCache ? 'LISTENER_UPDATE_CACHED' : 'LISTENER_UPDATE_FRESH',
          snapshot.docChanges().length,
        )
      }

      if (isFirstSnapshot) {
        // Initial load — process all docs at once
        this.rawNotes.clear()
        for (const doc of snapshot.docs) {
          const note = noteFromFirestore(doc.id, doc.data())
          this.rawNotes.set(doc.id, note)
        }
        this.rebuildAll()
        this.loaded = true
        this.loadResolve?.()
        this.loadResolve = null
      } else {
        // Incremental update — process only changed docs
        const affectedRoots = new Set<string>()

        for (const change of snapshot.docChanges()) {
          if (change.doc.metadata.hasPendingWrites) continue

          const note = noteFromFirestore(change.doc.id, change.doc.data())
          const rootId = note.rootNoteId ?? note.id

          if (change.type === 'removed') {
            this.rawNotes.delete(change.doc.id)
          } else {
            this.rawNotes.set(change.doc.id, note)
          }
          affectedRoots.add(rootId)
        }

        if (affectedRoots.size > 0) {
          this.rebuildAffected(affectedRoots)
          this.emitChangedNoteIds(affectedRoots)
        }
      }
    }, (error) => {
      console.error('NoteStore snapshot listener error:', error)
      this._error = error instanceof Error ? error.message : 'Note sync failed'
      this.emitErrorChange()
    })
  }

  private persistentCacheWarningSurfaced = false
  private surfacePersistentCacheError(): void {
    if (this.persistentCacheWarningSurfaced) return
    this.persistentCacheWarningSurfaced = true
    // Lazy import: pulling @/firebase/config at module init would force
    // every test that imports NoteStore to mock initializeFirestore.
    void import('@/firebase/config').then(({ persistentCacheError }) => {
      if (!persistentCacheError) return
      const stack = new Error().stack ?? '(unavailable)'
      console.error(
        `[Firestore persistence] persistent cache unavailable — every cold ` +
        `start will re-fetch the full notes collection from Firestore.\n` +
        `Reason: ${persistentCacheError}\n` +
        `Stack:\n${stack}`,
      )
      this.raiseWarning(
        `Local note cache is unavailable in this browser; every reload will ` +
        `re-download all notes. Use a different browser or disable private ` +
        `browsing if you want faster startup. (${persistentCacheError})`,
      )
    })
  }

  /**
   * Returns a promise that resolves after the first snapshot arrives.
   * Call after start() to wait for initial data.
   */
  async ensureLoaded(): Promise<void> {
    if (this.loaded) return
    if (this.loadPromise) return this.loadPromise
    // start() hasn't been called yet — create a pending promise
    this.loadPromise = new Promise<void>((resolve) => {
      this.loadResolve = resolve
    })
    return this.loadPromise
  }

  /** Stop listening and clear all data (e.g., on logout). */
  clear(): void {
    this.unsubscribe?.()
    this.unsubscribe = null
    this.rawNotes.clear()
    this.reconstructedNotes = []
    this.loaded = false
    this.loadPromise = null
    this.loadResolve = null
    this._error = null
    this._notesNeedingFix = new Set()
    this.emitChange()
    this.emitErrorChange()
    this.emitNeedsFixChange()
  }

  /** Optimistic update — replace a single reconstructed note by ID. */
  updateNote(noteId: string, updatedNote: Note): void {
    if (this.applyUpdate(noteId, updatedNote)) {
      this.emitChange()
    }
  }

  /**
   * Optimistic content-only update for save paths. Skips when the in-memory
   * content already matches so subscribers don't recompose for no-op writes.
   */
  updateContentIfChanged(noteId: string, content: string): void {
    const existing = this.getNoteById(noteId)
    if (!existing || existing.content === content) return
    this.updateNote(noteId, { ...existing, content })
  }

  /**
   * `containedNotes` snapshot for [rootNoteId] and every live descendant
   * under it, keyed by id. Editors capture this at edit-session start so the
   * 3-way merge in NoteRepository.planSave runs uniformly at every depth
   * (concurrent additions another client made under any descendant survive,
   * not just additions to the root). Returns an empty Map if the root
   * isn't loaded yet.
   */
  snapshotLocalBases(rootNoteId: string): Map<string, string[]> {
    const result = new Map<string, string[]>()
    const root = this.getRawNoteById(rootNoteId)
    if (root) result.set(rootNoteId, root.containedNotes.slice())
    for (const id of this.getDescendantIds(rootNoteId)) {
      const note = this.getRawNoteById(id)
      if (note) result.set(id, note.containedNotes.slice())
    }
    return result
  }

  /**
   * In-memory buffer of lines cut from any editor in this client session.
   * Phase 5 cross-note move identity preservation:
   *
   *   - Cut: editor calls [recordCut] for each cut line that has a real noteId.
   *   - Paste: editor calls [tryReclaim] per pasted text line; on hit, the
   *     line keeps its original noteId instead of getting a fresh sentinel.
   *   - Save: planSave excludes [pendingCutIds] from soft-delete; saveMultipleNotes
   *     appends a `state='cut-delete'` write for any unreclaimed entries so
   *     the line is parked rather than orphaned.
   *
   * Keyed by lineId; value is the line's content at cut time (the matcher
   * for reclaim). Buffer survives across saveAll commits — a cut now and a
   * paste in 5 minutes (after any number of auto-saves) still preserves
   * identity, because the cut-delete state on the doc is reclaimable.
   */
  private pendingCuts = new Map<string, string>()

  /** Add a cut line's id + content to the reclaim buffer. */
  recordCut(lineId: string, content: string): void {
    this.pendingCuts.set(lineId, content)
  }

  /**
   * Find a pendingCut with matching content; remove it from the buffer and
   * return its lineId. Returns `null` on miss (the paste then falls back to
   * sentinel allocation). Removal is one-shot so duplicate-content paste
   * doesn't double-claim a single cut line.
   *
   * Match key is `content` exactly. Two cut lines with identical content
   * resolve to one of the buffered ids non-deterministically (Map iteration
   * order = insertion order in JS, but callers shouldn't rely on it).
   */
  tryReclaim(content: string): string | null {
    for (const [lineId, c] of this.pendingCuts) {
      if (c === content) {
        this.pendingCuts.delete(lineId)
        return lineId
      }
    }
    return null
  }

  /** Snapshot of pending cuts for save planning. */
  getPendingCuts(): Map<string, string> {
    return new Map(this.pendingCuts)
  }

  /** Drop a single entry — used after the cut-delete write commits. */
  clearPendingCut(lineId: string): void {
    this.pendingCuts.delete(lineId)
  }

  /**
   * Update a note without notifying subscribers.
   * Use when the caller knows a re-render will happen for other reasons
   * (e.g., during unmount cleanup before a navigation) and doesn't want
   * to trigger a flash on the outgoing screen.
   * The new snapshot is visible via getSnapshot() on the next render.
   */
  updateNoteSilently(noteId: string, updatedNote: Note): void {
    this.applyUpdate(noteId, updatedNote)
  }

  private applyUpdate(noteId: string, updatedNote: Note): boolean {
    const index = this.reconstructedNotes.findIndex(n => n.id === noteId)
    if (index < 0) return false
    this.reconstructedNotes = [...this.reconstructedNotes]
    this.reconstructedNotes[index] = updatedNote
    return true
  }

  /** Add a new note to the reconstructed list. */
  addNote(note: Note): void {
    this.reconstructedNotes = [...this.reconstructedNotes, note]
    this.emitChange()
  }

  /** Remove a note from the reconstructed list by ID. */
  removeNote(noteId: string): void {
    const filtered = this.reconstructedNotes.filter(n => n.id !== noteId)
    if (filtered.length !== this.reconstructedNotes.length) {
      this.reconstructedNotes = filtered
      this.emitChange()
    }
  }

  getNoteById(noteId: string): Note | undefined {
    return this.reconstructedNotes.find(n => n.id === noteId)
  }

  /** Get a raw (unreconstructed) note by ID — includes notes filtered from the top-level list. */
  /** All live (non-deleted) descendants of [rootId] keyed by their parentNoteId. */
  getLiveDescendantsByParent(rootId: string): Map<string, Note[]> {
    const result = new Map<string, Note[]>()
    for (const note of this.rawNotes.values()) {
      if (note.rootNoteId !== rootId) continue
      if (!isLive(note.state)) continue
      const p = note.parentNoteId
      if (p == null) continue
      const list = result.get(p)
      if (list) list.push(note)
      else result.set(p, [note])
    }
    return result
  }

  getRawNoteById(noteId: string): Note | undefined {
    return this.rawNotes.get(noteId)
  }

  /** IDs of all non-deleted notes whose rootNoteId matches [noteId]. */
  getDescendantIds(noteId: string): Set<string> {
    const result = new Set<string>()
    for (const note of this.rawNotes.values()) {
      if (note.rootNoteId === noteId && isLive(note.state)) result.add(note.id)
    }
    return result
  }

  /** IDs of all descendants of [noteId] including soft-deleted ones. */
  getAllDescendantIds(noteId: string): Set<string> {
    const result = new Set<string>()
    for (const note of this.rawNotes.values()) {
      if (note.rootNoteId === noteId) result.add(note.id)
    }
    return result
  }

  /** Whether the first Firestore snapshot has arrived. */
  isLoaded(): boolean {
    return this.loaded
  }

  /** Returns per-line NoteLines for a note, using the same parentNoteId walk as the reconstructed snapshot. */
  getNoteLinesById(noteId: string): NoteLine[] | undefined {
    const note = this.rawNotes.get(noteId)
    if (!note) return undefined
    const childrenByParent = indexChildrenByParent(this.rawNotes)
    const [lines, fixed] = reconstructNoteLines(note, this.rawNotes, childrenByParent)
    // Keep the editor view in sync with rebuildAffected: if the shared walk
    // dropped a declared child (missing from rawNotes — typically a fresh
    // save whose descendant echo hasn't arrived) mark the note as needing a
    // fix so the save button flips to the warning state.
    if (fixed && !this._notesNeedingFix.has(noteId)) {
      const next = new Set(this._notesNeedingFix)
      next.add(noteId)
      this._notesNeedingFix = next
      this.emitNeedsFixChange()
    }
    return lines
  }

  /** Track an in-flight save so loaders can await it before reading from Firestore. */
  trackSave(noteId: string, promise: Promise<unknown>): void {
    this.pendingSaves.set(noteId, promise)
    void promise.finally(() => {
      if (this.pendingSaves.get(noteId) === promise) {
        this.pendingSaves.delete(noteId)
      }
    })
  }

  /** Await any pending save for a noteId. Call before loading from Firestore. */
  async awaitPendingSave(noteId: string): Promise<void> {
    const pending = this.pendingSaves.get(noteId)
    if (pending) await pending
  }

  /**
   * Resolve once [noteId] is loaded into rawNotes, or `false` after
   * [timeoutMs]. Editors call this at session start so the listener has
   * a chance to deliver the doc before we fall through to a Firestore
   * one-shot read.
   */
  async awaitNoteLoaded(noteId: string, timeoutMs: number = NOTE_STORE_AWAIT_MS): Promise<boolean> {
    if (this.getRawNoteById(noteId) != null) return true
    await this.awaitPendingSave(noteId)
    if (this.getRawNoteById(noteId) != null) return true
    return new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => {
        unsubscribe()
        resolve(false)
      }, timeoutMs)
      const unsubscribe = this.subscribeChangedNoteIds((changed) => {
        if (changed.has(noteId) && this.getRawNoteById(noteId) != null) {
          clearTimeout(timer)
          unsubscribe()
          resolve(true)
        }
      })
    })
  }

  /**
   * Run [operation] after every previously enqueued save has settled.
   *
   * Cross-note operations (cut/paste, move) leave shared docs in flight
   * between two trees. Two concurrent `saveNoteWithChildren` transactions
   * computing `existingDescendantIds` / `toDelete` / `assertNotContentDrop`
   * from the same NoteStore snapshot can clobber each other — e.g., A's
   * save soft-deletes the moved doc after B has already reparented it.
   * Serializing through this queue means each save reads NoteStore after
   * the prior save's transaction has committed (and Firestore's local
   * cache reflects it), so the second save sees the up-to-date tree.
   *
   * A failure in one operation does not block subsequent operations.
   */
  enqueueSave<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.saveQueueTail.catch(() => undefined).then(operation)
    this.saveQueueTail = next.catch(() => undefined)
    return next
  }

  // --- Internal reconstruction ---

  /** Rebuild all reconstructed notes from rawNotes. Used on initial load. */
  private rebuildAll(): void {
    const result = rebuildAllNotes(this.rawNotes)
    this.reconstructedNotes = result.notes
    this.emitChange()
    this.setNotesNeedingFix(result.notesNeedingFix)
  }

  /** Rebuild only the top-level notes whose trees were affected by a change. */
  private rebuildAffected(rootIds: Set<string>): void {
    const previous = this.reconstructedNotes
    const result = rebuildAffectedNotes(previous, rootIds, this.rawNotes)
    const defended = this.preservePartialReconstructions(previous, result, rootIds)
    if (defended.notes !== this.reconstructedNotes) {
      this.reconstructedNotes = defended.notes
      this.emitChange()
    }
    this.mergeNotesNeedingFix(rootIds, defended.notesNeedingFix)
  }

  /**
   * Guard against partial-snapshot windows where descendants haven't arrived
   * in rawNotes yet: if reconstruction would shrink a tree from many lines
   * to a near-empty single line, keep the previous reconstructed entry and
   * flag needsFix instead of surfacing empty content to the editor. Mirrors
   * NoteStore.preservePartialReconstructions on Android.
   */
  private preservePartialReconstructions(
    previous: Note[],
    result: RebuildResult,
    affectedRootIds: Set<string>,
  ): RebuildResult {
    if (result.notes === previous) return result

    let defendedNotes: Note[] | null = null
    const needsFix = new Set(result.notesNeedingFix)

    for (const rootId of affectedRootIds) {
      const prev = previous.find(n => n.id === rootId)
      const next = result.notes.find(n => n.id === rootId)
      if (!prev || !next || prev === next) continue

      const prevLineCount = prev.content.split('\n').length
      const nextLineCount = next.content.split('\n').length
      const rootNoteIdDescendantsPresent = Array.from(this.rawNotes.values()).some(
        n => n.rootNoteId === rootId && isLive(n.state),
      )
      const suspicious = prevLineCount >= 3 && nextLineCount <= 1 && rootNoteIdDescendantsPresent
      if (!suspicious) continue

      console.warn(
        `preservePartialReconstructions: keeping previous content for ${rootId} ` +
        `(prev=${prevLineCount} lines, new=${nextLineCount}). ` +
        `rootNoteId descendants present but parentNoteId walk found none — ` +
        `likely partial Firestore sync.`,
      )

      if (!defendedNotes) defendedNotes = [...result.notes]
      const idx = defendedNotes.findIndex(n => n.id === rootId)
      if (idx >= 0) defendedNotes[idx] = prev
      needsFix.add(rootId)
    }

    return {
      notes: defendedNotes ?? result.notes,
      notesNeedingFix: needsFix,
    }
  }

  private setNotesNeedingFix(next: Set<string>): void {
    if (sameSet(this._notesNeedingFix, next)) return
    this._notesNeedingFix = next
    this.emitNeedsFixChange()
  }

  /**
   * Incremental updates see only the affected roots: clear needsFix for any
   * affected root that now reconstructs cleanly, add any that newly need fixing.
   */
  private mergeNotesNeedingFix(affectedRootIds: Set<string>, stillNeedFix: Set<string>): void {
    const next = new Set(this._notesNeedingFix)
    let changed = false
    for (const id of affectedRootIds) {
      if (next.has(id) && !stillNeedFix.has(id)) {
        next.delete(id)
        changed = true
      }
    }
    for (const id of stillNeedFix) {
      if (!next.has(id)) {
        next.add(id)
        changed = true
      }
    }
    if (!changed) return
    this._notesNeedingFix = next
    this.emitNeedsFixChange()
  }

  private emitNeedsFixChange(): void {
    for (const listener of this.needsFixListeners) listener()
  }

  private emitChange(): void {
    for (const listener of this.listeners) {
      listener()
    }
  }

  private emitChangedNoteIds(changedIds: Set<string>): void {
    for (const listener of this.changeListeners) {
      listener(changedIds)
    }
  }

  private emitErrorChange(): void {
    for (const listener of this.errorListeners) {
      listener()
    }
  }
}

function sameSet(a: Set<string>, b: Set<string>): boolean {
  if (a === b) return true
  if (a.size !== b.size) return false
  for (const x of a) if (!b.has(x)) return false
  return true
}

export const noteStore = new NoteStore()
