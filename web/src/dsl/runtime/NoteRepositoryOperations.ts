import {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  setDoc,
  serverTimestamp,
  updateDoc,
  where,
  type Firestore,
} from 'firebase/firestore'
import { noteFromFirestore, type Note } from '@/data/Note'
import { withStampedWrite } from '@/data/NoteRepository'
import { noteStore } from '@/data/NoteStore'
import { type NoteOperations, NoteOperationException } from './NoteOperations'

/**
 * Firebase-backed implementation of NoteOperations for DSL mutations.
 */
export class NoteRepositoryOperations implements NoteOperations {
  private readonly notesRef

  constructor(
    db: Firestore,
    private readonly userId: string,
  ) {
    this.notesRef = collection(db, 'notes')
  }

  private noteRef(noteId: string) {
    return doc(this.notesRef, noteId)
  }

  private async fetchNote(noteId: string): Promise<Note> {
    const snap = await getDoc(this.noteRef(noteId))
    if (!snap.exists()) {
      throw new NoteOperationException(`Note not found: ${noteId}`)
    }
    return noteFromFirestore(snap.id, snap.data())
  }

  private async updateAndFetch(noteId: string, updates: Record<string, unknown>): Promise<Note> {
    const ref = this.noteRef(noteId)
    await withStampedWrite(async (_, stamp) => {
      await updateDoc(ref, { ...updates, updatedAt: serverTimestamp(), ...stamp })
    }, noteStore.getRawNoteById(noteId) ?? undefined)
    return this.fetchNote(noteId)
  }

  async createNote(path: string, content: string): Promise<Note> {
    const newRef = doc(this.notesRef)
    await withStampedWrite(async (_, stamp) => {
      await setDoc(newRef, {
        userId: this.userId,
        content,
        path,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
        parentNoteId: null,
        ...stamp,
      })
    }, undefined)
    return this.fetchNote(newRef.id)
  }

  async getNoteById(noteId: string): Promise<Note | null> {
    const snap = await getDoc(this.noteRef(noteId))
    if (!snap.exists()) return null
    return noteFromFirestore(snap.id, snap.data())
  }

  async findByPath(path: string): Promise<Note | null> {
    const q = query(
      this.notesRef,
      where('userId', '==', this.userId),
      where('path', '==', path),
    )
    const snapshot = await getDocs(q)
    if (snapshot.empty) return null
    const d = snapshot.docs[0]!
    return noteFromFirestore(d.id, d.data())
  }

  async noteExistsAtPath(path: string): Promise<boolean> {
    const note = await this.findByPath(path)
    return note != null
  }

  async updatePath(noteId: string, newPath: string): Promise<Note> {
    // Check path uniqueness
    const existing = await this.findByPath(newPath)
    if (existing && existing.id !== noteId) {
      throw new NoteOperationException(`Path already in use: ${newPath}`)
    }
    return this.updateAndFetch(noteId, { path: newPath })
  }

  async updateContent(noteId: string, newContent: string): Promise<Note> {
    return this.updateAndFetch(noteId, { content: newContent })
  }

  async appendToNote(noteId: string, text: string): Promise<Note> {
    const note = await this.fetchNote(noteId)
    const newContent = note.content.length > 0
      ? note.content + '\n' + text
      : text
    return this.updateAndFetch(noteId, { content: newContent })
  }
}
