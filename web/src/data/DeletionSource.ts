/**
 * What caused a note to be soft-deleted. Stamped (with a per-batch UUID)
 * onto every note transitioning to `state: "deleted"` so reconstruction,
 * undelete, and forensics can attribute deletions to their originating
 * operation.
 *
 * The format on the wire is `<label>_<uuid>` (see `Note.deletionBatchId`).
 *
 * **Vocabulary is closed.** Do not introduce free-form sources from call
 * sites — add a member to {@link DeletionSource}. `UNKNOWN` is reserved
 * for paths that haven't been instrumented yet; an `unknown_*` id
 * appearing in data means "a removal path leaked to the save layer
 * without naming itself" and should be tracked down.
 *
 * Keep in sync with Kotlin `DeletionSource.kt`.
 */
export const DeletionSource = {
  /** `NoteRepository.softDeleteNote` — whole-tree delete. */
  WHOLE_NOTE: 'whole-note',
  /** Editor selection delete (Delete/Backspace over a multi-line range). */
  SELECTION_DELETE: 'selection-delete',
  /** Backspace at line start, merging the line into the previous one. */
  BACKSPACE_MERGE: 'backspace-merge',
  /** Delete at line end, merging the next line into this one. */
  DELETE_MERGE: 'delete-merge',
  /** Paste with selection — the selection's lines are replaced. */
  PASTE_REPLACE: 'paste-replace',
  /** Selection move (cut-and-paste-in-place via `EditorController.moveSelectionTo`). */
  MOVE: 'move',
  /** Removal initiated by the local editor without naming a source. Drive to zero. */
  UNKNOWN: 'unknown',
} as const

export type DeletionSource = typeof DeletionSource[keyof typeof DeletionSource]

/**
 * Build a fresh `<source>_<uuid>` batch id. Different deletes within the
 * same source must each get their own id (call this once per batch, then
 * reuse the id across every doc in that batch).
 */
export function newDeletionBatchId(source: DeletionSource): string {
  return `${source}_${crypto.randomUUID()}`
}

/** Extract the source label from a batchId, or null if malformed. */
export function deletionSourceOf(batchId: string | null | undefined): string | null {
  if (!batchId) return null
  const sep = batchId.indexOf('_')
  if (sep <= 0 || sep === batchId.length - 1) return null
  return batchId.slice(0, sep)
}
