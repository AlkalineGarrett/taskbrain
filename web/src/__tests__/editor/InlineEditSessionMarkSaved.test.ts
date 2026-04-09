import { describe, it, expect } from 'vitest'
import { InlineEditSession } from '../../editor/InlineEditSession'

describe('InlineEditSession.markSaved', () => {
  it('clears dirty state after markSaved', () => {
    const session = new InlineEditSession('note1', 'Hello')
    session.editorState.lines[0]!.updateFull('Modified', 8)
    expect(session.isDirty).toBe(true)

    session.markSaved()
    expect(session.isDirty).toBe(false)
  })

  it('uses current text as new baseline', () => {
    const session = new InlineEditSession('note1', 'Original')
    session.editorState.lines[0]!.updateFull('Saved content', 13)
    session.markSaved()

    // Further edits relative to the saved content
    session.editorState.lines[0]!.updateFull('Saved content', 13)
    expect(session.isDirty).toBe(false)

    session.editorState.lines[0]!.updateFull('New edit', 8)
    expect(session.isDirty).toBe(true)
  })

  it('reverting to saved content (not original) clears dirty', () => {
    const session = new InlineEditSession('note1', 'Original')
    session.editorState.lines[0]!.updateFull('Saved', 5)
    session.markSaved()

    session.editorState.lines[0]!.updateFull('Temporary', 9)
    expect(session.isDirty).toBe(true)

    // Revert to saved content, not original
    session.editorState.lines[0]!.updateFull('Saved', 5)
    expect(session.isDirty).toBe(false)
  })

  it('reverting to original content after markSaved is dirty', () => {
    const session = new InlineEditSession('note1', 'Original')
    session.editorState.lines[0]!.updateFull('Saved', 5)
    session.markSaved()

    // Going back to the original is now a change relative to the saved baseline
    session.editorState.lines[0]!.updateFull('Original', 8)
    expect(session.isDirty).toBe(true)
  })

  it('works with multi-line content', () => {
    const session = new InlineEditSession('note1', 'Line 1\nLine 2')
    session.editorState.lines[0]!.updateFull('Changed 1', 9)
    session.editorState.lines[1]!.updateFull('Changed 2', 9)
    expect(session.isDirty).toBe(true)

    session.markSaved()
    expect(session.isDirty).toBe(false)
    expect(session.getText()).toBe('Changed 1\nChanged 2')
  })
})
