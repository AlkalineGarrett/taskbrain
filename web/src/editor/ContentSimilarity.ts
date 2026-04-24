/**
 * Content similarity utilities for matching edited lines to their originals.
 *
 * Used by line matching algorithms (NoteRepository.matchLinesToIds, EditorState.updateFromText)
 * to assign note IDs to the line fragment with the highest proportion of original content
 * after a line split.
 */

import { newSentinelNoteId } from '@/data/NoteIdSentinel'

/**
 * Returns the proportion of oldContent that appears in newContent,
 * measured by longest common subsequence length / old content length.
 * Returns 0 early if the strings share no characters.
 */
export function contentOverlapProportion(oldContent: string, newContent: string): number {
  if (oldContent.length === 0) return 0
  if (!sharesAnyCharacter(oldContent, newContent)) return 0
  return lcsLength(oldContent, newContent) / oldContent.length
}

function sharesAnyCharacter(a: string, b: string): boolean {
  const charSet = new Set(a)
  for (const ch of b) {
    if (charSet.has(ch)) return true
  }
  return false
}

/**
 * Computes the length of the longest common subsequence between two strings.
 * Space-optimized to O(min(m, n)).
 */
export function lcsLength(a: string, b: string): number {
  if (a.length === 0 || b.length === 0) return 0
  const m = a.length
  const n = b.length
  let prev = new Array<number>(n + 1).fill(0)
  let curr = new Array<number>(n + 1).fill(0)
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      curr[j] =
        a[i - 1] === b[j - 1] ? prev[j - 1]! + 1 : Math.max(prev[j]!, curr[j - 1]!)
    }
    const temp = prev
    prev = curr
    curr = temp
    curr.fill(0)
  }
  return prev[n]!
}

/**
 * Matches unmatched new lines to unconsumed old lines by content similarity.
 *
 * For each (old, new) pair, computes the proportion of old content present in the
 * new content (via LCS). Greedily assigns the highest-proportion match first, ensuring
 * that when a line is split, the fragment with more original content keeps the ID.
 */
export function performSimilarityMatching(
  unmatchedNewIndices: Set<number>,
  unconsumedOldIndices: number[],
  getOldContent: (idx: number) => string,
  getNewContent: (idx: number) => string,
  onMatch: (oldIdx: number, newIdx: number) => void,
): void {
  interface Match {
    oldIdx: number
    newIdx: number
    proportion: number
  }

  const candidates: Match[] = []
  for (const oldIdx of unconsumedOldIndices) {
    const oldContent = getOldContent(oldIdx)
    for (const newIdx of unmatchedNewIndices) {
      const proportion = contentOverlapProportion(oldContent, getNewContent(newIdx))
      if (proportion > 0) {
        candidates.push({ oldIdx, newIdx, proportion })
      }
    }
  }
  candidates.sort((a, b) => b.proportion - a.proportion)
  const matchedOld = new Set<number>()
  const matchedNew = new Set<number>()
  for (const match of candidates) {
    if (matchedOld.has(match.oldIdx) || matchedNew.has(match.newIdx)) continue
    onMatch(match.oldIdx, match.newIdx)
    matchedOld.add(match.oldIdx)
    matchedNew.add(match.newIdx)
  }
}

/**
 * Determines which half of a split line should keep the noteIds.
 *
 * When noteIdContentLengths is available (from a prior merge), each noteId is assigned
 * to the half that contains more of its original content. This correctly distributes
 * noteIds back to their original lines after a merge–split round-trip.
 *
 * @returns [beforeNoteIds, afterNoteIds]
 */
export function splitNoteIds(
  noteIds: string[],
  beforeContentLen: number,
  afterContentLen: number,
  beforeHasContent: boolean,
  afterHasContent: boolean,
  noteIdContentLengths: number[] = [],
): [string[], string[]] {
  let before: string[]
  let after: string[]
  if (!beforeHasContent && afterHasContent) { before = []; after = noteIds }
  else if (beforeHasContent && !afterHasContent) { before = noteIds; after = [] }
  else if (noteIds.length > 1 && noteIdContentLengths.length === noteIds.length) {
    [before, after] = distributeNoteIdsByOverlap(noteIds, beforeContentLen, noteIdContentLengths)
  } else if (beforeContentLen >= afterContentLen) { before = noteIds; after = [] }
  else { before = []; after = noteIds }
  // Stamp SPLIT sentinels on any content-bearing side without an id so
  // save-time attribution ("where did this fresh doc come from?") is consistent.
  return [
    stampSplitSentinelIfNeeded(before, beforeHasContent),
    stampSplitSentinelIfNeeded(after, afterHasContent),
  ]
}

function stampSplitSentinelIfNeeded(ids: string[], hasContent: boolean): string[] {
  return ids.length === 0 && hasContent ? [newSentinelNoteId('split')] : ids
}

function distributeNoteIdsByOverlap(
  noteIds: string[],
  splitPos: number,
  contentLengths: number[],
): [string[], string[]] {
  const beforeIds: string[] = []
  const afterIds: string[] = []
  let offset = 0

  for (let i = 0; i < noteIds.length; i++) {
    const len = contentLengths[i]!
    const end = offset + len
    const overlapBefore = Math.max(0, Math.min(end, splitPos) - offset)
    const overlapAfter = Math.max(0, end - Math.max(offset, splitPos))

    if (overlapBefore >= overlapAfter) {
      beforeIds.push(noteIds[i]!)
    } else {
      afterIds.push(noteIds[i]!)
    }
    offset = end
  }

  return [beforeIds, afterIds]
}
