import type { LineState } from './LineState'
import {
  type EditorSelection,
  hasSelection,
  selMin,
  selMax,
} from './EditorSelection'

export function getLineStartOffset(lines: LineState[], lineIndex: number): number {
  let offset = 0
  const end = Math.min(lineIndex, lines.length)
  for (let i = 0; i < end; i++) {
    offset += lines[i]!.text.length + 1
  }
  return offset
}

export function getLineAndLocalOffset(
  lines: LineState[],
  globalOffset: number,
): [lineIndex: number, localOffset: number] {
  let remaining = globalOffset
  for (let i = 0; i < lines.length; i++) {
    const lineLength = lines[i]!.text.length
    if (remaining <= lineLength) {
      return [i, remaining]
    }
    remaining -= lineLength + 1
  }
  const lastIdx = Math.max(lines.length - 1, 0)
  return [lastIdx, lines[lastIdx]?.text.length ?? 0]
}

export function getLineSelection(
  lines: LineState[],
  lineIndex: number,
  selection: EditorSelection,
): [number, number] | null {
  if (!hasSelection(selection) || lineIndex < 0 || lineIndex >= lines.length) {
    return null
  }

  const lineStart = getLineStartOffset(lines, lineIndex)
  const lineEnd = lineStart + lines[lineIndex]!.text.length

  const sMin = selMin(selection)
  const sMax = selMax(selection)

  if (sMax <= lineStart || sMin > lineEnd) return null

  const localStart = Math.max(0, Math.min(sMin - lineStart, lines[lineIndex]!.text.length))
  const localEnd = Math.max(0, Math.min(sMax - lineStart, lines[lineIndex]!.text.length))

  if (localStart === localEnd && lines[lineIndex]!.text.length > 0) return null

  return [localStart, localEnd]
}

export function getSelectedLineRange(
  lines: LineState[],
  selection: EditorSelection,
  focusedLineIndex: number,
): [number, number] {
  if (!hasSelection(selection)) {
    return [focusedLineIndex, focusedLineIndex]
  }
  const startLine = getLineAndLocalOffset(lines, selMin(selection))[0]
  const [endLine, endLocal] = getLineAndLocalOffset(lines, selMax(selection))

  const adjustedEndLine =
    endLocal === 0 && endLine > startLine ? endLine - 1 : endLine

  return [startLine, adjustedEndLine]
}

export function getEffectiveSelectionRange(
  fullText: string,
  selection: EditorSelection,
): [number, number] {
  const selStart = Math.max(0, Math.min(selMin(selection), fullText.length))
  const selEnd = Math.max(0, Math.min(selMax(selection), fullText.length))

  const extendedEnd = shouldExtendSelectionToNewline(fullText, selStart, selEnd)
    ? selEnd + 1
    : selEnd

  return [selStart, extendedEnd]
}

function shouldExtendSelectionToNewline(
  fullText: string,
  selStart: number,
  selEnd: number,
): boolean {
  if (selEnd >= fullText.length || fullText[selEnd] !== '\n') return false
  return selStart === 0 || fullText[selStart - 1] === '\n'
}
