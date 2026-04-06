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

  /** Update the baseline content after a successful save.
   *  Accepts optional pre-computed content to avoid redundant getText() calls. */
  markSaved(savedContent?: string): void {
    this.originalContent = savedContent ?? this.getText()
  }

  /** Update the baseline content after an external change was applied to the EditorState. */
  syncOriginalContent(content: string): void {
    this.originalContent = content
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

  /**
   * Builds tracked lines (content + noteId) from editor state, ready for
   * saveNoteWithChildren. Mirrors the main editor's save path in useEditor.
   */
  getTrackedLines(): NoteLine[] {
    const lines = this.editorState.lines
    // Strip trailing empty lines to match getText()
    let count = lines.length
    while (count > 1 && lines[count - 1]!.text === '') count--

    const contentLines = lines.slice(0, count).map(l => l.text)
    const lineNoteIds = lines.slice(0, count).map(l => l.noteIds)
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
