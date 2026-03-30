import { describe, it, expect } from 'vitest'
import { InlineEditSession } from '@/editor/InlineEditSession'

describe('InlineEditSession', () => {
  // --- Constructor ---

  it('initializes with single-line content', () => {
    const session = new InlineEditSession('note1', 'Hello')
    expect(session.noteId).toBe('note1')
    expect(session.editorState.lines.length).toBe(2) // content + trailing empty
    expect(session.editorState.lines[0]!.text).toBe('Hello')
    expect(session.editorState.lines[1]!.text).toBe('')
  })

  it('initializes with multi-line content', () => {
    const session = new InlineEditSession('note1', 'Line 1\nLine 2\nLine 3')
    expect(session.editorState.lines.length).toBe(4) // 3 lines + trailing empty
    expect(session.editorState.lines[0]!.text).toBe('Line 1')
    expect(session.editorState.lines[1]!.text).toBe('Line 2')
    expect(session.editorState.lines[2]!.text).toBe('Line 3')
    expect(session.editorState.lines[3]!.text).toBe('')
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

  it('hides trailing empty line', () => {
    const session = new InlineEditSession('note1', 'Line 1\nLine 2')
    // Lines: ['Line 1', 'Line 2', ''] — trailing empty at index 2
    expect(session.controller.hiddenIndices.has(2)).toBe(true)
    expect(session.controller.hiddenIndices.has(0)).toBe(false)
    expect(session.controller.hiddenIndices.has(1)).toBe(false)
  })

  it('does not hide anything for single empty line', () => {
    const session = new InlineEditSession('note1', '')
    // Lines: [''] — only one line, don't hide it
    expect(session.controller.hiddenIndices.size).toBe(0)
  })

  it('updates hidden indices after line count changes', () => {
    const session = new InlineEditSession('note1', 'A\nB')
    // Lines: ['A', 'B', ''] — hidden = {2}
    expect(session.controller.hiddenIndices.has(2)).toBe(true)

    // Simulate adding a line (split)
    session.controller.splitLine(1)
    session.updateHiddenIndices()
    // Lines should now have 4 lines, trailing empty at index 3
    expect(session.controller.hiddenIndices.has(session.editorState.lines.length - 1)).toBe(true)
  })
})
