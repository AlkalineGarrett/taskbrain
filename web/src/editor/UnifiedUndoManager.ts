import type { EditorController } from './EditorController'
import type { UndoSnapshot } from './UndoManager'

interface UnifiedStackEntry {
  contextId: string
  /** Non-null entries with matching ids undo/redo as one group (e.g. cross-editor move). */
  groupId: number | null
}

/**
 * Coordinates undo/redo across multiple editors into a single chronological timeline.
 *
 * Each editor keeps its own UndoManager for internal mechanics (pending state,
 * command grouping, baseline). This manager tracks the ORDER of undo entries
 * across editors via callbacks, enabling unified undo/redo that feels like
 * editing a single document.
 *
 * Group support: operations that span multiple editors (cross-editor move)
 * call [withGroup] so the per-editor pushes that happen inside share a
 * groupId; undo/redo then drain consecutive entries with the same id as a
 * single user-visible operation.
 */
export class UnifiedUndoManager {
  private readonly editors = new Map<string, EditorController>()
  private readonly unifiedUndoStack: UnifiedStackEntry[] = []
  private readonly unifiedRedoStack: UnifiedStackEntry[] = []
  private activeGroupId: number | null = null
  private groupCounter = 0
  private _stateVersion = 0

  get stateVersion(): number {
    return this._stateVersion
  }

  get canUndo(): boolean {
    // Defer to the registered controllers rather than the unified stack —
    // the stack can desync (e.g. note load resets the controller's stack
    // via resetUndoHistory but leaves stale entries here, or importState
    // restores entries the unified stack never saw). The stack is for
    // ordering, not the source of truth for "is anything undoable."
    return [...this.editors.values()].some(
      c => c.undoManager.canUndo || c.undoManager.currentHasUncommittedChanges,
    )
  }

  get canRedo(): boolean {
    return [...this.editors.values()].some(c => c.undoManager.canRedo)
  }

  registerEditor(contextId: string, controller: EditorController): void {
    this.editors.set(contextId, controller)
    controller.undoManager.onEntryPushed = () => {
      this.unifiedUndoStack.push({ contextId, groupId: this.activeGroupId })
      this._stateVersion++
    }
    controller.undoManager.onRedoCleared = () => {
      this.unifiedRedoStack.length = 0
      this._stateVersion++
    }
  }

  /**
   * Run [callback] with a group active so any per-editor undo entries pushed
   * inside share a groupId. Undo/redo then treats all entries in the group as
   * a single user-visible operation. Pending-edit state on all editors is
   * committed first so unrelated typing isn't swept into the group.
   */
  withGroup<T>(callback: () => T): T {
    for (const ctrl of this.editors.values()) {
      ctrl.commitUndoState()
    }
    const prev = this.activeGroupId
    this.activeGroupId = ++this.groupCounter
    try {
      return callback()
    } finally {
      this.activeGroupId = prev
    }
  }

  unregisterEditor(contextId: string): void {
    const controller = this.editors.get(contextId)
    if (controller) {
      controller.undoManager.onEntryPushed = null
      controller.undoManager.onRedoCleared = null
    }
    this.editors.delete(contextId)
  }

  /**
   * Perform unified undo. Drains consecutive entries that share a non-null
   * groupId (e.g. both halves of a cross-editor move) so a grouped op reverts
   * with one press. Returns the first applied snapshot for caller display.
   */
  undo(
    activeContextId: string,
    activateEditor: (contextId: string) => void,
  ): { contextId: string; snapshot: UndoSnapshot } | null {
    const activeCtrl = this.editors.get(activeContextId)
    if (activeCtrl) {
      activeCtrl.commitUndoState()
    }

    const result = this.drainStack(
      this.unifiedUndoStack,
      this.unifiedRedoStack,
      activeContextId,
      activateEditor,
      (ctrl) => ctrl.undo(),
    )
    if (result) return result

    // Fallback: the active controller may have entries the unified stack
    // doesn't know about (e.g. importState restored from localStorage).
    if (activeCtrl) {
      const snapshot = activeCtrl.undo()
      if (snapshot) {
        this.unifiedRedoStack.push({ contextId: activeContextId, groupId: null })
        this._stateVersion++
        return { contextId: activeContextId, snapshot }
      }
    }

    this._stateVersion++
    return null
  }

  /**
   * Perform unified redo. Drains consecutive entries that share a non-null
   * groupId so a grouped op replays with one press.
   */
  redo(
    activeContextId: string,
    activateEditor: (contextId: string) => void,
  ): { contextId: string; snapshot: UndoSnapshot } | null {
    const result = this.drainStack(
      this.unifiedRedoStack,
      this.unifiedUndoStack,
      activeContextId,
      activateEditor,
      (ctrl) => ctrl.redo(),
    )
    if (result) return result

    const activeCtrl = this.editors.get(activeContextId)
    if (activeCtrl) {
      const snapshot = activeCtrl.redo()
      if (snapshot) {
        this.unifiedUndoStack.push({ contextId: activeContextId, groupId: null })
        this._stateVersion++
        return { contextId: activeContextId, snapshot }
      }
    }

    this._stateVersion++
    return null
  }

  /**
   * Pop entries off [fromStack] and apply them via [apply], pushing each
   * onto [toStack]. Stops after the first applied entry unless that entry
   * is part of a non-null group, in which case continues draining while
   * the next entry shares the same groupId. Returns the first applied
   * result for caller display, or null if nothing applied.
   */
  private drainStack(
    fromStack: UnifiedStackEntry[],
    toStack: UnifiedStackEntry[],
    activeContextId: string,
    activateEditor: (contextId: string) => void,
    apply: (ctrl: EditorController) => UndoSnapshot | null,
  ): { contextId: string; snapshot: UndoSnapshot } | null {
    let firstResult: { contextId: string; snapshot: UndoSnapshot } | null = null
    let groupId: number | null = null
    let currentActive = activeContextId

    while (fromStack.length > 0) {
      const top = fromStack[fromStack.length - 1]!
      if (firstResult && top.groupId !== groupId) break
      fromStack.pop()
      const ctrl = this.editors.get(top.contextId)
      if (!ctrl) continue
      if (top.contextId !== currentActive) {
        activateEditor(top.contextId)
        currentActive = top.contextId
      }
      const snapshot = apply(ctrl)
      if (snapshot) {
        toStack.push(top)
        this._stateVersion++
        if (!firstResult) {
          firstResult = { contextId: top.contextId, snapshot }
          groupId = top.groupId
          if (groupId === null) break
        }
      }
    }

    return firstResult
  }

  /** Clear all unified history. Called when navigating to a direct view of an embedded note. */
  abandonHistory(): void {
    this.unifiedUndoStack.length = 0
    this.unifiedRedoStack.length = 0
    for (const controller of this.editors.values()) {
      controller.undoManager.reset()
    }
    this._stateVersion++
  }

  /** Reset all state. Called when switching notes. */
  reset(): void {
    this.unifiedUndoStack.length = 0
    this.unifiedRedoStack.length = 0
    for (const controller of this.editors.values()) {
      controller.undoManager.onEntryPushed = null
      controller.undoManager.onRedoCleared = null
    }
    this.editors.clear()
    this._stateVersion++
  }
}
