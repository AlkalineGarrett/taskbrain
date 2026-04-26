import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'

const { repoSaveSpy, updateNoteSpy, getNoteByIdSpy, trackSaveSpy, getNoteLinesByIdSpy } = vi.hoisted(() => ({
  repoSaveSpy: vi.fn(),
  updateNoteSpy: vi.fn(),
  getNoteByIdSpy: vi.fn(),
  trackSaveSpy: vi.fn(),
  getNoteLinesByIdSpy: vi.fn(),
}))

vi.mock('@/firebase/config', () => ({
  db: {},
  auth: { currentUser: { uid: 'u1' } },
}))

vi.mock('@/data/NoteRepository', () => {
  class FakeRepo {
    saveNoteWithFullContent = repoSaveSpy
  }
  return { NoteRepository: FakeRepo }
})

vi.mock('@/data/NoteStore', () => ({
  noteStore: {
    updateNote: updateNoteSpy,
    getNoteById: getNoteByIdSpy,
    trackSave: trackSaveSpy,
    getNoteLinesById: getNoteLinesByIdSpy,
  },
}))

import { EditorState } from '@/editor/EditorState'
import { InlineSessionManager } from '@/editor/InlineSessionManager'
import { useSaveCoordinator } from '@/hooks/useSaveCoordinator'
import { note } from '../factories'

function makeState() {
  const state = new EditorState()
  state.initFromNoteLines([{ text: 'title', noteIds: ['real-1'] }])
  return state
}

function setup(opts: {
  save?: () => Promise<void>
  dirty?: boolean
  noteId?: string | null
  pendingOnceCacheEntries?: Record<string, Record<string, unknown>> | null
  sessionManager?: InlineSessionManager
} = {}) {
  const editorState = makeState()
  const sessionManager = opts.sessionManager ?? new InlineSessionManager()
  const invalidate = vi.fn()
  const save = opts.save ?? vi.fn(async () => {})
  const utils = renderHook(({ dirty }) =>
    useSaveCoordinator({
      noteId: opts.noteId ?? 'note-1',
      editorState,
      save,
      dirty,
      sessionManager,
      invalidateAndRecompute: invalidate,
      pendingOnceCacheEntries: opts.pendingOnceCacheEntries ?? null,
    }),
    { initialProps: { dirty: opts.dirty ?? false } },
  )
  return { ...utils, save, invalidate, sessionManager, editorState }
}

describe('useSaveCoordinator', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    repoSaveSpy.mockResolvedValue(new Map<number, string>())
    getNoteByIdSpy.mockReturnValue(note({ id: 'note-1', content: 'old' }))
    getNoteLinesByIdSpy.mockReturnValue(undefined)
  })

  it('saveAll transitions saveStatus from saving to saved on full success', async () => {
    const { result } = setup()
    expect(result.current.saveStatus).toBe('idle')
    await act(async () => { await result.current.saveAll() })
    expect(result.current.saveStatus).toBe('saved')
  })

  it('saveAll reports partial-error when the main save rejects', async () => {
    const failingSave = vi.fn(async () => { throw new Error('boom') })
    const { result } = setup({ save: failingSave })
    await act(async () => { await result.current.saveAll() })
    // No dirty sessions and dirty=false → auto-reset will not fire,
    // so the partial-error status persists for the user to see.
    expect(result.current.saveStatus).toBe('partial-error')
  })

  it('logs the failing inline session id when its save rejects', async () => {
    const sessionManager = new InlineSessionManager()
    sessionManager.ensureSessions([note({ id: 'view1', content: 'orig' })])
    const session = sessionManager.getSession('view1')!
    session.editorState.lines[0]!.updateFull('mutated', 7)

    repoSaveSpy.mockRejectedValueOnce(new Error('boom'))
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    const { result } = setup({ sessionManager })
    await act(async () => { await result.current.saveAll() })

    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('Failed to save inline session view1'),
      expect.any(Error),
    )
    // The session is still dirty after a failed save, so anyDirty stays true
    // and the auto-reset effect lands the status back at 'idle' for retry.
    expect(result.current.anyDirty).toBe(true)
    expect(result.current.saveStatus).toBe('idle')
    errorSpy.mockRestore()
  })

  it('saveStatus resets to idle when content becomes dirty after a save', async () => {
    const { result, rerender } = setup({ dirty: false })
    await act(async () => { await result.current.saveAll() })
    expect(result.current.saveStatus).toBe('saved')

    rerender({ dirty: true })
    await waitFor(() => expect(result.current.saveStatus).toBe('idle'))
  })

  it('anyDirty reflects either main dirty OR a dirty session', () => {
    const sessionManager = new InlineSessionManager()
    const { result, rerender } = setup({ dirty: false, sessionManager })
    expect(result.current.anyDirty).toBe(false)

    rerender({ dirty: true })
    expect(result.current.anyDirty).toBe(true)

    rerender({ dirty: false })
    sessionManager.ensureSessions([note({ id: 'view2', content: 'a' })])
    sessionManager.getSession('view2')!.editorState.lines[0]!.updateFull('b', 1)
    rerender({ dirty: false })
    expect(result.current.anyDirty).toBe(true)
  })

  it('saving a dirty inline session optimistically updates the store and persists', async () => {
    const sessionManager = new InlineSessionManager()
    sessionManager.ensureSessions([note({ id: 'viewX', content: 'orig\nline2' })])
    const session = sessionManager.getSession('viewX')!
    session.editorState.lines[0]!.updateFull('changed', 7)

    const { result, invalidate } = setup({ sessionManager })
    await act(async () => { await result.current.saveAll() })

    expect(updateNoteSpy).toHaveBeenCalledWith(
      'viewX',
      expect.objectContaining({ content: expect.stringContaining('changed') }),
    )
    expect(repoSaveSpy).toHaveBeenCalledWith('viewX', expect.stringContaining('changed'))
    expect(trackSaveSpy).toHaveBeenCalled()
    expect(invalidate).toHaveBeenCalled()
  })

  it('on unmount, dirty inline sessions are auto-saved', async () => {
    const sessionManager = new InlineSessionManager()
    sessionManager.ensureSessions([note({ id: 'viewU', content: 'orig' })])
    const session = sessionManager.getSession('viewU')!
    session.editorState.lines[0]!.updateFull('changed', 7)

    const { unmount } = setup({ sessionManager })
    unmount()
    await waitFor(() => expect(repoSaveSpy).toHaveBeenCalledWith('viewU', expect.any(String)))
  })
})
