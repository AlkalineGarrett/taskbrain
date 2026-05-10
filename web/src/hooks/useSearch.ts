import { useState, useCallback, useEffect, useRef } from 'react'
import { useAllNotes } from './useNoteStore'
import { searchNotes, type NoteSearchResult } from '@/data/NoteSearchUtils'
import {
  SearchHistoryRepository,
  type SearchHistoryEntry,
} from '@/data/SearchHistoryRepository'
import { getDb, auth } from '@/firebase/config'

export interface SearchState {
  query: string
  searchByName: boolean
  searchByContent: boolean
  isSearchOpen: boolean
}

const INITIAL_STATE: SearchState = {
  query: '',
  searchByName: true,
  searchByContent: true,
  isSearchOpen: false,
}

export function useSearch() {
  const allNotes = useAllNotes()
  const [searchState, setSearchState] = useState<SearchState>(INITIAL_STATE)
  const [activeResults, setActiveResults] = useState<NoteSearchResult[]>([])
  const [deletedResults, setDeletedResults] = useState<NoteSearchResult[]>([])
  const [searchHistory, setSearchHistory] = useState<SearchHistoryEntry[]>([])
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Load history on mount and sync from Firebase
  useEffect(() => {
    const historyRepo = new SearchHistoryRepository(getDb(), auth)
    setSearchHistory(historyRepo.getHistory())
    void historyRepo.syncFromFirebase().then(() => {
      setSearchHistory(historyRepo.getHistory())
    })
  }, [])

  const runSearch = useCallback(
    (state: SearchState) => {
      if (!state.query) {
        setActiveResults([])
        setDeletedResults([])
        return
      }
      const results = searchNotes(
        allNotes,
        state.query,
        state.searchByName,
        state.searchByContent,
      )
      setActiveResults(results.active)
      setDeletedResults(results.deleted)
    },
    [allNotes],
  )

  const saveToHistory = useCallback(
    (state: SearchState) => {
      if (!state.query) return
      const historyRepo = new SearchHistoryRepository(getDb(), auth)
      historyRepo.saveEntry({
        query: state.query,
        criteria: { name: state.searchByName, content: state.searchByContent },
        timestamp: Date.now(),
      })
      setSearchHistory(historyRepo.getHistory())
    },
    [],
  )

  /** Explicit search triggered by Go button or Enter key. Saves to history. */
  const executeSearch = useCallback(
    (state: SearchState = searchState) => {
      runSearch(state)
      saveToHistory(state)
    },
    [searchState, runSearch, saveToHistory],
  )

  const toggleSearch = useCallback(() => {
    setSearchState((prev) => {
      if (prev.isSearchOpen) {
        setActiveResults([])
        setDeletedResults([])
        return INITIAL_STATE
      }
      return { ...prev, isSearchOpen: true }
    })
  }, [])

  const setQuery = useCallback((query: string) => {
    setSearchState((prev) => ({ ...prev, query }))
  }, [])

  const setSearchByName = useCallback(
    (enabled: boolean) => {
      setSearchState((prev) => {
        const next = { ...prev, searchByName: enabled }
        // Run search outside the updater via microtask to avoid setState-in-setState
        if (next.query) queueMicrotask(() => runSearch(next))
        return next
      })
    },
    [runSearch],
  )

  const setSearchByContent = useCallback(
    (enabled: boolean) => {
      setSearchState((prev) => {
        const next = { ...prev, searchByContent: enabled }
        if (next.query) queueMicrotask(() => runSearch(next))
        return next
      })
    },
    [runSearch],
  )

  const replaySearch = useCallback(
    (entry: SearchHistoryEntry) => {
      const state: SearchState = {
        query: entry.query,
        searchByName: entry.criteria.name ?? true,
        searchByContent: entry.criteria.content ?? true,
        isSearchOpen: true,
      }
      setSearchState(state)
      runSearch(state)
      saveToHistory(state)
    },
    [runSearch, saveToHistory],
  )

  // Auto-search with debounce when query >= 3 chars (no history save)
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current)

    if (searchState.query.length >= 3) {
      debounceRef.current = setTimeout(() => {
        runSearch(searchState)
      }, 300)
    } else {
      setActiveResults([])
      setDeletedResults([])
    }

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [searchState.query, allNotes]) // eslint-disable-line react-hooks/exhaustive-deps

  return {
    searchState,
    toggleSearch,
    setQuery,
    setSearchByName,
    setSearchByContent,
    executeSearch,
    replaySearch,
    activeResults,
    deletedResults,
    searchHistory,
  }
}
