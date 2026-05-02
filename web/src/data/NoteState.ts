/**
 * Lifecycle states for a Note document.
 *
 * - ACTIVE: the note is part of a live tree (no `state` field, or `state == null`).
 *   Stored as `null` in Firestore — the absence of a state field is the active state.
 * - DELETED: soft-deleted. Excluded from reconstruction and tree queries.
 * - CUT_DELETE: a doc that was cut from its parent's `containedNotes` and is
 *   parked awaiting paste. Excluded from reconstruction (so the parent renders
 *   as if the line is gone), but distinguishable from a hard soft-delete so a
 *   subsequent paste can resurrect it under a new parent. Swept to DELETED
 *   on the next save of the original parent if the doc is no longer in the
 *   client's clipboard registry. See Phase 8 of the save/echo redesign.
 *
 * Use isLive() to test for the active state — never compare strings directly.
 */
export const NoteState = {
  ACTIVE: 'active',
  DELETED: 'deleted',
  CUT_DELETE: 'cut-delete',
} as const

/**
 * Whether a note is part of the live tree (visible to reconstruction, queries,
 * and the editor). Returns true for `null` (default), false for any non-live
 * state including soft-delete and cut-delete.
 */
export function isLive(state: string | null | undefined): boolean {
  return state == null || state === NoteState.ACTIVE
}
