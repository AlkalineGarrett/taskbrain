import { useParams } from 'react-router-dom'
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
import { LOADING_NOTE } from '@/strings'
import { db, auth } from '@/firebase/config'
import { LineState } from '@/editor/LineState'
import { findDirectives } from '@/dsl/directives/DirectiveFinder'
import { getCharIndexAtX, getComputedFont } from '@/editor/TextMeasure'
import styles from './NoteEditorScreen.module.css'

const noteRepo = new NoteRepository(db, auth)

export function NoteEditorScreen() {
  const { noteId } = useParams<{ noteId: string }>()
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

  // Add/move tab to front when note first opens, and remember for nav
  useEffect(() => {
    if (!noteId || loading) return
    localStorage.setItem('lastNoteId', noteId)
    const displayText = extractDisplayText(editorState.lines[0]?.text ?? '')
    void addOrUpdateTab(noteId, displayText)
  }, [noteId, loading])

  // Update tab display text when title changes (without reordering)
  const firstLineText = editorState.lines[0]?.text ?? ''
  useEffect(() => {
    if (!noteId || loading) return
    const displayText = extractDisplayText(firstLineText)
    void updateTabDisplayText(noteId, displayText)
  }, [noteId, loading, firstLineText])

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


  // --- Drag selection across lines ---
  const editorRef = useRef<HTMLDivElement>(null)
  const isDraggingRef = useRef(false)

  const handleDragStart = useCallback((anchorGlobalOffset: number) => {
    isDraggingRef.current = true
    editorState.selectionAnchor = anchorGlobalOffset
  }, [editorState])

  const getGlobalOffsetFromPoint = useCallback((clientX: number, clientY: number): number | null => {
    const editorEl = editorRef.current
    if (!editorEl) return null

    // Find which line the Y falls in
    const lineElements = editorEl.querySelectorAll('[data-line-index]')
    let targetLineIndex = -1
    for (let i = 0; i < lineElements.length; i++) {
      const rect = lineElements[i]!.getBoundingClientRect()
      if (clientY >= rect.top && clientY < rect.bottom) {
        targetLineIndex = parseInt(lineElements[i]!.getAttribute('data-line-index')!)
        break
      }
    }
    // Clamp to first/last line if mouse is above/below
    if (targetLineIndex < 0) {
      if (clientY < (lineElements[0]?.getBoundingClientRect().top ?? 0)) {
        targetLineIndex = 0
      } else {
        targetLineIndex = editorState.lines.length - 1
      }
    }

    const targetLine = editorState.lines[targetLineIndex]
    if (!targetLine) return null

    // Find the input or selectedContent element within this line for char measurement
    const lineEl = lineElements[targetLineIndex]
    const inputEl = lineEl?.querySelector('input') ?? lineEl?.querySelector('[class*="selectedContent"]')
    if (!inputEl) {
      // Fallback: use line start or end based on horizontal position
      const lineStart = editorState.getLineStartOffset(targetLineIndex)
      return clientX < (lineEl?.getBoundingClientRect().left ?? 0 + 100)
        ? lineStart
        : lineStart + targetLine.text.length
    }

    const rect = inputEl.getBoundingClientRect()
    const clickX = clientX - rect.left - parseFloat(getComputedStyle(inputEl).paddingLeft)
    const content = targetLine.content
    const font = getComputedFont(inputEl as HTMLElement)
    const charIdx = getCharIndexAtX(content, clickX, font)
    return editorState.getLineStartOffset(targetLineIndex) + targetLine.prefix.length + charIdx
  }, [editorState])

  useEffect(() => {
    const handleMouseMove = (e: globalThis.MouseEvent) => {
      if (!isDraggingRef.current) return
      const globalOffset = getGlobalOffsetFromPoint(e.clientX, e.clientY)
      if (globalOffset != null) {
        editorState.extendSelectionTo(globalOffset)
      }
    }
    const handleMouseUp = () => {
      isDraggingRef.current = false
    }
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
  }, [editorState, getGlobalOffsetFromPoint])

  if (showLoading) {
    return <div className="loading">{LOADING_NOTE}</div>
  }

  if (error) {
    return (
      <div style={{ maxWidth: 800, margin: '0 auto', padding: '1rem' }}>
        <p style={{ color: 'var(--color-error-hover)' }}>{error}</p>
      </div>
    )
  }

  return (
    <div className={styles.container}>
      <RecentTabsBar />

      <CommandBar
        controller={controller}
        onSave={saveWithDirectives}
        onUndo={handleUndo}
        onRedo={handleRedo}
        dirty={dirty}
        saving={saving}
      />

      <div
        ref={editorRef}
        className={`${styles.editor} ${currentNote?.state === 'deleted' ? styles.deletedEditor : ''}`}
      >
        {editorState.lines.map((_, index) => (
          <div key={index} data-line-index={index}>
            <EditorLine
              lineIndex={index}
              controller={controller}
              editorState={editorState}
              directiveResults={directiveResults}
              onDirectiveEdit={handleDirectiveEdit}
              onDirectiveRefresh={refreshDirective}
              onViewNoteClick={handleViewNoteClick}
              onDragStart={handleDragStart}
            />
          </div>
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
