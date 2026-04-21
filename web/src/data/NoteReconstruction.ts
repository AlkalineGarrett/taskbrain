/**
 * Pure functions for reconstructing top-level note content from Firestore's
 * tree structure. Extracted from NoteStore for testability.
 *
 * Reconstruction walks the parentNoteId tree (not rootNoteId) so it auto-heals
 * data inconsistencies: stray children reachable via parentNoteId that the
 * parent's containedNotes forgot about get appended at the end, and orphan
 * references in containedNotes (IDs not in rawNotes, deleted, or parented
 * elsewhere) are dropped. Roots whose content required a fix are reported
 * via `RebuildResult.notesNeedingFix`; saving the note writes the healed
 * state back to Firestore.
 */

import type { Note } from './Note'

/**
 * Result of a rebuild pass.
 *
 * `notesNeedingFix` holds top-level note IDs whose reconstruction had to
 * auto-heal a discrepancy (orphan ref dropped or stray child appended).
 * These notes display with healed content; the UI should prompt a save.
 */
export interface RebuildResult {
  notes: Note[]
  notesNeedingFix: Set<string>
}

/**
 * Rebuild all reconstructed notes from a raw notes map.
 */
export function rebuildAllNotes(rawNotes: Map<string, Note>): RebuildResult {
  const childrenByParent = indexChildrenByParent(rawNotes)
  const needsFix = new Set<string>()
  const notes: Note[] = []
  for (const note of rawNotes.values()) {
    if (note.parentNoteId != null) continue
    const [reconstructed, fixed] = reconstructNoteContent(note, rawNotes, childrenByParent)
    notes.push(reconstructed)
    if (fixed) needsFix.add(note.id)
  }
  return { notes, notesNeedingFix: needsFix }
}

/**
 * Rebuild only the top-level notes whose trees were affected by changes.
 * Returns a new array with affected notes updated in place. `notesNeedingFix`
 * reflects only the affected roots — callers merge with previously-known
 * needsFix state themselves.
 */
export function rebuildAffectedNotes(
  currentReconstructed: Note[],
  affectedRootIds: Set<string>,
  rawNotes: Map<string, Note>
): RebuildResult {
  const childrenByParent = indexChildrenByParent(rawNotes)
  let changed = false
  const newNotes = [...currentReconstructed]
  const needsFix = new Set<string>()

  for (const rootId of affectedRootIds) {
    const rootNote = rawNotes.get(rootId)
    if (!rootNote || rootNote.parentNoteId != null) {
      const idx = newNotes.findIndex(n => n.id === rootId)
      if (idx >= 0) {
        newNotes.splice(idx, 1)
        changed = true
      }
      continue
    }

    const [reconstructed, fixed] = reconstructNoteContent(rootNote, rawNotes, childrenByParent)
    if (fixed) needsFix.add(rootId)
    const idx = newNotes.findIndex(n => n.id === rootId)
    if (idx >= 0) {
      newNotes[idx] = reconstructed
      changed = true
    } else {
      newNotes.push(reconstructed)
      changed = true
    }
  }

  return {
    notes: changed ? newNotes : currentReconstructed,
    notesNeedingFix: needsFix,
  }
}

/**
 * Reconstruct a note by walking the parentNoteId tree.
 *
 * Ordering: each parent renders its containedNotes entries in declared
 * order. Stray children (same parentNoteId but absent from containedNotes)
 * are appended after declared entries — the parent has no ordering
 * information for them. Orphan containedNotes entries (IDs not in rawNotes,
 * deleted, or parented elsewhere) are dropped.
 *
 * Returns `[lines, fixed]`: `fixed` is `true` iff a fix was applied (stray
 * appended or orphan dropped). This is the primitive the live editor uses
 * via `NoteStore.getNoteLinesById`; `reconstructNoteContent` composes it
 * into a joined-content Note.
 */
export function reconstructNoteLines(
  note: Note,
  rawNotes: Map<string, Note>,
  childrenByParent: Map<string, Note[]>,
): [{ content: string; noteId: string | null }[], boolean] {
  const hasDeclaredRefs = note.containedNotes.some(id => id.length > 0)
  const hasRealChildren = (childrenByParent.get(note.id)?.length ?? 0) > 0
  if (!hasDeclaredRefs && !hasRealChildren && note.containedNotes.length === 0) {
    return [[{ content: note.content, noteId: note.id }], false]
  }

  const lines: { content: string; noteId: string | null }[] = [
    { content: note.content, noteId: note.id },
  ]
  const visited = new Set<string>([note.id])
  const fixed = renderChildrenOf(note, rawNotes, childrenByParent, lines, 0, visited)
  return [lines, fixed]
}

/**
 * Thin wrapper around `reconstructNoteLines` that joins the lines into a
 * single `note.content` string. Preserves reference identity when the join
 * produces the original content, so callers' change-detection (e.g.,
 * `rebuildAffectedNotes`' unaffected-by-reference check) continues to work.
 */
export function reconstructNoteContent(
  note: Note,
  rawNotes: Map<string, Note>,
  childrenByParent: Map<string, Note[]>,
): [Note, boolean] {
  const [lines, fixed] = reconstructNoteLines(note, rawNotes, childrenByParent)
  const joined = lines.map(l => l.content).join('\n')
  const result = joined === note.content ? note : { ...note, content: joined }
  return [result, fixed]
}

function renderChildrenOf(
  parent: Note,
  rawNotes: Map<string, Note>,
  childrenByParent: Map<string, Note[]>,
  lines: { content: string; noteId: string | null }[],
  childDepth: number,
  visited: Set<string>,
): boolean {
  let fixed = false
  const childPrefix = '\t'.repeat(childDepth)
  const placed = new Set<string>()

  for (const childId of parent.containedNotes) {
    if (childId.length === 0) {
      lines.push({ content: childPrefix, noteId: null })
      continue
    }
    const child = rawNotes.get(childId)
    if (!child || child.state === 'deleted' || child.parentNoteId !== parent.id) {
      fixed = true
      console.warn(
        `reconstructNoteLines: dropping orphan ref ${childId} from parent ${parent.id} ` +
        `(missing/deleted/mis-parented). parentContent='${parent.content.slice(0, 40)}'`
      )
      continue
    }
    if (visited.has(child.id)) {
      fixed = true
      continue
    }
    visited.add(child.id)
    lines.push({ content: childPrefix + child.content, noteId: child.id })
    placed.add(child.id)
    if (renderChildrenOf(child, rawNotes, childrenByParent, lines, childDepth + 1, visited)) {
      fixed = true
    }
  }

  const direct = childrenByParent.get(parent.id) ?? []
  for (const stray of direct) {
    if (placed.has(stray.id)) continue
    if (visited.has(stray.id)) continue
    visited.add(stray.id)
    fixed = true
    console.warn(
      `reconstructNoteLines: appending stray child ${stray.id} to parent ${parent.id} ` +
      `(parentNoteId points here but id not in containedNotes). ` +
      `strayContent='${stray.content.slice(0, 40)}'`
    )
    lines.push({ content: childPrefix + stray.content, noteId: stray.id })
    placed.add(stray.id)
    renderChildrenOf(stray, rawNotes, childrenByParent, lines, childDepth + 1, visited)
  }

  return fixed
}

export function indexChildrenByParent(rawNotes: Map<string, Note>): Map<string, Note[]> {
  const result = new Map<string, Note[]>()
  for (const note of rawNotes.values()) {
    if (note.parentNoteId == null) continue
    if (note.state === 'deleted') continue
    const list = result.get(note.parentNoteId)
    if (list) list.push(note)
    else result.set(note.parentNoteId, [note])
  }
  return result
}
