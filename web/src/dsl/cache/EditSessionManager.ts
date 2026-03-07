import type { DirectiveCacheManager } from './DirectiveCache'

/**
 * Tracks the context of an active inline edit session.
 */
interface EditContext {
  editedNoteId: string
  originatingNoteId: string
  editStartTime: number
}

export enum InvalidationReason {
  CONTENT_CHANGED = 'CONTENT_CHANGED',
  NOTE_DELETED = 'NOTE_DELETED',
  NOTE_CREATED = 'NOTE_CREATED',
  METADATA_CHANGED = 'METADATA_CHANGED',
  MANUAL_REFRESH = 'MANUAL_REFRESH',
}

interface PendingInvalidation {
  noteId: string
  reason: InvalidationReason
  timestamp: number
}

const MAX_EDIT_DURATION_MS = 5 * 60 * 1000

/**
 * Manages inline edit sessions and deferred cache invalidations.
 */
export class EditSessionManager {
  private activeEditContext: EditContext | null = null
  private pendingInvalidations: PendingInvalidation[] = []
  private sessionEndListeners: Array<() => void> = []

  constructor(private readonly cacheManager?: DirectiveCacheManager) {}

  startEditSession(editedNoteId: string, originatingNoteId: string): void {
    if (this.activeEditContext) {
      this.endEditSession()
    }
    this.activeEditContext = {
      editedNoteId,
      originatingNoteId,
      editStartTime: Date.now(),
    }
  }

  isEditSessionActive(): boolean {
    return this.activeEditContext !== null
  }

  shouldSuppressInvalidation(noteId: string): boolean {
    const ctx = this.activeEditContext
    if (!ctx) return false
    if (Date.now() - ctx.editStartTime > MAX_EDIT_DURATION_MS) return false
    return noteId === ctx.originatingNoteId
  }

  requestInvalidation(noteId: string, reason: InvalidationReason): boolean {
    if (this.shouldSuppressInvalidation(noteId)) {
      this.pendingInvalidations.push({ noteId, reason, timestamp: Date.now() })
      return false
    }
    this.cacheManager?.clearNote(noteId)
    return true
  }

  endEditSession(): void {
    if (!this.activeEditContext) return

    for (const inv of this.pendingInvalidations) {
      this.cacheManager?.clearNote(inv.noteId)
    }
    this.pendingInvalidations = []
    this.activeEditContext = null

    for (const listener of this.sessionEndListeners) {
      listener()
    }
  }

  abortEditSession(): void {
    this.pendingInvalidations = []
    this.activeEditContext = null
  }

  addSessionEndListener(listener: () => void): void {
    this.sessionEndListeners.push(listener)
  }

  removeSessionEndListener(listener: () => void): void {
    const idx = this.sessionEndListeners.indexOf(listener)
    if (idx >= 0) this.sessionEndListeners.splice(idx, 1)
  }
}
