import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { EditorState } from '@/editor/EditorState'
import { EditorController } from '@/editor/EditorController'
import { useDirectiveMutations } from '@/hooks/useDirectiveMutations'
import { MutationType, type NoteMutation } from '@/dsl/runtime/NoteMutation'
import { isSentinelNoteId, originOfSentinel } from '@/data/NoteIdSentinel'
import { note } from '../factories'

const updateNoteSpy = vi.fn()
vi.mock('@/data/NoteStore', () => ({
  noteStore: {
    updateNote: (...args: unknown[]) => updateNoteSpy(...args),
  },
}))

function makeEditor() {
  const state = new EditorState()
  state.initFromNoteLines([
    { text: 'old title', noteIds: ['real-1'] },
    { text: 'child line', noteIds: ['real-2'] },
  ])
  const controller = new EditorController(state)
  return { state, controller }
}

describe('useDirectiveMutations', () => {
  beforeEach(() => {
    updateNoteSpy.mockClear()
  })

  it('updates the note store for every mutation regardless of target', () => {
    const { state, controller } = makeEditor()
    const mutations: NoteMutation[] = [
      {
        noteId: 'other',
        updatedNote: note({ id: 'other', content: 'X' }),
        mutationType: MutationType.CONTENT_CHANGED,
      },
    ]
    renderHook(() => useDirectiveMutations('current', state, controller, mutations))
    expect(updateNoteSpy).toHaveBeenCalledWith('other', expect.objectContaining({ id: 'other' }))
  })

  it('CONTENT_CHANGED on the current note updates line 0 in place, preserving subsequent line ids', () => {
    const { state, controller } = makeEditor()
    const originalChildIds = state.lines[1]!.noteIds
    const mutations: NoteMutation[] = [
      {
        noteId: 'current',
        updatedNote: note({ id: 'current', content: 'new title' }),
        mutationType: MutationType.CONTENT_CHANGED,
      },
    ]
    renderHook(() => useDirectiveMutations('current', state, controller, mutations))

    expect(state.lines[0]!.text).toBe('new title')
    // Critical: line 1's noteIds must be preserved.
    expect(state.lines[1]!.noteIds).toEqual(originalChildIds)
  })

  it('CONTENT_APPENDED appends new lines tagged with a directive sentinel', () => {
    const { state, controller } = makeEditor()
    const initialLineCount = state.lines.length
    const mutations: NoteMutation[] = [
      {
        noteId: 'current',
        updatedNote: note({ id: 'current', content: 'old title\nchild line\nappended A\nappended B' }),
        mutationType: MutationType.CONTENT_APPENDED,
        appendedText: 'appended A\nappended B',
      },
    ]
    renderHook(() => useDirectiveMutations('current', state, controller, mutations))

    expect(state.lines.length).toBe(initialLineCount + 2)
    const lastTwo = state.lines.slice(-2)
    expect(lastTwo.map(l => l.text)).toEqual(['appended A', 'appended B'])
    for (const l of lastTwo) {
      expect(isSentinelNoteId(l.noteIds[0])).toBe(true)
      expect(originOfSentinel(l.noteIds[0]!)).toBe('directive')
    }
  })

  it('PATH_CHANGED on the current note does not touch editor lines', () => {
    const { state, controller } = makeEditor()
    const before = state.lines.map(l => ({ text: l.text, ids: [...l.noteIds] }))
    const mutations: NoteMutation[] = [
      {
        noteId: 'current',
        updatedNote: note({ id: 'current', path: 'new/path' }),
        mutationType: MutationType.PATH_CHANGED,
      },
    ]
    renderHook(() => useDirectiveMutations('current', state, controller, mutations))

    const after = state.lines.map(l => ({ text: l.text, ids: [...l.noteIds] }))
    expect(after).toEqual(before)
  })

  it('mutations targeting another note do not modify editor lines', () => {
    const { state, controller } = makeEditor()
    const before = state.lines.map(l => l.text)
    const mutations: NoteMutation[] = [
      {
        noteId: 'someone-else',
        updatedNote: note({ id: 'someone-else', content: 'irrelevant' }),
        mutationType: MutationType.CONTENT_CHANGED,
      },
    ]
    renderHook(() => useDirectiveMutations('current', state, controller, mutations))

    expect(state.lines.map(l => l.text)).toEqual(before)
  })
})
