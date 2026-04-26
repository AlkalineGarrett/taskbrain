import React, { useState, useRef, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotes } from '@/hooks/useNotes'
import { useSearch } from '@/hooks/useSearch'
import { noteStore } from '@/data/NoteStore'
import { db, auth } from '@/firebase/config'
import type { NoteSearchResult, SearchMatch, ContentSnippet } from '@/data/NoteSearchUtils'
import type { SearchHistoryEntry } from '@/data/SearchHistoryRepository'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import {
  ADD_NOTE, DELETE_NOTE, RESTORE_NOTE, SECTION_DELETED_NOTES,
  NO_NOTES_FOUND, EMPTY_NOTE, REFRESH, LOADING, NOTE_MENU,
  SEARCH, SEARCH_HINT, SEARCH_FILTER_NAME, SEARCH_FILTER_CONTENT,
  SEARCH_GO, SEARCH_NO_RESULTS, SEARCH_HISTORY_BUTTON,
  CLEAR_DELETED, CLEAR_DELETED_CONFIRM_TITLE, CLEAR_DELETED_CONFIRM_MESSAGE, clearedDeletedCount,
  SORT_RECENT, SORT_FREQUENT, SORT_CONSISTENT,
} from '@/strings'
import type { NoteSortMode } from '@/data/NoteFilteringUtils'
import styles from './NoteListScreen.module.css'

export function NoteListScreen() {
  // Start NoteStore listener so search has access to reconstructed note content
  useEffect(() => { noteStore.start(db, auth) }, [])
  const {
    activeNotes,
    deletedNotes,
    stats,
    loading,
    error,
    sortMode,
    setSortMode,
    createNote,
    deleteNote,
    undeleteNote,
    clearDeleted,
    refresh,
  } = useNotes()
  const {
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
  } = useSearch()
  const navigate = useNavigate()

  const handleCreateNote = async () => {
    const id = await createNote()
    navigate(`/note/${id}`)
  }

  const showSearchResults = searchState.isSearchOpen && searchState.query.length > 0

  // Hard-delete all soft-deleted notes. Destructive — gated by a confirm dialog.
  const [clearConfirmOpen, setClearConfirmOpen] = useState(false)
  const [clearStatus, setClearStatus] = useState<{ count: number } | null>(null)
  const [clearing, setClearing] = useState(false)

  const handleClearDeleted = async () => {
    setClearConfirmOpen(false)
    setClearing(true)
    try {
      const count = await clearDeleted()
      setClearStatus({ count })
    } catch (e) {
      console.error('Failed to clear deleted notes', e)
    } finally {
      setClearing(false)
    }
  }

  return (
    <div className={styles.container}>
      <div className={styles.toolbar}>
        <button className={styles.createButton} onClick={handleCreateNote}>
          + {ADD_NOTE}
        </button>
        <div className={styles.toolbarRight}>
          <button className={styles.searchButton} onClick={toggleSearch}>
            {SEARCH}
          </button>
          <button className={styles.refreshButton} onClick={refresh}>
            {REFRESH}
          </button>
        </div>
      </div>

      {!searchState.isSearchOpen && (
        <SortModeRow selected={sortMode} onSelect={setSortMode} />
      )}

      {searchState.isSearchOpen && (
        <SearchPanel
          query={searchState.query}
          searchByName={searchState.searchByName}
          searchByContent={searchState.searchByContent}
          searchHistory={searchHistory}
          onQueryChange={setQuery}
          onSearchByNameChange={setSearchByName}
          onSearchByContentChange={setSearchByContent}
          onGoClick={() => executeSearch()}
          onHistorySelect={replaySearch}
        />
      )}

      <main className={styles.main}>
        {loading && <p className={styles.status}>{LOADING}</p>}
        {error && <p className={styles.error}>{error}</p>}

        {showSearchResults ? (
          <SearchResults
            activeResults={activeResults}
            deletedResults={deletedResults}
            onNoteClick={(id) => navigate(`/note/${id}`)}
            onDelete={deleteNote}
            onUndelete={undeleteNote}
          />
        ) : (
          <>
            {!loading && activeNotes.length === 0 && deletedNotes.length === 0 && (
              <p className={styles.status}>{NO_NOTES_FOUND}</p>
            )}

            <ul className={styles.noteList}>
              {activeNotes.map((note) => (
                <li key={note.id} className={styles.noteItem}>
                  <button
                    className={styles.noteButton}
                    onClick={() => navigate(`/note/${note.id}`)}
                  >
                    <span className={styles.noteContent}>
                      {note.content || EMPTY_NOTE}
                    </span>
                    <span className={styles.noteDate}>
                      {formatDate(stats.get(note.id)?.lastAccessedAt ?? note.updatedAt)}
                    </span>
                  </button>
                  <NoteItemMenu
                    onAction={() => deleteNote(note.id)}
                    actionLabel={DELETE_NOTE}
                    actionIcon={'\u{1F5D1}'}
                    isDanger
                  />
                </li>
              ))}
            </ul>

            {deletedNotes.length > 0 && (
              <>
                <h3 className={styles.deletedHeader}>{SECTION_DELETED_NOTES}</h3>
                <ul className={styles.noteList}>
                  {deletedNotes.map((note) => (
                    <li key={note.id} className={`${styles.noteItem} ${styles.deletedItem}`}>
                      <button
                        className={styles.noteButton}
                        onClick={() => navigate(`/note/${note.id}`)}
                      >
                        <span className={styles.noteContent}>
                          {note.content || EMPTY_NOTE}
                        </span>
                        <span className={styles.noteDate}>
                          {formatDate(note.updatedAt)}
                        </span>
                      </button>
                      <NoteItemMenu
                        onAction={() => undeleteNote(note.id)}
                        actionLabel={RESTORE_NOTE}
                        actionIcon={'\u21A9'}
                      />
                    </li>
                  ))}
                </ul>
                <div className={styles.clearDeletedRow}>
                  <button
                    className={styles.clearDeletedButton}
                    onClick={() => setClearConfirmOpen(true)}
                    disabled={clearing}
                  >
                    {CLEAR_DELETED}
                  </button>
                </div>
              </>
            )}
            {clearStatus && (
              <p className={styles.status}>{clearedDeletedCount(clearStatus.count)}</p>
            )}
          </>
        )}
      </main>

      <ConfirmDialog
        open={clearConfirmOpen}
        title={CLEAR_DELETED_CONFIRM_TITLE}
        message={CLEAR_DELETED_CONFIRM_MESSAGE}
        confirmLabel={CLEAR_DELETED}
        danger
        onConfirm={() => void handleClearDeleted()}
        onCancel={() => setClearConfirmOpen(false)}
      />
    </div>
  )
}

function SortModeRow({
  selected,
  onSelect,
}: {
  selected: NoteSortMode
  onSelect: (mode: NoteSortMode) => void
}) {
  const modes: { mode: NoteSortMode; label: string }[] = [
    { mode: 'recent', label: SORT_RECENT },
    { mode: 'frequent', label: SORT_FREQUENT },
    { mode: 'consistent', label: SORT_CONSISTENT },
  ]
  return (
    <div className={styles.sortRow}>
      {modes.map(({ mode, label }) => (
        <button
          key={mode}
          className={`${styles.sortButton} ${selected === mode ? styles.sortButtonActive : ''}`}
          onClick={() => onSelect(mode)}
        >
          {label}
        </button>
      ))}
    </div>
  )
}

function SearchPanel({
  query,
  searchByName,
  searchByContent,
  searchHistory,
  onQueryChange,
  onSearchByNameChange,
  onSearchByContentChange,
  onGoClick,
  onHistorySelect,
}: {
  query: string
  searchByName: boolean
  searchByContent: boolean
  searchHistory: SearchHistoryEntry[]
  onQueryChange: (q: string) => void
  onSearchByNameChange: (v: boolean) => void
  onSearchByContentChange: (v: boolean) => void
  onGoClick: () => void
  onHistorySelect: (entry: SearchHistoryEntry) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [showHistory, setShowHistory] = useState(false)
  const historyRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  useEffect(() => {
    if (!showHistory) return
    function handleClickOutside(e: MouseEvent) {
      if (historyRef.current && !historyRef.current.contains(e.target as Node)) {
        setShowHistory(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [showHistory])

  return (
    <div className={styles.searchPanel}>
      <div className={styles.searchInputRow}>
        <div className={styles.searchInputWrapper} ref={historyRef}>
          <input
            ref={inputRef}
            className={styles.searchInput}
            type="text"
            placeholder={SEARCH_HINT}
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && query.length > 0) onGoClick()
            }}
          />
          {searchHistory.length > 0 && (
            <button
              className={styles.searchTriangle}
              onClick={() => setShowHistory((prev) => !prev)}
              title={SEARCH_HISTORY_BUTTON}
            >
              &#x25BE;
            </button>
          )}
          {showHistory && searchHistory.length > 0 && (
            <div className={styles.searchHistoryDropdown}>
              {searchHistory.map((entry, i) => (
                <button
                  key={i}
                  className={styles.searchHistoryItem}
                  onClick={() => {
                    setShowHistory(false)
                    onHistorySelect(entry)
                  }}
                >
                  <span className={styles.searchHistoryQuery}>{entry.query}</span>
                  <span className={styles.searchHistoryTags}>
                    {Object.entries(entry.criteria)
                      .filter(([, v]) => v)
                      .map(([k]) => k.charAt(0).toUpperCase() + k.slice(1))
                      .join(', ')}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
        {query.length > 0 && (
          <button className={styles.searchGoButton} onClick={onGoClick}>
            {SEARCH_GO}
          </button>
        )}
      </div>
      <div className={styles.searchCheckboxes}>
        <label className={styles.searchCheckboxLabel}>
          <input
            type="checkbox"
            checked={searchByName}
            onChange={(e) => onSearchByNameChange(e.target.checked)}
          />
          {SEARCH_FILTER_NAME}
        </label>
        <label className={styles.searchCheckboxLabel}>
          <input
            type="checkbox"
            checked={searchByContent}
            onChange={(e) => onSearchByContentChange(e.target.checked)}
          />
          {SEARCH_FILTER_CONTENT}
        </label>
      </div>
    </div>
  )
}

function SearchResults({
  activeResults,
  deletedResults,
  onNoteClick,
  onDelete,
  onUndelete,
}: {
  activeResults: NoteSearchResult[]
  deletedResults: NoteSearchResult[]
  onNoteClick: (id: string) => void
  onDelete: (id: string) => void
  onUndelete: (id: string) => void
}) {
  if (activeResults.length === 0 && deletedResults.length === 0) {
    return <p className={styles.status}>{SEARCH_NO_RESULTS}</p>
  }

  return (
    <>
      <ul className={styles.noteList}>
        {activeResults.map((result) => (
          <SearchResultItem
            key={result.note.id}
            result={result}
            onClick={() => onNoteClick(result.note.id)}
            menuAction={() => onDelete(result.note.id)}
            menuLabel={DELETE_NOTE}
            menuIcon={'\u{1F5D1}'}
            isDanger
          />
        ))}
      </ul>

      {deletedResults.length > 0 && (
        <>
          <h3 className={styles.deletedHeader}>{SECTION_DELETED_NOTES}</h3>
          <ul className={styles.noteList}>
            {deletedResults.map((result) => (
              <SearchResultItem
                key={result.note.id}
                result={result}
                onClick={() => onNoteClick(result.note.id)}
                isDeleted
                menuAction={() => onUndelete(result.note.id)}
                menuLabel={RESTORE_NOTE}
                menuIcon={'\u21A9'}
              />
            ))}
          </ul>
        </>
      )}
    </>
  )
}

function SearchResultItem({
  result,
  onClick,
  menuAction,
  menuLabel,
  menuIcon,
  isDeleted = false,
  isDanger = false,
}: {
  result: NoteSearchResult
  onClick: () => void
  menuAction: () => void
  menuLabel: string
  menuIcon: string
  isDeleted?: boolean
  isDanger?: boolean
}) {
  const firstLine = result.note.content.split('\n')[0] ?? ''

  return (
    <li className={`${styles.noteItem} ${isDeleted ? styles.deletedItem : ''}`}>
      <div className={styles.searchResultContent}>
        <button className={styles.noteButton} onClick={onClick}>
          <span className={styles.noteContent}>
            <HighlightedText
              text={firstLine || EMPTY_NOTE}
              matches={firstLine ? result.nameMatches : []}
            />
          </span>
          <span className={styles.noteDate}>
            {formatDate(result.note.updatedAt)}
          </span>
        </button>
        {result.contentSnippets.map((snippet, i) => (
          <SnippetView key={i} snippet={snippet} />
        ))}
      </div>
      <NoteItemMenu
        onAction={menuAction}
        actionLabel={menuLabel}
        actionIcon={menuIcon}
        isDanger={isDanger}
      />
    </li>
  )
}

function HighlightedText({ text, matches }: { text: string; matches: SearchMatch[] }) {
  if (matches.length === 0) return <>{text}</>

  const sorted = [...matches].sort((a, b) => a.matchStart - b.matchStart)
  const parts: React.ReactNode[] = []
  let cursor = 0

  for (let i = 0; i < sorted.length; i++) {
    const m = sorted[i]!
    if (m.matchStart > cursor) {
      parts.push(<span key={`t${i}`}>{text.substring(cursor, m.matchStart)}</span>)
    }
    parts.push(
      <strong key={`m${i}`} className={styles.searchMatch}>
        {text.substring(m.matchStart, Math.min(m.matchEnd, text.length))}
      </strong>,
    )
    cursor = Math.min(m.matchEnd, text.length)
  }
  if (cursor < text.length) {
    parts.push(<span key="tail">{text.substring(cursor)}</span>)
  }

  return <>{parts}</>
}

function SnippetView({ snippet }: { snippet: ContentSnippet }) {
  return (
    <div className={styles.searchSnippet}>
      {snippet.lines.map((line) => {
        const lineMatches = snippet.matches.filter((m) => m.lineIndex === line.lineIndex)
        return (
          <div key={line.lineIndex} className={styles.searchSnippetLine}>
            <HighlightedText text={line.text} matches={lineMatches} />
          </div>
        )
      })}
    </div>
  )
}

function NoteItemMenu({
  onAction,
  actionLabel,
  actionIcon,
  isDanger = false,
}: {
  onAction: () => void
  actionLabel: string
  actionIcon: string
  isDanger?: boolean
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function handleClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [open])

  return (
    <div className={styles.menuContainer} ref={ref}>
      <button
        className={styles.menuTrigger}
        onClick={() => setOpen((prev) => !prev)}
        title={NOTE_MENU}
      >
        ⋮
      </button>
      {open && (
        <div className={styles.menu}>
          <button
            className={`${styles.menuItem} ${isDanger ? styles.menuItemDanger : ''}`}
            onClick={() => { setOpen(false); onAction() }}
          >
            <span className={styles.menuIcon}>{actionIcon}</span>
            {actionLabel}
          </button>
        </div>
      )}
    </div>
  )
}

function formatDate(ts: { toDate(): Date } | null): string {
  if (!ts) return ''
  const date = ts.toDate()
  return date.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}
