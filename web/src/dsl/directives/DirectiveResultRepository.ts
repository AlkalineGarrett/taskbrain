import { collection, doc, getDocs, setDoc, updateDoc, serverTimestamp, writeBatch } from 'firebase/firestore'
import { db, auth } from '@/firebase/config'
import { firestoreUsage } from '@/data/FirestoreUsage'
import type { DirectiveResult } from './DirectiveResult'
import type { DirectiveWarningType } from './DirectiveResult'

function resultsCollection(noteId: string) {
  return collection(db, 'notes', noteId, 'directiveResults')
}

/**
 * Save a directive execution result to Firestore.
 *
 * Stamps `userId` on the doc so security rules can check ownership directly
 * instead of doing a billed `get()` of the parent note on every access.
 */
export async function saveDirectiveResult(
  noteId: string,
  directiveHash: string,
  result: DirectiveResult,
): Promise<void> {
  const userId = auth.currentUser?.uid
  if (!userId) throw new Error('User not signed in')
  const docRef = doc(resultsCollection(noteId), directiveHash)
  await setDoc(docRef, {
    userId,
    result: result.result,
    executedAt: serverTimestamp(),
    error: result.error,
    warning: result.warning ?? null,
    collapsed: result.collapsed,
  })
  firestoreUsage.recordWrite('saveDirectiveResult', 'SET')
}

/**
 * Get all directive results for a note.
 */
export async function getDirectiveResults(noteId: string): Promise<Map<string, DirectiveResult>> {
  const snapshot = await getDocs(resultsCollection(noteId))
  firestoreUsage.recordRead('getDirectiveResults', 'GET_DOCS', snapshot.size)
  const results = new Map<string, DirectiveResult>()
  for (const docSnap of snapshot.docs) {
    const data = docSnap.data()
    results.set(docSnap.id, {
      result: (data.result as Record<string, unknown>) ?? null,
      executedAt: data.executedAt ?? null,
      error: (data.error as string) ?? null,
      warning: (data.warning as DirectiveWarningType) ?? null,
      collapsed: (data.collapsed as boolean) ?? true,
    })
  }
  return results
}

/**
 * Update the collapsed state of a directive result.
 */
export async function updateCollapsedState(
  noteId: string,
  directiveHash: string,
  collapsed: boolean,
): Promise<void> {
  const docRef = doc(resultsCollection(noteId), directiveHash)
  await updateDoc(docRef, { collapsed })
  firestoreUsage.recordWrite('updateCollapsedState', 'UPDATE')
}

/**
 * Delete all directive results for a note.
 */
export async function deleteAllDirectiveResults(noteId: string): Promise<void> {
  const snapshot = await getDocs(resultsCollection(noteId))
  firestoreUsage.recordRead('deleteAllDirectiveResults', 'GET_DOCS', snapshot.size)
  if (snapshot.empty) return
  const batch = writeBatch(db)
  for (const docSnap of snapshot.docs) {
    batch.delete(docSnap.ref)
  }
  await batch.commit()
  firestoreUsage.recordWrite('deleteAllDirectiveResults', 'BATCH_COMMIT', snapshot.size)
}
