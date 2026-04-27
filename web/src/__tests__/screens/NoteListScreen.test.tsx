import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { Note } from '@/data/Note'

const { useNotesSpy, useSearchSpy, noteStoreStartSpy, navigateSpy } = vi.hoisted(() => ({
  useNotesSpy: vi.fn(),
  useSearchSpy: vi.fn(),
  noteStoreStartSpy: vi.fn(),
  navigateSpy: vi.fn(),
}))

vi.mock('@/firebase/config', () => ({ db: {}, auth: {} }))

vi.mock('@/data/NoteStore', () => ({
  noteStore: { start: noteStoreStartSpy },
}))

vi.mock('@/hooks/useNotes', () => ({
  useNotes: useNotesSpy,
}))

vi.mock('@/hooks/useSearch', () => ({
  useSearch: useSearchSpy,
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigateSpy,
}))

import { NoteListScreen } from '@/screens/NoteListScreen'

function makeNote(overrides: Partial<Note> & { id: string; content: string }): Note {
  return {
    id: overrides.id,
    userId: 'user',
    content: overrides.content,
    parentNoteId: null,
    rootNoteId: null,
    containedNotes: [],
    state: overrides.state ?? null,
    showCompleted: true,
    createdAt: null,
    updatedAt: null,
    path: '',
    onceCache: {},
    tags: [],
  } as Note
}

describe('NoteListScreen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useSearchSpy.mockReturnValue({
      searchState: { isSearchOpen: false, query: '', searchByName: false, searchByContent: false },
      toggleSearch: vi.fn(),
      setQuery: vi.fn(),
      setSearchByName: vi.fn(),
      setSearchByContent: vi.fn(),
      executeSearch: vi.fn(),
      replaySearch: vi.fn(),
      activeResults: [],
      deletedResults: [],
      searchHistory: [],
    })
  })

  it('renders only the first line of a multi-line note in the active list', () => {
    // Regression guard: NoteStore returns reconstructed notes whose `content`
    // is the full joined tree. The list view must show only the first line.
    useNotesSpy.mockReturnValue({
      activeNotes: [
        makeNote({
          id: 'n1',
          content: 'First line shown\nSecond line hidden\nThird line hidden',
        }),
      ],
      deletedNotes: [],
      stats: new Map(),
      loading: false,
      error: null,
      sortMode: 'recent',
      setSortMode: vi.fn(),
      createNote: vi.fn(),
      deleteNote: vi.fn(),
      undeleteNote: vi.fn(),
      clearDeleted: vi.fn(),
      refresh: vi.fn(),
    })

    render(<NoteListScreen />)

    expect(screen.getByText('First line shown')).toBeInTheDocument()
    expect(screen.queryByText(/Second line hidden/)).toBeNull()
    expect(screen.queryByText(/Third line hidden/)).toBeNull()
  })

  it('renders only the first line of a multi-line note in the deleted list', () => {
    useNotesSpy.mockReturnValue({
      activeNotes: [],
      deletedNotes: [
        makeNote({
          id: 'd1',
          content: 'Deleted first line\nDeleted second line',
          state: 'deleted',
        }),
      ],
      stats: new Map(),
      loading: false,
      error: null,
      sortMode: 'recent',
      setSortMode: vi.fn(),
      createNote: vi.fn(),
      deleteNote: vi.fn(),
      undeleteNote: vi.fn(),
      clearDeleted: vi.fn(),
      refresh: vi.fn(),
    })

    render(<NoteListScreen />)

    expect(screen.getByText('Deleted first line')).toBeInTheDocument()
    expect(screen.queryByText(/Deleted second line/)).toBeNull()
  })
})
