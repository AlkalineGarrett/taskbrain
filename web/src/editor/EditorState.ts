import { LineState } from './LineState'
import {
  type EditorSelection,
  SELECTION_NONE,
  hasSelection as hasSel,
  selMin,
  selMax,
} from './EditorSelection'
import * as SC from './SelectionCoordinates'
import * as IU from './IndentationUtils'
import { performSimilarityMatching } from './ContentSimilarity'
import { newSentinelNoteId } from '@/data/NoteIdSentinel'
import * as MTF from './MoveTargetFinder'
import * as ME from './MoveExecutor'

const DOUBLE_SPACE_THRESHOLD_MS = 250

export class EditorState {
  lines: LineState[] = [new LineState('')]
  focusedLineIndex = 0
  parentNoteId: string = ''
  selection: EditorSelection = SELECTION_NONE
  /** Anchor point for shift+click/arrow selection. Global character offset. */
  selectionAnchor = -1

  onTextChange: ((text: string) => void) | null = null
  /** Called when selection changes (without text changing). Triggers re-render without marking dirty. */
  onSelectionChange: (() => void) | null = null

  private lastSpaceIndentTime = 0
  private _stateVersion = 0

  get stateVersion(): number {
    return this._stateVersion
  }

  requestFocusUpdate(): void {
    this._stateVersion++
    this.onSelectionChange?.()
  }

  get text(): string {
    return this.lines.map((l) => l.text).join('\n')
  }

  get currentLine(): LineState | null {
    return this.lines[this.focusedLineIndex] ?? null
  }

  get hasSelection(): boolean {
    return hasSel(this.selection)
  }

  getLineStartOffset(lineIndex: number): number {
    return SC.getLineStartOffset(this.lines, lineIndex)
  }

  getLineAndLocalOffset(globalOffset: number): [number, number] {
    return SC.getLineAndLocalOffset(this.lines, globalOffset)
  }

  getLineSelection(lineIndex: number): [number, number] | null {
    return SC.getLineSelection(this.lines, lineIndex, this.selection)
  }

  setSelection(start: number, end: number): void {
    this.selection = { start, end }
    const cursorPos = selMin(this.selection)
    const [lineIndex, localOffset] = this.getLineAndLocalOffset(cursorPos)
    this.focusedLineIndex = lineIndex
    this.lines[lineIndex]?.updateFull(this.lines[lineIndex]!.text, localOffset)
    this.notifySelectionChange()
  }

  clearSelection(): void {
    const hadSelection = hasSel(this.selection)
    this.selection = SELECTION_NONE
    if (hadSelection) this.notifySelectionChange()
  }

  getEffectiveSelectionRange(): [number, number] {
    return SC.getEffectiveSelectionRange(this.text, this.selection)
  }

  getSelectedText(): string {
    if (!this.hasSelection) return ''
    const fullText = this.text
    const [selStart, selEnd] = this.getEffectiveSelectionRange()
    return fullText.substring(selStart, selEnd)
  }

  deleteSelectionInternal(): number {
    if (!this.hasSelection) return -1
    const [selStart, selEnd] = this.getEffectiveSelectionRange()
    const [lineA, offsetA] = this.getLineAndLocalOffset(selStart)
    const [lineB, offsetB] = this.getLineAndLocalOffset(selEnd)
    this.replaceRangeSurgical(lineA, offsetA, lineB, offsetB, '')
    this.clearSelection()
    this.requestFocusUpdate()
    this.notifyChange()
    return selStart
  }

  replaceSelectionInternal(replacement: string): number {
    let selStart: number
    let selEnd: number
    if (this.hasSelection) {
      [selStart, selEnd] = this.getEffectiveSelectionRange()
    } else {
      const currentLine = this.lines[this.focusedLineIndex]
      if (!currentLine) return 0
      selStart = this.getLineStartOffset(this.focusedLineIndex) + currentLine.cursorPosition
      selEnd = selStart
    }
    const [lineA, offsetA] = this.getLineAndLocalOffset(selStart)
    const [lineB, offsetB] = this.getLineAndLocalOffset(selEnd)
    this.replaceRangeSurgical(lineA, offsetA, lineB, offsetB, replacement)
    const newCursorPos = selStart + replacement.length
    this.removeEmptyLineAtCursor(newCursorPos)
    this.clearSelection()
    this.requestFocusUpdate()
    this.notifyChange()
    return newCursorPos
  }

  /**
   * Surgically replaces the range `[lineA:offsetA, lineB:offsetB)` with
   * `replacement` (which may contain newlines), preserving noteIds on the
   * edge lines (lineA and lineB). Middle lines whose content is entirely
   * deleted are removed along with their noteIds; newly introduced middle
   * lines from a multi-line replacement start with empty noteIds.
   *
   * Replaces the legacy roundtrip through `updateFromText` which reconciled
   * noteIds by content match and silently dropped them on non-exact matches
   * (the root cause of cut/paste-induced noteId wipes).
   */
  private replaceRangeSurgical(
    lineA: number,
    offsetA: number,
    lineB: number,
    offsetB: number,
    replacement: string,
  ): void {
    const a = this.lines[lineA]
    const b = this.lines[lineB]
    if (!a || !b) return

    const prefix = a.text.substring(0, offsetA)
    const suffix = b.text.substring(offsetB)
    const parts = replacement.split('\n')

    if (parts.length === 1) {
      // No newlines in replacement: collapse to a single line.
      // If lineA's full content was consumed by the selection (offsetA == 0)
      // and the replacement's first part is empty, the surviving text comes
      // entirely from lineB's suffix — so lineB's noteIds should carry, not
      // lineA's. Otherwise keep lineA's noteIds (the dominant case for
      // partial edits inside a line or spanning a partial prefix of A).
      const newText = prefix + parts[0]! + suffix
      const takeLineBIds = lineA !== lineB && offsetA === 0 && parts[0]! === ''
      if (takeLineBIds) {
        a.updateFull(newText, 0)
        a.noteIds = b.noteIds
      } else {
        a.updateFull(newText, offsetA + parts[0]!.length)
      }
      if (lineA !== lineB) this.lines.splice(lineA + 1, lineB - lineA)
      this.focusedLineIndex = lineA
      return
    }

    // Replacement crosses line boundaries.
    const first = parts[0]!
    const last = parts[parts.length - 1]!
    const middle = parts.slice(1, -1)
    a.updateFull(prefix + first, offsetA + first.length)

    if (lineA === lineB) {
      // One line split into multiple: everything after offsetA becomes new lines.
      // Stamp a sentinel on each newly introduced LineState so the save layer
      // can attribute fresh-doc allocations to the surgical-replace path.
      const newLast = new LineState(last + suffix, last.length, [newSentinelNoteId('surgical')])
      const mid = middle.map(t => new LineState(t, undefined, [newSentinelNoteId('surgical')]))
      this.lines.splice(lineA + 1, 0, ...mid, newLast)
      this.focusedLineIndex = lineA + 1 + middle.length
    } else {
      // Multi-line selection: reuse lineB as the trailing line so we retain its
      // noteIds for the suffix that survived the selection.
      b.updateFull(last + suffix, last.length)
      const mid = middle.map(t => new LineState(t, undefined, [newSentinelNoteId('surgical')]))
      this.lines.splice(lineA + 1, lineB - lineA - 1, ...mid)
      this.focusedLineIndex = lineA + 1 + middle.length
    }
  }

  /** Extends selection from anchor to the given global offset. Sets anchor from cursor if not yet set. */
  extendSelectionTo(globalOffset: number): void {
    if (this.selectionAnchor < 0) {
      this.selectionAnchor = this.getLineStartOffset(this.focusedLineIndex) +
        (this.lines[this.focusedLineIndex]?.cursorPosition ?? 0)
    }
    if (this.selectionAnchor === globalOffset) {
      this.clearSelection()
    } else {
      this.selection = { start: this.selectionAnchor, end: globalOffset }
    }
    const [lineIndex, localOffset] = this.getLineAndLocalOffset(globalOffset)
    this.focusedLineIndex = lineIndex
    this.lines[lineIndex]?.updateFull(this.lines[lineIndex]!.text, localOffset)
    this.requestFocusUpdate()
  }

  /** Gets the current cursor position as a global character offset. */
  getCursorGlobalOffset(): number {
    return this.getLineStartOffset(this.focusedLineIndex) +
      (this.lines[this.focusedLineIndex]?.cursorPosition ?? 0)
  }

  selectAll(): void {
    const fullText = this.text
    if (fullText.length > 0) {
      this.setSelection(0, fullText.length)
    }
  }

  handleSpaceWithSelectionInternal(): boolean {
    if (!this.hasSelection) return false

    const now = Date.now()
    const timeSinceLastIndent = now - this.lastSpaceIndentTime

    if (timeSinceLastIndent <= DOUBLE_SPACE_THRESHOLD_MS && this.lastSpaceIndentTime > 0) {
      this.unindentInternal()
      this.unindentInternal()
      this.lastSpaceIndentTime = 0
    } else {
      this.indentInternal()
      this.lastSpaceIndentTime = now
    }

    return true
  }

  indentInternal(hiddenIndices: Set<number> = new Set()): void {
    const linesToIndent = this.getSelectedLineIndices(hiddenIndices)
    const hadSelection = this.hasSelection
    const oldSelStart = this.selection.start
    const oldSelEnd = this.selection.end

    for (const lineIndex of linesToIndent) {
      this.lines[lineIndex]?.indent()
    }

    if (hadSelection) {
      const [startLine] = this.getLineAndLocalOffset(oldSelStart)
      const [endLine] = this.getLineAndLocalOffset(oldSelEnd)
      const tabsBeforeStart = linesToIndent.filter((i) => i <= startLine).length
      const tabsBeforeEnd = linesToIndent.filter((i) => i <= endLine).length
      this.selection = {
        start: oldSelStart + tabsBeforeStart,
        end: oldSelEnd + tabsBeforeEnd,
      }
    }

    this.requestFocusUpdate()
    this.notifyChange()
  }

  unindentInternal(hiddenIndices: Set<number> = new Set()): void {
    const linesToUnindent = this.getSelectedLineIndices(hiddenIndices)
    const hadSelection = this.hasSelection
    const oldSelStart = this.selection.start
    const oldSelEnd = this.selection.end
    const [startLine] = hadSelection ? this.getLineAndLocalOffset(oldSelStart) : [0]
    const [endLine] = hadSelection ? this.getLineAndLocalOffset(oldSelEnd) : [0]

    const unindentedLines: number[] = []
    for (const lineIndex of linesToUnindent) {
      if (this.lines[lineIndex]?.unindent()) {
        unindentedLines.push(lineIndex)
      }
    }

    if (unindentedLines.length > 0) {
      if (hadSelection) {
        const tabsRemovedBeforeStart = unindentedLines.filter((i) => i <= startLine).length
        const tabsRemovedBeforeEnd = unindentedLines.filter((i) => i <= endLine).length
        this.selection = {
          start: Math.max(0, oldSelStart - tabsRemovedBeforeStart),
          end: Math.max(0, oldSelEnd - tabsRemovedBeforeEnd),
        }
      }

      this.requestFocusUpdate()
      this.notifyChange()
    }
  }

  private getSelectedLineIndices(hiddenIndices: Set<number> = new Set()): number[] {
    if (!this.hasSelection) return [this.focusedLineIndex]
    const startLine = this.getLineAndLocalOffset(selMin(this.selection))[0]
    const endLine = this.getLineAndLocalOffset(selMax(this.selection))[0]
    const result: number[] = []
    for (let i = startLine; i <= endLine; i++) {
      if (!hiddenIndices.has(i)) result.push(i)
    }
    return result
  }

  toggleBulletInternal(): void {
    this.togglePrefixOnSelectedLines((line) => line.toggleBullet())
  }

  toggleCheckboxInternal(): void {
    this.togglePrefixOnSelectedLines((line) => line.toggleCheckbox())
  }

  private togglePrefixOnSelectedLines(toggle: (line: LineState) => void): void {
    const lineIndices = this.getSelectedLineIndices()
    const hadSelection = this.hasSelection
    const oldSelStart = this.selection.start
    const oldSelEnd = this.selection.end

    const deltas: { lineIndex: number; delta: number }[] = []
    for (const lineIndex of lineIndices) {
      const line = this.lines[lineIndex]
      if (!line) continue
      const lenBefore = line.text.length
      toggle(line)
      deltas.push({ lineIndex, delta: line.text.length - lenBefore })
    }

    if (hadSelection) {
      const [startLine] = this.getLineAndLocalOffset(oldSelStart)
      const [endLine] = this.getLineAndLocalOffset(oldSelEnd)
      let startAdjust = 0
      let endAdjust = 0
      for (const { lineIndex, delta } of deltas) {
        if (lineIndex <= startLine) startAdjust += delta
        if (lineIndex <= endLine) endAdjust += delta
      }
      this.selection = {
        start: Math.max(0, oldSelStart + startAdjust),
        end: Math.max(0, oldSelEnd + endAdjust),
      }
    }

    this.requestFocusUpdate()
    this.notifyChange()
  }

  // --- Move Lines ---

  getIndentLevel(lineIndex: number): number {
    return IU.getIndentLevel(this.lines, lineIndex)
  }

  getLogicalBlock(startIndex: number): [number, number] {
    return IU.getLogicalBlock(this.lines, startIndex)
  }

  getSelectedLineRange(): [number, number] {
    return SC.getSelectedLineRange(this.lines, this.selection, this.focusedLineIndex)
  }

  getMoveTarget(moveUp: boolean, hiddenIndices: Set<number> = new Set()): number | null {
    return MTF.getMoveTarget(
      this.lines, this.hasSelection, this.selection, this.focusedLineIndex, moveUp, hiddenIndices,
    )
  }

  wouldOrphanChildren(): boolean {
    return MTF.wouldOrphanChildren(
      this.lines, this.hasSelection, this.selection, this.focusedLineIndex,
    )
  }

  moveLinesInternal(sourceFirst: number, sourceLast: number, targetIndex: number): [number, number] | null {
    const selectionForCalc = this.hasSelection ? this.selection : null
    // Capture noteIds before move
    const oldNoteIds = this.lines.map((l) => l.noteIds)
    const result = ME.calculateMove(
      this.lines, sourceFirst, sourceLast, targetIndex,
      this.focusedLineIndex, selectionForCalc,
    )
    if (!result) return null

    // Reorder noteIds in parallel with text. If the reorder introduces a
    // line without a prior noteId entry (shouldn't happen, but defensive),
    // stamp a SURGICAL sentinel so save-time attribution catches it.
    const reorderedNoteIds = result.newLineOrder.map(
      (oldIdx) => oldNoteIds[oldIdx] ?? [newSentinelNoteId('surgical')],
    )
    this.lines = result.newLines.map((t, i) => new LineState(t, undefined, reorderedNoteIds[i]))
    this.focusedLineIndex = result.newFocusedLineIndex
    if (result.newSelection) {
      this.selection = result.newSelection
    }

    this.requestFocusUpdate()
    this.notifyChange()
    return result.newRange
  }

  insertAtEndOfCurrentLineInternal(textToInsert: string): void {
    const line = this.currentLine
    if (!line) return
    const lineText = line.text
    const prefix = lineText.length > 0 && !/\s$/.test(lineText) ? ' ' : ''
    const newLineText = lineText + prefix + textToInsert
    line.updateFull(newLineText, line.cursorPosition)
    this.notifyChange()
  }

  /**
   * @deprecated Lossy — reconciles noteIds by content match and silently drops any
   * line whose text doesn't exactly (or by LCS similarity) match an old line. Only
   * retained because the agent-result rewrite path has no structured alternative.
   * Do NOT reintroduce for editing operations (cut/paste/select-replace) — use
   * `replaceRangeSurgical` via `deleteSelectionInternal`/`replaceSelectionInternal`.
   */
  updateFromText(newText: string): void {
    const oldLines = this.lines
    const newLineTexts = newText.split('\n')

    // Build content-to-old-indices map for exact matching
    const contentToOldIndices = new Map<string, number[]>()
    oldLines.forEach((line, index) => {
      const indices = contentToOldIndices.get(line.text)
      if (indices) indices.push(index)
      else contentToOldIndices.set(line.text, [index])
    })

    const matchedNoteIds: (string[] | null)[] = new Array(newLineTexts.length).fill(null) as (string[] | null)[]
    const oldConsumed = new Array(oldLines.length).fill(false) as boolean[]

    // Phase 1: Exact content match
    newLineTexts.forEach((text, index) => {
      const indices = contentToOldIndices.get(text)
      if (indices && indices.length > 0) {
        const oldIdx = indices.shift()!
        matchedNoteIds[index] = oldLines[oldIdx]!.noteIds
        oldConsumed[oldIdx] = true
      }
    })

    // Phase 2: Similarity-based matching for modifications and splits.
    performSimilarityMatching(
      new Set(newLineTexts.map((_, i) => i).filter((i) => matchedNoteIds[i] == null)),
      oldLines.map((_, i) => i).filter((i) => !oldConsumed[i]),
      (idx) => oldLines[idx]!.text,
      (idx) => newLineTexts[idx]!,
      (oldIdx, newIdx) => {
        matchedNoteIds[newIdx] = oldLines[oldIdx]!.noteIds
        oldConsumed[oldIdx] = true
      },
    )

    this.lines = newLineTexts.map((t, i) => new LineState(t, undefined, matchedNoteIds[i] ?? []))
    this.focusedLineIndex = Math.max(0, Math.min(this.focusedLineIndex, this.lines.length - 1))

    if (this.parentNoteId && this.lines.length > 0) {
      const first = this.lines[0]!
      if (!first.noteIds.includes(this.parentNoteId)) {
        first.noteIds = [this.parentNoteId, ...first.noteIds]
      } else if (first.noteIds[0] !== this.parentNoteId) {
        first.noteIds = [this.parentNoteId, ...first.noteIds.filter(id => id !== this.parentNoteId)]
      }
    }
  }

  /**
   * Initializes editor lines with noteIds from loaded note data.
   * @param preserveCursor If true, restores cursor to the same line (by noteId)
   *   and position. Used for external change reloads.
   */
  initFromNoteLines(noteLines: Array<{ text: string; noteIds: string[] }>, preserveCursor = false): void {
    const prevLineIndex = this.focusedLineIndex
    const prevNoteId = this.lines[prevLineIndex]?.noteIds[0]
    const prevCursorPos = this.lines[prevLineIndex]?.cursorPosition ?? 0

    this.lines = noteLines.map((nl) => new LineState(nl.text, undefined, nl.noteIds))
    this.parentNoteId = noteLines[0]?.noteIds[0] ?? ''
    this.clearSelection()

    if (preserveCursor && prevNoteId) {
      const restoredIndex = this.lines.findIndex((l) => l.noteIds[0] === prevNoteId)
      if (restoredIndex >= 0) {
        this.focusedLineIndex = restoredIndex
        this.lines[restoredIndex]!.cursorPosition = Math.min(prevCursorPos, this.lines[restoredIndex]!.text.length)
      } else {
        this.focusedLineIndex = Math.min(prevLineIndex, this.lines.length - 1)
      }
    } else {
      this.focusedLineIndex = 0
    }
  }

  /** Updates noteIds on existing lines without disturbing editor state. */
  updateNoteIds(lineNoteIds: string[][]): void {
    for (let i = 0; i < this.lines.length && i < lineNoteIds.length; i++) {
      this.lines[i]!.noteIds = lineNoteIds[i]!
    }
  }

  private removeEmptyLineAtCursor(cursorPosition: number): void {
    if (this.lines.length <= 1) return
    const [lineIndex] = this.getLineAndLocalOffset(cursorPosition)
    if (lineIndex >= this.lines.length - 1) return
    const line = this.lines[lineIndex]!
    if (line.text === '' || line.content === '') {
      this.lines.splice(lineIndex, 1)
    }
  }

  notifyChange(): void {
    this.onTextChange?.(this.text)
  }

  notifySelectionChange(): void {
    this.onSelectionChange?.()
  }
}
