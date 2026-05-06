import {
  addDoc,
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  where,
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
import { isRealNoteId, newSentinelNoteId } from './NoteIdSentinel'
import { isLive, NoteState } from './NoteState'
import { performSimilarityMatching } from '@/editor/ContentSimilarity'

export interface NoteLoadResult {
  lines: NoteLine[]
  isDeleted: boolean
  showCompleted: boolean
}

interface BatchWriteOp {
  ref: DocumentReference
  data: Record<string, unknown>
  merge: boolean
}

interface SavePlan {
  noteId: string
  ops: BatchWriteOp[]
  createdIds: Map<number, string>
  /** Doc ids the plan keeps alive: local survivors + concurrent-subtree
   *  preservation. Used by [NoteRepository.buildCutDeleteOps] to decide
   *  whether a pendingCut is being revived in this batch. */
  survivingIds: Set<string>
  /** Post-write `containedNotes` for every saved id (root + each
   *  descendant in the plan), keyed by the post-allocate effective id.
   *  The editor refreshes its `localBases` from this so the next save's
   *  3-way merge has an accurate base — independent of the Firestore
   *  listener echo, which races with applyResult and can leave
   *  `rawNotes[noteId].containedNotes` stale for a brief window after a
   *  newly-created note's first save. */
  postSaveContainedNotes: Map<string, string[]>
}

/**
 * Outcome of a successful save: the line-index → newly-allocated-id
 * map [createdIds], plus [postSaveContainedNotes] (see [SavePlan])
 * which the editor uses to refresh its localBases without going
 * through the listener cache.
 */
export interface SaveResult {
  createdIds: Map<number, string>
  postSaveContainedNotes: Map<string, string[]>
}

/**
 * Per-batch context shared by every [planSave] in a single
 * `saveNoteWithChildren` or `saveMultipleNotes` invocation. Bundling these
 * together keeps the planner signatures from sprawling and ensures every plan
 * in the batch sees the same userId / cut-buffer snapshot.
 */
interface SaveContext {
  userId: string
  pendingCuts: Map<string, string>
  /** Real noteIds claimed by ANY session in the same `saveMultipleNotes`
   *  batch. A line moved cross-note via paste-with-tryReclaim consumes its
   *  pendingCut entry at paste time, so by save time the cut buffer is
   *  empty — the source's planSave would otherwise add the line to its
   *  toDelete (because it's no longer in source's trackedLines and the
   *  buffer can't tell us it's being kept by another session). Excluding
   *  this set from toDelete prevents the soft-delete from racing against
   *  the destination's reparent write in the same batch. Empty for
   *  single-note saves where cross-session coordination doesn't apply. */
  globalSurvivingIds: Set<string>
}

/**
 * One unit of work for [NoteRepository.saveMultipleNotes]: the note id, the
 * editor's tracked lines, and the `containedNotes` snapshots captured at
 * edit-session start (root + every live descendant, keyed by id). The
 * snapshots anchor a 3-way merge in [planSave] at every depth so concurrent
 * edits from other clients aren't silently overwritten. Null on legacy
 * paths (e.g. RecoverScreen) — planSave then writes through without merging.
 */
export interface SaveItem {
  noteId: string
  trackedLines: NoteLine[]
  localBases: Map<string, string[]> | null
}

/** Cap for waiting on the listener to surface a specific note. */
const NOTE_STORE_AWAIT_MS = 1500
const MAX_BATCH_SIZE = 500

/**
 * 3-way merge of `containedNotes` ID lists. Returns:
 *   (local ∪ remote_added) − remote_removed
 * where remote_added = remote − base and remote_removed = base − remote.
 *
 * Behavior:
 * - [base] is null (legacy / no session tracking): returns [local] unchanged.
 * - [base] equals [remote] (no concurrent edit): returns [local].
 * - Otherwise: keeps everything in [local], appends remote-only additions in
 *   their relative remote order at the end, and drops items removed by remote
 *   even if local still had them.
 */
export function mergeContainedNotes(
  local: string[],
  base: string[] | null,
  remote: string[],
): string[] {
  if (base == null) return local
  // Fast path: no concurrent edit since base was captured. Skip the Set
  // construction — this is the dominant case in practice.
  if (stringArraysEqual(base, remote)) return local

  const baseSet = new Set(base)
  const remoteSet = new Set(remote)
  const localSet = new Set(local)

  const remoteRemoved = new Set<string>()
  for (const id of base) if (!remoteSet.has(id)) remoteRemoved.add(id)

  const result: string[] = []
  for (const id of local) {
    if (!remoteRemoved.has(id)) result.push(id)
  }
  for (const id of remote) {
    if (!baseSet.has(id) && !localSet.has(id)) result.push(id)
  }
  return result
}

function stringArraysEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false
  return true
}

/**
 * IDs of every doc whose ancestor chain reaches a `containedNotes` entry
 * added by a concurrent client at any anchored parent in [bases] — i.e. an
 * item present in that parent's remote `containedNotes` but not in its
 * recorded base. Used to extend [survivingIds] in [planSave] so the
 * soft-delete pass doesn't wipe subtrees a different client just created.
 *
 * The BFS walks within [existingDescendantIds] so survivors are bounded to
 * the current subtree. Returns an empty set when [bases] is null/empty.
 */
function findConcurrentSubtree(
  bases: Map<string, string[]> | null,
  existingDescendantIds: Set<string>,
  getNote: (id: string) => Note | undefined,
): Set<string> {
  if (bases == null || bases.size === 0) return new Set()
  const result = new Set<string>()
  const queue: string[] = []
  for (const [parentId, base] of bases) {
    const remote = getNote(parentId)?.containedNotes ?? []
    if (remote.length === 0) continue
    const baseSet = new Set(base)
    for (const id of remote) {
      if (!baseSet.has(id) && existingDescendantIds.has(id) && !result.has(id)) {
        result.add(id)
        queue.push(id)
      }
    }
  }
  if (queue.length === 0) return result

  // Build a parent → children index over the existing descendants once.
  const childrenByParent = new Map<string, string[]>()
  for (const id of existingDescendantIds) {
    const parent = getNote(id)?.parentNoteId
    if (parent == null) continue
    const list = childrenByParent.get(parent)
    if (list) list.push(id)
    else childrenByParent.set(parent, [id])
  }
  while (queue.length > 0) {
    const id = queue.shift()!
    for (const child of childrenByParent.get(id) ?? []) {
      if (!result.has(child)) {
        result.add(child)
        queue.push(child)
      }
    }
  }
  return result
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
   *
   * On cold start the snapshot listener may not have delivered its first
   * cached snapshot yet. Wait briefly so the in-memory path can serve this
   * load instead of issuing a parallel server fetch for data the listener
   * is about to deliver from the IndexedDB persistent cache.
   */
  /**
   * Editor session-init entry point. Awaits the listener for [noteId]; on
   * timeout, falls back to a one-shot Firestore read. Throws on hard
   * failure. Replaces the deleted synth-on-miss path so sessions only
   * ever start from structurally-valid lines.
   */
  async loadNoteLinesAwait(noteId: string): Promise<NoteLine[]> {
    return this.logged('loadNoteLinesAwait', async () => {
      if (await noteStore.awaitNoteLoaded(noteId)) {
        const lines = noteStore.getNoteLinesById(noteId)
        if (!lines) {
          throw new Error(
            `NoteStore awaitNoteLoaded(${noteId}) returned true but getNoteLinesById was null`,
          )
        }
        return lines
      }
      console.warn(
        `awaitNoteLoaded(${noteId}) timed out — falling back to Firestore read`,
      )
      return (await this.loadNoteWithChildren(noteId)).lines
    })
  }

  async loadNoteWithChildren(noteId: string): Promise<NoteLoadResult> {
    return this.logged('loadNoteWithChildren', async () => {
      this.requireUserId()

      if (!noteStore.isLoaded()) {
        let timer: ReturnType<typeof setTimeout> | undefined
        await Promise.race([
          noteStore.ensureLoaded().finally(() => clearTimeout(timer)),
          new Promise<void>((resolve) => { timer = setTimeout(resolve, NOTE_STORE_AWAIT_MS) }),
        ])
      }
      if (noteStore.isLoaded()) {
        const rawNote = noteStore.getRawNoteById(noteId)
        const storeLines = rawNote ? noteStore.getNoteLinesById(noteId) : undefined
        if (rawNote && storeLines) {
          return {
            lines: storeLines,
            isDeleted: rawNote.state === NoteState.DELETED,
            showCompleted: rawNote.showCompleted ?? true,
          }
        }
      }

      const docSnap = await getDoc(this.noteRef(noteId))
      firestoreUsage.recordRead('loadNoteWithChildren', 'DOC_GET')

      if (!docSnap.exists()) {
        return { lines: [{ content: '', noteId }], isDeleted: false, showCompleted: true }
      }

      const note = noteFromFirestore(docSnap.id, docSnap.data())
      const allLines = await this.loadNoteLines(note)

      return {
        lines: allLines,
        isDeleted: note.state === NoteState.DELETED,
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
    firestoreUsage.recordRead('loadNoteLines', 'GET_DOCS', descendantSnap.size)

    const descendants = descendantSnap.docs
      .map((d) => noteFromFirestore(d.id, d.data()))
      .filter((n) => isLive(n.state))

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
      firestoreUsage.recordRead('loadNotesWithFullContent', 'GET_DOCS', snapshot.size)

      const allNotes = snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => isLive(n.state))

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
      firestoreUsage.recordRead('loadUserNotes', 'GET_DOCS', snapshot.size)

      return snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => n.parentNoteId == null && isLive(n.state))
    })
  }

  async loadAllUserNotes(): Promise<Note[]> {
    return this.logged('loadAllUserNotes', async () => {
      const userId = this.requireUserId()
      const q = query(this.notesRef, where('userId', '==', userId))
      const snapshot = await getDocs(q)
      firestoreUsage.recordRead('loadAllUserNotes', 'GET_DOCS', snapshot.size)

      return snapshot.docs
        .map((d) => noteFromFirestore(d.id, d.data()))
        .filter((n) => n.parentNoteId == null)
    })
  }

  async loadNoteById(noteId: string): Promise<Note | null> {
    return this.logged('loadNoteById', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))
      firestoreUsage.recordRead('loadNoteById', 'DOC_GET')
      if (!docSnap.exists()) return null
      return noteFromFirestore(docSnap.id, docSnap.data())
    })
  }

  async isNoteDeleted(noteId: string): Promise<boolean> {
    return this.logged('isNoteDeleted', async () => {
      this.requireUserId()
      const docSnap = await getDoc(this.noteRef(noteId))
      firestoreUsage.recordRead('isNoteDeleted', 'DOC_GET')
      if (!docSnap.exists()) return false
      return docSnap.data().state === NoteState.DELETED
    })
  }

  // ── Save operations ─────────────────────────────────────────────────

  /**
   * Saves a note with tree structure derived from tab-indented lines.
   * Returns a map of line indices to newly created note IDs.
   *
   * [localBases] is the `containedNotes` snapshot the editor captured at
   * edit-session start (root + every live descendant, keyed by id), used by
   * [planSave] for a 3-way merge at every depth so concurrent edits from
   * other clients aren't silently overwritten. Pass `null` only at startup
   * paths that have no edit-session anchor (no merges happen).
   */
  async saveNoteWithChildren(
    noteId: string,
    trackedLines: NoteLine[],
    localBases: Map<string, string[]> | null,
  ): Promise<SaveResult> {
    return this.logged('saveNoteWithChildren', async () => {
      if (trackedLines.length === 0) {
        return { createdIds: new Map(), postSaveContainedNotes: new Map() }
      }
      const userId = this.requireUserId()
      // Single-note save: no cross-session reparent coordination needed.
      const ctx: SaveContext = {
        userId,
        pendingCuts: noteStore.getPendingCuts(),
        globalSurvivingIds: new Set(),
      }
      const plan = this.planSave({ noteId, trackedLines, localBases }, ctx)
      const cutDeleteOps = this.buildCutDeleteOps([plan.survivingIds], ctx)
      await this.commitInBatches('saveNoteWithChildren', [...plan.ops, ...cutDeleteOps.ops])
      for (const id of cutDeleteOps.committedCutIds) noteStore.clearPendingCut(id)
      return {
        createdIds: plan.createdIds,
        postSaveContainedNotes: plan.postSaveContainedNotes,
      }
    })
  }

  /**
   * Saves multiple notes atomically as a single Firestore batch (chunked at
   * 500 ops). Use when more than one note has unsaved edits — e.g. main
   * editor + dirty inline view-directive sessions — so partial commits can't
   * leave the user with stale content in some notes and saved content in
   * others. Returns per-noteId line-index→new-id maps.
   */
  async saveMultipleNotes(
    items: SaveItem[],
  ): Promise<Map<string, SaveResult>> {
    return this.logged('saveMultipleNotes', async () => {
      if (items.length === 0) return new Map()
      const userId = this.requireUserId()
      // Pre-compute the union of real noteIds claimed across every session's
      // trackedLines so each session's planSave can exclude them from
      // toDelete — see SaveContext.globalSurvivingIds for the race this
      // prevents.
      const globalSurvivingIds = new Set<string>()
      for (const item of items) {
        for (const line of item.trackedLines) {
          if (isRealNoteId(line.noteId)) globalSurvivingIds.add(line.noteId!)
        }
      }
      const ctx: SaveContext = {
        userId,
        pendingCuts: noteStore.getPendingCuts(),
        globalSurvivingIds,
      }
      const plans = items
        .filter((item) => item.trackedLines.length > 0)
        .map((item) => this.planSave(item, ctx))
      const cutDeleteOps = this.buildCutDeleteOps(plans.map((p) => p.survivingIds), ctx)
      const allOps = plans.flatMap((p) => p.ops).concat(cutDeleteOps.ops)
      await this.commitInBatches('saveMultipleNotes', allOps)
      for (const id of cutDeleteOps.committedCutIds) noteStore.clearPendingCut(id)
      const result = new Map<string, SaveResult>()
      for (const plan of plans) {
        result.set(plan.noteId, {
          createdIds: plan.createdIds,
          postSaveContainedNotes: plan.postSaveContainedNotes,
        })
      }
      return result
    })
  }

  /**
   * For each pendingCut whose lineId isn't a survivor in any of [survivingIdSets]
   * (i.e., not being revived as a child of some destination note in this batch),
   * append a `state='cut-delete'` write so the line is parked rather than left
   * orphaned in its old parent's tree. Returns every input id in
   * [committedCutIds] — including ids whose underlying doc has vanished from
   * NoteStore (where [buildStateChangeOps] silently skipped the write) — so
   * the cut buffer is fully drained on commit and doesn't leak phantoms.
   */
  private buildCutDeleteOps(
    survivingIdSets: Set<string>[],
    ctx: SaveContext,
  ): { ops: BatchWriteOp[]; committedCutIds: string[] } {
    // Partition pendingCuts: ids being revived in this batch (skip the
    // cut-delete write but still clear from buffer) vs ids needing parking.
    const idsToPark: string[] = []
    const committedCutIds: string[] = []
    for (const [lineId] of ctx.pendingCuts) {
      committedCutIds.push(lineId)
      const reviving = survivingIdSets.some((s) => s.has(lineId))
      // The destination's planSave writes state=null for revived ids; we
      // only need to schedule the cut-delete write for the rest.
      if (!reviving) idsToPark.push(lineId)
    }
    const ops = this.buildStateChangeOps(idsToPark, NoteState.CUT_DELETE)
    return { ops, committedCutIds }
  }

  /**
   * Builds the writes for a single-note save without committing them. Shared
   * by [saveNoteWithChildren] (single note) and [saveMultipleNotes] (combined
   * batch). Assertions throw inline so a bad item in a multi-note save aborts
   * the whole batch before any commit.
   *
   * [item.localBases], when non-null, is the `containedNotes` snapshot the
   * editor observed at edit-session start (root + every live descendant);
   * used to 3-way merge against the current remote at every depth so
   * concurrent edits from other clients aren't silently overwritten.
   * [ctx.pendingCuts] is the editor's cut buffer; planSave excludes its
   * keys from soft-delete so cross-note moves aren't lost when this note's
   * save runs ahead of the destination's.
   */
  private planSave(
    item: SaveItem,
    ctx: SaveContext,
  ): SavePlan {
    const { userId, pendingCuts } = ctx
    const { noteId, trackedLines, localBases } = item
    this.assertNoteStoreLoaded('saveNoteWithChildren', noteId)
    this.warnIfDescendantsLikelyStale('saveNoteWithChildren', noteId)

    const parentRef = this.noteRef(noteId)
    const rootContent = trackedLines[0]!.content.replace(/^\t+/, '')
    const linesToSave = trackedLines

    // Pre-allocate refs for sentinel lines — they mark "new doc, needs
    // allocation" (typed/paste/split). Real ids refer to existing docs.
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
    // before any batch work; matches the Android save path.
    const survivingIds = new Set<string>()
    for (let i = 1; i < linesToSave.length; i++) {
      survivingIds.add(effectiveId(i))
    }
    // Subtrees a concurrent client added — at any depth — since the editor
    // captured [localBases] aren't represented in our trackedLines. Without
    // this, the soft-delete pass would wipe their work.
    for (const id of findConcurrentSubtree(localBases, existingDescendantIds, (n) => noteStore.getRawNoteById(n))) {
      survivingIds.add(id)
    }
    // Cut lines awaiting paste in this session aren't local survivors here,
    // but their destination's planSave (or saveMultipleNotes' cut-delete pass)
    // handles them — exclude from toDelete so this save doesn't soft-delete
    // a line that's being moved cross-note. globalSurvivingIds covers the
    // case where the cut buffer was already drained by tryReclaim at paste
    // time but the destination still claims the line in this batch.
    const toDelete = new Set<string>()
    for (const id of existingDescendantIds) {
      if (survivingIds.has(id)) continue
      if (pendingCuts.has(id)) continue
      if (ctx.globalSurvivingIds.has(id)) continue
      toDelete.add(id)
    }

    this.assertNotContentDrop(noteId, trackedLines, linesToSave, existingDescendantIds, survivingIds, toDelete)

    // Fix parent cycles: if this note's parent chain loops back
    // to itself, clear parentNoteId/rootNoteId to make it a root note.
    const hasCycle = this.hasParentCycle(noteId)

    // Pre-compute skips: any merge write whose payload already matches
    // in-memory state would be a no-op write. Skipping it saves the doc
    // write AND the listener echo/fresh reads it would trigger across
    // all clients listening to the notes collection.
    // 3-way merge of `containedNotes` runs uniformly at the root and every
    // real-id descendant: combine our local children list with the doc's
    // remote `containedNotes` using its base from edit-session start. Picks
    // up concurrent additions by other clients and respects their removals.
    // Sentinel (new) lines have no remote — fall through to the local list.
    const mergedContained: string[][] = childrenOfLine.map((local, i) => {
      if (i > 0 && !isRealNoteId(linesToSave[i]!.noteId)) return local
      const id = effectiveId(i)
      const base = localBases?.get(id) ?? null
      const remote = noteStore.getRawNoteById(id)?.containedNotes ?? []
      return mergeContainedNotes(local, base, remote)
    })
    const existingRoot = noteStore.getRawNoteById(noteId)
    const rootUnchanged =
      !hasCycle &&
      existingRoot != null &&
      existingRoot.content === rootContent &&
      stringArraysEqual(existingRoot.containedNotes, mergedContained[0]!) &&
      existingRoot.state == null
    const descendantSkipped = new Set<number>()
    for (let i = 1; i < linesToSave.length; i++) {
      if (!isRealNoteId(linesToSave[i]!.noteId)) continue
      const existing = noteStore.getRawNoteById(effectiveId(i))
      const newContent = linesToSave[i]!.content.replace(/^\t+/, '')
      const newParentId = effectiveId(parentOfLine[i]!)
      if (
        existing != null &&
        existing.content === newContent &&
        existing.parentNoteId === newParentId &&
        existing.rootNoteId === noteId &&
        stringArraysEqual(existing.containedNotes, mergedContained[i]!) &&
        existing.state == null
      ) {
        descendantSkipped.add(i)
      }
    }
    const skippedUnchanged = (rootUnchanged ? 1 : 0) + descendantSkipped.size
    if (skippedUnchanged > 0) {
      const writeCount =
        (rootUnchanged ? 0 : 1) +
        (linesToSave.length - 1 - descendantSkipped.size) +
        toDelete.size
      console.log(
        `saveNoteWithChildren(${noteId}): skipped ${skippedUnchanged} unchanged docs of ${linesToSave.length}, writing ${writeCount}`,
      )
    }
    if (typeof localStorage !== 'undefined' && localStorage.getItem('taskbrainDebugSaves') === '1') {
      const lineDump = linesToSave.map((l, i) => {
        const eid = effectiveId(i)
        const skipped = i === 0 ? rootUnchanged : descendantSkipped.has(i)
        return `  [${i}] eid=${eid} content=${JSON.stringify(l.content.slice(0, 40))} ` +
          `parentLine=${parentOfLine[i]} children=[${childrenOfLine[i]!.join(',')}] skip=${skipped}`
      }).join('\n')
      const existingContained = existingDescendantIds.size > 0
        ? Array.from(existingDescendantIds).slice(0, 20).join(',') + (existingDescendantIds.size > 20 ? `+${existingDescendantIds.size - 20}` : '')
        : '(none)'
      console.log(
        `[debugSave] saveNoteWithChildren(${noteId})\n` +
        lineDump + '\n' +
        `  existingDescendants=[${existingContained}]\n` +
        `  toDelete=[${Array.from(toDelete).join(',')}]`
      )
    }

    const ops: BatchWriteOp[] = []

    if (!rootUnchanged) {
      const rootBase = localBases?.get(noteId) ?? null
      const rootData: Record<string, unknown> = {
        ...this.baseNoteData(userId, rootContent),
        containedNotes: mergedContained[0]!,
      }
      // Stamp containedNotesBase only when an anchor was recorded — skipping
      // when null avoids writing a tautological field on legacy paths that
      // don't track an edit-session anchor.
      if (rootBase != null) rootData.containedNotesBase = rootBase
      if (hasCycle) {
        rootData.parentNoteId = null
        rootData.rootNoteId = null
      }
      ops.push({ ref: parentRef, data: rootData, merge: true })
    }

    for (let i = 1; i < linesToSave.length; i++) {
      if (descendantSkipped.has(i)) continue
      const content = linesToSave[i]!.content.replace(/^\t+/, '')
      const id = effectiveId(i)
      const parentId = effectiveId(parentOfLine[i]!)

      if (isRealNoteId(linesToSave[i]!.noteId)) {
        const base = localBases?.get(id) ?? null
        const data: Record<string, unknown> = {
          content,
          parentNoteId: parentId,
          rootNoteId: noteId,
          containedNotes: mergedContained[i]!,
          state: null, // Clear deleted state for reparented notes
          updatedAt: serverTimestamp(),
        }
        if (base != null) data.containedNotesBase = base
        ops.push({ ref: this.noteRef(id), data, merge: true })
      } else {
        ops.push({
          ref: newRefs.get(i)!,
          data: {
            userId,
            content,
            parentNoteId: parentId,
            rootNoteId: noteId,
            containedNotes: mergedContained[i]!,
            createdAt: serverTimestamp(),
            updatedAt: serverTimestamp(),
          },
          merge: false,
        })
      }
    }

    // Soft-delete removed notes. Preserve parentNoteId/rootNoteId so the
    // deleted-notes view can distinguish removed child lines (have a parent)
    // from deleted top-level notes (don't).
    for (const id of toDelete) {
      ops.push({
        ref: this.noteRef(id),
        data: {
          state: NoteState.DELETED,
          updatedAt: serverTimestamp(),
        },
        merge: true,
      })
    }

    const createdIds = new Map<number, string>()
    for (const [lineIndex, ref] of newRefs) {
      createdIds.set(lineIndex, ref.id)
    }
    const postSaveContainedNotes = new Map<string, string[]>()
    postSaveContainedNotes.set(noteId, mergedContained[0]!)
    for (let i = 1; i < linesToSave.length; i++) {
      postSaveContainedNotes.set(effectiveId(i), mergedContained[i]!)
    }
    return { noteId, ops, createdIds, survivingIds, postSaveContainedNotes }
  }

  /**
   * Build merge writes that flip `state` for each id. Skips ids absent
   * from NoteStore (defends against the doc being hard-deleted between
   * caller's id-collection and write). Shared by softDeleteNote,
   * undeleteNote, restoreCutDeletedNotes, and buildCutDeleteOps.
   */
  private buildStateChangeOps(
    ids: Iterable<string>,
    newState: string | null,
  ): BatchWriteOp[] {
    const ops: BatchWriteOp[] = []
    for (const id of ids) {
      if (!noteStore.getRawNoteById(id)) continue
      ops.push({
        ref: this.noteRef(id),
        data: {
          state: newState,
          updatedAt: serverTimestamp(),
        },
        merge: true,
      })
    }
    return ops
  }

  private async commitInBatches(operation: string, ops: BatchWriteOp[]): Promise<void> {
    if (ops.length === 0) return
    for (let i = 0; i < ops.length; i += MAX_BATCH_SIZE) {
      const chunk = ops.slice(i, i + MAX_BATCH_SIZE)
      const batch = writeBatch(this.db)
      for (const op of chunk) {
        if (op.merge) {
          batch.set(op.ref, op.data, { merge: true })
        } else {
          batch.set(op.ref, op.data)
        }
      }
      await batch.commit()
      firestoreUsage.recordWrite(operation, 'BATCH_COMMIT', chunk.length)
    }
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
      const trackedLines = await this.prepareInlineEditTrackedLines(
        noteId, newContent, 'saveNoteWithFullContent', editorNoteIds,
      )
      // No edit-session anchor on this legacy path — skip the 3-way merge.
      const result = await this.saveNoteWithChildren(noteId, trackedLines, null)
      return result.createdIds
    })
  }

  /**
   * Builds tracked lines for an inline-edited note by matching its flat
   * editor content against the existing tree (preserving grandchild
   * relationships). Mirrors the load+match step inside [saveNoteWithFullContent].
   * Exposed so callers can pre-build planning input for [saveMultipleNotes]
   * without committing each session separately. Falls back to a Firestore
   * read when the note isn't in NoteStore yet (post-create, pre-listener).
   */
  async prepareInlineEditTrackedLines(
    noteId: string,
    newContent: string,
    operation: string,
    editorNoteIds?: (string | null)[],
  ): Promise<NoteLine[]> {
    this.requireUserId()
    this.assertNoteStoreLoaded(operation, noteId)
    this.warnIfDescendantsLikelyStale(operation, noteId)

    const existingLines = noteStore.getNoteLinesById(noteId)
      ?? (await this.loadNoteWithChildren(noteId)).lines

    return matchLinesToIds(noteId, existingLines, newContent.split('\n'), editorNoteIds)
  }

  async createNote(): Promise<string> {
    return this.logged('createNote', async () => {
      const userId = this.requireUserId()
      const data = this.newNoteData(userId, '')
      const ref = await addDoc(this.notesRef, data)
      firestoreUsage.recordWrite('createNote', 'SET')
      return ref.id
    })
  }

  /**
   * Creates a new multi-line note with tree structure derived from indentation.
   */
  async createMultiLineNote(content: string): Promise<string> {
    return this.logged('createMultiLineNote', async () => {
      const userId = this.requireUserId()
      return this.createMultiLineNoteInner(userId, content)
    })
  }

  private async createMultiLineNoteInner(
    userId: string,
    content: string,
  ): Promise<string> {
      const lines = content.split('\n')
      const firstLine = (lines[0] ?? '').replace(/^\t+/, '')
      const childLines = lines.slice(1)

      if (childLines.length === 0 || childLines.every((l) => l.replace(/^\t+/, '') === '')) {
        const data = this.newNoteData(userId, firstLine)
        const ref = await addDoc(this.notesRef, data)
        firestoreUsage.recordWrite('createMultiLineNote', 'SET')
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
      firestoreUsage.recordWrite('createMultiLineNote', 'BATCH_COMMIT', nodes.length + 1)
      return parentRef.id
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
      const ops = this.buildStateChangeOps(idsToDelete, NoteState.DELETED)
      await this.commitInBatches('softDeleteNote', ops)
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
        where('state', '==', NoteState.DELETED),
        where('userId', '==', userId),
      )
      const snap = await getDocs(q)
      firestoreUsage.recordRead('hardDeleteAllSoftDeleted', 'GET_DOCS', snap.size)
      if (snap.empty) return 0

      for (let i = 0; i < snap.docs.length; i += MAX_BATCH_SIZE) {
        const chunk = snap.docs.slice(i, i + MAX_BATCH_SIZE)
        const batch = writeBatch(this.db)
        for (const d of chunk) {
          batch.delete(d.ref)
        }
        await batch.commit()
        firestoreUsage.recordWrite('hardDeleteAllSoftDeleted', 'BATCH_COMMIT', chunk.length)
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
      const ops = this.buildStateChangeOps(idsToRestore, null)
      await this.commitInBatches('undeleteNote', ops)
    })
  }

  /**
   * Restores parked cut-delete docs by flipping `state` back to null. The
   * stray-child healing inside reconstruction picks each restored doc up
   * under its preserved parentNoteId; the next save of that root writes the
   * healed `containedNotes` back to Firestore.
   */
  async restoreCutDeletedNotes(noteIds: string[]): Promise<void> {
    return this.logged('restoreCutDeletedNotes', async () => {
      this.requireUserId()
      if (noteIds.length === 0) return
      const ops = this.buildStateChangeOps(noteIds, null)
      await this.commitInBatches('restoreCutDeletedNotes', ops)
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
      firestoreUsage.recordWrite('updateShowCompleted', 'UPDATE')
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
    lines.push(`  [${i}] noteId=${line.noteId} content='${preview}'`)
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
      noteId: index === 0 ? parentNoteId : newSentinelNoteId('typed'),
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
  const existingNoteIdSet = new Set(existingLines.map(l => l.noteId))

  const newIds: (string | null)[] = new Array(newLinesContent.length).fill(null) as (string | null)[]
  const oldConsumed = new Array(existingLines.length).fill(false) as boolean[]

  // Phase 0: Use editor-provided noteIds for foreign notes (reparented from another tree).
  // Only trust real ids that aren't already in this tree's existing lines —
  // existing notes are matched more reliably by content in phases 1-3, and
  // sentinels are per-allocation unique so they'd spuriously pass the set
  // check; the save planner resolves them.
  if (editorNoteIds) {
    for (let i = 0; i < newLinesContent.length; i++) {
      const editorId = editorNoteIds[i]
      if (editorId && isRealNoteId(editorId) && !existingNoteIdSet.has(editorId)) {
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
    noteId: newIds[index] ?? newSentinelNoteId('typed'),
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
    lines.push(`  [${i}] noteId=${line.noteId} content='${preview}'`)
  }
  if (existingLines.length > 40) lines.push(`  ... (${existingLines.length - 40} more)`)

  lines.push('--- Stack trace ---')
  lines.push(new Error().stack?.split('\n').slice(2, 17).join('\n') ?? '(unavailable)')
  lines.push('=== END MATCH LINES TO IDS ===')
  return lines.join('\n')
}
