import type { NoteLine } from '@/data/Note'
import { noteStore } from '@/data/NoteStore'
import { EditorState } from './EditorState'
import { EditorController } from './EditorController'
import { UndoManager } from './UndoManager'
import { resolveNoteIds } from './resolveNoteIds'
import { computeHiddenIndices } from './CompletedLineUtils'

/**
 * Encapsulates a self-contained editing session for a view note.
 * Each session has its own EditorState, EditorController, and UndoManager,
 * isolated from the parent note's editor.
 *
 * Caller must pre-resolve [lines] (typically via
 * `NoteRepository.loadNoteLinesAwait`) so the session is always initialized
 * from structurally-valid lines — never from synthesized null-id lines.
 */
export class InlineEditSession {
  readonly noteId: string
  readonly editorState: EditorState
  readonly controller: EditorController
  readonly undoManager: UndoManager
  private originalContent: string
  /**
   * `containedNotes` snapshots captured at session start, keyed by id for
   * the root and every live descendant. Anchors the 3-way merge in
   * NoteRepository.planSave at every depth. Refreshed after each save via
   * [refreshLocalBase].
   */
  private localBases: Map<string, string[]>

  constructor(noteId: string, lines: NoteLine[]) {
    this.noteId = noteId
    this.localBases = noteStore.snapshotLocalBases(noteId)
    this.editorState = new EditorState()
    this.undoManager = new UndoManager()
    this.controller = new EditorController(this.editorState, this.undoManager)

    const texts: string[] = new Array(lines.length)
    const editorLines = lines.map((l, i) => {
      texts[i] = l.content
      return { text: l.content, noteIds: l.noteId ? [l.noteId] : [] }
    })
    this.originalContent = texts.join('\n')
    this.editorState.initFromNoteLines(editorLines)
    this.undoManager.setBaseline(this.editorState.lines, this.editorState.focusedLineIndex)

    this.updateHiddenIndices()
  }

  getLocalBases(): Map<string, string[]> {
    return this.localBases
  }

  /** Refresh from rawNote — call after a successful save. */
  refreshLocalBase(): void {
    this.localBases = noteStore.snapshotLocalBases(this.noteId)
  }

  /** Recompute hidden indices. When this session is rendered inside a parent
   * editor's view directive, the parent's `showCompleted` overrides the embedded
   * note's own setting. Pass `undefined` (the standalone case) to leave nothing
   * hidden — every line is real and should participate in move operations. */
  updateHiddenIndices(parentShowCompleted?: boolean): void {
    if (parentShowCompleted === false) {
      const lineTexts = this.editorState.lines.map((l) => l.text)
      this.controller.hiddenIndices = computeHiddenIndices(lineTexts, false)
    } else {
      this.controller.hiddenIndices = new Set<number>()
    }
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
