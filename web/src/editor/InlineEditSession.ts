import { EditorState } from './EditorState'
import { EditorController } from './EditorController'
import { UndoManager } from './UndoManager'

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
  private readonly originalContent: string

  constructor(noteId: string, content: string, lineNoteIds?: string[][]) {
    this.noteId = noteId
    this.originalContent = content
    this.editorState = new EditorState()
    this.undoManager = new UndoManager()
    this.controller = new EditorController(this.editorState, this.undoManager)

    // Split content into lines and initialize the editor.
    // Append a trailing empty line for typing (matches main editor convention).
    const rawLines = content.split('\n')
    const noteLines = rawLines.map((text, i) => ({
      text,
      noteIds: lineNoteIds?.[i] ?? [],
    }))
    if (noteLines.length === 0 || noteLines[noteLines.length - 1]!.text !== '') {
      noteLines.push({ text: '', noteIds: [] })
    }
    this.editorState.initFromNoteLines(noteLines)
    this.undoManager.setBaseline(this.editorState.lines, this.editorState.focusedLineIndex)

    // Hide the trailing empty line from move operations so move-down
    // correctly disables at the last content line.
    this.updateHiddenIndices()
  }

  /** Recompute hidden indices — hides the trailing empty line. */
  updateHiddenIndices(): void {
    const lines = this.editorState.lines
    const hidden = new Set<number>()
    if (lines.length > 1 && lines[lines.length - 1]!.text === '') {
      hidden.add(lines.length - 1)
    }
    this.controller.hiddenIndices = hidden
  }

  get isDirty(): boolean {
    return this.getText() !== this.originalContent
  }

  /**
   * Returns the serialized content, stripping the trailing empty line
   * that was added for editing (matches main editor save convention).
   */
  getText(): string {
    const lines = this.editorState.lines
    const texts = lines.map((l) => l.text)
    // Drop trailing empty line(s) — UI always has one for typing
    while (texts.length > 1 && texts[texts.length - 1] === '') {
      texts.pop()
    }
    return texts.join('\n')
  }
}
