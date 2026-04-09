export enum CommandType {
  BULLET = 'BULLET',
  CHECKBOX = 'CHECKBOX',
  INDENT = 'INDENT',
  UNINDENT = 'UNINDENT',
  MOVE = 'MOVE',
  OTHER = 'OTHER',
}

export interface UndoSnapshot {
  lineContents: string[]
  lineNoteIds: string[][]
  focusedLineIndex: number
  cursorPosition: number
}

/**
 * Undo/redo history manager for the editor.
 * Line-level granularity with command grouping.
 */
export class UndoManager {
  private undoStack: UndoSnapshot[] = []
  private redoStack: UndoSnapshot[] = []

  private baselineSnapshot: UndoSnapshot | null = null
  private isAtBaseline = false

  private pendingSnapshot: UndoSnapshot | null = null
  private editingLineIndex: number | null = null
  private lastCommandType: CommandType | null = null
  private lastMoveLineRange: [number, number] | null = null
  private hasUncommittedChanges = false
  private _stateVersion = 0
  private suppressCallbacks = false

  /** Whether this editor has uncommitted changes. Exposed for UnifiedUndoManager. */
  get currentHasUncommittedChanges(): boolean { return this.hasUncommittedChanges }

  /** Callback fired when a new undo entry is committed (not during undo/redo). */
  onEntryPushed: (() => void) | null = null

  /** Callback fired when the redo stack is cleared (not during undo/redo). */
  onRedoCleared: (() => void) | null = null

  private readonly maxHistorySize: number

  constructor(maxHistorySize = 50) {
    this.maxHistorySize = maxHistorySize
  }

  get stateVersion(): number {
    return this._stateVersion
  }

  private notifyStateChanged(): void {
    this._stateVersion++
  }

  markContentChanged(): void {
    if (this.pendingSnapshot != null && !this.hasUncommittedChanges) {
      this.hasUncommittedChanges = true
      this.clearRedoStack()
      this.notifyStateChanged()
    }
  }

  get canUndo(): boolean {
    return (
      this.undoStack.length > 0 ||
      this.hasUncommittedChanges ||
      (this.baselineSnapshot != null && !this.isAtBaseline)
    )
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0
  }

  private captureSnapshot(lines: { text: string; noteIds?: string[] }[], focusedLineIndex: number): UndoSnapshot {
    const focusedLine = lines[focusedLineIndex] as { text: string; cursorPosition: number; noteIds?: string[] } | undefined
    return {
      lineContents: lines.map((l) => l.text),
      lineNoteIds: lines.map((l) => l.noteIds ?? []),
      focusedLineIndex,
      cursorPosition: focusedLine?.cursorPosition ?? 0,
    }
  }

  setBaseline(lines: { text: string; cursorPosition: number }[], focusedLineIndex: number): void {
    this.baselineSnapshot = this.captureSnapshot(lines, focusedLineIndex)
    this.isAtBaseline = true
    this.notifyStateChanged()
  }

  beginEditingLine(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
    lineIndex: number,
  ): void {
    if (this.editingLineIndex !== lineIndex) {
      this.pendingSnapshot = this.captureSnapshot(lines, focusedLineIndex)
      this.editingLineIndex = lineIndex
    }
    this.lastCommandType = null
    this.lastMoveLineRange = null
  }

  commitPendingUndoState(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
  ): void {
    const pending = this.pendingSnapshot
    if (!pending) return
    const currentSnapshot = this.captureSnapshot(lines, focusedLineIndex)

    if (!arraysEqual(pending.lineContents, currentSnapshot.lineContents)) {
      this.pushUndo(pending)
      this.clearRedoStack()
    }

    this.pendingSnapshot = null
    this.editingLineIndex = null
    this.lastCommandType = null
    this.lastMoveLineRange = null
    this.hasUncommittedChanges = false
  }

  prepareForStructuralChange(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
  ): void {
    const pending = this.pendingSnapshot
    const currentSnapshot = this.captureSnapshot(lines, focusedLineIndex)

    if (pending && !arraysEqual(pending.lineContents, currentSnapshot.lineContents)) {
      this.pushUndo(pending)
      this.clearRedoStack()
    }

    this.pendingSnapshot = currentSnapshot
    this.editingLineIndex = focusedLineIndex
    this.lastCommandType = null
    this.hasUncommittedChanges = false
  }

  continueAfterStructuralChange(newLineIndex: number): void {
    this.editingLineIndex = newLineIndex
  }

  captureStateBeforeChange(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
  ): void {
    this.commitPendingUndoState(lines, focusedLineIndex)
    this.pushUndo(this.captureSnapshot(lines, focusedLineIndex))
    this.clearRedoStack()
  }

  recordCommand(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
    type: CommandType,
  ): boolean {
    let shouldCommit: boolean

    switch (type) {
      case CommandType.BULLET:
      case CommandType.CHECKBOX:
        shouldCommit = true
        break
      case CommandType.INDENT:
      case CommandType.UNINDENT:
        shouldCommit = this.lastCommandType !== CommandType.INDENT &&
          this.lastCommandType !== CommandType.UNINDENT
        break
      default:
        shouldCommit = true
    }

    if (shouldCommit) {
      this.commitPendingUndoState(lines, focusedLineIndex)
      this.pendingSnapshot = this.captureSnapshot(lines, focusedLineIndex)
      this.editingLineIndex = focusedLineIndex
    }

    this.lastCommandType = type
    return shouldCommit
  }

  commitAfterCommand(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
    type: CommandType,
  ): void {
    if (type === CommandType.BULLET || type === CommandType.CHECKBOX) {
      const pending = this.pendingSnapshot
      if (pending) {
        this.pushUndo(pending)
        this.clearRedoStack()
        this.pendingSnapshot = null
        this.editingLineIndex = null
      }
    }
    void lines
    void focusedLineIndex
  }

  recordMoveCommand(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
    newRange: [number, number],
  ): boolean {
    const isSameGroup =
      this.lastCommandType === CommandType.MOVE &&
      this.lastMoveLineRange != null &&
      this.lastMoveLineRange[0] === newRange[0] &&
      this.lastMoveLineRange[1] === newRange[1]

    if (!isSameGroup) {
      this.commitPendingUndoState(lines, focusedLineIndex)
      this.pendingSnapshot = this.captureSnapshot(lines, focusedLineIndex)
      this.editingLineIndex = focusedLineIndex
    }

    this.lastMoveLineRange = newRange
    this.lastCommandType = CommandType.MOVE
    return !isSameGroup
  }

  updateMoveRange(newRange: [number, number]): void {
    this.lastMoveLineRange = newRange
  }

  undo(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
  ): UndoSnapshot | null {
    this.suppressCallbacks = true
    try {
      this.commitPendingUndoState(lines, focusedLineIndex)

      if (this.undoStack.length === 0 && (this.baselineSnapshot == null || this.isAtBaseline)) {
        return null
      }

      let targetSnapshot: UndoSnapshot
      if (this.undoStack.length > 0) {
        targetSnapshot = this.undoStack.pop()!
        if (this.undoStack.length === 0 && this.baselineSnapshot != null) {
          this.isAtBaseline = true
        }
      } else {
        this.isAtBaseline = true
        targetSnapshot = this.baselineSnapshot!
      }

      const currentSnapshot = this.captureSnapshot(lines, focusedLineIndex)
      if (!arraysEqual(currentSnapshot.lineContents, targetSnapshot.lineContents)) {
        this.redoStack.push(currentSnapshot)
      }
      this.notifyStateChanged()

      return targetSnapshot
    } finally {
      this.suppressCallbacks = false
    }
  }

  redo(
    lines: { text: string; cursorPosition: number }[],
    focusedLineIndex: number,
  ): UndoSnapshot | null {
    this.suppressCallbacks = true
    try {
      if (this.redoStack.length === 0) return null

      const currentSnapshot = this.captureSnapshot(lines, focusedLineIndex)
      this.pushUndo(currentSnapshot)

      const snapshot = this.redoStack.pop()!
      this.notifyStateChanged()
      return snapshot
    } finally {
      this.suppressCallbacks = false
    }
  }

  private pushUndo(snapshot: UndoSnapshot): void {
    this.undoStack.push(snapshot)
    this.isAtBaseline = false
    while (this.undoStack.length > this.maxHistorySize) {
      this.undoStack.shift()
    }
    this.notifyStateChanged()
    if (!this.suppressCallbacks) this.onEntryPushed?.()
  }

  clearRedoStack(): void {
    if (this.redoStack.length > 0) {
      this.redoStack.length = 0
      this.notifyStateChanged()
      if (!this.suppressCallbacks) this.onRedoCleared?.()
    }
  }

  reset(): void {
    this.undoStack.length = 0
    this.redoStack.length = 0
    this.baselineSnapshot = null
    this.isAtBaseline = false
    this.pendingSnapshot = null
    this.editingLineIndex = null
    this.lastCommandType = null
    this.lastMoveLineRange = null
    this.hasUncommittedChanges = false
    this.notifyStateChanged()
  }

  // --- Persistence ---

  exportState(): UndoManagerState {
    return {
      undoStack: [...this.undoStack],
      redoStack: [...this.redoStack],
      baselineSnapshot: this.baselineSnapshot,
      isAtBaseline: this.isAtBaseline,
      hasUncommittedChanges: this.hasUncommittedChanges,
    }
  }

  importState(state: UndoManagerState): void {
    this.undoStack = [...state.undoStack]
    this.redoStack = [...state.redoStack]
    this.baselineSnapshot = state.baselineSnapshot
    this.isAtBaseline = state.isAtBaseline
    this.hasUncommittedChanges = state.hasUncommittedChanges
    this.pendingSnapshot = null
    this.editingLineIndex = null
    this.lastCommandType = null
    this.lastMoveLineRange = null
    this.notifyStateChanged()
  }
}

export interface UndoManagerState {
  undoStack: UndoSnapshot[]
  redoStack: UndoSnapshot[]
  baselineSnapshot: UndoSnapshot | null
  isAtBaseline: boolean
  hasUncommittedChanges: boolean
}

function arraysEqual(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return false
  }
  return true
}
