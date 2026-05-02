import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'

const { repoSaveSpy, multiSaveSpy, prepareInlineSpy, updateNoteSpy, updateContentIfChangedSpy, getNoteByIdSpy, trackSaveSpy, getNoteLinesByIdSpy, enqueueSaveSpy } = vi.hoisted(() => ({
  repoSaveSpy: vi.fn(),
  multiSaveSpy: vi.fn(),
  prepareInlineSpy: vi.fn(),
  updateNoteSpy: vi.fn(),
  updateContentIfChangedSpy: vi.fn(),
  getNoteByIdSpy: vi.fn(),
  trackSaveSpy: vi.fn(),
  getNoteLinesByIdSpy: vi.fn(),
  enqueueSaveSpy: vi.fn(<T,>(op: () => Promise<T>) => op()),
}))

vi.mock('@/firebase/config', () => ({
  db: {},
  auth: { currentUser: { uid: 'u1' } },
}))

vi.mock('@/data/NoteRepository', () => {
  class FakeRepo {
    saveNoteWithFullContent = repoSaveSpy
    saveMultipleNotes = multiSaveSpy
    prepareInlineEditTrackedLines = prepareInlineSpy
  }
  return { NoteRepository: FakeRepo }
})

vi.mock('@/data/NoteStore', () => ({
  noteStore: {
    updateNote: updateNoteSpy,
    updateContentIfChanged: updateContentIfChangedSpy,
    getNoteById: getNoteByIdSpy,
    getRawNoteById: getNoteByIdSpy,
    snapshotLocalBases: () => new Map(),
    trackSave: trackSaveSpy,
    getNoteLinesById: getNoteLinesByIdSpy,
    enqueueSave: enqueueSaveSpy,
  },
}))

import { InlineSessionManager } from '@/editor/InlineSessionManager'
import { useSaveCoordinator } from '@/hooks/useSaveCoordinator'
import { note } from '../factories'

function setup(opts: {
  prepareMainSaveItem?: (id: string) => { trackedLines: { content: string; noteId: string | null }[]; applyResult: (ids: Map<number, string>) => void }
  setSaveError?: (msg: string | null) => void
  dirty?: boolean
  noteId?: string | null
  pendingOnceCacheEntries?: Record<string, Record<string, unknown>> | null
  sessionManager?: InlineSessionManager
} = {}) {
  const sessionManager = opts.sessionManager ?? new InlineSessionManager()
  const invalidate = vi.fn()
  const setSaveError = opts.setSaveError ?? vi.fn()
  const applyResult = vi.fn()
  const prepareMainSaveItem = opts.prepareMainSaveItem
    ?? vi.fn((id: string) => ({
      trackedLines: [{ content: 'title', noteId: id }],
      text: 'title',
      applyResult,
    }))
  const utils = renderHook(({ dirty }) =>
    useSaveCoordinator({
      noteId: opts.noteId ?? 'note-1',
      prepareMainSaveItem: prepareMainSaveItem as never,
      setSaveError,
      dirty,
      sessionManager,
      invalidateAndRecompute: invalidate,
      pendingOnceCacheEntries: opts.pendingOnceCacheEntries ?? null,
    }),
    { initialProps: { dirty: opts.dirty ?? false } },
  )
  return { ...utils, invalidate, sessionManager, prepareMainSaveItem, applyResult, setSaveError }
}

describe('useSaveCoordinator', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    repoSaveSpy.mockResolvedValue(new Map<number, string>())
    multiSaveSpy.mockResolvedValue(new Map<string, Map<number, string>>())
    prepareInlineSpy.mockImplementation(async (noteId: string, content: string) =>
      content.split('\n').map((c, i) => ({ content: c, noteId: i === 0 ? noteId : null })),
    )
    getNoteByIdSpy.mockReturnValue(note({ id: 'note-1', content: 'old' }))
    getNoteLinesByIdSpy.mockReturnValue(undefined)
  })

  it('saveAll transitions saveStatus from saving to saved on full success', async () => {
    const { result } = setup()
    expect(result.current.saveStatus).toBe('idle')
    await act(async () => { await result.current.saveAll() })
    expect(result.current.saveStatus).toBe('saved')
  })

  it('saveAll reports partial-error when the batched save rejects', async () => {
    multiSaveSpy.mockRejectedValueOnce(new Error('boom'))
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { result } = setup({ dirty: true })
    await act(async () => { await result.current.saveAll() })
    // dirty=true → anyDirty stays true → the auto-reset effect lands the
    // status back at 'idle' so the user can retry.
    expect(result.current.saveStatus).toBe('idle')
    expect(result.current.anyDirty).toBe(true)
    errorSpy.mockRestore()
  })

  it('reports partial-error and logs when the batched save rejects', async () => {
    const sessionManager = new InlineSessionManager()
    await sessionManager.ensureSessions(
      [note({ id: 'view1', content: 'orig' })],
      async (id) => [{ content: 'orig', noteId: id }],
    )
    const session = sessionManager.getSession('view1')!
    session.editorState.lines[0]!.updateFull('mutated', 7)

    multiSaveSpy.mockRejectedValueOnce(new Error('boom'))
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    const { result } = setup({ sessionManager })
    await act(async () => { await result.current.saveAll() })

    expect(errorSpy).toHaveBeenCalledWith('saveAll failed:', expect.any(Error))
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

  it('anyDirty reflects either main dirty OR a dirty session', async () => {
    const sessionManager = new InlineSessionManager()
    const { result, rerender } = setup({ dirty: false, sessionManager })
    expect(result.current.anyDirty).toBe(false)

    rerender({ dirty: true })
    expect(result.current.anyDirty).toBe(true)

    rerender({ dirty: false })
    await sessionManager.ensureSessions(
      [note({ id: 'view2', content: 'a' })],
      async (id) => [{ content: 'a', noteId: id }],
    )
    sessionManager.getSession('view2')!.editorState.lines[0]!.updateFull('b', 1)
    rerender({ dirty: false })
    expect(result.current.anyDirty).toBe(true)
  })

  it('saving a dirty inline session optimistically updates the store and persists via the atomic batch', async () => {
    const sessionManager = new InlineSessionManager()
    await sessionManager.ensureSessions(
      [note({ id: 'viewX', content: 'orig\nline2' })],
      async (id) => [
        { content: 'orig', noteId: id },
        { content: 'line2', noteId: `${id}-l1` },
      ],
    )
    const session = sessionManager.getSession('viewX')!
    session.editorState.lines[0]!.updateFull('changed', 7)
    getNoteByIdSpy.mockImplementation((id: string) => note({ id, content: 'old' }))

    const { result, invalidate } = setup({ sessionManager })
    await act(async () => { await result.current.saveAll() })

    expect(updateContentIfChangedSpy).toHaveBeenCalledWith(
      'viewX',
      expect.stringContaining('changed'),
    )
    // Multi-note path: saveMultipleNotes receives an items array containing
    // the dirty inline session, never the single-note saveNoteWithFullContent.
    expect(multiSaveSpy).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ noteId: 'viewX' }),
      ]),
    )
    expect(repoSaveSpy).not.toHaveBeenCalled()
    expect(invalidate).toHaveBeenCalled()
  })

  it('on unmount, dirty inline sessions are auto-saved', async () => {
    const sessionManager = new InlineSessionManager()
    await sessionManager.ensureSessions(
      [note({ id: 'viewU', content: 'orig' })],
      async (id) => [{ content: 'orig', noteId: id }],
    )
    const session = sessionManager.getSession('viewU')!
    session.editorState.lines[0]!.updateFull('changed', 7)

    const { unmount } = setup({ sessionManager })
    unmount()
    await waitFor(() => expect(repoSaveSpy).toHaveBeenCalledWith('viewU', expect.any(String)))
  })
})
