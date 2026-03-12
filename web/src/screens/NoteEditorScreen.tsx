import { useParams, useNavigate } from 'react-router-dom'
import { useEffect, useCallback, useState, useRef, useMemo } from 'react'
import type { Note } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import { MutationType, type NoteMutation } from '@/dsl/runtime/NoteMutation'
import { NoteRepositoryOperations } from '@/dsl/runtime/NoteRepositoryOperations'
import { useEditor } from '@/hooks/useEditor'
import { useDirectives } from '@/hooks/useDirectives'
import { CommandBar } from '@/components/CommandBar'
import { EditorLine } from '@/components/EditorLine'
import { InlineEditor } from '@/components/InlineEditor'
import { RecentTabsBar, addOrUpdateTab, updateTabDisplayText } from '@/components/RecentTabsBar'
import { extractDisplayText } from '@/data/TabState'
import { db, auth } from '@/firebase/config'
import { LineState } from '@/editor/LineState'
import { findDirectives } from '@/dsl/directives/DirectiveFinder'
import styles from './NoteEditorScreen.module.css'

const noteRepo = new NoteRepository(db, auth)

export function NoteEditorScreen() {
  const { noteId } = useParams<{ noteId: string }>()
  const navigate = useNavigate()
  const { controller, editorState, loading, showLoading, saving, error, dirty, save } = useEditor(noteId)

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

  // Create NoteOperations for DSL mutations
  const noteOperations = useMemo(() => {
    const userId = auth.currentUser?.uid
    if (!userId) return undefined
    return new NoteRepositoryOperations(db, userId)
  }, [])

  // Handle mutations from directive execution
  const handleMutations = useCallback((mutations: NoteMutation[]) => {
    for (const mutation of mutations) {
      // Update local notes cache
      setAllNotes((prev) =>
        prev.map((n) => (n.id === mutation.noteId ? mutation.updatedNote : n)),
      )

      // Update current note cache if affected
      if (mutation.noteId === noteId) {
        setCurrentNote(mutation.updatedNote)
      }

      // Update editor content if the currently-edited note was mutated
      if (mutation.noteId === noteId) {
        switch (mutation.mutationType) {
          case MutationType.CONTENT_CHANGED: {
            // Update first line (content change = note name change)
            const currentLines = editorState.lines.map((l) => l.text)
            currentLines[0] = mutation.updatedNote.content
            editorState.lines = currentLines.map((t) => new LineState(t))
            editorState.requestFocusUpdate()
            editorState.notifyChange()
            controller.resetUndoHistory()
            break
          }
          case MutationType.CONTENT_APPENDED: {
            if (mutation.appendedText) {
              const newLines = mutation.appendedText.split('\n')
              for (const lineText of newLines) {
                editorState.lines.push(new LineState(lineText))
              }
              editorState.requestFocusUpdate()
              editorState.notifyChange()
              controller.resetUndoHistory()
            }
            break
          }
          case MutationType.PATH_CHANGED:
            // Path changes don't affect editor content
            break
        }
      }
    }
  }, [noteId, editorState, controller])

  const { results: directiveResults, loadAndExecute, executeAndSave, refreshDirective } =
    useDirectives({
      noteId: noteId ?? null,
      notes: allNotes,
      currentNote,
      noteOperations,
      onMutations: handleMutations,
    })

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

  // Add/move tab to front when note first opens
  useEffect(() => {
    if (!noteId || loading) return
    const displayText = extractDisplayText(editorState.lines[0]?.text ?? '')
    void addOrUpdateTab(noteId, displayText)
  }, [noteId, loading])

  // Update tab display text when title changes (without reordering)
  const prevFirstLineRef = useRef<string>('')
  useEffect(() => {
    if (!noteId || loading) return
    const firstLine = editorState.lines[0]?.text ?? ''
    if (firstLine === prevFirstLineRef.current) return
    prevFirstLineRef.current = firstLine
    const displayText = extractDisplayText(firstLine)
    void updateTabDisplayText(noteId, displayText)
  }, [noteId, loading, editorState.lines])

  // Save with directive execution
  const saveWithDirectives = useCallback(async () => {
    await save()
    const content = editorState.lines.map((l) => l.text).join('\n')
    void executeAndSave(content)
  }, [save, editorState, executeAndSave])

  // Directive edit callback
  const handleDirectiveEdit = useCallback((key: string, newSourceText: string) => {
    const parts = key.split(':')
    const lineIndex = parseInt(parts[0]!)
    const startOffset = parseInt(parts[1]!)
    const lineContent = editorState.lines[lineIndex]?.text ?? ''
    const directives = findDirectives(lineContent)
    const directive = directives.find((d) => d.startOffset === startOffset)
    if (!directive) return
    controller.confirmDirectiveEdit(lineIndex, startOffset, directive.endOffset, newSourceText)
    const content = editorState.lines.map((l) => l.text).join('\n')
    void executeAndSave(content)
  }, [editorState, controller, executeAndSave])

  // Undo/redo with directive re-execution
  const handleUndo = useCallback(() => {
    controller.undo()
    const content = editorState.lines.map((l) => l.text).join('\n')
    void executeAndSave(content)
  }, [controller, editorState, executeAndSave])

  const handleRedo = useCallback(() => {
    controller.redo()
    const content = editorState.lines.map((l) => l.text).join('\n')
    void executeAndSave(content)
  }, [controller, editorState, executeAndSave])

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

  // Keep a ref to the save function so cleanup effects can use it
  const saveRef = useRef(save)
  saveRef.current = save
  const dirtyRef = useRef(dirty)
  dirtyRef.current = dirty

  // Auto-save on beforeunload (tab close/refresh)
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current) {
        // Trigger save — use sendBeacon pattern for reliability
        void saveRef.current()
        e.preventDefault()
      }
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [])

  // Auto-save on unmount (navigation away)
  useEffect(() => {
    return () => {
      if (dirtyRef.current) {
        void saveRef.current()
      }
    }
  }, [noteId])

  const handleBack = useCallback(() => {
    // Auto-save triggers on unmount, so just navigate
    navigate('/')
  }, [navigate])

  if (showLoading) {
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
        onUndo={handleUndo}
        onRedo={handleRedo}
        dirty={dirty}
        saving={saving}
      />

      <div className={`${styles.editor} ${currentNote?.state === 'deleted' ? styles.deletedEditor : ''}`}>
        {editorState.lines.map((_, index) => (
          <EditorLine
            key={index}
            lineIndex={index}
            controller={controller}
            editorState={editorState}
            directiveResults={directiveResults}
            onDirectiveEdit={handleDirectiveEdit}
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
