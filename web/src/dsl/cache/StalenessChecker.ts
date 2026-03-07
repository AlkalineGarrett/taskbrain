import type { Note } from '@/data/Note'
import type { CachedDirectiveResult } from './CachedDirectiveResult'
import { shouldRetryError } from './CachedDirectiveResult'
import { hashFirstLine, hashNonFirstLine, hashField } from './ContentHasher'
import * as MetadataHasher from './MetadataHasher'
import { resolveHierarchyPath } from './HierarchyResolver'

/**
 * Determine if a cached result should be re-executed.
 */
export function shouldReExecute(
  cached: CachedDirectiveResult,
  currentNotes: Note[],
  currentNote: Note | null = null,
): boolean {
  if (shouldRetryError(cached)) return true
  return isStale(cached, currentNotes, currentNote)
}

/**
 * Check if a cached result is stale based on dependency hashes.
 */
export function isStale(
  cached: CachedDirectiveResult,
  currentNotes: Note[],
  currentNote: Note | null = null,
): boolean {
  const deps = cached.dependencies
  const cachedHashes = cached.metadataHashes

  // Check global metadata dependencies (short-circuit at first stale)
  if (deps.dependsOnNoteExistence) {
    if (cachedHashes.existenceHash !== MetadataHasher.computeExistenceHash(currentNotes)) return true
  }
  if (deps.dependsOnPath) {
    if (cachedHashes.pathHash !== MetadataHasher.computePathHash(currentNotes)) return true
  }
  if (deps.dependsOnModified) {
    if (cachedHashes.modifiedHash !== MetadataHasher.computeModifiedHash(currentNotes)) return true
  }
  if (deps.dependsOnCreated) {
    if (cachedHashes.createdHash !== MetadataHasher.computeCreatedHash(currentNotes)) return true
  }
  if (deps.dependsOnViewed) {
    if (cachedHashes.viewedHash !== MetadataHasher.computeViewedHash(currentNotes)) return true
  }
  if (deps.dependsOnAllNames) {
    if (cachedHashes.allNamesHash !== MetadataHasher.computeAllNamesHash(currentNotes)) return true
  }

  // Check per-note content dependencies
  for (const noteId of deps.firstLineNotes) {
    const note = currentNotes.find((n) => n.id === noteId)
    if (!note) return true // deleted
    const currentHash = hashFirstLine(note.content)
    const cachedHash = cached.noteContentHashes.get(noteId)?.firstLineHash
    if (!cachedHash || currentHash !== cachedHash) return true
  }

  for (const noteId of deps.nonFirstLineNotes) {
    const note = currentNotes.find((n) => n.id === noteId)
    if (!note) return true
    const currentHash = hashNonFirstLine(note.content)
    const cachedHash = cached.noteContentHashes.get(noteId)?.nonFirstLineHash
    if (!cachedHash || currentHash !== cachedHash) return true
  }

  // Check hierarchy dependencies
  if (deps.hierarchyDeps.length > 0 && currentNote) {
    for (const dep of deps.hierarchyDeps) {
      const currentResolved = resolveHierarchyPath(dep.path, currentNote, currentNotes)
      if (currentResolved?.id !== dep.resolvedNoteId) return true
      if (dep.field && currentResolved) {
        const currentHash = hashField(currentResolved, dep.field)
        if (currentHash !== dep.fieldHash) return true
      }
    }
  }

  return false
}
