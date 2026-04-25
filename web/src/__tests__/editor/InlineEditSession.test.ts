import { describe, it, expect } from 'vitest'
import { InlineEditSession } from '../../editor/InlineEditSession'

describe('InlineEditSession', () => {
  // --- Constructor ---

  it('initializes with single-line content', () => {
    const session = new InlineEditSession('note1', 'Hello')
    expect(session.noteId).toBe('note1')
    // Content is loaded verbatim — no auto-appended trailing empty since
    // empty lines are first-class docs that round-trip via Firestore now.
    expect(session.editorState.lines.length).toBe(1)
    expect(session.editorState.lines[0]!.text).toBe('Hello')
  })

  it('initializes with multi-line content', () => {
    const session = new InlineEditSession('note1', 'Line 1\nLine 2\nLine 3')
    expect(session.editorState.lines.length).toBe(3)
    expect(session.editorState.lines[0]!.text).toBe('Line 1')
    expect(session.editorState.lines[1]!.text).toBe('Line 2')
    expect(session.editorState.lines[2]!.text).toBe('Line 3')
  })

  it('initializes with empty content', () => {
    const session = new InlineEditSession('note1', '')
    expect(session.editorState.lines.length).toBe(1) // just the empty line
    expect(session.editorState.lines[0]!.text).toBe('')
  })

  it('does not add extra trailing empty if content already ends with empty line', () => {
    const session = new InlineEditSession('note1', 'Line 1\n')
    // 'Line 1\n' splits to ['Line 1', ''] — already has trailing empty
    expect(session.editorState.lines.length).toBe(2)
    expect(session.editorState.lines[0]!.text).toBe('Line 1')
    expect(session.editorState.lines[1]!.text).toBe('')
  })

  // --- isDirty ---

  it('is not dirty initially', () => {
    const session = new InlineEditSession('note1', 'Hello')
    expect(session.isDirty).toBe(false)
  })

  it('is dirty after content modification', () => {
    const session = new InlineEditSession('note1', 'Hello')
    session.editorState.lines[0]!.updateFull('Modified', 8)
    expect(session.isDirty).toBe(true)
  })

  it('is not dirty when content is restored to original', () => {
    const session = new InlineEditSession('note1', 'Hello')
    session.editorState.lines[0]!.updateFull('Changed', 7)
    expect(session.isDirty).toBe(true)
    session.editorState.lines[0]!.updateFull('Hello', 5)
    expect(session.isDirty).toBe(false)
  })

  // --- getText ---

  it('returns content without trailing empty line', () => {
    const session = new InlineEditSession('note1', 'Line 1\nLine 2')
    expect(session.getText()).toBe('Line 1\nLine 2')
  })

  it('returns single line content without trailing empty', () => {
    const session = new InlineEditSession('note1', 'Hello')
    expect(session.getText()).toBe('Hello')
  })

  it('preserves intentional empty lines in content', () => {
    const session = new InlineEditSession('note1', 'Line 1\n\nLine 3')
    expect(session.getText()).toBe('Line 1\n\nLine 3')
  })

  it('returns empty string for empty content', () => {
    const session = new InlineEditSession('note1', '')
    expect(session.getText()).toBe('')
  })

  // --- updateHiddenIndices ---

  it('hides nothing — every line is a real doc under the new model', () => {
    const session = new InlineEditSession('note1', 'Line 1\nLine 2')
    expect(session.controller.hiddenIndices.size).toBe(0)
  })

  it('does not hide anything for single empty line', () => {
    const session = new InlineEditSession('note1', '')
    expect(session.controller.hiddenIndices.size).toBe(0)
  })

  it('updates hidden indices after line count changes', () => {
    const session = new InlineEditSession('note1', 'A\nB')
    expect(session.controller.hiddenIndices.size).toBe(0)

    // Simulate adding a line (split)
    session.controller.splitLine(1)
    session.updateHiddenIndices()
    // Empty lines are no longer hidden — the trailing-empty UI affordance is gone.
    expect(session.controller.hiddenIndices.size).toBe(0)
  })

  // --- syncOriginalContent ---

  it('syncOriginalContent resets dirty state when content matches editor', () => {
    const session = new InlineEditSession('note1', 'Hello\nWorld')
    // Not dirty initially
    expect(session.isDirty).toBe(false)

    // Simulate external change applied to editor
    session.editorState.lines[0]!.updateFull('Hello changed', 0)
    expect(session.isDirty).toBe(true)

    // Sync baseline to match editor content
    session.syncOriginalContent(session.getText())
    expect(session.isDirty).toBe(false)
  })

  it('syncOriginalContent with different content leaves session dirty', () => {
    const session = new InlineEditSession('note1', 'Hello\nWorld')
    session.editorState.lines[0]!.updateFull('User edit', 0)

    // Sync to external content that differs from editor
    session.syncOriginalContent('External content')
    expect(session.isDirty).toBe(true)
  })

  it('syncOriginalContent does not modify editor state', () => {
    const session = new InlineEditSession('note1', 'Hello\nWorld')
    const linesBefore = session.editorState.lines.map(l => l.text)

    session.syncOriginalContent('Completely different')

    const linesAfter = session.editorState.lines.map(l => l.text)
    expect(linesAfter).toEqual(linesBefore)
  })
})
