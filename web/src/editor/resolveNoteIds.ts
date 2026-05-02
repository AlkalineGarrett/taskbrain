import type { NoteLine } from '@/data/Note'
import { newSentinelNoteId } from '@/data/NoteIdSentinel'
import { extractPrefix } from './LineState'

/**
 * Resolves noteId conflicts at save time.
 *
 * When multiple lines claim the same noteId (e.g., after a split), the line with
 * the most content (excluding prefix) keeps the noteId. Others get a fresh
 * `split` sentinel so the save planner allocates a new Firestore document.
 *
 * Lines arriving with no primary id at all also get a `split` sentinel — the
 * editor is supposed to stamp a sentinel at edit time, but this is the
 * defensive scaffolding so structural identity is unambiguous at save entry.
 *
 * For lines with multiple noteIds (from merges), the first (primary) noteId is used.
 */
export function resolveNoteIds(
  contentLines: string[],
  lineNoteIds: string[][],
): NoteLine[] {
  interface LineCandidate {
    index: number
    noteId: string
    contentLength: number
  }

  const candidates: LineCandidate[] = []
  for (let i = 0; i < contentLines.length; i++) {
    const ids = lineNoteIds[i] ?? []
    const primaryId = ids[0]
    if (!primaryId) continue
    const prefix = extractPrefix(contentLines[i]!)
    const contentLength = contentLines[i]!.length - prefix.length
    candidates.push({ index: i, noteId: primaryId, contentLength })
  }

  // Group by noteId, pick winner (longest content)
  const noteIdWinner = new Map<string, number>()
  const grouped = new Map<string, LineCandidate[]>()
  for (const c of candidates) {
    const group = grouped.get(c.noteId)
    if (group) {
      group.push(c)
    } else {
      grouped.set(c.noteId, [c])
    }
  }
  for (const [noteId, group] of grouped) {
    let best = group[0]!
    for (let i = 1; i < group.length; i++) {
      if (group[i]!.contentLength > best.contentLength) {
        best = group[i]!
      }
    }
    noteIdWinner.set(noteId, best.index)
  }

  return contentLines.map((content, index) => {
    const ids = lineNoteIds[index] ?? []
    const primaryId = ids[0] ?? null
    const resolvedId =
      primaryId != null && noteIdWinner.get(primaryId) === index
        ? primaryId
        : newSentinelNoteId('split')
    return { content, noteId: resolvedId }
  })
}
