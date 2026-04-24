import { LineState, extractPrefix } from './LineState'
import {
  type EditorSelection,
  hasSelection as hasSel,
  selMin,
  selMax,
} from './EditorSelection'
import { getLineStartOffset, getLineAndLocalOffset } from './SelectionCoordinates'
import { type ParsedLine, parsedLineHasPrefix, buildLineText } from './ClipboardParser'
import { performSimilarityMatching } from './ContentSimilarity'
import { newSentinelNoteId, isRealNoteId } from '@/data/NoteIdSentinel'

export interface PasteResult {
  lines: LineState[]
  cursorLineIndex: number
  cursorPosition: number   // position within the cursor line's full text
}

// --- Full-line detection ---

/**
 * A selection is "full-line" if it starts at the beginning of a line
 * and ends at the end of a line.
 */
export function isFullLineSelection(
  lines: LineState[],
  selection: EditorSelection,
): boolean {
  if (!hasSel(selection)) return false
  const sMin = selMin(selection)
  const sMax = selMax(selection)

  const [startLine] = getLineAndLocalOffset(lines, sMin)
  const startOfLine = getLineStartOffset(lines, startLine)
  if (sMin !== startOfLine) return false

  const [endLine, endLocal] = getLineAndLocalOffset(lines, sMax)
  const endLineLen = lines[endLine]?.text.length ?? 0
  // End at the end of a line, or at position 0 of the next line (trailing newline)
  return endLocal === endLineLen || (endLocal === 0 && endLine > startLine)
}

// --- Main entry point ---

export function executePaste(
  lines: LineState[],
  focusedLineIndex: number,
  selection: EditorSelection,
  parsed: ParsedLine[],
  cutLines?: LineState[],
): PasteResult {
  // A trailing \n is a line terminator, not an extra empty line.
  // "line\n" = 1 complete line; "line\nfoo" = 1 line + trailing content.
  // split('\n') on "line\n" produces ["line", ""] — strip the empty terminator
  // but remember it was there so line-terminated content gets line-level paste.
  let trimmed = parsed
  let isLineTerminated = false
  if (parsed.length > 1) {
    const last = parsed[parsed.length - 1]!
    if (last.content === '' && last.bullet === '' && last.indent === 0) {
      trimmed = parsed.slice(0, -1)
      isLineTerminated = true
    }
  }

  if (trimmed.length === 0) {
    return { lines: [...lines], cursorLineIndex: focusedLineIndex, cursorPosition: 0 }
  }

  let result: PasteResult

  // Rule 5: single-line paste with no prefix = simple text insertion
  // Skip if line-terminated: "content\n" is a full line, not inline content.
  if (trimmed.length === 1 && !parsedLineHasPrefix(trimmed[0]!) && !isLineTerminated) {
    result = applySingleLinePaste(lines, focusedLineIndex, selection, trimmed[0]!.content)
  } else if (hasSel(selection) && isFullLineSelection(lines, selection)) {
    // Rule 3: full-line selection = clean replacement
    result = applyFullLineReplace(lines, selection, trimmed)
  } else if (isLineTerminated && !hasSel(selection)) {
    // Line-level insert: complete lines go before the cursor line, cursor line is preserved
    result = applyLineInsert(lines, focusedLineIndex, trimmed)
  } else {
    // Rules 1, 2, 4: multi-line or prefixed paste
    result = applyStructuredPaste(lines, focusedLineIndex, selection, trimmed)
  }

  // Recover noteIds from cut lines for any pasted lines that have none
  if (cutLines && cutLines.length > 0) {
    recoverCutNoteIds(result.lines, cutLines)
  }

  return result
}

// --- Rule 5: Simple single-line text insertion ---

function applySingleLinePaste(
  lines: LineState[],
  focusedLineIndex: number,
  selection: EditorSelection,
  text: string,
): PasteResult {
  // Work on fresh LineState copies so callers don't observe our in-place mutations.
  const newLines = lines.map(l => new LineState(l.text, l.cursorPosition, l.noteIds))

  if (hasSel(selection)) {
    // Surgical replace: delete the selected range across `newLines` and splice
    // `text` at the start. Merges startLine's prefix + text + endLine's suffix
    // onto startLine, preserving startLine's noteIds. Replaces a prior round-
    // trip (lines→text→lines via rebuildWithNoteIds) that matched by content
    // and silently dropped noteIds on non-exact matches.
    const [startLine, startLocal] = getLineAndLocalOffset(newLines, selMin(selection))
    const [endLine, endLocal] = getLineAndLocalOffset(newLines, selMax(selection))
    const startLineState = newLines[startLine]!
    const endLineState = newLines[endLine]!

    const prefix = startLineState.text.substring(0, startLocal)
    const suffix = endLineState.text.substring(endLocal)
    const newText = prefix + text + suffix
    const newCursor = startLocal + text.length

    // Mirror EditorState.replaceRangeSurgical's edge-id handling: if startLine's
    // content was fully consumed by the selection and no text is being inserted,
    // the surviving line is effectively endLine — adopt its noteIds.
    const takeEndIds = startLine !== endLine && startLocal === 0 && text === ''
    startLineState.updateFull(newText, newCursor)
    if (takeEndIds) startLineState.noteIds = endLineState.noteIds

    if (endLine > startLine) newLines.splice(startLine + 1, endLine - startLine)
    return { lines: newLines, cursorLineIndex: startLine, cursorPosition: newCursor }
  }

  // Insert at cursor
  const line = newLines[focusedLineIndex]
  if (!line) return { lines: newLines, cursorLineIndex: focusedLineIndex, cursorPosition: 0 }
  const cursor = line.cursorPosition
  const newText = line.text.substring(0, cursor) + text + line.text.substring(cursor)
  const newCursor = cursor + text.length
  line.updateFull(newText, newCursor)
  return { lines: newLines, cursorLineIndex: focusedLineIndex, cursorPosition: newCursor }
}

// --- Rule 3: Full-line replacement ---

function applyFullLineReplace(
  lines: LineState[],
  selection: EditorSelection,
  parsed: ParsedLine[],
): PasteResult {
  const sMin = selMin(selection)
  const sMax = selMax(selection)

  const [startLine] = getLineAndLocalOffset(lines, sMin)
  let [endLine, endLocal] = getLineAndLocalOffset(lines, sMax)
  // If selection ends at position 0 of a line, the previous line is the last selected
  if (endLocal === 0 && endLine > startLine) endLine--

  const before = lines.slice(0, startLine)
  const after = lines.slice(endLine + 1)
  const deletedLines = lines.slice(startLine, endLine + 1)
  const pastedTexts = parsed.map(p => buildLineText(p))

  // Content-match noteIds from deleted lines to pasted lines
  const pastedLines = rebuildWithNoteIds(pastedTexts, deletedLines)

  const newLines = [...before, ...pastedLines, ...after]
  const cursorLineIndex = before.length + pastedLines.length - 1
  const cursorLine = newLines[cursorLineIndex]
  const cursorPosition = cursorLine?.text.length ?? 0

  return { lines: newLines, cursorLineIndex, cursorPosition }
}

// --- Shared: prefix merging + indent shifting ---

function buildMergedPastedLines(destLine: LineState, parsed: ParsedLine[]): LineState[] {
  const destPrefix = extractPrefix(destLine.text)
  const destIndent = destPrefix.match(/^\t*/)?.[0].length ?? 0
  const destBullet = destPrefix.slice(destIndent)
  const indentDelta = destIndent - parsed[0]!.indent

  return parsed.map(p => {
    const newIndent = Math.max(0, p.indent + indentDelta)
    const bullet = p.bullet || destBullet
    return new LineState(
      buildLineText({ indent: newIndent, bullet, content: p.content }),
      undefined,
      [newSentinelNoteId('paste')],
    )
  })
}

// --- Line-level insert (line-terminated paste without selection) ---

function applyLineInsert(
  lines: LineState[],
  focusedLineIndex: number,
  parsed: ParsedLine[],
): PasteResult {
  const pastedLines = buildMergedPastedLines(lines[focusedLineIndex]!, parsed)

  const before = lines.slice(0, focusedLineIndex)
  const atAndAfter = lines.slice(focusedLineIndex)
  const resultLines = [...before, ...pastedLines, ...atAndAfter]
  const lastPastedIndex = before.length + pastedLines.length - 1
  const cursorPosition = resultLines[lastPastedIndex]?.text.length ?? 0
  return { lines: resultLines, cursorLineIndex: lastPastedIndex, cursorPosition }
}

// --- Rules 1, 2, 4: Structured paste (multi-line or prefixed) ---

function applyStructuredPaste(
  lines: LineState[],
  focusedLineIndex: number,
  selection: EditorSelection,
  parsed: ParsedLine[],
): PasteResult {
  // Determine the destination line(s) and split points
  let destLineIndex: number
  let splitOffset: number              // cursor offset within line.text
  let trailingText: string             // text after split in last affected line
  let trailingPrefix: string           // prefix for trailing half
  let trailingSourceLine: LineState    // line that supplied the trailing text
  let beforeLines: LineState[]         // lines before the affected region
  let afterLines: LineState[]          // lines after the affected region

  if (hasSel(selection)) {
    const fullText = lines.map(l => l.text).join('\n')
    const sMin = Math.max(0, Math.min(selMin(selection), fullText.length))
    const sMax = Math.max(0, Math.min(selMax(selection), fullText.length))

    const [startLine, startLocal] = getLineAndLocalOffset(lines, sMin)
    const [endLine, endLocal] = getLineAndLocalOffset(lines, sMax)

    destLineIndex = startLine
    splitOffset = startLocal
    trailingText = lines[endLine]!.text.substring(endLocal)
    trailingPrefix = extractPrefix(lines[endLine]!.text)
    trailingSourceLine = lines[endLine]!
    beforeLines = lines.slice(0, startLine)
    afterLines = lines.slice(endLine + 1)
  } else {
    destLineIndex = focusedLineIndex
    const line = lines[destLineIndex]!
    splitOffset = line.cursorPosition
    trailingText = line.text.substring(splitOffset)
    trailingPrefix = extractPrefix(line.text)
    trailingSourceLine = line
    beforeLines = lines.slice(0, destLineIndex)
    afterLines = lines.slice(destLineIndex + 1)
  }

  const destLine = lines[destLineIndex]!
  const leadingText = destLine.text.substring(0, splitOffset)
  const leadingContent = leadingText.substring(extractPrefix(leadingText).length)

  const pastedLines = buildMergedPastedLines(destLine, parsed)

  // Identity flow mirrors insertTextAt: leading keeps destLine's noteIds;
  // trailing gets a SPLIT sentinel when leading also exists, else inherits its
  // source line's noteIds.
  const result: LineState[] = [...beforeLines]

  // Leading half (text before cursor on the destination line)
  const hasLeadingContent = leadingContent.length > 0
  if (hasLeadingContent) {
    result.push(new LineState(leadingText, undefined, destLine.noteIds))
  }

  // Pasted lines
  for (const pl of pastedLines) {
    result.push(pl)
  }

  // Trailing half (text after cursor / after selection end)
  const trailingContent = trailingText.substring(extractPrefix(trailingText).length)
  const hasTrailingContent = trailingContent.length > 0
  // Only preserve trailing half if it has actual content. Lines that are empty
  // or prefix-only (e.g. bare "☐ ") have nothing worth preserving — the pasted
  // content replaces them.
  if (hasTrailingContent) {
    const trailingLine = trailingPrefix + trailingContent
    const trailingNoteIds = !hasLeadingContent
      ? trailingSourceLine.noteIds
      : [newSentinelNoteId('split')]
    result.push(new LineState(trailingLine, undefined, trailingNoteIds))
  }

  // Remaining lines after the affected region
  result.push(...afterLines)

  // Cursor goes to end of last pasted line
  const lastPastedIndex = (hasLeadingContent ? beforeLines.length + 1 : beforeLines.length) + pastedLines.length - 1
  const cursorLine = result[lastPastedIndex]
  const cursorPosition = cursorLine?.text.length ?? 0

  return { lines: result, cursorLineIndex: lastPastedIndex, cursorPosition }
}

/**
 * Recovers noteIds from cut lines onto pasted lines that have no noteIds.
 * Matches by content (excluding prefix) so indent changes from paste don't prevent recovery.
 */
function recoverCutNoteIds(resultLines: LineState[], cutLines: LineState[]): void {
  // Build a map from content (prefix-stripped) to noteIds from cut lines
  const contentToNoteIds = new Map<string, string[][]>()
  for (const cl of cutLines) {
    if (cl.noteIds.length === 0) continue
    const content = cl.text.substring(extractPrefix(cl.text).length)
    const entries = contentToNoteIds.get(content)
    if (entries) entries.push(cl.noteIds)
    else contentToNoteIds.set(content, [cl.noteIds])
  }

  for (const line of resultLines) {
    // Sentinels are placeholders that should be replaced by cut ids, same as
    // empty noteIds. Only skip lines with a real doc id already.
    if (isRealNoteId(line.noteIds[0])) continue
    const content = line.text.substring(extractPrefix(line.text).length)
    const entries = contentToNoteIds.get(content)
    if (entries && entries.length > 0) {
      line.noteIds = entries.shift()!
    }
  }
}

/** Rebuilds LineState array from new texts, matching noteIds from old lines via content matching. */
function rebuildWithNoteIds(newTexts: string[], oldLines: LineState[]): LineState[] {
  const contentToOldIndices = new Map<string, number[]>()
  oldLines.forEach((line, index) => {
    const indices = contentToOldIndices.get(line.text)
    if (indices) indices.push(index)
    else contentToOldIndices.set(line.text, [index])
  })

  const matchedNoteIds: (string[] | null)[] = new Array(newTexts.length).fill(null) as (string[] | null)[]
  const oldConsumed = new Array(oldLines.length).fill(false) as boolean[]

  // Phase 1: Exact content match
  newTexts.forEach((text, index) => {
    const indices = contentToOldIndices.get(text)
    if (indices && indices.length > 0) {
      const oldIdx = indices.shift()!
      matchedNoteIds[index] = oldLines[oldIdx]!.noteIds
      oldConsumed[oldIdx] = true
    }
  })

  // Phase 2: Similarity-based matching for modifications and splits.
  performSimilarityMatching(
    new Set(newTexts.map((_, i) => i).filter((i) => matchedNoteIds[i] == null)),
    oldLines.map((_, i) => i).filter((i) => !oldConsumed[i]),
    (idx) => oldLines[idx]!.text,
    (idx) => newTexts[idx]!,
    (oldIdx, newIdx) => {
      matchedNoteIds[newIdx] = oldLines[oldIdx]!.noteIds
      oldConsumed[oldIdx] = true
    },
  )

  return newTexts.map((t, i) => {
    // If content-match didn't find a prior id, stamp a PASTE sentinel so
    // save-time attribution can trace this back to the paste path.
    const ids = matchedNoteIds[i] ?? [newSentinelNoteId('paste')]
    return new LineState(t, undefined, ids)
  })
}
