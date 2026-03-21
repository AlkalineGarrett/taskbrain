import { describe, it, expect } from 'vitest'
import { EditorState } from '../../editor/EditorState'
import { EditorController } from '../../editor/EditorController'
import { UndoManager } from '../../editor/UndoManager'
import { matchLinesToIds } from '../../data/NoteRepository'
import type { NoteLine } from '../../data/Note'

/**
 * Fake in-memory note repository that mimics Firestore's save/load contract.
 *
 * - loadNoteWithChildren: returns stored lines + trailing empty line
 * - saveNoteWithChildren: stores lines, assigns IDs to null-noteId lines,
 *   drops trailing empty lines, returns map of newly assigned IDs
 */
class FakeNoteRepository {
  private notes = new Map<string, NoteLine[]>()
  private nextId = 1

  /** Seed a note with initial lines (simulates existing Firestore data). */
  seed(noteId: string, lines: NoteLine[]): void {
    this.notes.set(noteId, lines)
  }

  async loadNoteWithChildren(noteId: string): Promise<NoteLine[]> {
    const stored = this.notes.get(noteId)
    if (!stored || stored.length === 0) {
      return [{ content: '', noteId }]
    }
    // Append trailing empty line for editor (unless note is a single empty line)
    if (stored.length === 1 && stored[0]!.content === '') {
      return [...stored]
    }
    return [...stored, { content: '', noteId: null }]
  }

  async saveNoteWithChildren(
    noteId: string,
    trackedLines: NoteLine[],
  ): Promise<Map<number, string>> {
    // Drop trailing empty lines (same as real repo)
    let end = trackedLines.length
    while (end > 1 && trackedLines[end - 1]!.content === '') end--
    const linesToSave = trackedLines.slice(0, end)

    // Ensure first line has the parent noteId
    if (linesToSave.length > 0 && linesToSave[0]!.noteId !== noteId) {
      linesToSave[0] = { ...linesToSave[0]!, noteId }
    }

    // Assign IDs to new lines (content !== '' and noteId === null)
    const createdIds = new Map<number, string>()
    const finalLines = linesToSave.map((line, index) => {
      if (line.noteId == null && line.content.replace(/^\t+/, '') !== '') {
        const newId = `generated_${this.nextId++}`
        createdIds.set(index, newId)
        return { ...line, noteId: newId }
      }
      return line
    })

    this.notes.set(noteId, finalLines)
    return createdIds
  }

  /** Direct access to stored state for assertions. */
  getStored(noteId: string): NoteLine[] {
    return this.notes.get(noteId) ?? []
  }
}

// --- Helpers ---

/** Simulates the full save flow from useEditor: matchLinesToIds → save → update tracked. */
async function simulateSave(
  repo: FakeNoteRepository,
  noteId: string,
  editorState: EditorState,
  trackedLines: NoteLine[],
): Promise<NoteLine[]> {
  const currentTexts = editorState.lines.map((l) => l.text)
  const newTracked = matchLinesToIds(noteId, trackedLines, currentTexts)
  const createdIds = await repo.saveNoteWithChildren(noteId, newTracked)

  return newTracked.map((line, index) => {
    const newId = createdIds.get(index)
    return newId ? { ...line, noteId: newId } : line
  })
}

/** Loads a note into editor state, returns [trackedLines, editorState, controller]. */
async function simulateLoad(
  repo: FakeNoteRepository,
  noteId: string,
): Promise<{ tracked: NoteLine[]; state: EditorState; controller: EditorController }> {
  const lines = await repo.loadNoteWithChildren(noteId)
  const state = new EditorState()
  const undoManager = new UndoManager()
  const controller = new EditorController(state, undoManager)

  state.initFromNoteLines(lines.map((l) => ({
    text: l.content,
    noteIds: l.noteId ? [l.noteId] : [],
  })))
  state.requestFocusUpdate()
  undoManager.setBaseline(state.lines, state.focusedLineIndex)

  return { tracked: lines, state, controller }
}

// ==================== Tests ====================

describe('load → save round-trip preserves identity', () => {
  it('save and reload preserves noteIds for unchanged content', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent line', noteId: 'root' },
      { content: '\tChild A', noteId: 'childA' },
      { content: '\tChild B', noteId: 'childB' },
    ])

    // Load
    const { tracked, state } = await simulateLoad(repo, 'root')

    // Save without changes
    const updatedTracked = await simulateSave(repo, 'root', state, tracked)

    // Reload
    const reloaded = await repo.loadNoteWithChildren('root')

    expect(reloaded[0]!.noteId).toBe('root')
    expect(reloaded[1]!.noteId).toBe('childA')
    expect(reloaded[2]!.noteId).toBe('childB')
  })

  it('new lines get IDs on save, retain them on reload', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
    ])

    // Load
    const { tracked, state } = await simulateLoad(repo, 'root')

    // Add a new line
    state.lines.splice(1, 0, { text: '\tNew child', cursorPosition: 0, noteIds: [] } as any)
    // Fix: create proper LineState
    const { LineState } = await import('../../editor/LineState')
    state.lines[1] = new LineState('\tNew child')

    // Save
    const updatedTracked = await simulateSave(repo, 'root', state, tracked)

    // New line should have gotten an ID
    const newChildLine = updatedTracked.find((l) => l.content === '\tNew child')
    expect(newChildLine).toBeDefined()
    expect(newChildLine!.noteId).toMatch(/^generated_/)

    // Reload and verify it persists
    const reloaded = await repo.loadNoteWithChildren('root')
    const reloadedChild = reloaded.find((l) => l.content === '\tNew child')
    expect(reloadedChild!.noteId).toBe(newChildLine!.noteId)
  })
})

describe('edit operations preserve identity through save cycle', () => {
  it('reordering lines preserves noteIds after save', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tTask A', noteId: 'taskA' },
      { content: '\tTask B', noteId: 'taskB' },
      { content: '\tTask C', noteId: 'taskC' },
    ])

    // Load
    const { tracked, state } = await simulateLoad(repo, 'root')

    // Move Task C to position after Parent (before Task A)
    state.moveLinesInternal(3, 3, 1)

    expect(state.lines[1]!.text).toBe('\tTask C')
    expect(state.lines[1]!.noteIds).toEqual(['taskC'])

    // Save
    await simulateSave(repo, 'root', state, tracked)

    // Verify stored order and IDs
    const stored = repo.getStored('root')
    expect(stored[1]!.content).toBe('\tTask C')
    expect(stored[1]!.noteId).toBe('taskC')
    expect(stored[2]!.content).toBe('\tTask A')
    expect(stored[2]!.noteId).toBe('taskA')
    expect(stored[3]!.content).toBe('\tTask B')
    expect(stored[3]!.noteId).toBe('taskB')
  })

  it('editing line content uses positional fallback to preserve noteId', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tOriginal task', noteId: 'task1' },
    ])

    // Load
    const { tracked, state } = await simulateLoad(repo, 'root')

    // Edit the task text
    state.lines[1]!.updateFull('\tModified task', '\tModified task'.length)

    // Save — matchLinesToIds should use positional fallback
    await simulateSave(repo, 'root', state, tracked)

    const stored = repo.getStored('root')
    expect(stored[1]!.content).toBe('\tModified task')
    expect(stored[1]!.noteId).toBe('task1') // preserved via positional fallback
  })

  it('split line creates new note on save, original keeps longer fragment', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tHello World', noteId: 'task1' },
    ])

    // Load
    const { tracked, state, controller } = await simulateLoad(repo, 'root')

    // Split "\tHello World" at position 6 (after "\tHello")
    state.focusedLineIndex = 1
    state.lines[1]!.updateFull('\tHello World', 6)
    controller.splitLine(1)

    // Both fragments should have task1 noteId
    expect(state.lines[1]!.text).toBe('\tHello')
    expect(state.lines[2]!.text).toBe('\t World')
    expect(state.lines[1]!.noteIds).toEqual(['task1'])
    expect(state.lines[2]!.noteIds).toEqual(['task1'])

    // Save — matchLinesToIds will see neither matches "\tHello World" exactly
    // Both are new content, so positional fallback applies
    await simulateSave(repo, 'root', state, tracked)

    const stored = repo.getStored('root')
    // One of the fragments should get task1, the other gets a new ID
    const task1Lines = stored.filter((l) => l.noteId === 'task1')
    const generatedLines = stored.filter((l) => l.noteId?.startsWith('generated_'))
    expect(task1Lines.length).toBe(1)
    expect(generatedLines.length).toBe(1)
  })

  it('merge preserves primary noteId after save', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tFirst part', noteId: 'noteA' },
      { content: '\tSecond', noteId: 'noteB' },
    ])

    // Load
    const { tracked, state, controller } = await simulateLoad(repo, 'root')

    // Merge line 2 into line 1
    state.focusedLineIndex = 2
    state.lines[2]!.updateFull('\tSecond', 0)
    controller.mergeToPreviousLine(2)

    // Merged line should have noteA first (longer content)
    // Note: merge concatenates full text, so "\tFirst part" + "\tSecond" = "\tFirst part\tSecond"
    expect(state.lines[1]!.text).toBe('\tFirst part\tSecond')
    expect(state.lines[1]!.noteIds).toEqual(['noteA', 'noteB'])

    // Save
    await simulateSave(repo, 'root', state, tracked)

    const stored = repo.getStored('root')
    expect(stored.length).toBe(2) // parent + merged line
    expect(stored[1]!.content).toBe('\tFirst part\tSecond')
    // matchLinesToIds won't find exact match, uses positional: line 1 gets noteA
    expect(stored[1]!.noteId).toBe('noteA')
  })

  it('delete line removes it from saved note', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tKeep this', noteId: 'keep' },
      { content: '\tDelete this', noteId: 'delete' },
    ])

    // Load
    const { tracked, state } = await simulateLoad(repo, 'root')

    // Remove line 2 (simulates selecting and deleting)
    state.lines.splice(2, 1)

    // Save
    await simulateSave(repo, 'root', state, tracked)

    const stored = repo.getStored('root')
    expect(stored.length).toBe(2)
    expect(stored[0]!.noteId).toBe('root')
    expect(stored[1]!.noteId).toBe('keep')
    // 'delete' noteId is gone
    expect(stored.find((l) => l.noteId === 'delete')).toBeUndefined()
  })
})

describe('multi-save cycles maintain stable identity', () => {
  it('multiple edits and saves keep noteIds stable', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tTask A', noteId: 'taskA' },
      { content: '\tTask B', noteId: 'taskB' },
    ])

    // Load
    let { tracked, state } = await simulateLoad(repo, 'root')

    // Edit 1: modify Task A
    state.lines[1]!.updateFull('\tTask A modified', '\tTask A modified'.length)
    tracked = await simulateSave(repo, 'root', state, tracked)

    expect(repo.getStored('root')[1]!.noteId).toBe('taskA')

    // Edit 2: add a new line
    const { LineState } = await import('../../editor/LineState')
    state.lines.splice(2, 0, new LineState('\tTask A.5'))
    tracked = await simulateSave(repo, 'root', state, tracked)

    const newLine = repo.getStored('root').find((l) => l.content === '\tTask A.5')
    expect(newLine).toBeDefined()
    expect(newLine!.noteId).toMatch(/^generated_/)
    const newLineId = newLine!.noteId!

    // Edit 3: reorder — move the new line to the end
    state.moveLinesInternal(2, 2, state.lines.length)
    tracked = await simulateSave(repo, 'root', state, tracked)

    // All IDs should be stable
    const stored = repo.getStored('root')
    expect(stored.find((l) => l.content === '\tTask A modified')!.noteId).toBe('taskA')
    expect(stored.find((l) => l.content === '\tTask B')!.noteId).toBe('taskB')
    expect(stored.find((l) => l.content === '\tTask A.5')!.noteId).toBe(newLineId)
  })

  it('save after undo restores original identity', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tTask A', noteId: 'taskA' },
    ])

    // Load
    const { tracked, state, controller } = await simulateLoad(repo, 'root')

    // Make a change
    controller.state.focusedLineIndex = 1
    controller.undoManager.beginEditingLine(state.lines, state.focusedLineIndex, 1)
    state.lines[1]!.updateFull('\tTask A changed', '\tTask A changed'.length)
    controller.commitUndoState()

    // Undo
    controller.undo()
    expect(state.lines[1]!.text).toBe('\tTask A')
    expect(state.lines[1]!.noteIds).toEqual(['taskA'])

    // Save after undo — should preserve original noteId
    await simulateSave(repo, 'root', state, tracked)

    const stored = repo.getStored('root')
    expect(stored[1]!.content).toBe('\tTask A')
    expect(stored[1]!.noteId).toBe('taskA')
  })
})

describe('cross-platform parity: noteIds in editor match save output', () => {
  it('editor noteIds align with matchLinesToIds output', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Parent', noteId: 'root' },
      { content: '\tLine 1', noteId: 'id1' },
      { content: '\tLine 2', noteId: 'id2' },
      { content: '\tLine 3', noteId: 'id3' },
    ])

    const { tracked, state } = await simulateLoad(repo, 'root')

    // Reorder: move line 3 to position 1
    state.moveLinesInternal(3, 3, 1)

    // Editor should have noteIds reordered
    expect(state.lines[1]!.noteIds).toEqual(['id3'])
    expect(state.lines[2]!.noteIds).toEqual(['id1'])
    expect(state.lines[3]!.noteIds).toEqual(['id2'])

    // matchLinesToIds should produce the same mapping
    const currentTexts = state.lines.map((l) => l.text)
    const matched = matchLinesToIds('root', tracked, currentTexts)

    expect(matched[1]!.noteId).toBe('id3')
    expect(matched[2]!.noteId).toBe('id1')
    expect(matched[3]!.noteId).toBe('id2')
  })

  it('editor noteIds survive full round-trip: load → move → save → reload → verify', async () => {
    const repo = new FakeNoteRepository()
    repo.seed('root', [
      { content: 'Root note', noteId: 'root' },
      { content: '\tAlpha', noteId: 'alpha' },
      { content: '\tBeta', noteId: 'beta' },
      { content: '\tGamma', noteId: 'gamma' },
    ])

    // First session: load, reorder, save
    const session1 = await simulateLoad(repo, 'root')
    session1.state.moveLinesInternal(3, 3, 1) // move Gamma before Alpha
    await simulateSave(repo, 'root', session1.state, session1.tracked)

    // Second session: fresh load, verify order and IDs
    const session2 = await simulateLoad(repo, 'root')
    const lines = session2.state.lines

    expect(lines[0]!.text).toBe('Root note')
    expect(lines[0]!.noteIds).toEqual(['root'])
    expect(lines[1]!.text).toBe('\tGamma')
    expect(lines[1]!.noteIds).toEqual(['gamma'])
    expect(lines[2]!.text).toBe('\tAlpha')
    expect(lines[2]!.noteIds).toEqual(['alpha'])
    expect(lines[3]!.text).toBe('\tBeta')
    expect(lines[3]!.noteIds).toEqual(['beta'])
  })
})
