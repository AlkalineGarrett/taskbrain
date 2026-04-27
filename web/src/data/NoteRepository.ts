import {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  where,
  runTransaction,
  serverTimestamp,
  writeBatch,
  updateDoc,
  type Firestore,
  type DocumentReference,
} from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import { noteFromFirestore, type Note, type NoteLine } from './Note'
import { reconstructNoteContent, reconstructNoteLines } from './NoteReconstruction'
import { noteStore, NoteStoreNotLoadedError } from './NoteStore'
import { firestoreUsage } from './FirestoreUsage'
import { isSentinelNoteId, isRealNoteId } from './NoteIdSentinel'
import { performSimilarityMatching } from '@/editor/ContentSimilarity'

export interface NoteLoadResult {
  lines: NoteLine[]
  isDeleted: boolean
  showCompleted: boolean
}

/**
 * Repository for managing composable notes in Firestore.
 *
 * Notes form a tree: parentNoteId points to immediate parent, rootNoteId enables
 * single-query loading of all descendants. Indentation is derived from tree depth
 * (no tabs stored in Firestore content).
 *
 * Indentation is derived from tree depth (no tabs stored in Firestore content).
 */
export class NoteRepository {
  private readonly notesRef

  constructor(
    private readonly db: Firestore,
    private readonly auth: Auth,
  ) {
    this.notesRef = collection(db, 'notes')
  }

  private async logged<T>(operation: string, fn: () => Promise<T>): Promise<T> {
    try {
      return await fn()
    } catch (e) {
      console.error(`NoteRepository.${operation} failed:`, e)
      throw e
    }
  }

  private requireUserId(): string {
    const uid = this.auth.currentUser?.uid
    if (!uid) throw new Error('User not signed in')
    return uid
  }

  private noteRef(noteId: string): DocumentReference {
    return doc(this.notesRef, noteId)
  }

  private baseNoteData(userId: string, content: string) {
    return {
      userId,
      content,
      updatedAt: serverTimestamp(),
    }
  }

  private newNoteData(userId: string, content: string, parentNoteId?: string) {
    return {
      userId,
      content,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      parentNoteId: parentNoteId ?? null,
    }
  }

  // ── Load operations ─────────────────────────────────────────────────

  /**
   * Loads a note and its descendants, returning lines plus note metadata.
   */
  async loadNoteWithChildren(noteId: string): Promise<NoteLoadResult> {
    return this.logged('loadNoteWithChildren', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))
      firestoreUsage.recordRead('loadNoteWithChildren', 'doc.get')

      if (!docSnap.exists()) {
        return { lines: [{ content: '', noteId }], isDeleted: false, showCompleted: true }
      }

      const note = noteFromFirestore(docSnap.id, docSnap.data())
      const allLines = await this.loadNoteLines(note)

      return {
        lines: allLines,
        isDeleted: note.state === 'deleted',
        showCompleted: note.showCompleted ?? true,
      }
    })
  }

  /**
   * Loads note lines via the same parentNoteId walk used by `reconstructNoteLines`.
   * Shares heal semantics with `NoteStore.getNoteLinesById`: orphans are dropped,
   * strays linked by parentNoteId are appended, so the Firestore-fallback load
   * stays consistent with the reconstructed snapshot.
   */
  private async loadNoteLines(note: Note): Promise<NoteLine[]> {
    const userId = this.requireUserId()
    const descendantQuery = query(this.notesRef, where('rootNoteId', '==', note.id), where('userId', '==', userId))
    const descendantSnap = await getDocs(descendantQuery)
    firestoreUsage.recordRead('loadNoteLines', 'getDocs', descendantSnap.size)

    const descendants = descendantSnap.docs
      .map((d) => noteFromFirestore(d.id, d.data()))
      .filter((n) => n.state !== 'deleted')

    if (descendants.length === 0) {
      return [{ content: note.content, noteId: note.id }]
    }

    const rawById = new Map<string, Note>([[note.id, note]])
    const childrenByParent = new Map<string, Note[]>()
    for (const d of descendants) {
      rawById.set(d.id, d)
      if (d.parentNoteId != null) {
        const list = childrenByParent.get(d.parentNoteId)
        if (list) list.push(d)
        else childrenByParent.set(d.parentNoteId, [d])
      }
    }
    const [lines] = reconstructNoteLines(note, rawById, childrenByParent)
    return lines
  }

  /**
   * Loads all top-level notes with full content reconstructed.
   */
  async loadNotesWithFullContent(): Promise<Note[]> {
    return this.logged('loadNotesWithFullContent', async () => {
      const userId = this.requireUserId()
      const q = query(this.notesRef, where('userId', '==', userId))
      const snapshot = await getDocs(q)
      firestoreUsage.recordRead('loadNotesWithFullContent', 'getDocs', snapshot.size)

      const allNotes = snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => n.state !== 'deleted')

      const topLevelNotes = allNotes.filter((n) => n.parentNoteId == null)
      const rawById = new Map<string, Note>()
      const childrenByParent = new Map<string, Note[]>()
      for (const n of allNotes) {
        rawById.set(n.id, n)
        if (n.parentNoteId != null) {
          const list = childrenByParent.get(n.parentNoteId)
          if (list) list.push(n)
          else childrenByParent.set(n.parentNoteId, [n])
        }
      }

      return topLevelNotes.map((note) => {
        const [reconstructed] = reconstructNoteContent(note, rawById, childrenByParent)
        return reconstructed
      })
    })
  }

  async loadUserNotes(): Promise<Note[]> {
    return this.logged('loadUserNotes', async () => {
      const userId = this.requireUserId()
      const q = query(this.notesRef, where('userId', '==', userId))
      const snapshot = await getDocs(q)
      firestoreUsage.recordRead('loadUserNotes', 'getDocs', snapshot.size)

      return snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => n.parentNoteId == null && n.state !== 'deleted')
    })
  }

  async loadAllUserNotes(): Promise<Note[]> {
    return this.logged('loadAllUserNotes', async () => {
      const userId = this.requireUserId()
      const q = query(this.notesRef, where('userId', '==', userId))
      const snapshot = await getDocs(q)
      firestoreUsage.recordRead('loadAllUserNotes', 'getDocs', snapshot.size)

      return snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => n.parentNoteId == null)
    })
  }

  async loadNoteById(noteId: string): Promise<Note | null> {
    return this.logged('loadNoteById', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))
      firestoreUsage.recordRead('loadNoteById', 'doc.get')
      if (!docSnap.exists()) return null
      return noteFromFirestore(docSnap.id, docSnap.data())
    })
  }

  async isNoteDeleted(noteId: string): Promise<boolean> {
    return this.logged('isNoteDeleted', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))
      firestoreUsage.recordRead('isNoteDeleted', 'doc.get')
      if (!docSnap.exists()) return false
      return docSnap.data().state === 'deleted'
    })
  }

  // ── Save operations ─────────────────────────────────────────────────

  /**
   * Saves a note with tree structure derived from tab-indented lines.
   * Returns a map of line indices to newly created note IDs.
   *
   * Note: Firestore transactions can read/write at most 500 documents.
   * Notes with >500 descendants will fail. Add batched transaction support if needed.
   */
  async saveNoteWithChildren(
    noteId: string,
    trackedLines: NoteLine[],
  ): Promise<Map<number, string>> {
    return this.logged('saveNoteWithChildren', async () => {
      if (trackedLines.length === 0) return new Map()
      this.assertNoteStoreLoaded('saveNoteWithChildren', noteId)
      this.warnIfDescendantsLikelyStale('saveNoteWithChildren', noteId)

      const userId = this.requireUserId()
      const parentRef = this.noteRef(noteId)
      const rootContent = trackedLines[0]!.content.replace(/^\t+/, '')

      const linesToSaveUnreconciled = trackedLines

      // Recover noteIds that the editor lost along the way: if a null-id
      // line sits at the same tree position and has identical content to
      // an existing descendant, reuse that descendant's id. Without this
      // the save would allocate a fresh doc and orphan the real one.
      const linesToSave = reconcileNullNoteIdsByContent(noteId, linesToSaveUnreconciled)

      // Pre-allocate refs for new notes. Both null (upstream-bug signal) and
      // sentinel (expected placeholder from paste/split/typed/etc.) ids
      // need a fresh Firestore doc — only real ids refer to existing docs we
      // can update.
      const newRefs = new Map<number, DocumentReference>()
      for (let i = 1; i < linesToSave.length; i++) {
        if (!isRealNoteId(linesToSave[i]!.noteId)) {
          newRefs.set(i, doc(this.notesRef))
        }
      }

      function effectiveId(lineIndex: number): string {
        if (lineIndex === 0) return noteId
        if (isRealNoteId(linesToSave[lineIndex]!.noteId)) return linesToSave[lineIndex]!.noteId!
        return newRefs.get(lineIndex)!.id
      }

      // Empty lines don't push the indent stack — indented children below
      // them attach to the last content-bearing ancestor.
      const parentOfLine = new Array<number>(linesToSave.length).fill(0)
      const childrenOfLine = linesToSave.map(() => [] as string[])

      const stack: { depth: number; lineIndex: number }[] = [{ depth: 0, lineIndex: 0 }]

      for (let i = 1; i < linesToSave.length; i++) {
        const tabMatch = linesToSave[i]!.content.match(/^\t*/)
        const depth = tabMatch ? tabMatch[0]!.length : 0
        const content = linesToSave[i]!.content.replace(/^\t+/, '')

        while (stack.length > 1 && stack[stack.length - 1]!.depth >= depth) {
          stack.pop()
        }
        parentOfLine[i] = stack[stack.length - 1]!.lineIndex
        childrenOfLine[parentOfLine[i]!]!.push(effectiveId(i))
        if (content !== '') {
          stack.push({ depth, lineIndex: i })
        }
      }

      // Pull existing descendants from in-memory NoteStore — the listener
      // already mirrors Firestore truth, so the save no longer pays a
      // descendant-collection read on every commit.
      const existingDescendantIds = noteStore.getDescendantIds(noteId)

      // Compute surviving IDs upfront so the content-drop guard can run
      // before any transaction work; matches the Android save path.
      const survivingIds = new Set<string>()
      for (let i = 1; i < linesToSave.length; i++) {
        survivingIds.add(effectiveId(i))
      }
      const toDelete = new Set<string>()
      for (const id of existingDescendantIds) {
        if (!survivingIds.has(id)) toDelete.add(id)
      }

      this.assertNotContentDrop(noteId, trackedLines, linesToSave, existingDescendantIds, survivingIds, toDelete)

      // Fix parent cycles: if this note's parent chain loops back
      // to itself, clear parentNoteId/rootNoteId to make it a root note.
      const hasCycle = this.hasParentCycle(noteId)

      // Total docs touched: root (1) + descendants + soft-deletes.
      const txnDocCount = 1 + (linesToSave.length - 1) + toDelete.size
      firestoreUsage.recordWrite('saveNoteWithChildren', 'transaction', txnDocCount)
      return runTransaction(this.db, async (transaction) => {
        // Update root
        const rootData: Record<string, unknown> = {
          ...this.baseNoteData(userId, rootContent),
          containedNotes: childrenOfLine[0]!,
        }
        if (hasCycle) {
          rootData.parentNoteId = null
          rootData.rootNoteId = null
        }
        transaction.set(parentRef, rootData, { merge: true })

        // Write each descendant
        for (let i = 1; i < linesToSave.length; i++) {
          const content = linesToSave[i]!.content.replace(/^\t+/, '')

          const id = effectiveId(i)
          const parentId = effectiveId(parentOfLine[i]!)

          if (isRealNoteId(linesToSave[i]!.noteId)) {
            transaction.set(
              this.noteRef(id),
              {
                content,
                parentNoteId: parentId,
                rootNoteId: noteId,
                containedNotes: childrenOfLine[i]!,
                state: null, // Clear deleted state for reparented notes
                updatedAt: serverTimestamp(),
              },
              { merge: true },
            )
          } else {
            transaction.set(newRefs.get(i)!, {
              userId,
              content,
              parentNoteId: parentId,
              rootNoteId: noteId,
              containedNotes: childrenOfLine[i]!,
              createdAt: serverTimestamp(),
              updatedAt: serverTimestamp(),
            })
          }
        }

        // Soft-delete removed notes and clear parent refs to prevent orphan cycles
        for (const id of toDelete) {
          transaction.update(this.noteRef(id), {
            state: 'deleted',
            parentNoteId: null,
            rootNoteId: null,
            updatedAt: serverTimestamp(),
          })
        }

        const createdIds = new Map<number, string>()
        for (const [lineIndex, ref] of newRefs) {
          createdIds.set(lineIndex, ref.id)
        }
        return createdIds
      })
    })
  }

  /**
   * Hard guard for ops that read descendants from NoteStore. Throws a
   * `NoteStoreNotLoadedError` (with auto-captured stack) when violated; the
   * `logged()` wrapper prints message+stack to the console and the message
   * propagates to the caller's error UI (saveError banner / list error row).
   */
  private assertNoteStoreLoaded(operation: string, noteId: string): void {
    if (noteStore.isLoaded()) return
    throw new NoteStoreNotLoadedError(operation, noteId)
  }

  /**
   * Content-drop guard: aborts the save if it would soft-delete more than
   * half of the note's directly-declared `containedNotes` children. Mirrors
   * the Android guard. Defends against an editor bug or partial-sync window
   * dropping line identities and accidentally wiping the user's content.
   *
   * Compares against `containedNotes ∩ existingDescendants`, ignoring orphan
   * refs (declared but not in rawNotes) so a save of an auto-healed note
   * doesn't false-trip.
   */
  private assertNotContentDrop(
    noteId: string,
    originalTrackedLines: NoteLine[],
    linesToSave: NoteLine[],
    existingDescendantIds: Set<string>,
    survivingIds: Set<string>,
    toDelete: Set<string>,
  ): void {
    const storeNote = noteStore.getNoteById(noteId)
    const declaredContainedNotes = new Set(storeNote?.containedNotes ?? [])
    const realContainedNotes = new Set<string>()
    for (const id of declaredContainedNotes) {
      if (existingDescendantIds.has(id)) realContainedNotes.add(id)
    }
    const orphanRefs: string[] = []
    for (const id of declaredContainedNotes) {
      if (!existingDescendantIds.has(id)) orphanRefs.push(id)
    }
    if (orphanRefs.length > 0) {
      console.warn(
        `saveNoteWithChildren(${noteId}): ignoring ${orphanRefs.length} orphan ` +
        `containedNotes refs for content-drop guard (no live child): ` +
        `[${orphanRefs.join(', ')}]`,
      )
    }
    const directToDelete = new Set<string>()
    for (const id of realContainedNotes) {
      if (!survivingIds.has(id)) directToDelete.add(id)
    }
    if (realContainedNotes.size < 3) return
    if (directToDelete.size <= realContainedNotes.size / 2) return

    const diagnostics = buildContentDropDiagnostics(
      noteId, originalTrackedLines, linesToSave, existingDescendantIds, survivingIds, toDelete, storeNote,
    )
    console.error(diagnostics)
    throw new ContentDropAbortError(
      `Save aborted: would delete ${toDelete.size} of ` +
      `${existingDescendantIds.size} child notes ` +
      `(saving ${linesToSave.length} lines). ` +
      `This was blocked to prevent data loss. ` +
      `Your note content is still safe — please save again. ` +
      `Open devtools console for full diagnostics.`,
    )
  }

  /**
   * Soft guard: detect the brief race window where a note's `containedNotes`
   * declares children whose docs haven't arrived in the listener's snapshot
   * yet. Save would proceed with an incomplete descendant set, so we warn the
   * user (banner via `noteStore.raiseWarning`) and log a full diagnostic with
   * stack to the console for debugging.
   */
  private warnIfDescendantsLikelyStale(operation: string, noteId: string): void {
    const rawNote = noteStore.getRawNoteById(noteId)
    if (!rawNote) return
    const declared = rawNote.containedNotes ?? []
    if (declared.length === 0) return

    const missing: string[] = []
    for (const id of declared) {
      if (!noteStore.getRawNoteById(id)) missing.push(id)
    }
    if (missing.length === 0) return

    const stack = new Error().stack ?? '(stack unavailable)'
    const sampleIds = missing.slice(0, 5).join(', ')
    const ellipsis = missing.length > 5 ? `, ... (+${missing.length - 5} more)` : ''
    console.warn(
      `[NoteStore stale] ${operation}(noteId=${noteId}): ${rawNote.id} declares ` +
      `${declared.length} child note(s) but ${missing.length} are not in the ` +
      `local store yet: [${sampleIds}${ellipsis}]. The descendant set used ` +
      `for soft-delete tracking may be incomplete; if any of these were ` +
      `removed in this save, their old docs will remain active until the ` +
      `next save after the listener catches up.\n\nStack:\n${stack}`,
    )
    noteStore.raiseWarning(
      `Note has ${missing.length} child note(s) not yet visible in the local ` +
      `store; recent edits may not be fully synced. ` +
      `Open devtools console for full diagnostic.`,
    )
  }

  private hasParentCycle(noteId: string): boolean {
    const visited = new Set<string>()
    let current: string | null = noteId
    while (current) {
      if (visited.has(current)) return true
      visited.add(current)
      current = noteStore.getRawNoteById(current)?.parentNoteId ?? null
    }
    return false
  }

  /**
   * Saves a note with full multi-line content.
   * Used for inline editing of notes within view directives.
   */
  async saveNoteWithFullContent(
    noteId: string,
    newContent: string,
    editorNoteIds?: (string | null)[],
  ): Promise<Map<number, string>> {
    return this.logged('saveNoteWithFullContent', async () => {
      this.requireUserId()
      this.assertNoteStoreLoaded('saveNoteWithFullContent', noteId)
      this.warnIfDescendantsLikelyStale('saveNoteWithFullContent', noteId)

      // Prefer NoteStore in-memory lines; fall back to Firestore only when
      // the store doesn't have this specific note yet (e.g., immediately
      // after createNote, before the listener echo). Mirrors Android.
      const existingLines = noteStore.getNoteLinesById(noteId)
        ?? (await this.loadNoteWithChildren(noteId)).lines

      const newLinesContent = newContent.split('\n')
      const trackedLines = matchLinesToIds(noteId, existingLines, newLinesContent, editorNoteIds)
      return this.saveNoteWithChildren(noteId, trackedLines)
    })
  }

  async createNote(): Promise<string> {
    return this.logged('createNote', async () => {
      const userId = this.requireUserId()
      const { addDoc } = await import('firebase/firestore')
      const ref = await addDoc(this.notesRef, this.newNoteData(userId, ''))
      firestoreUsage.recordWrite('createNote', 'set')
      return ref.id
    })
  }

  /**
   * Creates a new multi-line note with tree structure derived from indentation.
   */
  async createMultiLineNote(content: string): Promise<string> {
    return this.logged('createMultiLineNote', async () => {
      const userId = this.requireUserId()
      const lines = content.split('\n')
      const firstLine = (lines[0] ?? '').replace(/^\t+/, '')
      const childLines = lines.slice(1)

      if (childLines.length === 0 || childLines.every((l) => l.replace(/^\t+/, '') === '')) {
        const { addDoc } = await import('firebase/firestore')
        const ref = await addDoc(this.notesRef, this.newNoteData(userId, firstLine))
        firestoreUsage.recordWrite('createMultiLineNote', 'set')
        return ref.id
      }

      const parentRef = doc(this.notesRef)

      interface NodeInfo {
        ref: DocumentReference
        content: string
        parentId: string
        children: string[]
      }

      const nodes: NodeInfo[] = []
      const rootChildren: string[] = []

      const stack: { depth: number; id: string; children: string[] }[] = [
        { depth: 0, id: parentRef.id, children: rootChildren },
      ]

      for (let i = 1; i < lines.length; i++) {
        const tabMatch = lines[i]!.match(/^\t*/)
        const depth = tabMatch ? tabMatch[0]!.length : 0
        const lineContent = lines[i]!.replace(/^\t+/, '')

        while (stack.length > 1 && stack[stack.length - 1]!.depth >= depth) {
          stack.pop()
        }
        const parent = stack[stack.length - 1]!

        // Empty lines don't push the indent stack — children below attach
        // to the last content-bearing ancestor.
        const ref = doc(this.notesRef)
        const nodeChildren: string[] = []
        nodes.push({ ref, content: lineContent, parentId: parent.id, children: nodeChildren })
        parent.children.push(ref.id)
        if (lineContent !== '') {
          stack.push({ depth, id: ref.id, children: nodeChildren })
        }
      }

      const batch = writeBatch(this.db)

      batch.set(parentRef, {
        ...this.newNoteData(userId, firstLine),
        containedNotes: rootChildren,
      })

      for (const node of nodes) {
        batch.set(node.ref, {
          userId,
          content: node.content,
          parentNoteId: node.parentId,
          rootNoteId: parentRef.id,
          containedNotes: node.children,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        })
      }

      await batch.commit()
      // Root note + N descendants written in a single batch.
      firestoreUsage.recordWrite('createMultiLineNote', 'batch.commit', nodes.length + 1)
      return parentRef.id
    })
  }

  // ── Delete/restore operations ───────────────────────────────────────

  /**
   * Soft-deletes a note and all its descendants.
   */
  async softDeleteNote(noteId: string): Promise<void> {
    return this.logged('softDeleteNote', async () => {
      this.requireUserId()
      this.assertNoteStoreLoaded('softDeleteNote', noteId)
      this.warnIfDescendantsLikelyStale('softDeleteNote', noteId)
      const idsToDelete = new Set([noteId, ...noteStore.getDescendantIds(noteId)])

      const batch = writeBatch(this.db)
      for (const id of idsToDelete) {
        batch.update(this.noteRef(id), {
          state: 'deleted',
          updatedAt: serverTimestamp(),
        })
      }
      await batch.commit()
      firestoreUsage.recordWrite('softDeleteNote', 'batch.commit', idsToDelete.size)
    })
  }

  /**
   * Hard-deletes every note already in soft-deleted state (root + descendant
   * docs are all flagged when softDeleteNote runs, so a single
   * `state == "deleted"` query catches the whole tree). Returns the number of
   * docs removed. Chunks at the 500-op Firestore batch limit.
   *
   * Irreversible — callers should confirm before invoking.
   */
  async hardDeleteAllSoftDeleted(): Promise<number> {
    return this.logged('hardDeleteAllSoftDeleted', async () => {
      const userId = this.requireUserId()
      const q = query(
        this.notesRef,
        where('state', '==', 'deleted'),
        where('userId', '==', userId),
      )
      const snap = await getDocs(q)
      firestoreUsage.recordRead('hardDeleteAllSoftDeleted', 'getDocs', snap.size)
      if (snap.empty) return 0

      const BATCH_LIMIT = 500
      for (let i = 0; i < snap.docs.length; i += BATCH_LIMIT) {
        const chunk = snap.docs.slice(i, i + BATCH_LIMIT)
        const batch = writeBatch(this.db)
        for (const d of chunk) {
          batch.delete(d.ref)
        }
        await batch.commit()
        firestoreUsage.recordWrite('hardDeleteAllSoftDeleted', 'batch.commit', chunk.length)
      }
      return snap.size
    })
  }

  /**
   * Restores a deleted note and all its descendants.
   */
  async undeleteNote(noteId: string): Promise<void> {
    return this.logged('undeleteNote', async () => {
      this.requireUserId()
      this.assertNoteStoreLoaded('undeleteNote', noteId)
      // Use getAllDescendantIds (includes soft-deleted) since restoring a tree
      // should bring back its soft-deleted descendants too. Skip the
      // stale-descendants warning: a deleted note's containedNotes only lists
      // active children, so it can't detect missing soft-deleted descendants.
      const idsToRestore = new Set([noteId, ...noteStore.getAllDescendantIds(noteId)])

      const batch = writeBatch(this.db)
      for (const id of idsToRestore) {
        batch.update(this.noteRef(id), {
          state: null,
          updatedAt: serverTimestamp(),
        })
      }
      await batch.commit()
      firestoreUsage.recordWrite('undeleteNote', 'batch.commit', idsToRestore.size)
    })
  }

  // ── Utility operations ──────────────────────────────────────────────

  async updateShowCompleted(noteId: string, showCompleted: boolean): Promise<void> {
    return this.logged('updateShowCompleted', async () => {
      this.requireUserId()
      await updateDoc(this.noteRef(noteId), {
        showCompleted,
        updatedAt: serverTimestamp(),
      })
      firestoreUsage.recordWrite('updateShowCompleted', 'update')
    })
  }

}


/**
 * Thrown when [NoteRepository.saveNoteWithChildren] aborts because the save
 * would soft-delete an unreasonable number of declared child notes,
 * indicating either an editor bug that wiped line identities or a partial-
 * sync race. Mirrors the Android `ContentDropAbortException`.
 */
export class ContentDropAbortError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ContentDropAbortError'
  }
}

function buildContentDropDiagnostics(
  noteId: string,
  originalTrackedLines: NoteLine[],
  linesToSave: NoteLine[],
  existingDescendantIds: Set<string>,
  survivingIds: Set<string>,
  toDelete: Set<string>,
  storeNote: Note | undefined,
): string {
  const lines: string[] = []
  lines.push('=== CONTENT DROP GUARD TRIGGERED ===')
  lines.push(`noteId: ${noteId}`)
  lines.push(`originalTrackedLines: ${originalTrackedLines.length}`)
  lines.push(`linesToSave: ${linesToSave.length}`)
  lines.push(`existingDescendants (rootNoteId in NoteStore): ${existingDescendantIds.size} ${[...existingDescendantIds].join(', ')}`)
  lines.push(`survivingIds: ${survivingIds.size} ${[...survivingIds].join(', ')}`)
  lines.push(`toDelete: ${toDelete.size} ${[...toDelete].join(', ')}`)

  lines.push('--- containedNotes guard ---')
  const containedNotes = storeNote?.containedNotes ?? []
  lines.push(`  containedNotes: ${containedNotes.length} [${containedNotes.join(', ')}]`)
  const directToDelete: string[] = []
  for (const id of containedNotes) {
    if (!survivingIds.has(id)) directToDelete.push(id)
  }
  lines.push(`  directToDelete (containedNotes − surviving): ${directToDelete.length} [${directToDelete.join(', ')}]`)

  lines.push('--- trackedLines detail ---')
  for (let i = 0; i < originalTrackedLines.length; i++) {
    const line = originalTrackedLines[i]!
    const preview = line.content.slice(0, 60).replace(/\n/g, '\\n')
    lines.push(`  [${i}] noteId=${line.noteId ?? 'null'} content='${preview}'`)
  }

  lines.push('--- NoteStore state ---')
  if (storeNote) {
    lines.push(`  parentNoteId: ${storeNote.parentNoteId ?? 'null'}`)
    lines.push(`  rootNoteId: ${storeNote.rootNoteId ?? 'null'}`)
    lines.push(`  state: ${storeNote.state ?? 'active'}`)
    const storeLines = storeNote.content.split('\n')
    lines.push(`  content lines: ${storeLines.length}`)
    for (let i = 0; i < storeLines.length; i++) {
      lines.push(`  [${i}] '${storeLines[i]!.slice(0, 60)}'`)
    }
  } else {
    lines.push(`  NoteStore has NO entry for ${noteId}`)
  }

  lines.push('--- Stack trace ---')
  lines.push(new Error().stack?.split('\n').slice(2, 17).join('\n') ?? '(unavailable)')
  lines.push('=== END CONTENT DROP GUARD ===')
  return lines.join('\n')
}


/**
 * For each non-empty line whose `noteId` is null, try to recover a real id
 * from rawNotes by matching (parent id, trimmed content) against existing
 * live descendants. Preserves the original order of candidates so duplicate-
 * content siblings bind left-to-right.
 *
 * This is defensive: the editor is supposed to carry noteIds through edits,
 * but pastes, lossy re-inits, or stale snapshots have been observed to wipe
 * them. Without recovery the save would allocate fresh docs for these lines
 * and the existing descendants would be orphaned by the soft-delete path.
 */
function reconcileNullNoteIdsByContent(
  rootNoteId: string,
  linesToSave: NoteLine[],
): NoteLine[] {
  if (linesToSave.length <= 1) return linesToSave

  // Parent line-index per line, via indentation (independent of noteIds).
  const parentLineOf = new Array<number>(linesToSave.length).fill(0)
  const stack: Array<[number, number]> = [[0, 0]] // [depth, lineIndex]
  for (let i = 1; i < linesToSave.length; i++) {
    const raw = linesToSave[i]!.content
    const depth = raw.match(/^\t*/)?.[0].length ?? 0
    const content = raw.replace(/^\t+/, '')
    while (stack.length > 1 && stack[stack.length - 1]![0] >= depth) stack.pop()
    parentLineOf[i] = stack[stack.length - 1]![1]
    if (content.length > 0) stack.push([depth, i])
  }

  // Candidates grouped by parentNoteId for O(1) lookup during reconciliation.
  const byParent = noteStore.getLiveDescendantsByParent(rootNoteId)
  if (byParent.size === 0) return linesToSave

  const usedIds = new Set<string>()
  for (const l of linesToSave) if (l.noteId) usedIds.add(l.noteId)

  const result = linesToSave.slice()
  const lineIds: (string | null)[] = new Array(linesToSave.length).fill(null)
  lineIds[0] = rootNoteId

  let reconciledFromNull = 0 // upstream bug signal
  let reconciledFromSentinel = 0 // expected placeholder
  type NullIdRecovery = {
    lineIndex: number
    parentLineIndex: number
    parentId: string | null
    contentPreview: string
    recoveredId: string | null
  }
  const nullTrace: NullIdRecovery[] = []
  for (let i = 1; i < result.length; i++) {
    const content = result[i]!.content.replace(/^\t+/, '')
    const existing = result[i]!.noteId
    if (isRealNoteId(existing)) {
      lineIds[i] = existing
      continue
    }
    const existingIsSentinel = isSentinelNoteId(existing)
    const parentId = lineIds[parentLineOf[i]!] ?? null
    const candidates = parentId ? byParent.get(parentId) : undefined
    let matchId: string | null = null
    if (candidates) {
      const matchIdx = candidates.findIndex(c => c.content === content && !usedIds.has(c.id))
      if (matchIdx >= 0) {
        const match = candidates[matchIdx]!
        candidates.splice(matchIdx, 1)
        usedIds.add(match.id)
        lineIds[i] = match.id
        result[i] = { ...result[i]!, noteId: match.id }
        matchId = match.id
        if (existingIsSentinel) reconciledFromSentinel++
        else reconciledFromNull++
      }
    }
    if (!existingIsSentinel) {
      nullTrace.push({
        lineIndex: i,
        parentLineIndex: parentLineOf[i]!,
        parentId,
        contentPreview: content.slice(0, 60),
        recoveredId: matchId,
      })
    }
  }

  if (reconciledFromSentinel > 0) {
    console.debug(
      `reconcileNullNoteIdsByContent(${rootNoteId}): matched ` +
      `${reconciledFromSentinel} sentinel line(s) to existing docs.`,
    )
  }
  if (reconciledFromNull > 0 || nullTrace.some(e => e.recoveredId === null)) {
    // Upstream lossy path produced bare null ids on non-empty lines. Log a
    // detailed diagnostic block (mirrors the Android content-drop guard style)
    // so the failing site can be investigated without repro.
    console.warn(buildNullIdRecoveryDiagnostics(
      rootNoteId, linesToSave, byParent, nullTrace,
      reconciledFromNull, reconciledFromSentinel,
    ))
  }
  if (reconciledFromNull > 0) {
    noteStore.raiseWarning(
      `Recovered ${reconciledFromNull} line ID(s) during save. Your content is ` +
      `safe, but an editor path may have dropped line identities — please ` +
      `double-check the note after saving.`,
    )
  }
  return result
}

function buildNullIdRecoveryDiagnostics(
  rootNoteId: string,
  linesToSave: NoteLine[],
  byParent: Map<string, Note[]>,
  nullTrace: Array<{
    lineIndex: number
    parentLineIndex: number
    parentId: string | null
    contentPreview: string
    recoveredId: string | null
  }>,
  reconciledFromNull: number,
  reconciledFromSentinel: number,
): string {
  const lines: string[] = []
  const unrecovered = nullTrace.filter(e => e.recoveredId === null).length
  lines.push('=== NULL NOTE-ID RECOVERY ===')
  lines.push(`rootNoteId: ${rootNoteId}`)
  lines.push(`linesToSave: ${linesToSave.length}`)
  lines.push(`null-id lines: ${nullTrace.length} (recovered=${reconciledFromNull}, unrecovered=${unrecovered})`)
  lines.push(`sentinel lines matched to existing docs: ${reconciledFromSentinel}`)

  lines.push('--- null-id line detail ---')
  for (const e of nullTrace) {
    const status = e.recoveredId ? `RECOVERED → ${e.recoveredId}` : 'UNMATCHED (will allocate fresh doc)'
    const parent = e.parentId ?? '<no parent id resolved>'
    lines.push(
      `  [${e.lineIndex}] parentLine=${e.parentLineIndex} parentId=${parent} ` +
      `content='${e.contentPreview}' → ${status}`,
    )
  }

  lines.push('--- linesToSave (full list) ---')
  for (let i = 0; i < linesToSave.length; i++) {
    const line = linesToSave[i]!
    const preview = line.content.slice(0, 60).replace(/\n/g, '\\n')
    let idLabel: string
    if (line.noteId == null) idLabel = 'null'
    else if (isSentinelNoteId(line.noteId)) idLabel = `sentinel=${line.noteId}`
    else idLabel = line.noteId
    lines.push(`  [${i}] noteId=${idLabel} content='${preview}'`)
  }

  lines.push('--- live descendants grouped by parent ---')
  if (byParent.size === 0) {
    lines.push(`  (no live descendants in NoteStore for root ${rootNoteId})`)
  } else {
    for (const [parentId, children] of byParent) {
      lines.push(`  parent=${parentId} (${children.length} unmatched candidate(s))`)
      for (const child of children) {
        const preview = child.content.slice(0, 60)
        lines.push(`    child=${child.id} content='${preview}'`)
      }
    }
  }

  lines.push('--- NoteStore state ---')
  const storeNote = noteStore.getNoteById(rootNoteId)
  if (storeNote) {
    lines.push(`  parentNoteId: ${storeNote.parentNoteId ?? 'null'}`)
    lines.push(`  rootNoteId: ${storeNote.rootNoteId ?? 'null'}`)
    lines.push(`  state: ${storeNote.state ?? 'active'}`)
    const containedNotes = storeNote.containedNotes ?? []
    lines.push(`  containedNotes: ${containedNotes.length} [${containedNotes.join(', ')}]`)
  } else {
    lines.push(`  NoteStore has NO entry for ${rootNoteId}`)
  }

  lines.push('--- Stack trace ---')
  const stack = new Error().stack?.split('\n').slice(2, 17).join('\n') ?? '(unavailable)'
  lines.push(stack)
  lines.push('=== END NULL NOTE-ID RECOVERY ===')
  return lines.join('\n')
}

/**
 * Two-phase line matching: exact content match first, then positional fallback.
 */
export function matchLinesToIds(
  parentNoteId: string,
  existingLines: NoteLine[],
  newLinesContent: string[],
  editorNoteIds?: (string | null)[],
): NoteLine[] {
  if (existingLines.length === 0 && !editorNoteIds) {
    return newLinesContent.map((content, index) => ({
      content,
      noteId: index === 0 ? parentNoteId : null,
    }))
  }

  const contentToOldIndices = new Map<string, number[]>()
  existingLines.forEach((line, index) => {
    const indices = contentToOldIndices.get(line.content)
    if (indices) {
      indices.push(index)
    } else {
      contentToOldIndices.set(line.content, [index])
    }
  })

  // Build set of noteIds known to this tree so we can detect foreign (reparented) IDs
  const existingNoteIdSet = new Set(existingLines.map(l => l.noteId).filter((id): id is string => id != null))

  const newIds: (string | null)[] = new Array(newLinesContent.length).fill(null) as (string | null)[]
  const oldConsumed = new Array(existingLines.length).fill(false) as boolean[]

  // Phase 0: Use editor-provided noteIds for foreign notes (reparented from another tree).
  // Only trust editor noteIds that aren't already in this tree's existing lines —
  // existing notes are matched more reliably by content in phases 1-3.
  if (editorNoteIds) {
    for (let i = 0; i < newLinesContent.length; i++) {
      const editorId = editorNoteIds[i]
      if (editorId && !existingNoteIdSet.has(editorId)) {
        newIds[i] = editorId
      }
    }
  }

  // Phase 1: Exact matches (skip lines already assigned by editor noteIds)
  newLinesContent.forEach((content, index) => {
    if (newIds[index] != null) return
    const indices = contentToOldIndices.get(content)
    if (indices && indices.length > 0) {
      const oldIdx = indices.shift()!
      newIds[index] = existingLines[oldIdx]!.noteId
      oldConsumed[oldIdx] = true
    }
  })

  // Phase 2: Similarity-based matching for modifications and splits.
  performSimilarityMatching(
    new Set(newLinesContent.map((_, i) => i).filter((i) => newIds[i] == null)),
    existingLines.map((_, i) => i).filter((i) => !oldConsumed[i]),
    (idx) => existingLines[idx]!.content,
    (idx) => newLinesContent[idx]!,
    (oldIdx, newIdx) => {
      newIds[newIdx] = existingLines[oldIdx]!.noteId
      oldConsumed[oldIdx] = true
    },
  )

  // Phase 3: Positional fallback for lines that changed too much for similarity matching.
  // Reaching this phase means similarity matching failed, which is a signal that
  // the caller passed a content string that diverges heavily from existingLines.
  const positionalIdx: number[] = []
  for (let i = 0; i < newLinesContent.length; i++) {
    if (newIds[i] == null && i < existingLines.length && !oldConsumed[i]) {
      newIds[i] = existingLines[i]!.noteId
      oldConsumed[i] = true
      positionalIdx.push(i)
    }
  }

  const stillNullIdx: number[] = []
  for (let i = 0; i < newLinesContent.length; i++) {
    if (newIds[i] == null && newLinesContent[i]!.length > 0) stillNullIdx.push(i)
  }

  if (positionalIdx.length > 0 || stillNullIdx.length > 0) {
    console.warn(buildMatchLinesToIdsDiagnostics(
      parentNoteId, existingLines, newLinesContent, editorNoteIds,
      positionalIdx.map(i => ({
        newIdx: i,
        oldIdx: i,
        content: newLinesContent[i]!,
        boundId: existingLines[i]!.noteId,
      })),
      stillNullIdx.map(i => ({ i, content: newLinesContent[i]! })),
    ))
  }

  const trackedLines = newLinesContent.map((content, index) => ({
    content,
    noteId: newIds[index] ?? null,
  }))

  if (trackedLines.length > 0 && trackedLines[0]!.noteId !== parentNoteId) {
    trackedLines[0] = { ...trackedLines[0]!, noteId: parentNoteId }
  }

  return trackedLines
}

function buildMatchLinesToIdsDiagnostics(
  parentNoteId: string,
  existingLines: NoteLine[],
  newLinesContent: string[],
  editorNoteIds: (string | null)[] | undefined,
  positionalBindings: Array<{ newIdx: number; oldIdx: number; content: string; boundId: string | null }>,
  stillNull: Array<{ i: number; content: string }>,
): string {
  const lines: string[] = []
  lines.push('=== MATCH LINES TO IDS (LOSSY FALLBACK) ===')
  lines.push(`parentNoteId: ${parentNoteId}`)
  lines.push(`existingLines: ${existingLines.length}`)
  lines.push(`newLines: ${newLinesContent.length}`)
  lines.push(`editorNoteIds provided: ${editorNoteIds != null} (${editorNoteIds?.length ?? 0})`)
  lines.push(`positional-fallback bindings: ${positionalBindings.length}`)
  lines.push(`unmatched non-empty lines: ${stillNull.length}`)

  lines.push('--- positional fallback detail ---')
  for (const b of positionalBindings.slice(0, 20)) {
    lines.push(`  new[${b.newIdx}] ↔ old[${b.oldIdx}] boundId=${b.boundId ?? 'null'} content='${b.content.slice(0, 60)}'`)
  }
  if (positionalBindings.length > 20) lines.push(`  ... (${positionalBindings.length - 20} more)`)

  lines.push('--- unmatched non-empty lines ---')
  for (const e of stillNull.slice(0, 20)) {
    lines.push(`  new[${e.i}] content='${e.content.slice(0, 60)}'`)
  }
  if (stillNull.length > 20) lines.push(`  ... (${stillNull.length - 20} more)`)

  lines.push('--- existingLines ---')
  for (let i = 0; i < Math.min(existingLines.length, 40); i++) {
    const line = existingLines[i]!
    const preview = line.content.slice(0, 60).replace(/\n/g, '\\n')
    lines.push(`  [${i}] noteId=${line.noteId ?? 'null'} content='${preview}'`)
  }
  if (existingLines.length > 40) lines.push(`  ... (${existingLines.length - 40} more)`)

  lines.push('--- Stack trace ---')
  lines.push(new Error().stack?.split('\n').slice(2, 17).join('\n') ?? '(unavailable)')
  lines.push('=== END MATCH LINES TO IDS ===')
  return lines.join('\n')
}
