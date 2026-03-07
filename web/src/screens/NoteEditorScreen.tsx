import { useParams, useNavigate } from 'react-router-dom'
import { useEffect, useCallback, useState } from 'react'
import type { Note } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import { useEditor } from '@/hooks/useEditor'
import { useDirectives } from '@/hooks/useDirectives'
import { CommandBar } from '@/components/CommandBar'
import { EditorLine } from '@/components/EditorLine'
import { InlineEditor } from '@/components/InlineEditor'
import { RecentTabsBar, addOrUpdateTab } from '@/components/RecentTabsBar'
import { db, auth } from '@/firebase/config'
import styles from './NoteEditorScreen.module.css'

const noteRepo = new NoteRepository(db, auth)

export function NoteEditorScreen() {
  const { noteId } = useParams<{ noteId: string }>()
  const navigate = useNavigate()
  const { controller, editorState, loading, saving, error, dirty, save } = useEditor(noteId)

  // Load all notes for DSL context
  const [allNotes, setAllNotes] = useState<Note[]>([])
  const [currentNote, setCurrentNote] = useState<Note | null>(null)

  useEffect(() => {
    if (!noteId) return
    let cancelled = false
    const load = async () => {
      const [notes, note] = await Promise.all([
        noteRepo.loadAllUserNotes(),
        noteRepo.loadNoteById(noteId),
      ])
      if (!cancelled) {
        setAllNotes(notes)
        setCurrentNote(note)
      }
    }
    void load()
    return () => { cancelled = true }
  }, [noteId])

  const { results: directiveResults, loadAndExecute, executeAndSave, refreshDirective } =
    useDirectives({ noteId: noteId ?? null, notes: allNotes, currentNote })

  // Inline editing state for viewed notes
  const [inlineEditNoteId, setInlineEditNoteId] = useState<string | null>(null)
  const [inlineEditContent, setInlineEditContent] = useState<string>('')

  const handleViewNoteClick = useCallback((viewedNoteId: string) => {
    const note = allNotes.find((n) => n.id === viewedNoteId)
    if (note) {
      setInlineEditNoteId(viewedNoteId)
      setInlineEditContent(note.content)
    }
  }, [allNotes])

  const handleInlineEditClose = useCallback(() => {
    setInlineEditNoteId(null)
    setInlineEditContent('')
  }, [])

  const handleInlineEditSaved = useCallback(async () => {
    // Refresh notes and re-execute directives after inline save
    const [notes, note] = await Promise.all([
      noteRepo.loadAllUserNotes(),
      noteId ? noteRepo.loadNoteById(noteId) : Promise.resolve(null),
    ])
    setAllNotes(notes)
    setCurrentNote(note)
    const content = editorState.lines.map((l) => l.text).join('\n')
    void executeAndSave(content)
  }, [noteId, editorState, executeAndSave])

  // Execute directives when note finishes loading and notes context is available
  useEffect(() => {
    if (loading || !noteId || allNotes.length === 0) return
    const content = editorState.lines.map((l) => l.text).join('\n')
    void loadAndExecute(content)
  }, [loading, noteId, allNotes.length])

  // Update recent tab when note loads
  useEffect(() => {
    if (!noteId || loading) return
    const firstLine = editorState.lines[0]?.text ?? ''
    void addOrUpdateTab(noteId, firstLine || '(empty)')
  }, [noteId, loading, editorState.lines])

  // Save with directive execution
  const saveWithDirectives = useCallback(async () => {
    await save()
    const content = editorState.lines.map((l) => l.text).join('\n')
    void executeAndSave(content)
  }, [save, editorState, executeAndSave])

  // Ctrl+S to save
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault()
        void saveWithDirectives()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [saveWithDirectives])

  // Warn on unsaved changes
  useEffect(() => {
    if (!dirty) return
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault()
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  const handleBack = useCallback(() => {
    if (dirty) {
      if (confirm('You have unsaved changes. Discard them?')) {
        navigate('/')
      }
    } else {
      navigate('/')
    }
  }, [dirty, navigate])

  if (loading) {
    return <div className="loading">Loading note...</div>
  }

  if (error) {
    return (
      <div style={{ maxWidth: 800, margin: '0 auto', padding: '1rem' }}>
        <button onClick={handleBack}>Back</button>
        <p style={{ color: '#d32f2f' }}>{error}</p>
      </div>
    )
  }

  return (
    <div className={styles.container}>
      <header className={styles.header}>
        <button className={styles.backButton} onClick={handleBack}>
          ← Back
        </button>
      </header>

      <RecentTabsBar />

      <CommandBar
        controller={controller}
        onSave={saveWithDirectives}
        dirty={dirty}
        saving={saving}
      />

      <div className={styles.editor}>
        {editorState.lines.map((_, index) => (
          <EditorLine
            key={index}
            lineIndex={index}
            controller={controller}
            editorState={editorState}
            directiveResults={directiveResults}
            onDirectiveRefresh={refreshDirective}
            onViewNoteClick={handleViewNoteClick}
          />
        ))}
      </div>

      {inlineEditNoteId && (
        <InlineEditor
          noteId={inlineEditNoteId}
          initialContent={inlineEditContent}
          onClose={handleInlineEditClose}
          onSaved={() => void handleInlineEditSaved()}
        />
      )}
    </div>
  )
}
