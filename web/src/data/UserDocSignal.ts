import {
  doc,
  serverTimestamp,
  setDoc,
  type Firestore,
} from 'firebase/firestore'
import { firestoreUsage } from './FirestoreUsage'

/**
 * Per-user signal observed by NoteStore's listener to trigger delta pulls.
 * Fire-and-forget — callers should not await. See `docs/live-cross-platform-sync.md`.
 * The returned Promise exists so tests can await; production callers `void` it.
 */
export const UserDocSignal = {
  /** Called after every successful notes-collection write. */
  bump(db: Firestore, userId: string): Promise<void> {
    return setUserField(db, userId, { lastNoteChange: serverTimestamp() }, 'UserDocSignal.bump')
  },

  /** Create-on-login so the signal listener has a doc to attach to. */
  ensureExists(db: Firestore, userId: string): Promise<void> {
    return setUserField(db, userId, { uid: userId }, 'UserDocSignal.ensureExists')
  },
}

async function setUserField(
  db: Firestore,
  userId: string,
  field: Record<string, unknown>,
  operation: string,
): Promise<void> {
  try {
    await setDoc(doc(db, 'users', userId), field, { merge: true })
    firestoreUsage.recordWrite(operation, 'SET')
  } catch (e) {
    console.warn(`[UserDocSignal] ${operation}(userId=${userId}) failed`, e)
  }
}
