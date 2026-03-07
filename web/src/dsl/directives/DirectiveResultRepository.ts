import { collection, doc, getDocs, setDoc, updateDoc, serverTimestamp, writeBatch } from 'firebase/firestore'
import { db } from '@/firebase/config'
import type { DirectiveResult } from './DirectiveResult'

function resultsCollection(noteId: string) {
  return collection(db, 'notes', noteId, 'directiveResults')
}

/**
 * Save a directive execution result to Firestore.
 */
export async function saveDirectiveResult(
  noteId: string,
  directiveHash: string,
  result: DirectiveResult,
): Promise<void> {
  const docRef = doc(resultsCollection(noteId), directiveHash)
  await setDoc(docRef, {
    result: result.result,
    executedAt: serverTimestamp(),
    error: result.error,
    collapsed: result.collapsed,
  })
}

/**
 * Get all directive results for a note.
 */
export async function getDirectiveResults(noteId: string): Promise<Map<string, DirectiveResult>> {
  const snapshot = await getDocs(resultsCollection(noteId))
  const results = new Map<string, DirectiveResult>()
  for (const docSnap of snapshot.docs) {
    const data = docSnap.data()
    results.set(docSnap.id, {
      result: (data.result as Record<string, unknown>) ?? null,
      executedAt: data.executedAt ?? null,
      error: (data.error as string) ?? null,
      warning: null,
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
}

/**
 * Delete all directive results for a note.
 */
export async function deleteAllDirectiveResults(noteId: string): Promise<void> {
  const snapshot = await getDocs(resultsCollection(noteId))
  const batch = writeBatch(db)
  for (const docSnap of snapshot.docs) {
    batch.delete(docSnap.ref)
  }
  await batch.commit()
}
