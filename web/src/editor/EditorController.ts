import { LineState } from './LineState'
import { EditorState } from './EditorState'
import { CommandType, UndoManager, type UndoSnapshot } from './UndoManager'
import * as LP from './LinePrefixes'
import { parseClipboardContent } from './ClipboardParser'
import { sortCompletedToBottom as sortCompleted } from './CompletedLineUtils'
import { executePaste } from './PasteHandler'
import { splitNoteIds } from './ContentSimilarity'

export enum OperationType {
  COMMAND_BULLET = 'COMMAND_BULLET',
  COMMAND_CHECKBOX = 'COMMAND_CHECKBOX',
  COMMAND_INDENT = 'COMMAND_INDENT',
  PASTE = 'PASTE',
  CUT = 'CUT',
  DELETE_SELECTION = 'DELETE_SELECTION',
  CHECKBOX_TOGGLE = 'CHECKBOX_TOGGLE',
  ALARM_SYMBOL = 'ALARM_SYMBOL',
  MOVE_LINES = 'MOVE_LINES',
  DIRECTIVE_EDIT = 'DIRECTIVE_EDIT',
  SORT_COMPLETED = 'SORT_COMPLETED',
}

export interface MoveButtonState {
  isEnabled: boolean
  isWarning: boolean
}

export class EditorController {
  readonly state: EditorState
  readonly undoManager: UndoManager
  /** Hidden line indices (completed lines when showCompleted=false). Set by the UI layer. */
  hiddenIndices: Set<number> = new Set()
  /** Line indices recently toggled from unchecked to checked. Visible at reduced opacity until save. */
  readonly recentlyCheckedIndices: Set<number> = new Set()
  /** Lines from the most recent cut, used to recover noteIds on paste. */
  private lastCutLines: LineState[] = []

  constructor(state: EditorState, undoManager?: UndoManager) {
    this.state = state
    this.undoManager = undoManager ?? new UndoManager()
  }

  // --- Undo/Redo ---

  get canUndo(): boolean { return this.undoManager.canUndo }
  get canRedo(): boolean { return this.undoManager.canRedo }

  commitUndoState(continueEditing = false): void {
    this.undoManager.commitPendingUndoState(this.state.lines, this.state.focusedLineIndex)
    if (continueEditing) {
      this.undoManager.beginEditingLine(
        this.state.lines, this.state.focusedLineIndex, this.state.focusedLineIndex,
      )
    }
  }

  undo(): UndoSnapshot | null {
    const snapshot = this.undoManager.undo(this.state.lines, this.state.focusedLineIndex)
    if (!snapshot) return null
    this.restoreFromSnapshot(snapshot)
    this.undoManager.beginEditingLine(
      this.state.lines, this.state.focusedLineIndex, this.state.focusedLineIndex,
    )
    return snapshot
  }

  redo(): UndoSnapshot | null {
    const snapshot = this.undoManager.redo(this.state.lines, this.state.focusedLineIndex)
    if (!snapshot) return null
    this.restoreFromSnapshot(snapshot)
    this.undoManager.beginEditingLine(
      this.state.lines, this.state.focusedLineIndex, this.state.focusedLineIndex,
    )
    return snapshot
  }

  private restoreFromSnapshot(snapshot: UndoSnapshot): void {
    this.state.lines = snapshot.lineContents.map((t, i) =>
      new LineState(t, undefined, snapshot.lineNoteIds[i] ?? []),
    )
    this.state.focusedLineIndex = Math.max(0, Math.min(snapshot.focusedLineIndex, this.state.lines.length - 1))
    const line = this.state.lines[this.state.focusedLineIndex]
    if (line) {
      line.updateFull(line.text, Math.max(0, Math.min(snapshot.cursorPosition, line.text.length)))
    }
    this.state.clearSelection()
    this.state.requestFocusUpdate()
    this.state.notifyChange()
  }

  resetUndoHistory(): void {
    this.undoManager.reset()
  }

  // --- Operation Executor ---

  private executeOperation<T>(type: OperationType, action: () => T): T {
    this.handlePreOperation(type)
    const result = action()
    this.handlePostOperation(type)
    return result
  }

  private handlePreOperation(type: OperationType): void {
    const s = this.state
    const um = this.undoManager
    switch (type) {
      case OperationType.COMMAND_BULLET:
      case OperationType.COMMAND_CHECKBOX:
      case OperationType.CHECKBOX_TOGGLE:
        um.recordCommand(s.lines, s.focusedLineIndex, this.commandTypeFor(type))
        break
      case OperationType.COMMAND_INDENT:
        um.recordCommand(s.lines, s.focusedLineIndex, CommandType.INDENT)
        break
      case OperationType.PASTE:
      case OperationType.CUT:
      case OperationType.DELETE_SELECTION:
        um.captureStateBeforeChange(s.lines, s.focusedLineIndex)
        break
      case OperationType.ALARM_SYMBOL:
      case OperationType.DIRECTIVE_EDIT:
      case OperationType.SORT_COMPLETED:
        um.commitPendingUndoState(s.lines, s.focusedLineIndex)
        um.captureStateBeforeChange(s.lines, s.focusedLineIndex)
        break
      case OperationType.MOVE_LINES:
        break // has its own undo handling
    }
  }

  private handlePostOperation(type: OperationType): void {
    const s = this.state
    const um = this.undoManager
    switch (type) {
      case OperationType.COMMAND_BULLET:
      case OperationType.COMMAND_CHECKBOX:
      case OperationType.CHECKBOX_TOGGLE:
        um.commitAfterCommand(s.lines, s.focusedLineIndex, this.commandTypeFor(type))
        break
      case OperationType.CUT:
      case OperationType.DELETE_SELECTION:
      case OperationType.DIRECTIVE_EDIT:
      case OperationType.SORT_COMPLETED:
        um.beginEditingLine(s.lines, s.focusedLineIndex, s.focusedLineIndex)
        break
      case OperationType.PASTE:
        // Don't begin editing — paste should be its own undo boundary
        // so the next keystroke creates a separate undo entry
        um.commitPendingUndoState(s.lines, s.focusedLineIndex)
        break
      case OperationType.COMMAND_INDENT:
      case OperationType.ALARM_SYMBOL:
      case OperationType.MOVE_LINES:
        break
    }
  }

  private commandTypeFor(type: OperationType): CommandType {
    switch (type) {
      case OperationType.COMMAND_BULLET: return CommandType.BULLET
      case OperationType.COMMAND_CHECKBOX:
      case OperationType.CHECKBOX_TOGGLE: return CommandType.CHECKBOX
      case OperationType.COMMAND_INDENT: return CommandType.INDENT
      default: return CommandType.OTHER
    }
  }

  // --- Command Operations ---

  toggleBullet(): void {
    this.executeOperation(OperationType.COMMAND_BULLET, () => {
      this.state.toggleBulletInternal()
    })
  }

  toggleCheckbox(): void {
    const [startLine, endLine] = this.state.getSelectedLineRange()
    const uncheckedBefore: Map<number, boolean> = new Map()
    for (let i = startLine; i <= endLine; i++) {
      uncheckedBefore.set(i, this.state.lines[i]?.prefix.includes(LP.CHECKBOX_UNCHECKED) ?? false)
    }
    this.executeOperation(OperationType.COMMAND_CHECKBOX, () => {
      this.state.toggleCheckboxInternal()
    })
    for (const [lineIndex, wasUnchecked] of uncheckedBefore) {
      this.trackRecentlyChecked(lineIndex, wasUnchecked)
    }
  }

  indent(): void {
    this.executeOperation(OperationType.COMMAND_INDENT, () => {
      this.state.indentInternal(this.hiddenIndices)
    })
  }

  unindent(): void {
    this.executeOperation(OperationType.COMMAND_INDENT, () => {
      this.state.unindentInternal(this.hiddenIndices)
    })
  }

  paste(plainText: string, html?: string | null): void {
    this.executeOperation(OperationType.PASTE, () => {
      const parsed = parseClipboardContent(plainText, html ?? null)
      const cutLines = this.lastCutLines
      this.lastCutLines = []
      const result = executePaste(
        this.state.lines,
        this.state.focusedLineIndex,
        this.state.selection,
        parsed,
        cutLines.length > 0 ? cutLines : undefined,
      )
      this.state.lines = result.lines
      this.state.focusedLineIndex = result.cursorLineIndex
      this.state.lines[result.cursorLineIndex]?.updateFull(
        this.state.lines[result.cursorLineIndex]!.text,
        result.cursorPosition,
      )
      this.state.clearSelection()
      this.state.requestFocusUpdate()
      this.state.notifyChange()
    })
  }

  cutSelection(): string | null {
    if (!this.state.hasSelection) return null
    return this.executeOperation(OperationType.CUT, () => {
      // Capture cut lines (with noteIds) before deletion for paste recovery
      const [rangeFirst, rangeLast] = this.state.getSelectedLineRange()
      this.lastCutLines = this.state.lines.slice(rangeFirst, rangeLast + 1)
        .map(l => new LineState(l.text, undefined, [...l.noteIds]))

      const clipText = this.getSelectedTextWithPrefix()
      if (clipText.length > 0) {
        void navigator.clipboard.writeText(clipText)
        this.state.deleteSelectionInternal()
      }
      return clipText.length > 0 ? clipText : null
    })
  }

  deleteSelectionWithUndo(): void {
    if (!this.state.hasSelection) return
    this.executeOperation(OperationType.DELETE_SELECTION, () => {
      this.state.deleteSelectionInternal()
    })
  }

  insertAtEndOfCurrentLine(text: string): void {
    this.executeOperation(OperationType.ALARM_SYMBOL, () => {
      this.state.insertAtEndOfCurrentLineInternal(text)
    })
  }

  confirmDirectiveEdit(lineIndex: number, startOffset: number, endOffset: number, newText: string): void {
    this.executeOperation(OperationType.DIRECTIVE_EDIT, () => {
      const line = this.state.lines[lineIndex]
      if (!line) return
      const content = line.content
      if (startOffset < 0 || endOffset > content.length || startOffset > endOffset) return

      const newContent = content.substring(0, startOffset) + newText + content.substring(endOffset)
      line.updateContent(newContent, line.contentCursorPosition)
      this.state.notifyChange()
    })
  }

  // --- Move Lines ---

  getMoveUpState(): MoveButtonState {
    return {
      isEnabled: this.state.getMoveTarget(true, this.hiddenIndices) != null,
      isWarning: this.state.wouldOrphanChildren(),
    }
  }

  getMoveDownState(): MoveButtonState {
    return {
      isEnabled: this.state.getMoveTarget(false, this.hiddenIndices) != null,
      isWarning: this.state.wouldOrphanChildren(),
    }
  }

  moveUp(): boolean {
    const s = this.state
    let rangeFirst: number, rangeLast: number
    if (s.hasSelection) {
      ;[rangeFirst, rangeLast] = s.getSelectedLineRange()
    } else {
      ;[rangeFirst, rangeLast] = s.getLogicalBlock(s.focusedLineIndex)
    }
    const target = s.getMoveTarget(true, this.hiddenIndices)
    if (target == null) return false

    this.undoManager.recordMoveCommand(s.lines, s.focusedLineIndex, [rangeFirst, rangeLast])
    const newRange = s.moveLinesInternal(rangeFirst, rangeLast, target)
    if (newRange) {
      this.undoManager.updateMoveRange(newRange)
      this.undoManager.markContentChanged()
    }
    return newRange != null
  }

  moveDown(): boolean {
    const s = this.state
    let rangeFirst: number, rangeLast: number
    if (s.hasSelection) {
      ;[rangeFirst, rangeLast] = s.getSelectedLineRange()
    } else {
      ;[rangeFirst, rangeLast] = s.getLogicalBlock(s.focusedLineIndex)
    }
    const target = s.getMoveTarget(false, this.hiddenIndices)
    if (target == null) return false

    this.undoManager.recordMoveCommand(s.lines, s.focusedLineIndex, [rangeFirst, rangeLast])
    const newRange = s.moveLinesInternal(rangeFirst, rangeLast, target)
    if (newRange) {
      this.undoManager.updateMoveRange(newRange)
      this.undoManager.markContentChanged()
    }
    return newRange != null
  }

  /**
   * Sorts completed (checked) lines to the bottom of each sibling group.
   * Called at save time so undo restores the pre-sort order.
   * @returns true if any reordering occurred
   */
  sortCompletedToBottom(): boolean {
    ;(this.recentlyCheckedIndices as Set<number> & { clear(): void }).clear()
    const currentTexts = this.state.lines.map((l) => l.text)
    const sorted = sortCompleted(currentTexts)
    if (sorted.every((t, i) => t === currentTexts[i])) return false
    this.executeOperation(OperationType.SORT_COMPLETED, () => {
      this.state.lines.forEach((line, i) => {
        line.updateFull(sorted[i]!, Math.min(line.cursorPosition, sorted[i]!.length))
      })
      this.state.notifyChange()
    })
    return true
  }

  /** Returns the selected text, prepending the first line's prefix when selection starts at content boundary. */
  private getSelectedTextWithPrefix(): string {
    const text = this.state.getSelectedText()
    if (text.length === 0) return text

    // Only prepend prefix when selection starts exactly at the content boundary
    // (right after the prefix), meaning the full content from the start is selected.
    // Partial selections within content are returned as-is.
    const [selStart] = this.state.getEffectiveSelectionRange()
    const [lineIndex, selLocalOffset] = this.state.getLineAndLocalOffset(selStart)
    const firstLine = this.state.lines[lineIndex]
    if (firstLine) {
      const prefix = firstLine.prefix
      if (prefix.length > 0 && selLocalOffset === prefix.length) {
        return prefix + text
      }
    }
    return text
  }

  moveSelectionTo(targetGlobalOffset: number): void {
    if (!this.state.hasSelection) return
    const [selStart, selEnd] = this.state.getEffectiveSelectionRange()
    // No-op if dropping inside the selection
    if (targetGlobalOffset >= selStart && targetGlobalOffset <= selEnd) return

    this.undoManager.captureStateBeforeChange(this.state.lines, this.state.focusedLineIndex)

    // Get clipboard text with prefix (same as cut)
    const clipText = this.getSelectedTextWithPrefix()

    // Delete the selection
    this.state.deleteSelectionInternal()

    // Adjust target offset for removed text
    const adjustedTarget = targetGlobalOffset > selEnd
      ? targetGlobalOffset - (selEnd - selStart)
      : targetGlobalOffset

    // Position cursor at the drop target
    const [lineIndex, localOffset] = this.state.getLineAndLocalOffset(adjustedTarget)
    this.state.focusedLineIndex = lineIndex
    this.state.lines[lineIndex]?.updateFull(this.state.lines[lineIndex]!.text, localOffset)

    // Parse and paste using the structured paste system (same as paste)
    const parsed = parseClipboardContent(clipText, null)
    const result = executePaste(
      this.state.lines,
      this.state.focusedLineIndex,
      this.state.selection,
      parsed,
    )
    this.state.lines = result.lines
    this.state.focusedLineIndex = result.cursorLineIndex
    this.state.lines[result.cursorLineIndex]?.updateFull(
      this.state.lines[result.cursorLineIndex]!.text,
      result.cursorPosition,
    )
    this.state.clearSelection()

    this.state.requestFocusUpdate()
    this.state.notifyChange()
    this.undoManager.beginEditingLine(this.state.lines, this.state.focusedLineIndex, this.state.focusedLineIndex)
  }

  copySelection(): void {
    const text = this.getSelectedTextWithPrefix()
    if (text.length > 0) {
      void navigator.clipboard.writeText(text)
    }
  }

  clearSelection(): void {
    const selectionStart = Math.min(this.state.selection.start, this.state.selection.end)
    this.state.clearSelection()
    this.setCursorFromGlobalOffset(selectionStart)
  }

  // --- Text Input ---

  insertText(lineIndex: number, text: string): void {
    if (text.includes('\n')) {
      const parts = text.split('\n')
      parts.forEach((part, index) => {
        if (index > 0) {
          this.splitLine(lineIndex + index - 1)
        }
        if (part.length > 0) {
          this.insertTextAtCursor(lineIndex + index, part)
        }
      })
      return
    }

    if (this.state.hasSelection) {
      this.state.replaceSelectionInternal(text)
      return
    }

    this.insertTextAtCursor(lineIndex, text)
  }

  private insertTextAtCursor(lineIndex: number, text: string): void {
    const line = this.state.lines[lineIndex]
    if (!line) return
    const cursor = line.cursorPosition
    const newText = line.text.substring(0, cursor) + text + line.text.substring(cursor)
    line.updateFull(newText, cursor + text.length)
    this.state.requestFocusUpdate()
    this.state.notifyChange()
  }

  deleteBackward(lineIndex: number): void {
    if (this.state.hasSelection) {
      this.state.deleteSelectionInternal()
      this.undoManager.markContentChanged()
      return
    }

    const line = this.state.lines[lineIndex]
    if (!line) return
    const cursor = line.cursorPosition

    if (cursor <= line.prefix.length) {
      if (lineIndex <= 0) return
      // Skip hidden lines when merging backward
      let target = lineIndex - 1
      while (target >= 0 && this.hiddenIndices.has(target)) target--
      if (target < 0) return
      const previousLine = this.state.lines[target]
      if (!previousLine) return

      const currentHasContent = line.content.length > 0
      const previousHasContent = previousLine.content.length > 0

      if (previousHasContent) {
        // Previous line has content: merge current content into it
        this.mergeToPreviousLine(lineIndex, target)
      } else if (currentHasContent) {
        // Current has content, previous is empty: delete previous line, keep current
        this.deleteLineAndMergeIds(line, previousLine, target, target)
      } else {
        // Neither has content: keep previous, delete current
        this.deleteLineAndMergeIds(previousLine, line, lineIndex, target)
      }
      return
    }

    const newText = line.text.substring(0, cursor - 1) + line.text.substring(cursor)
    line.updateFull(newText, cursor - 1)
    this.state.requestFocusUpdate()
    this.state.notifyChange()
    this.undoManager.markContentChanged()
  }

  deleteForward(lineIndex: number): void {
    if (this.state.hasSelection) {
      this.state.deleteSelectionInternal()
      this.undoManager.markContentChanged()
      return
    }

    const line = this.state.lines[lineIndex]
    if (!line) return
    const cursor = line.cursorPosition

    if (cursor >= line.text.length) {
      // Skip hidden lines when merging forward
      let target = lineIndex + 1
      while (target < this.state.lines.length && this.hiddenIndices.has(target)) target++
      if (target < this.state.lines.length) {
        this.mergeNextLine(lineIndex, target)
      }
      return
    }

    const newText = line.text.substring(0, cursor) + line.text.substring(cursor + 1)
    line.updateFull(newText, cursor)
    this.state.requestFocusUpdate()
    this.state.notifyChange()
    this.undoManager.markContentChanged()
  }

  // --- Line Operations ---

  private getNewLinePrefix(currentPrefix: string): string {
    return currentPrefix.replace(LP.CHECKBOX_CHECKED, LP.CHECKBOX_UNCHECKED)
  }

  private createNewLineWithPrefix(
    lineIndex: number,
    newLineContent: string,
    currentPrefix: string,
    noteIds: string[] = [],
  ): void {
    const newLinePrefix = this.getNewLinePrefix(currentPrefix)
    const newLineText = newLinePrefix + newLineContent
    const newLineCursor = newLinePrefix.length
    this.state.lines.splice(lineIndex + 1, 0, new LineState(newLineText, newLineCursor, noteIds))
    this.state.focusedLineIndex = lineIndex + 1
    this.state.requestFocusUpdate()
    this.state.notifyChange()
  }

  splitLine(lineIndex: number): void {
    this.undoManager.prepareForStructuralChange(this.state.lines, this.state.focusedLineIndex)
    this.state.clearSelection()
    const line = this.state.lines[lineIndex]
    if (!line) return
    const cursor = line.cursorPosition
    const prefix = line.prefix
    const beforeCursor = line.text.substring(0, cursor)
    const afterCursor = line.text.substring(cursor)
    const noteIds = line.noteIds

    const beforeHasContent = beforeCursor.length > prefix.length
    const afterHasContent = afterCursor.length > 0
    const [currentNoteIds, newNoteIds] = splitNoteIds(
      noteIds,
      beforeCursor.length - prefix.length,
      afterCursor.length,
      beforeHasContent,
      afterHasContent,
      line.noteIdContentLengths,
    )

    line.updateFull(beforeCursor, beforeCursor.length)
    line.noteIds = currentNoteIds

    if (cursor >= prefix.length) {
      this.createNewLineWithPrefix(lineIndex, afterCursor, prefix, newNoteIds)
    } else {
      this.state.lines.splice(lineIndex + 1, 0, new LineState(afterCursor, 0, newNoteIds))
      this.state.focusedLineIndex = lineIndex + 1
      this.state.requestFocusUpdate()
      this.state.notifyChange()
    }

    this.undoManager.continueAfterStructuralChange(this.state.focusedLineIndex)
  }

  /**
   * Deletes a line and transfers its noteIds to the surviving line.
   * Used by deleteBackward when one or both lines have no content.
   */
  private deleteLineAndMergeIds(
    survivor: LineState, other: LineState,
    removeIndex: number, focusIndex: number,
  ): void {
    this.undoManager.captureStateBeforeChange(this.state.lines, this.state.focusedLineIndex)
    this.state.clearSelection()
    const merged = mergeNoteIds(survivor, other)
    survivor.noteIds = merged.noteIds
    survivor.noteIdContentLengths = merged.contentLengths
    this.state.lines.splice(removeIndex, 1)
    this.state.focusedLineIndex = focusIndex
    this.state.requestFocusUpdate()
    this.state.notifyChange()
    this.undoManager.beginEditingLine(
      this.state.lines, this.state.focusedLineIndex, this.state.focusedLineIndex,
    )
  }

  mergeToPreviousLine(lineIndex: number, targetIndex?: number): void {
    const prevIdx = targetIndex ?? lineIndex - 1
    if (prevIdx < 0) return
    this.undoManager.captureStateBeforeChange(this.state.lines, this.state.focusedLineIndex)
    this.state.clearSelection()
    const currentLine = this.state.lines[lineIndex]
    const previousLine = this.state.lines[prevIdx]
    if (!currentLine || !previousLine) return

    const merged = mergeNoteIds(previousLine, currentLine)
    const previousLength = previousLine.text.length
    previousLine.updateFull(previousLine.text + currentLine.content, previousLength)
    previousLine.noteIds = merged.noteIds
    previousLine.noteIdContentLengths = merged.contentLengths
    this.state.lines.splice(lineIndex, 1)
    this.state.focusedLineIndex = prevIdx
    this.state.requestFocusUpdate()
    this.state.notifyChange()

    this.undoManager.beginEditingLine(
      this.state.lines, this.state.focusedLineIndex, this.state.focusedLineIndex,
    )
  }

  mergeNextLine(lineIndex: number, targetIndex?: number): void {
    const nextIdx = targetIndex ?? lineIndex + 1
    if (nextIdx >= this.state.lines.length) return
    this.undoManager.captureStateBeforeChange(this.state.lines, this.state.focusedLineIndex)
    this.state.clearSelection()
    const currentLine = this.state.lines[lineIndex]
    const nextLine = this.state.lines[nextIdx]
    if (!currentLine || !nextLine) return

    const merged = mergeNoteIds(currentLine, nextLine)
    const currentLength = currentLine.text.length
    currentLine.updateFull(currentLine.text + nextLine.content, currentLength)
    currentLine.noteIds = merged.noteIds
    currentLine.noteIdContentLengths = merged.contentLengths
    this.state.lines.splice(nextIdx, 1)
    this.state.requestFocusUpdate()
    this.state.notifyChange()

    this.undoManager.beginEditingLine(
      this.state.lines, this.state.focusedLineIndex, this.state.focusedLineIndex,
    )
  }

  // --- Cursor Operations ---

  setCursor(lineIndex: number, position: number): void {
    const line = this.state.lines[lineIndex]
    if (!line) return
    if (lineIndex !== this.state.focusedLineIndex) {
      this.undoManager.commitPendingUndoState(this.state.lines, this.state.focusedLineIndex)
      this.state.focusedLineIndex = lineIndex
      this.undoManager.beginEditingLine(this.state.lines, this.state.focusedLineIndex, lineIndex)
    } else {
      this.state.focusedLineIndex = lineIndex
    }
    const clampedPos = Math.max(0, Math.min(position, line.text.length))
    line.updateFull(line.text, clampedPos)
    this.state.clearSelection()
    this.state.selectionAnchor = this.state.getLineStartOffset(lineIndex) + clampedPos
    this.state.requestFocusUpdate()
  }

  setCursorFromGlobalOffset(globalOffset: number): void {
    this.state.clearSelection()
    const [lineIndex, localOffset] = this.state.getLineAndLocalOffset(globalOffset)
    if (lineIndex !== this.state.focusedLineIndex) {
      this.undoManager.commitPendingUndoState(this.state.lines, this.state.focusedLineIndex)
      this.state.focusedLineIndex = lineIndex
      this.undoManager.beginEditingLine(this.state.lines, this.state.focusedLineIndex, lineIndex)
    } else {
      this.state.focusedLineIndex = lineIndex
    }
    const line = this.state.lines[lineIndex]
    if (line) line.updateFull(line.text, localOffset)
    this.state.selectionAnchor = globalOffset
    this.state.requestFocusUpdate()
  }

  // --- Content Update (for direct input) ---

  updateLineContent(lineIndex: number, newContent: string, contentCursor: number): void {
    const line = this.state.lines[lineIndex]
    if (!line) return

    if (newContent.includes('\n')) {
      this.undoManager.prepareForStructuralChange(this.state.lines, this.state.focusedLineIndex)
      this.state.clearSelection()
      const nlIndex = newContent.indexOf('\n')
      const beforeNewline = newContent.substring(0, nlIndex)
      const afterNewline = newContent.substring(nlIndex + 1)

      const noteIds = line.noteIds
      const [currentNoteIds, newNoteIds] = splitNoteIds(
        noteIds,
        beforeNewline.length,
        afterNewline.length,
        beforeNewline.length > 0,
        afterNewline.length > 0,
        line.noteIdContentLengths,
      )

      line.updateContent(beforeNewline, beforeNewline.length)
      line.noteIds = currentNoteIds
      this.createNewLineWithPrefix(lineIndex, afterNewline, line.prefix, newNoteIds)
      this.undoManager.continueAfterStructuralChange(this.state.focusedLineIndex)
      return
    }

    const contentChanged = line.content !== newContent
    if (contentChanged) {
      this.state.clearSelection()
    }

    line.updateContent(newContent, contentCursor)
    this.state.notifyChange()

    if (contentChanged) {
      this.undoManager.markContentChanged()
    }
  }

  // --- Focus ---

  focusLine(lineIndex: number): void {
    if (lineIndex >= 0 && lineIndex < this.state.lines.length) {
      if (lineIndex !== this.state.focusedLineIndex) {
        this.undoManager.commitPendingUndoState(this.state.lines, this.state.focusedLineIndex)
        this.state.focusedLineIndex = lineIndex
        this.undoManager.beginEditingLine(this.state.lines, this.state.focusedLineIndex, lineIndex)
      } else {
        this.state.focusedLineIndex = lineIndex
      }
      this.state.selectionAnchor = this.state.getLineStartOffset(lineIndex)
      this.state.requestFocusUpdate()
    }
  }

  // --- Selection ---

  hasSelection(): boolean { return this.state.hasSelection }

  setSelection(start: number, end: number): void {
    if (start === end) {
      this.setCursorFromGlobalOffset(start)
    } else {
      this.state.setSelection(start, end)
    }
  }

  setSelectionInLine(lineIndex: number, contentStart: number, contentEnd: number): void {
    const line = this.state.lines[lineIndex]
    if (!line) return
    const lineStartOffset = this.state.getLineStartOffset(lineIndex)
    const prefixLen = line.prefix.length
    const globalStart = lineStartOffset + prefixLen + contentStart
    const globalEnd = lineStartOffset + prefixLen + contentEnd
    this.state.setSelection(globalStart, globalEnd)
  }

  extendSelectionTo(globalOffset: number): void {
    this.state.extendSelectionTo(globalOffset)
  }

  handleSpaceWithSelection(): boolean {
    if (!this.state.hasSelection) return false
    this.undoManager.recordCommand(this.state.lines, this.state.focusedLineIndex, CommandType.INDENT)
    return this.state.handleSpaceWithSelectionInternal()
  }

  /** Updates recentlyCheckedIndices after a checkbox toggle. */
  private trackRecentlyChecked(lineIndex: number, wasUnchecked: boolean): void {
    const isNowChecked = this.state.lines[lineIndex]?.prefix.includes(LP.CHECKBOX_CHECKED) ?? false
    const mutable = this.recentlyCheckedIndices as Set<number> & { add(v: number): void; delete(v: number): void }
    if (wasUnchecked && isNowChecked) {
      mutable.add(lineIndex)
    } else {
      mutable.delete(lineIndex)
    }
  }

  // --- Line Prefix Operations ---

  toggleCheckboxOnLine(lineIndex: number): void {
    const line = this.state.lines[lineIndex]
    if (!line) return
    const wasUnchecked = line.prefix.includes(LP.CHECKBOX_UNCHECKED)
    this.undoManager.recordCommand(this.state.lines, this.state.focusedLineIndex, CommandType.CHECKBOX)
    line.toggleCheckboxState()
    this.undoManager.commitAfterCommand(this.state.lines, this.state.focusedLineIndex, CommandType.CHECKBOX)
    this.state.requestFocusUpdate()
    this.state.notifyChange()
    this.trackRecentlyChecked(lineIndex, wasUnchecked)
  }

  // --- Read Access ---

  getLineText(lineIndex: number): string { return this.state.lines[lineIndex]?.text ?? '' }
  getLineContent(lineIndex: number): string { return this.state.lines[lineIndex]?.content ?? '' }
  getContentCursor(lineIndex: number): number { return this.state.lines[lineIndex]?.contentCursorPosition ?? 0 }
  getLineCursor(lineIndex: number): number { return this.state.lines[lineIndex]?.cursorPosition ?? 0 }
  isValidLine(lineIndex: number): boolean { return lineIndex >= 0 && lineIndex < this.state.lines.length }
}

interface MergedNoteIds {
  noteIds: string[]
  contentLengths: number[]
}

/**
 * Combines noteIds from two lines being merged, in text order (lineA first),
 * and records per-noteId content lengths for correct distribution on re-split.
 */
function mergeNoteIds(lineA: LineState, lineB: LineState): MergedNoteIds {
  const allIds = [...new Set([...lineA.noteIds, ...lineB.noteIds])]
  const contentLengths = [...buildMergedContentLengths(lineA), ...buildMergedContentLengths(lineB)]
  return { noteIds: allIds, contentLengths }
}

/**
 * Extracts per-noteId content lengths from a line.
 * If the line already has content-length metadata (from a prior merge), uses that.
 * Otherwise creates a single entry spanning the full content.
 */
function buildMergedContentLengths(line: LineState): number[] {
  if (line.noteIdContentLengths.length > 0 && line.noteIdContentLengths.length === line.noteIds.length) {
    return line.noteIdContentLengths
  }
  return line.noteIds.length > 0 ? [line.content.length] : []
}
