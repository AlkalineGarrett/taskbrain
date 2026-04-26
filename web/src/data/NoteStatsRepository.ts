import {
  collection,
  doc,
  getDocs,
  setDoc,
  serverTimestamp,
  increment,
  type Firestore,
} from 'firebase/firestore'
import type { Auth } from 'firebase/auth'
import { noteStatsFromFirestore, type NoteStats } from './NoteStats'

/**
 * Per-user view tracking. Lives apart from the note doc so writes don't echo through
 * the notes collection listener and trigger the directive cache invalidation path.
 */
export class NoteStatsRepository {
  constructor(
    private readonly db: Firestore,
    private readonly auth: Auth,
  ) {}

  private requireUserId(): string {
    const uid = this.auth.currentUser?.uid
    if (!uid) throw new Error('User not signed in')
    return uid
  }

  private statsRef(userId: string, noteId: string) {
    return doc(this.db, 'users', userId, 'noteStats', noteId)
  }

  /** Embedded `[view ...]` directive renders MUST NOT call this — only top-level note opens. */
  async recordView(noteId: string): Promise<void> {
    const userId = this.requireUserId()
    const today = todayLocalDate()
    await setDoc(
      this.statsRef(userId, noteId),
      {
        lastAccessedAt: serverTimestamp(),
        viewCount: increment(1),
        viewedDays: { [today]: true },
      },
      { merge: true },
    )
  }

  async loadAllNoteStats(): Promise<Map<string, NoteStats>> {
    const userId = this.requireUserId()
    const snap = await getDocs(collection(this.db, 'users', userId, 'noteStats'))
    const out = new Map<string, NoteStats>()
    snap.forEach((d) => {
      out.set(d.id, noteStatsFromFirestore(d.data()))
    })
    return out
  }
}

function todayLocalDate(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
