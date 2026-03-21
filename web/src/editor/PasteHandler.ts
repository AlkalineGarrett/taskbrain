import { LineState, extractPrefix } from './LineState'
import {
  type EditorSelection,
  hasSelection as hasSel,
  selMin,
  selMax,
} from './EditorSelection'
import { getLineStartOffset, getLineAndLocalOffset } from './SelectionCoordinates'
import { type ParsedLine, parsedLineHasPrefix, buildLineText } from './ClipboardParser'

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
  if (parsed.length === 0) {
    return { lines: [...lines], cursorLineIndex: focusedLineIndex, cursorPosition: 0 }
  }

  let result: PasteResult

  // Rule 5: single-line paste with no prefix = simple text insertion
  if (parsed.length === 1 && !parsedLineHasPrefix(parsed[0]!)) {
    result = applySingleLinePaste(lines, focusedLineIndex, selection, parsed[0]!.content)
  } else if (hasSel(selection) && isFullLineSelection(lines, selection)) {
    // Rule 3: full-line selection = clean replacement
    result = applyFullLineReplace(lines, selection, parsed)
  } else {
    // Rules 1, 2, 4: multi-line or prefixed paste
    result = applyStructuredPaste(lines, focusedLineIndex, selection, parsed)
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
  const newLines = lines.map(l => new LineState(l.text, l.cursorPosition, l.noteIds))

  if (hasSel(selection)) {
    // Replace selection with text
    const fullText = lines.map(l => l.text).join('\n')
    const sMin = Math.max(0, Math.min(selMin(selection), fullText.length))
    const sMax = Math.max(0, Math.min(selMax(selection), fullText.length))
    const newText = fullText.substring(0, sMin) + text + fullText.substring(sMax)
    const cursorPos = sMin + text.length
    const rebuilt = rebuildWithNoteIds(newText.split('\n'), lines)
    const [lineIdx, localOff] = getLineAndLocalOffset(rebuilt, cursorPos)
    rebuilt[lineIdx]?.updateFull(rebuilt[lineIdx]!.text, localOff)
    return { lines: rebuilt, cursorLineIndex: lineIdx, cursorPosition: localOff }
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

// --- Rules 1, 2, 4: Structured paste (multi-line or prefixed) ---

function applyStructuredPaste(
  lines: LineState[],
  focusedLineIndex: number,
  selection: EditorSelection,
  parsed: ParsedLine[],
): PasteResult {
  // Determine the destination line(s) and split points
  let destLineIndex: number
  let splitOffset: number         // cursor offset within line.text
  let trailingText: string        // text after split in last affected line
  let trailingPrefix: string      // prefix for trailing half
  let beforeLines: LineState[]    // lines before the affected region
  let afterLines: LineState[]     // lines after the affected region

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
    beforeLines = lines.slice(0, startLine)
    afterLines = lines.slice(endLine + 1)
  } else {
    destLineIndex = focusedLineIndex
    const line = lines[destLineIndex]!
    splitOffset = line.cursorPosition
    trailingText = line.text.substring(splitOffset)
    trailingPrefix = extractPrefix(line.text)
    beforeLines = lines.slice(0, destLineIndex)
    afterLines = lines.slice(destLineIndex + 1)
  }

  const destLine = lines[destLineIndex]!
  const destPrefix = extractPrefix(destLine.text)
  const destIndent = destPrefix.match(/^\t*/)?.[0].length ?? 0
  const destBullet = destPrefix.slice(destIndent)
  const leadingText = destLine.text.substring(0, splitOffset)
  const leadingContent = leadingText.substring(extractPrefix(leadingText).length)

  // Rule 4: compute indent delta
  const firstPastedIndent = parsed[0]!.indent
  const indentDelta = destIndent - firstPastedIndent

  // Rule 1: apply prefix merging + Rule 4: indent shifting
  const pastedLines = parsed.map(p => {
    const newIndent = Math.max(0, p.indent + indentDelta)
    const bullet = p.bullet || destBullet  // source wins, adopt dest if absent
    return new LineState(buildLineText({ indent: newIndent, bullet, content: p.content }))
  })

  // Rule 2: build leading half, pasted lines, trailing half
  const result: LineState[] = [...beforeLines]

  // Leading half (text before cursor on the destination line)
  const hasLeadingContent = leadingContent.length > 0
  if (hasLeadingContent) {
    result.push(new LineState(leadingText))
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
    result.push(new LineState(trailingLine))
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
    if (line.noteIds.length > 0) continue
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

  // Phase 2: Positional fallback
  newTexts.forEach((_, index) => {
    if (matchedNoteIds[index] == null && index < oldLines.length && !oldConsumed[index]) {
      matchedNoteIds[index] = oldLines[index]!.noteIds
      oldConsumed[index] = true
    }
  })

  return newTexts.map((t, i) => new LineState(t, undefined, matchedNoteIds[i] ?? []))
}
