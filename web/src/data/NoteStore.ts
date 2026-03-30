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
import { flattenTreeToLines } from './NoteTree'
import { rebuildAllNotes, rebuildAffectedNotes } from './NoteReconstruction'

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
  private errorListeners = new Set<() => void>()
  private pendingSaves = new Map<string, Promise<void>>()
  private _error: string | null = null

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = (): Note[] => {
    return this.reconstructedNotes
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
   * Start the Firestore collection listener. Idempotent — calling multiple
   * times is safe (subsequent calls are no-ops if already started).
   */
  start(db: Firestore, auth: Auth): void {
    if (this.unsubscribe) return
    const userId = auth.currentUser?.uid
    if (!userId) return

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
      if (this.loaded && snapshot.metadata.hasPendingWrites) return

      const isFirstSnapshot = !this.loaded

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
        }
      }
    }, (error) => {
      console.error('NoteStore snapshot listener error:', error)
      this._error = error instanceof Error ? error.message : 'Note sync failed'
      this.emitErrorChange()
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
    this.emitChange()
    this.emitErrorChange()
  }

  /** Optimistic update — replace a single reconstructed note by ID. */
  updateNote(noteId: string, updatedNote: Note): void {
    if (this.applyUpdate(noteId, updatedNote)) {
      this.emitChange()
    }
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

  /** Returns per-line NoteLines for a note, using the tree structure for correct noteId mapping. */
  getNoteLinesById(noteId: string): NoteLine[] | undefined {
    const note = this.rawNotes.get(noteId)
    if (!note) return undefined
    if (note.containedNotes.length === 0) {
      return [{ content: note.content, noteId: note.id }]
    }
    const descendants: Note[] = []
    for (const raw of this.rawNotes.values()) {
      if (raw.rootNoteId === noteId) descendants.push(raw)
    }
    if (descendants.length > 0) {
      return flattenTreeToLines(note, descendants)
    }
    // Old format fallback
    const lines: NoteLine[] = [{ content: note.content, noteId: note.id }]
    for (const childId of note.containedNotes) {
      if (childId !== '') {
        const child = this.rawNotes.get(childId)
        lines.push({ content: child?.content ?? '', noteId: childId })
      } else {
        lines.push({ content: '', noteId: null })
      }
    }
    return lines
  }

  /** Track an in-flight save so loaders can await it before reading from Firestore. */
  trackSave(noteId: string, promise: Promise<void>): void {
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

  // --- Internal reconstruction ---

  /** Rebuild all reconstructed notes from rawNotes. Used on initial load. */
  private rebuildAll(): void {
    this.reconstructedNotes = rebuildAllNotes(this.rawNotes)
    this.emitChange()
  }

  /** Rebuild only the top-level notes whose trees were affected by a change. */
  private rebuildAffected(rootIds: Set<string>): void {
    const result = rebuildAffectedNotes(this.reconstructedNotes, rootIds, this.rawNotes)
    if (result !== this.reconstructedNotes) {
      this.reconstructedNotes = result
      this.emitChange()
    }
  }

  private emitChange(): void {
    for (const listener of this.listeners) {
      listener()
    }
  }

  private emitErrorChange(): void {
    for (const listener of this.errorListeners) {
      listener()
    }
  }
}

export const noteStore = new NoteStore()
