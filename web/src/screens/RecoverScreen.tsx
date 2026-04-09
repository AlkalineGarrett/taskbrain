import { useEffect, useState, useCallback } from 'react'
import { collection, query, where, getDocs } from 'firebase/firestore'
import { db, auth } from '@/firebase/config'
import { type Note, noteFromFirestore } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import styles from './RecoverScreen.module.css'

const repo = new NoteRepository(db, auth)

/** Max gap between consecutive timestamps to be considered the same batch. */
const BATCH_WINDOW_MS = 5000

// --- Unified recovery group ---

type GroupLabel = 'self-parent' | 'missing-parent' | 'cycle' | 'batch-deleted'

interface RecoverGroup {
  label: GroupLabel
  notes: Note[]
  timestamp: Date
  rootNoteId: string | null
  /** First line of the root note, used to prepopulate the "Create new note" title. */
  rootNoteTitle: string
}

// --- Detection ---

type OrphanReason = 'self-parent' | 'missing-parent' | 'cycle'

function detectOrphanedNotes(allNotes: Note[]): { note: Note; reason: OrphanReason }[] {
  const noteMap = new Map(allNotes.map(n => [n.id, n]))
  const orphans: { note: Note; reason: OrphanReason }[] = []

  for (const note of allNotes) {
    if (!note.parentNoteId) continue

    if (note.parentNoteId === note.id) {
      orphans.push({ note, reason: 'self-parent' })
      continue
    }

    if (!noteMap.has(note.parentNoteId)) {
      orphans.push({ note, reason: 'missing-parent' })
      continue
    }

    const visited = new Set<string>()
    let current: string | null = note.id
    while (current) {
      if (visited.has(current)) {
        orphans.push({ note, reason: 'cycle' })
        break
      }
      visited.add(current)
      current = noteMap.get(current)?.parentNoteId ?? null
    }
  }

  return orphans
}

// --- Shared time-clustering ---

/**
 * Cluster items into temporal batches. Items within BATCH_WINDOW_MS of each
 * other (by updatedAt) are grouped together.
 */
function clusterByTime<T>(items: T[], getTime: (item: T) => number, minSize = 1): T[][] {
  if (items.length === 0) return []

  const sorted = [...items].sort((a, b) => getTime(a) - getTime(b))
  const clusters: T[][] = []
  let current: T[] = [sorted[0]!]

  for (let i = 1; i < sorted.length; i++) {
    if (getTime(sorted[i]!) - getTime(sorted[i - 1]!) <= BATCH_WINDOW_MS) {
      current.push(sorted[i]!)
    } else {
      if (current.length >= minSize) clusters.push(current)
      current = [sorted[i]!]
    }
  }
  if (current.length >= minSize) clusters.push(current)

  return clusters
}

function noteUpdatedTime(note: Note): number {
  return note.updatedAt?.toMillis() ?? 0
}

// --- Build all recovery groups ---

function buildRecoverGroups(allNotes: Note[]): RecoverGroup[] {
  const noteMap = new Map(allNotes.map(n => [n.id, n]))
  const orphans = detectOrphanedNotes(allNotes)
  const orphanIds = new Set(orphans.map(o => o.note.id))

  // Group orphans by (reason, rootNoteId), then cluster each bucket by time
  const orphanBuckets = new Map<string, { reason: OrphanReason; rootNoteId: string; notes: Note[] }>()
  for (const { note, reason } of orphans) {
    const rootId = note.rootNoteId ?? note.id
    const key = `${reason}:${rootId}`
    const bucket = orphanBuckets.get(key)
    if (bucket) bucket.notes.push(note)
    else orphanBuckets.set(key, { reason, rootNoteId: rootId, notes: [note] })
  }

  const groups: RecoverGroup[] = []

  for (const { reason, rootNoteId, notes } of orphanBuckets.values()) {
    for (const cluster of clusterByTime(notes, noteUpdatedTime)) {
      const sharedRoot = cluster.every(n => (n.rootNoteId ?? n.id) === rootNoteId) ? rootNoteId : null
      const rootTitle = sharedRoot ? (noteMap.get(sharedRoot)?.content.split('\n')[0] ?? '') : ''
      groups.push({
        label: reason,
        notes: cluster.sort((a, b) => a.content.localeCompare(b.content)),
        timestamp: cluster[0]!.updatedAt?.toDate() ?? new Date(),
        rootNoteId: sharedRoot,
        rootNoteTitle: rootTitle,
      })
    }
  }

  // Batch-deleted: exclude notes already in orphan groups
  const deleted = allNotes.filter(n => n.state === 'deleted' && !orphanIds.has(n.id))
  for (const cluster of clusterByTime(deleted, noteUpdatedTime, 3)) {
    const rootIds = new Set(cluster.map(n => n.rootNoteId ?? n.id))
    const sharedRoot = rootIds.size === 1 ? [...rootIds][0]! : null
    const rootTitle = sharedRoot ? (noteMap.get(sharedRoot)?.content.split('\n')[0] ?? '') : ''
    groups.push({
      label: 'batch-deleted',
      notes: cluster.sort((a, b) => a.content.localeCompare(b.content)),
      timestamp: cluster[0]!.updatedAt?.toDate() ?? new Date(),
      rootNoteId: sharedRoot,
      rootNoteTitle: rootTitle,
    })
  }

  // Most recent first
  groups.sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime())
  return groups
}

// --- Component ---

export function RecoverScreen() {
  const [groups, setGroups] = useState<RecoverGroup[]>([])
  const [titles, setTitles] = useState<Map<number, string>>(new Map())
  const [loading, setLoading] = useState(true)
  const [busyIndex, setBusyIndex] = useState<number | null>(null)

  useEffect(() => {
    void loadNotes()
  }, [])

  async function loadNotes() {
    setLoading(true)
    const userId = auth.currentUser?.uid
    if (!userId) return

    const notesRef = collection(db, 'notes')
    const snapshot = await getDocs(query(notesRef, where('userId', '==', userId)))
    const allNotes = snapshot.docs.map(d => noteFromFirestore(d.id, d.data()))

    const built = buildRecoverGroups(allNotes)
    setGroups(built)
    setTitles(new Map(built.map((g, i) => [i, g.rootNoteTitle])))
    setLoading(false)
  }

  const handleCreateNote = useCallback(async (index: number) => {
    const group = groups[index]
    if (!group) return
    setBusyIndex(index)
    try {
      const title = titles.get(index) ?? ''
      const noteId = await repo.createNote()
      const trackedLines = [
        { content: title, noteId },
        ...group.notes.map(n => ({ content: n.content, noteId: null })),
      ]
      await repo.saveNoteWithChildren(noteId, trackedLines)
      setGroups(prev => prev.filter((_, i) => i !== index))
    } catch (e) {
      console.error('Create note failed:', e)
      alert('Create note failed: ' + (e instanceof Error ? e.message : String(e)))
    } finally {
      setBusyIndex(null)
    }
  }, [groups, titles])

  if (loading) {
    return <div className={styles.loading}>Loading notes...</div>
  }

  return (
    <div className={styles.container}>
      <h2 className={styles.title}>Recoverable Notes</h2>
      <p className={styles.subtitle}>
        Orphaned notes (broken parent chains) and batch-deleted notes, sorted by most recent.
      </p>

      {groups.length === 0 && <p>No recoverable notes found.</p>}

      {groups.map((group, i) => {
        const isBusy = busyIndex === i
        return (
          <div key={i} className={styles.batch}>
            <div className={styles.batchHeader}>
              <div className={styles.batchMeta}>
                <span className={styles.groupLabel}>{group.label}</span> &middot; {group.notes.length} notes &middot; {group.timestamp.toLocaleString()}
                {group.rootNoteId && <> &middot; root: {group.rootNoteId}</>}
              </div>
            </div>
            <div className={styles.createRow}>
              <input
                className={styles.titleInput}
                type="text"
                placeholder="Title for new note"
                value={titles.get(i) ?? ''}
                onChange={e => setTitles(prev => new Map(prev).set(i, e.target.value))}
              />
              <button
                className={styles.restoreButton}
                onClick={() => void handleCreateNote(i)}
                disabled={isBusy}
              >
                {isBusy ? 'Creating...' : 'Create new note'}
              </button>
            </div>
            <div className={styles.noteList}>
              {group.notes.map(n => (
                <div
                  key={n.id}
                  className={`${styles.noteItem} ${n.parentNoteId ? styles.child : ''}`}
                >
                  {n.content || '(empty)'}
                </div>
              ))}
            </div>
          </div>
        )
      })}
    </div>
  )
}
