/**
 * Determines whether a view note's inline editor has unsaved changes.
 *
 * Not dirty when:
 * - editContent matches the note's current content (no local changes)
 * - editContent matches lastSavedContent (save in flight / just completed)
 *
 * Dirty when:
 * - editContent differs from both noteContent and lastSavedContent
 */
export function isViewNoteDirty(
  editContent: string,
  noteContent: string,
  lastSavedContent: string | null,
): boolean {
  return editContent !== noteContent && editContent !== lastSavedContent
}
