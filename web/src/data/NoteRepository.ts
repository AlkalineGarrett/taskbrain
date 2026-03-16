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
import { flattenTreeToLines } from './NoteTree'

/**
 * Repository for managing composable notes in Firestore.
 *
 * Notes form a tree: parentNoteId points to immediate parent, rootNoteId enables
 * single-query loading of all descendants. Indentation is derived from tree depth
 * (no tabs stored in Firestore content).
 *
 * Old-format notes (flat children, tabs in content) are migrated lazily on save.
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
   * Loads a note and its descendants, returning a flat list of tab-prefixed NoteLines.
   */
  async loadNoteWithChildren(noteId: string): Promise<NoteLine[]> {
    return this.logged('loadNoteWithChildren', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))

      if (!docSnap.exists()) {
        return [{ content: '', noteId }]
      }

      const note = noteFromFirestore(docSnap.id, docSnap.data())
      const allLines = await this.loadNoteLines(note)

      // Append empty line for typing, unless note is a single empty line
      if (allLines.length === 1 && allLines[0]!.content === '') {
        return allLines
      }
      return [...allLines, { content: '', noteId: null }]
    })
  }

  /**
   * Loads note lines using tree query (new format) or individual reads (old format).
   */
  private async loadNoteLines(note: Note): Promise<NoteLine[]> {
    // Try tree query first
    const userId = this.requireUserId()
    const descendantQuery = query(this.notesRef, where('rootNoteId', '==', note.id), where('userId', '==', userId))
    const descendantSnap = await getDocs(descendantQuery)

    const descendants = descendantSnap.docs
      .map((d) => noteFromFirestore(d.id, d.data()))
      .filter((n) => n.state !== 'deleted')

    if (descendants.length > 0) {
      return flattenTreeToLines(note, descendants)
    }

    // Old format or no children
    if (note.containedNotes.length === 0) {
      return [{ content: note.content, noteId: note.id }]
    }

    // Old format: load children individually
    const parentLine: NoteLine = { content: note.content, noteId: note.id }
    const childLines = await this.loadOldFormatChildren(note.containedNotes)
    return [parentLine, ...childLines]
  }

  private async loadOldFormatChildren(childIds: string[]): Promise<NoteLine[]> {
    return Promise.all(childIds.map((id) => this.loadOldFormatChild(id)))
  }

  private async loadOldFormatChild(childId: string): Promise<NoteLine> {
    if (childId === '') return { content: '', noteId: null }

    try {
      const childDoc = await getDoc(this.noteRef(childId))
      if (childDoc.exists()) {
        const content = (childDoc.data().content as string) ?? ''
        return { content, noteId: childId }
      }
      return { content: '', noteId: null }
    } catch {
      return { content: '', noteId: null }
    }
  }

  /**
   * Loads all top-level notes with full content reconstructed.
   */
  async loadNotesWithFullContent(): Promise<Note[]> {
    return this.logged('loadNotesWithFullContent', async () => {
      const userId = this.requireUserId()
      const q = query(this.notesRef, where('userId', '==', userId))
      const snapshot = await getDocs(q)

      const allNotes = snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => n.state !== 'deleted')

      const notesById = new Map(allNotes.map((n) => [n.id, n]))
      const topLevelNotes = allNotes.filter((n) => n.parentNoteId == null)
      const descendantsByRoot = new Map<string, Note[]>()
      for (const n of allNotes) {
        if (n.rootNoteId != null) {
          const list = descendantsByRoot.get(n.rootNoteId)
          if (list) list.push(n)
          else descendantsByRoot.set(n.rootNoteId, [n])
        }
      }

      return topLevelNotes.map((note) =>
        this.reconstructNoteContent(note, notesById, descendantsByRoot.get(note.id)),
      )
    })
  }

  private reconstructNoteContent(
    note: Note,
    allNotesById: Map<string, Note>,
    treeDescendants: Note[] | undefined,
  ): Note {
    if (note.containedNotes.length === 0) return note

    if (treeDescendants) {
      const lines = flattenTreeToLines(note, treeDescendants)
      return { ...note, content: lines.map((l) => l.content).join('\n') }
    }

    // Old format
    const parts = [note.content]
    for (const childId of note.containedNotes) {
      if (childId !== '') {
        parts.push(allNotesById.get(childId)?.content ?? '')
      } else {
        parts.push('')
      }
    }
    return { ...note, content: parts.join('\n') }
  }

  async loadUserNotes(): Promise<Note[]> {
    return this.logged('loadUserNotes', async () => {
      const userId = this.requireUserId()
      const q = query(this.notesRef, where('userId', '==', userId))
      const snapshot = await getDocs(q)

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

      return snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => n.parentNoteId == null)
    })
  }

  async loadNoteById(noteId: string): Promise<Note | null> {
    return this.logged('loadNoteById', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))
      if (!docSnap.exists()) return null
      return noteFromFirestore(docSnap.id, docSnap.data())
    })
  }

  async isNoteDeleted(noteId: string): Promise<boolean> {
    return this.logged('isNoteDeleted', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))
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

      const userId = this.requireUserId()
      const parentRef = this.noteRef(noteId)
      const rootContent = trackedLines[0]!.content.replace(/^\t+/, '')

      // Drop trailing empty lines (editor's typing line)
      const childPortion = dropLastWhile(trackedLines.slice(1), (l) => l.content === '')
      const linesToSave = [trackedLines[0]!, ...childPortion]

      // Pre-allocate refs for new notes
      const newRefs = new Map<number, DocumentReference>()
      for (let i = 1; i < linesToSave.length; i++) {
        const content = linesToSave[i]!.content.replace(/^\t+/, '')
        if (linesToSave[i]!.noteId == null && content !== '') {
          newRefs.set(i, doc(this.notesRef))
        }
      }

      function effectiveId(lineIndex: number): string {
        if (lineIndex === 0) return noteId
        if (linesToSave[lineIndex]!.noteId != null) return linesToSave[lineIndex]!.noteId!
        return newRefs.get(lineIndex)?.id ?? ''
      }

      // Compute tree structure from indentation
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

        if (content === '') {
          childrenOfLine[parentOfLine[i]!]!.push('')
        } else {
          childrenOfLine[parentOfLine[i]!]!.push(effectiveId(i))
          stack.push({ depth, lineIndex: i })
        }
      }

      // Fetch existing descendants for deletion tracking
      const existingDescendantIds = await this.fetchExistingDescendantIds(noteId)

      return runTransaction(this.db, async (transaction) => {
        const survivingIds = new Set<string>()

        // Update root
        transaction.set(
          parentRef,
          { ...this.baseNoteData(userId, rootContent), containedNotes: childrenOfLine[0]! },
          { merge: true },
        )

        // Write each descendant
        for (let i = 1; i < linesToSave.length; i++) {
          const content = linesToSave[i]!.content.replace(/^\t+/, '')
          if (content === '') continue // spacer

          const id = effectiveId(i)
          const parentId = effectiveId(parentOfLine[i]!)
          survivingIds.add(id)

          if (linesToSave[i]!.noteId != null) {
            transaction.set(
              this.noteRef(id),
              {
                content,
                parentNoteId: parentId,
                rootNoteId: noteId,
                containedNotes: childrenOfLine[i]!,
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

        // Soft-delete removed notes
        for (const id of existingDescendantIds) {
          if (!survivingIds.has(id)) {
            transaction.update(this.noteRef(id), {
              state: 'deleted',
              updatedAt: serverTimestamp(),
            })
          }
        }

        const createdIds = new Map<number, string>()
        for (const [lineIndex, ref] of newRefs) {
          createdIds.set(lineIndex, ref.id)
        }
        return createdIds
      })
    })
  }

  private async fetchExistingDescendantIds(noteId: string): Promise<Set<string>> {
    const userId = this.requireUserId()
    const descendantQuery = query(this.notesRef, where('rootNoteId', '==', noteId), where('userId', '==', userId))
    const descendantSnap = await getDocs(descendantQuery)

    if (descendantSnap.docs.length > 0) {
      return new Set(
        descendantSnap.docs
          .filter((d) => d.data().state !== 'deleted')
          .map((d) => d.id),
      )
    }

    // Old format fallback
    const rootDoc = await getDoc(this.noteRef(noteId))
    if (!rootDoc.exists()) return new Set()
    const containedNotes = (rootDoc.data().containedNotes as string[]) ?? []
    return new Set(containedNotes.filter((id) => id !== ''))
  }

  /**
   * Saves a note with full multi-line content.
   * Used for inline editing of notes within view directives.
   */
  async saveNoteWithFullContent(noteId: string, newContent: string): Promise<void> {
    return this.logged('saveNoteWithFullContent', async () => {
      this.requireUserId()

      // Load existing structure (tree-aware)
      const existingLines = await this.loadNoteWithChildren(noteId)
      const existingLinesNoTrailing =
        existingLines.length > 1 && existingLines[existingLines.length - 1]!.content === ''
          ? existingLines.slice(0, -1)
          : existingLines

      const newLinesContent = newContent.split('\n')
      const trackedLines = matchLinesToIds(noteId, existingLinesNoTrailing, newLinesContent)
      await this.saveNoteWithChildren(noteId, trackedLines)
    })
  }

  async createNote(): Promise<string> {
    return this.logged('createNote', async () => {
      const userId = this.requireUserId()
      const { addDoc } = await import('firebase/firestore')
      const ref = await addDoc(this.notesRef, this.newNoteData(userId, ''))
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

        if (lineContent === '') {
          parent.children.push('')
        } else {
          const ref = doc(this.notesRef)
          const nodeChildren: string[] = []
          nodes.push({ ref, content: lineContent, parentId: parent.id, children: nodeChildren })
          parent.children.push(ref.id)
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
      return parentRef.id
    })
  }

  // ── Delete/restore operations ───────────────────────────────────────

  /**
   * Soft-deletes a note and all its descendants.
   */
  async softDeleteNote(noteId: string): Promise<void> {
    return this.logged('softDeleteNote', async () => {
      const userId = this.requireUserId()
      const idsToDelete = new Set([noteId])

      // New-format descendants
      const descendantQuery = query(this.notesRef, where('rootNoteId', '==', noteId), where('userId', '==', userId))
      const descendantSnap = await getDocs(descendantQuery)
      for (const d of descendantSnap.docs) {
        idsToDelete.add(d.id)
      }

      // Old-format fallback
      if (descendantSnap.empty) {
        const rootDoc = await getDoc(this.noteRef(noteId))
        if (rootDoc.exists()) {
          const containedNotes = (rootDoc.data().containedNotes as string[]) ?? []
          for (const childId of containedNotes) {
            if (childId !== '') idsToDelete.add(childId)
          }
        }
      }

      const batch = writeBatch(this.db)
      for (const id of idsToDelete) {
        batch.update(this.noteRef(id), {
          state: 'deleted',
          updatedAt: serverTimestamp(),
        })
      }
      await batch.commit()
    })
  }

  /**
   * Restores a deleted note and all its descendants.
   */
  async undeleteNote(noteId: string): Promise<void> {
    return this.logged('undeleteNote', async () => {
      const userId = this.requireUserId()

      const idsToRestore = new Set([noteId])

      const descendantQuery = query(this.notesRef, where('rootNoteId', '==', noteId), where('userId', '==', userId))
      const descendantSnap = await getDocs(descendantQuery)
      for (const d of descendantSnap.docs) {
        idsToRestore.add(d.id)
      }

      // Old-format fallback
      if (descendantSnap.empty) {
        const rootDoc = await getDoc(this.noteRef(noteId))
        if (rootDoc.exists()) {
          const containedNotes = (rootDoc.data().containedNotes as string[]) ?? []
          for (const childId of containedNotes) {
            if (childId !== '') idsToRestore.add(childId)
          }
        }
      }

      const batch = writeBatch(this.db)
      for (const id of idsToRestore) {
        batch.update(this.noteRef(id), {
          state: null,
          updatedAt: serverTimestamp(),
        })
      }
      await batch.commit()
    })
  }

  // ── Utility operations ──────────────────────────────────────────────

  async updateLastAccessed(noteId: string): Promise<void> {
    return this.logged('updateLastAccessed', async () => {
      this.requireUserId()
      await updateDoc(this.noteRef(noteId), {
        lastAccessedAt: serverTimestamp(),
      })
    })
  }
}

// --- Utility functions ---

function dropLastWhile<T>(arr: T[], predicate: (item: T) => boolean): T[] {
  let end = arr.length
  while (end > 0 && predicate(arr[end - 1]!)) {
    end--
  }
  return arr.slice(0, end)
}

/**
 * Two-phase line matching: exact content match first, then positional fallback.
 */
export function matchLinesToIds(
  parentNoteId: string,
  existingLines: NoteLine[],
  newLinesContent: string[],
): NoteLine[] {
  if (existingLines.length === 0) {
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

  const newIds: (string | null)[] = new Array(newLinesContent.length).fill(null) as (string | null)[]
  const oldConsumed = new Array(existingLines.length).fill(false) as boolean[]

  // Phase 1: Exact matches
  newLinesContent.forEach((content, index) => {
    const indices = contentToOldIndices.get(content)
    if (indices && indices.length > 0) {
      const oldIdx = indices.shift()!
      newIds[index] = existingLines[oldIdx]!.noteId
      oldConsumed[oldIdx] = true
    }
  })

  // Phase 2: Positional matches
  newLinesContent.forEach((_, index) => {
    if (newIds[index] == null) {
      if (index < existingLines.length && !oldConsumed[index]) {
        newIds[index] = existingLines[index]!.noteId
        oldConsumed[index] = true
      }
    }
  })

  const trackedLines = newLinesContent.map((content, index) => ({
    content,
    noteId: newIds[index] ?? null,
  }))

  if (trackedLines.length > 0 && trackedLines[0]!.noteId !== parentNoteId) {
    trackedLines[0] = { ...trackedLines[0]!, noteId: parentNoteId }
  }

  return trackedLines
}
