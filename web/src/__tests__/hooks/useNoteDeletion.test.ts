import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'

const { softDeleteSpy, undeleteSpy, getNoteByIdSpy, updateNoteSpy, removeTabSpy, navigateSpy } = vi.hoisted(() => ({
  softDeleteSpy: vi.fn(),
  undeleteSpy: vi.fn(),
  getNoteByIdSpy: vi.fn(),
  updateNoteSpy: vi.fn(),
  removeTabSpy: vi.fn(),
  navigateSpy: vi.fn(),
}))

vi.mock('@/firebase/config', () => ({ db: {}, auth: {} }))

vi.mock('@/data/NoteRepository', () => {
  class FakeRepo {
    softDeleteNote = softDeleteSpy
    undeleteNote = undeleteSpy
  }
  return { NoteRepository: FakeRepo }
})

vi.mock('@/data/NoteStore', () => ({
  noteStore: {
    getNoteById: getNoteByIdSpy,
    updateNote: updateNoteSpy,
  },
}))

vi.mock('@/components/RecentTabsBar', () => ({
  removeTab: removeTabSpy,
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigateSpy,
}))

import { useNoteDeletion } from '@/hooks/useNoteDeletion'

describe('useNoteDeletion', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    softDeleteSpy.mockResolvedValue(undefined)
    undeleteSpy.mockResolvedValue(undefined)
  })

  it('handleDeleteNote soft-deletes, removes the tab, and navigates to the next note', async () => {
    removeTabSpy.mockResolvedValue('next-id')
    const { result } = renderHook(() => useNoteDeletion('current-id'))
    await act(async () => { await result.current.handleDeleteNote() })

    expect(softDeleteSpy).toHaveBeenCalledWith('current-id')
    expect(removeTabSpy).toHaveBeenCalledWith('current-id', 'current-id')
    expect(navigateSpy).toHaveBeenCalledWith('/note/next-id')
  })

  it('handleDeleteNote navigates to root when no next tab exists', async () => {
    removeTabSpy.mockResolvedValue(null)
    const { result } = renderHook(() => useNoteDeletion('current-id'))
    await act(async () => { await result.current.handleDeleteNote() })
    expect(navigateSpy).toHaveBeenCalledWith('/')
  })

  it('handleDeleteNote is a no-op when noteId is missing', async () => {
    const { result } = renderHook(() => useNoteDeletion(null))
    await act(async () => { await result.current.handleDeleteNote() })
    expect(softDeleteSpy).not.toHaveBeenCalled()
    expect(navigateSpy).not.toHaveBeenCalled()
  })

  it('handleDeleteNote logs and recovers when softDelete throws', async () => {
    softDeleteSpy.mockRejectedValue(new Error('network'))
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { result } = renderHook(() => useNoteDeletion('current-id'))
    await act(async () => { await result.current.handleDeleteNote() })
    expect(errorSpy).toHaveBeenCalled()
    expect(navigateSpy).not.toHaveBeenCalled()
    errorSpy.mockRestore()
  })

  it('handleRestoreNote undeletes and clears the deleted state in the store', async () => {
    getNoteByIdSpy.mockReturnValue({ id: 'current-id', state: 'deleted' })
    const { result } = renderHook(() => useNoteDeletion('current-id'))
    await act(async () => { await result.current.handleRestoreNote() })

    expect(undeleteSpy).toHaveBeenCalledWith('current-id')
    expect(updateNoteSpy).toHaveBeenCalledWith(
      'current-id',
      expect.objectContaining({ state: null }),
    )
  })

  it('handleRestoreNote skips store update if note is missing locally', async () => {
    getNoteByIdSpy.mockReturnValue(undefined)
    const { result } = renderHook(() => useNoteDeletion('current-id'))
    await act(async () => { await result.current.handleRestoreNote() })

    expect(undeleteSpy).toHaveBeenCalledWith('current-id')
    expect(updateNoteSpy).not.toHaveBeenCalled()
  })
})
