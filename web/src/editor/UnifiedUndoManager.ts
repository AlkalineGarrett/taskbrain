import type { EditorController } from './EditorController'
import type { UndoSnapshot } from './UndoManager'

/**
 * Coordinates undo/redo across multiple editors into a single chronological timeline.
 *
 * Each editor keeps its own UndoManager for internal mechanics (pending state,
 * command grouping, baseline). This manager tracks the ORDER of undo entries
 * across editors via callbacks, enabling unified undo/redo that feels like
 * editing a single document.
 */
export class UnifiedUndoManager {
  private readonly editors = new Map<string, EditorController>()
  private readonly unifiedUndoStack: string[] = [] // context IDs
  private readonly unifiedRedoStack: string[] = [] // context IDs
  private _stateVersion = 0

  get stateVersion(): number {
    return this._stateVersion
  }

  get canUndo(): boolean {
    return (
      this.unifiedUndoStack.length > 0 ||
      [...this.editors.values()].some(c => c.undoManager.currentHasUncommittedChanges)
    )
  }

  get canRedo(): boolean {
    return this.unifiedRedoStack.length > 0
  }

  registerEditor(contextId: string, controller: EditorController): void {
    this.editors.set(contextId, controller)
    controller.undoManager.onEntryPushed = () => {
      this.unifiedUndoStack.push(contextId)
      this._stateVersion++
    }
    controller.undoManager.onRedoCleared = () => {
      this.unifiedRedoStack.length = 0
      this._stateVersion++
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
   * Perform unified undo.
   * @param activeContextId - which editor currently has focus
   * @param activateEditor - callback to switch focus to a different editor
   * @returns the target context ID and snapshot, or null if nothing to undo
   */
  undo(
    activeContextId: string,
    activateEditor: (contextId: string) => void,
  ): { contextId: string; snapshot: UndoSnapshot } | null {
    // Commit pending on the active editor first — may add to unified stack
    const activeCtrl = this.editors.get(activeContextId)
    if (activeCtrl) {
      activeCtrl.commitUndoState()
    }

    if (this.unifiedUndoStack.length === 0) return null

    const targetContextId = this.unifiedUndoStack.pop()!
    const targetCtrl = this.editors.get(targetContextId)
    if (!targetCtrl) return null

    // Switch editor if needed
    if (targetContextId !== activeContextId) {
      activateEditor(targetContextId)
    }

    // Perform undo on the target editor (callbacks suppressed internally)
    const snapshot = targetCtrl.undo()
    if (snapshot) {
      this.unifiedRedoStack.push(targetContextId)
    }
    this._stateVersion++
    return snapshot ? { contextId: targetContextId, snapshot } : null
  }

  /**
   * Perform unified redo.
   * @param activeContextId - which editor currently has focus
   * @param activateEditor - callback to switch focus to a different editor
   * @returns the target context ID and snapshot, or null if nothing to redo
   */
  redo(
    activeContextId: string,
    activateEditor: (contextId: string) => void,
  ): { contextId: string; snapshot: UndoSnapshot } | null {
    if (this.unifiedRedoStack.length === 0) return null

    const targetContextId = this.unifiedRedoStack.pop()!
    const targetCtrl = this.editors.get(targetContextId)
    if (!targetCtrl) return null

    // Switch editor if needed
    if (targetContextId !== activeContextId) {
      activateEditor(targetContextId)
    }

    // Perform redo on the target editor (callbacks suppressed internally)
    const snapshot = targetCtrl.redo()
    if (snapshot) {
      this.unifiedUndoStack.push(targetContextId)
    }
    this._stateVersion++
    return snapshot ? { contextId: targetContextId, snapshot } : null
  }

  /** Clear all unified history. Called when navigating to a direct view of an embedded note. */
  abandonHistory(): void {
    this.unifiedUndoStack.length = 0
    this.unifiedRedoStack.length = 0
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
