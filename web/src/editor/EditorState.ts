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
import * as MTF from './MoveTargetFinder'
import * as ME from './MoveExecutor'

const DOUBLE_SPACE_THRESHOLD_MS = 250

export class EditorState {
  lines: LineState[] = [new LineState('')]
  focusedLineIndex = 0
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

    const fullText = this.text
    const [selStart, selEnd] = this.getEffectiveSelectionRange()
    const newText = fullText.substring(0, selStart) + fullText.substring(selEnd)
    const newCursorPos = selStart

    this.updateFromText(newText)
    this.clearSelection()

    const [lineIndex, localOffset] = this.getLineAndLocalOffset(newCursorPos)
    this.focusedLineIndex = lineIndex
    this.lines[lineIndex]?.updateFull(this.lines[lineIndex]!.text, localOffset)

    this.requestFocusUpdate()
    this.notifyChange()
    return newCursorPos
  }

  replaceSelectionInternal(replacement: string): number {
    const fullText = this.text
    let insertPos: number
    let newText: string

    if (this.hasSelection) {
      const [selStart, selEnd] = this.getEffectiveSelectionRange()
      insertPos = selStart
      newText = fullText.substring(0, selStart) + replacement + fullText.substring(selEnd)
    } else {
      const currentLine = this.lines[this.focusedLineIndex]
      if (!currentLine) return 0
      insertPos = this.getLineStartOffset(this.focusedLineIndex) + currentLine.cursorPosition
      newText = fullText.substring(0, insertPos) + replacement + fullText.substring(insertPos)
    }

    const newCursorPos = insertPos + replacement.length

    this.updateFromText(newText)
    this.removeEmptyLineAtCursor(newCursorPos)
    this.clearSelection()

    const [lineIndex, localOffset] = this.getLineAndLocalOffset(newCursorPos)
    this.focusedLineIndex = lineIndex
    this.lines[lineIndex]?.updateFull(this.lines[lineIndex]!.text, localOffset)

    this.requestFocusUpdate()
    this.notifyChange()
    return newCursorPos
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

  indentInternal(): void {
    const linesToIndent = this.getSelectedLineIndices()
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

  unindentInternal(): void {
    const linesToUnindent = this.getSelectedLineIndices()
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

  private getSelectedLineIndices(): number[] {
    if (!this.hasSelection) return [this.focusedLineIndex]
    const startLine = this.getLineAndLocalOffset(selMin(this.selection))[0]
    const endLine = this.getLineAndLocalOffset(selMax(this.selection))[0]
    const result: number[] = []
    for (let i = startLine; i <= endLine; i++) result.push(i)
    return result
  }

  toggleBulletInternal(): void {
    this.currentLine?.toggleBullet()
    this.requestFocusUpdate()
    this.notifyChange()
  }

  toggleCheckboxInternal(): void {
    this.currentLine?.toggleCheckbox()
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
    const result = ME.calculateMove(
      this.lines, sourceFirst, sourceLast, targetIndex,
      this.focusedLineIndex, selectionForCalc,
    )
    if (!result) return null

    this.lines = result.newLines.map((t) => new LineState(t))
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

  updateFromText(newText: string): void {
    const newLines = newText.split('\n')
    this.lines = newLines.map((t) => new LineState(t))
    this.focusedLineIndex = Math.max(0, Math.min(this.focusedLineIndex, this.lines.length - 1))
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
