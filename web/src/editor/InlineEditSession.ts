import type { NoteLine } from '@/data/Note'
import { EditorState } from './EditorState'
import { EditorController } from './EditorController'
import { UndoManager } from './UndoManager'
import { resolveNoteIds } from './resolveNoteIds'

/**
 * Encapsulates a self-contained editing session for a view note.
 * Each session has its own EditorState, EditorController, and UndoManager,
 * isolated from the parent note's editor.
 */
export class InlineEditSession {
  readonly noteId: string
  readonly editorState: EditorState
  readonly controller: EditorController
  readonly undoManager: UndoManager
  private originalContent: string

  constructor(noteId: string, content: string, lineNoteIds?: string[][]) {
    this.noteId = noteId
    this.originalContent = content
    this.editorState = new EditorState()
    this.undoManager = new UndoManager()
    this.controller = new EditorController(this.editorState, this.undoManager)

    // Split content into lines and initialize the editor. Empty lines are
    // first-class docs now, so whatever the source content provides — including
    // any trailing empty — is loaded verbatim. No auto-append.
    const rawLines = content.split('\n')
    const noteLines = rawLines.map((text, i) => ({
      text,
      noteIds: lineNoteIds?.[i] ?? [],
    }))
    this.editorState.initFromNoteLines(noteLines)
    this.undoManager.setBaseline(this.editorState.lines, this.editorState.focusedLineIndex)

    this.updateHiddenIndices()
  }

  /** Recompute hidden indices. No lines are hidden under the new empty-line
   * model — every line is real and should participate in move operations. */
  updateHiddenIndices(): void {
    this.controller.hiddenIndices = new Set<number>()
  }

  get isDirty(): boolean {
    return this.getText() !== this.originalContent
  }

  /** Update the baseline content after a successful save.
   *  Accepts optional pre-computed content to avoid redundant getText() calls. */
  markSaved(savedContent?: string): void {
    this.originalContent = savedContent ?? this.getText()
  }

  /** Update the baseline content after an external change was applied to the EditorState. */
  syncOriginalContent(content: string): void {
    this.originalContent = content
  }

  /** Returns the serialized content. Empty lines round-trip as their own
   * docs now, so no trailing-empty stripping is needed. */
  getText(): string {
    return this.editorState.lines.map((l) => l.text).join('\n')
  }

  /**
   * Builds tracked lines (content + noteId) from editor state, ready for
   * saveNoteWithChildren. Mirrors the main editor's save path in useEditor.
   */
  getTrackedLines(): NoteLine[] {
    const lines = this.editorState.lines
    const contentLines = lines.map(l => l.text)
    const lineNoteIds = lines.map(l => l.noteIds)
    const tracked = resolveNoteIds(contentLines, lineNoteIds)

    // Ensure first line always maps to the parent noteId
    if (tracked.length > 0 && tracked[0]!.noteId !== this.noteId) {
      tracked[0] = { ...tracked[0]!, noteId: this.noteId }
    }
    return tracked
  }

  /**
   * Merges newly created noteIds (from Firestore save) into the editor state
   * so subsequent saves preserve child note associations without page refresh.
   */
  applyCreatedIds(createdIds: Map<number, string>): void {
    if (createdIds.size === 0) return
    const updatedNoteIds = this.editorState.lines.map((line, i) => {
      const newId = createdIds.get(i)
      return newId ? [newId, ...line.noteIds] : line.noteIds
    })
    this.editorState.updateNoteIds(updatedNoteIds)
  }
}
