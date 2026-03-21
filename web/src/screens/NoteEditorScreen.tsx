import { useParams, useNavigate } from 'react-router-dom'
import { useEffect, useCallback, useState, useRef, useMemo } from 'react'
import type { Note } from '@/data/Note'
import { NoteRepository } from '@/data/NoteRepository'
import { MutationType, type NoteMutation } from '@/dsl/runtime/NoteMutation'
import { NoteRepositoryOperations } from '@/dsl/runtime/NoteRepositoryOperations'
import { useEditor } from '@/hooks/useEditor'
import { useDirectives } from '@/hooks/useDirectives'
import { CommandBar } from '@/components/CommandBar'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { EditorLine } from '@/components/EditorLine'
import { InlineEditor } from '@/components/InlineEditor'
import { CompletedPlaceholderRow } from '@/components/CompletedPlaceholderRow'
import { RecentTabsBar, addOrUpdateTab, updateTabDisplayText, removeTab } from '@/components/RecentTabsBar'
import { extractDisplayText } from '@/data/TabState'
import { LOADING_NOTE, DELETE_NOTE, DELETE_NOTE_CONFIRM_TITLE, DELETE_NOTE_CONFIRM_MESSAGE } from '@/strings'
import { db, auth } from '@/firebase/config'
import { LineState } from '@/editor/LineState'
import { computeHiddenIndices, computeDisplayItemsFromHidden, computeEffectiveHidden, computeFadedIndices, nearestVisibleLine } from '@/editor/CompletedLineUtils'
import { findDirectives } from '@/dsl/directives/DirectiveFinder'
import { getCharOffsetHidingTextarea, getCharRectInElement } from '@/editor/TextMeasure'
import styles from './NoteEditorScreen.module.css'

const noteRepo = new NoteRepository(db, auth)

interface HitResult {
  globalOffset: number
  lineIndex: number
  charIndex: number
  inputEl: Element | null
  lineEl: Element | null
}

export function NoteEditorScreen() {
  const { noteId } = useParams<{ noteId: string }>()
  const navigate = useNavigate()
  const { controller, editorState, loading, showLoading, saving, error, dirty, save, showCompleted, toggleShowCompleted } = useEditor(noteId)

  // Load all notes for DSL context
  const [allNotes, setAllNotes] = useState<Note[]>([])
  const [currentNote, setCurrentNote] = useState<Note | null>(null)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)

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
    const content = editorState.text
    void executeAndSave(content)
  }, [noteId, editorState, executeAndSave])

  // Execute directives when note finishes loading and notes context is available
  useEffect(() => {
    if (loading || !noteId || allNotes.length === 0) return
    const content = editorState.text
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
    const content = editorState.text
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
    const content = editorState.text
    void executeAndSave(content)
  }, [editorState, controller, executeAndSave])

  // Undo/redo with directive re-execution
  const handleUndo = useCallback(() => {
    controller.undo()
    const content = editorState.text
    void executeAndSave(content)
  }, [controller, editorState, executeAndSave])

  const handleRedo = useCallback(() => {
    controller.redo()
    const content = editorState.text
    void executeAndSave(content)
  }, [controller, editorState, executeAndSave])

  const handleDeleteNote = useCallback(async () => {
    if (!noteId) return
    try {
      await noteRepo.softDeleteNote(noteId)
      const nextNoteId = await removeTab(noteId, noteId)
      navigate(nextNoteId ? `/note/${nextNoteId}` : '/')
    } catch (e) {
      console.error('Failed to delete note:', e)
    }
  }, [noteId, navigate])

  const handleRestoreNote = useCallback(async () => {
    if (!noteId) return
    try {
      await noteRepo.undeleteNote(noteId)
      setCurrentNote((prev) => prev ? { ...prev, state: null } : null)
    } catch (e) {
      console.error('Failed to restore note:', e)
    }
  }, [noteId])

  // Global keyboard shortcuts — Ctrl+S always, others when no textarea has focus
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault()
        void saveWithDirectives()
        return
      }

      // Remaining shortcuts only apply when no textarea has focus
      // (e.g. after gutter-selecting a placeholder)
      const active = document.activeElement
      if (active && active.tagName === 'TEXTAREA') return

      if (e.metaKey || e.ctrlKey) {
        if (e.key === 'z') {
          e.preventDefault()
          if (e.shiftKey) handleRedo()
          else handleUndo()
          return
        }
        if (e.key === 'y') {
          e.preventDefault()
          handleRedo()
          return
        }
        if (e.key === 'a') {
          e.preventDefault()
          editorState.selectAll()
          return
        }
        if (e.key === 'x' && editorState.hasSelection) {
          e.preventDefault()
          controller.cutSelection()
          return
        }
        if (e.key === 'c' && editorState.hasSelection) {
          e.preventDefault()
          controller.copySelection()
          return
        }
        return
      }

      // Non-modifier keys with selection
      if (editorState.hasSelection && (e.key === 'Backspace' || e.key === 'Delete')) {
        e.preventDefault()
        controller.deleteSelectionWithUndo()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [saveWithDirectives, editorState, controller, handleUndo, handleRedo])


  // --- Gutter selection (select whole lines by click/drag) ---
  // Anchor is a range [start, end] so placeholder blocks lock in their full extent
  const gutterAnchorRef = useRef<[number, number]>([-1, -1])

  const selectLineRange = useCallback((fromLine: number, toLine: number) => {
    const first = Math.max(0, Math.min(fromLine, toLine))
    const last = Math.min(editorState.lines.length - 1, Math.max(fromLine, toLine))
    const start = editorState.getLineStartOffset(first)
    const lastLine = editorState.lines[last]
    let end = editorState.getLineStartOffset(last) + (lastLine?.text.length ?? 0)
    // Extend past empty trailing lines so the selection crosses through them
    // (otherwise start === end and getLineSelection returns null)
    if ((lastLine?.text.length ?? 0) === 0 && last < editorState.lines.length - 1) {
      end += 1
    }
    controller.setSelection(start, end)
  }, [editorState, controller])

  const handleGutterDragStart = useCallback((lineIndex: number) => {
    gutterAnchorRef.current = [lineIndex, lineIndex]
    selectLineRange(lineIndex, lineIndex)
  }, [selectLineRange])

  const handleGutterDragUpdate = useCallback((lineIndex: number) => {
    const [anchorStart, anchorEnd] = gutterAnchorRef.current
    if (anchorStart < 0) return
    selectLineRange(Math.min(anchorStart, lineIndex), Math.max(anchorEnd, lineIndex))
  }, [selectLineRange])

  // Reset gutter drag anchor on mouseup anywhere
  useEffect(() => {
    const handleMouseUp = () => { gutterAnchorRef.current = [-1, -1] }
    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [])

  // --- Drag selection across lines ---
  const editorRef = useRef<HTMLDivElement>(null)
  const noteIdColumnRef = useRef<HTMLDivElement>(null)
  const dropCursorRef = useRef<HTMLDivElement>(null)
  const isDraggingRef = useRef(false)
  const isMoveDraggingRef = useRef(false)

  const handleDragStart = useCallback((anchorGlobalOffset: number) => {
    isDraggingRef.current = true
    editorState.selectionAnchor = anchorGlobalOffset
  }, [editorState])

  const handleMoveStart = useCallback(() => {
    isMoveDraggingRef.current = true
    editorRef.current?.classList.add(styles.moveDragging!)
  }, [])

  const hitTestFromPoint = useCallback((clientX: number, clientY: number): HitResult | null => {
    const editorEl = editorRef.current
    if (!editorEl) return null

    const lineElements = editorEl.querySelectorAll('[data-line-index]')
    let targetLineIndex = -1
    let matchedLineEl: Element | null = null
    for (let i = 0; i < lineElements.length; i++) {
      const rect = lineElements[i]!.getBoundingClientRect()
      if (clientY >= rect.top && clientY < rect.bottom) {
        targetLineIndex = parseInt(lineElements[i]!.getAttribute('data-line-index')!)
        matchedLineEl = lineElements[i]!
        break
      }
    }
    if (targetLineIndex < 0) {
      // Mouse is between visible line elements (e.g. over a placeholder row) or outside.
      // Find the nearest visible line element by Y-distance instead of jumping to first/last.
      let bestDist = Infinity
      for (let i = 0; i < lineElements.length; i++) {
        const rect = lineElements[i]!.getBoundingClientRect()
        const dist = clientY < rect.top ? rect.top - clientY : clientY - rect.bottom
        if (dist < bestDist) {
          bestDist = dist
          targetLineIndex = parseInt(lineElements[i]!.getAttribute('data-line-index')!)
          matchedLineEl = lineElements[i]!
        }
      }
      // Final fallback if no line elements exist at all
      if (targetLineIndex < 0) {
        targetLineIndex = 0
        matchedLineEl = null
      }
    }

    const targetLine = editorState.lines[targetLineIndex]
    if (!targetLine) return null

    const lineEl = matchedLineEl
    const overlayEl = lineEl?.querySelector('[data-text-overlay]') ?? null
    if (!overlayEl) {
      const lineStart = editorState.getLineStartOffset(targetLineIndex)
      const offset = clientX < (lineEl?.getBoundingClientRect().left ?? 100)
        ? lineStart
        : lineStart + targetLine.text.length
      return { globalOffset: offset, lineIndex: targetLineIndex, charIndex: 0, inputEl: null, lineEl }
    }

    const textareaEl = lineEl?.querySelector('textarea') as HTMLElement | null
    const charIdx = textareaEl
      ? getCharOffsetHidingTextarea(overlayEl as HTMLElement, textareaEl, clientX, clientY) ?? targetLine.content.length
      : targetLine.content.length
    const globalOffset = editorState.getLineStartOffset(targetLineIndex) + targetLine.prefix.length + charIdx
    return { globalOffset, lineIndex: targetLineIndex, charIndex: charIdx, inputEl: overlayEl, lineEl }
  }, [editorState])

  const getGlobalOffsetFromPoint = useCallback((clientX: number, clientY: number): number | null => {
    return hitTestFromPoint(clientX, clientY)?.globalOffset ?? null
  }, [hitTestFromPoint])

  const positionDropCursor = useCallback((clientX: number, clientY: number) => {
    const cursor = dropCursorRef.current
    const editorEl = editorRef.current
    if (!cursor || !editorEl) return

    const hit = hitTestFromPoint(clientX, clientY)
    if (!hit?.inputEl) {
      cursor.style.display = 'none'
      return
    }

    // Use browser's own layout to get the character position (works with wrapped text)
    const charRect = getCharRectInElement(hit.inputEl as HTMLElement, hit.charIndex)
    if (!charRect) {
      cursor.style.display = 'none'
      return
    }

    const editorRect = editorEl.getBoundingClientRect()
    cursor.style.display = 'block'
    cursor.style.left = `${charRect.left - editorRect.left}px`
    cursor.style.top = `${charRect.top - editorRect.top}px`
    cursor.style.height = `${charRect.height}px`
  }, [hitTestFromPoint])

  useEffect(() => {
    const handleMouseMove = (e: globalThis.MouseEvent) => {
      if (!isDraggingRef.current && !isMoveDraggingRef.current) return
      if (isDraggingRef.current) {
        const globalOffset = getGlobalOffsetFromPoint(e.clientX, e.clientY)
        if (globalOffset != null) {
          editorState.extendSelectionTo(globalOffset)
        }
      } else if (isMoveDraggingRef.current) {
        positionDropCursor(e.clientX, e.clientY)
      }
    }
    const hideDropCursor = () => {
      if (dropCursorRef.current) dropCursorRef.current.style.display = 'none'
      editorRef.current?.classList.remove(styles.moveDragging!)
    }
    const handleMouseUp = (e: globalThis.MouseEvent) => {
      if (isMoveDraggingRef.current) {
        isMoveDraggingRef.current = false
        hideDropCursor()
        const dropOffset = getGlobalOffsetFromPoint(e.clientX, e.clientY)
        if (dropOffset != null) {
          controller.moveSelectionTo(dropOffset)
        }
        return
      }
      isDraggingRef.current = false
    }
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
  }, [editorState, controller, getGlobalOffsetFromPoint, positionDropCursor])

  // Sync note ID column scroll with editor scroll
  useEffect(() => {
    const scrollEl = editorRef.current
    const colEl = noteIdColumnRef.current
    if (!scrollEl || !colEl) return
    const syncScroll = () => { colEl.scrollTop = scrollEl.scrollTop }
    syncScroll()
    scrollEl.addEventListener('scroll', syncScroll, { passive: true })
    return () => scrollEl.removeEventListener('scroll', syncScroll)
  })

  // Compute display items and hidden indices for show/hide completed lines
  const lineTexts = editorState.lines.map((l) => l.text)
  const lineTextsKey = lineTexts.join('\n')
  const hiddenIndices = useMemo(
    () => computeHiddenIndices(lineTexts, showCompleted),
    [lineTextsKey, showCompleted],
  )
  // Exclude recently-checked lines from hidden so they stay visible at reduced opacity.
  // Snapshot the set contents into a stable key — the Set is mutated in place so
  // reference/size comparison alone won't trigger useMemo recalculation.
  const recentlyChecked = controller.recentlyCheckedIndices
  const recentlyCheckedKey = [...recentlyChecked].join(',')
  const effectiveHidden = useMemo(
    () => computeEffectiveHidden(hiddenIndices, recentlyChecked, lineTexts),
    [hiddenIndices, recentlyCheckedKey],
  )
  const fadedIndices = useMemo(
    () => computeFadedIndices(hiddenIndices, recentlyChecked, lineTexts),
    [hiddenIndices, recentlyCheckedKey],
  )
  const displayItems = useMemo(
    () => computeDisplayItemsFromHidden(lineTexts, effectiveHidden),
    [lineTextsKey, effectiveHidden],
  )
  controller.hiddenIndices = effectiveHidden

  // Snap focus to nearest visible line when toggling showCompleted OFF
  const prevShowCompletedRef = useRef(showCompleted)
  useEffect(() => {
    if (prevShowCompletedRef.current && !showCompleted) {
      if (hiddenIndices.has(editorState.focusedLineIndex)) {
        const newFocus = nearestVisibleLine(lineTexts, editorState.focusedLineIndex, hiddenIndices)
        controller.setCursor(newFocus, editorState.lines[newFocus]?.cursorPosition ?? 0)
      }
      if (editorState.hasSelection) {
        editorState.clearSelection()
      }
    }
    prevShowCompletedRef.current = showCompleted
  }, [showCompleted])

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
        onDelete={() => setShowDeleteConfirm(true)}
        onRestore={() => void handleRestoreNote()}
        isDeleted={currentNote?.state === 'deleted'}
        dirty={dirty}
        saving={saving}
        showCompleted={showCompleted}
        onToggleShowCompleted={toggleShowCompleted}
      />

      <ConfirmDialog
        open={showDeleteConfirm}
        title={DELETE_NOTE_CONFIRM_TITLE}
        message={DELETE_NOTE_CONFIRM_MESSAGE}
        confirmLabel={DELETE_NOTE}
        danger
        onConfirm={() => { setShowDeleteConfirm(false); void handleDeleteNote() }}
        onCancel={() => setShowDeleteConfirm(false)}
      />

      <div className={styles.editorArea}>
        <div ref={noteIdColumnRef} className={styles.noteIdColumn}>
          {displayItems.map((item, i) =>
            item.type === 'placeholder' ? (
              <div key={`ph-${i}`}>
                {Array.from({ length: item.count }, (_, j) => {
                  const lineIdx = item.startIndex + j
                  return editorState.lines[lineIdx]?.noteIds ?? []
                }).flat().join(', ') || '\u00A0'}
              </div>
            ) : (
              <div key={item.realIndex}>
                {editorState.lines[item.realIndex]?.noteIds.join(', ') || '\u00A0'}
              </div>
            )
          )}
        </div>
        <div
          ref={editorRef}
          className={`${styles.editor} ${currentNote?.state === 'deleted' ? styles.deletedEditor : ''}`}
        >
        <div ref={dropCursorRef} className={styles.dropCursor} style={{ display: 'none' }} />
        {displayItems.map((item, i) =>
          item.type === 'placeholder' ? (
            <CompletedPlaceholderRow
              key={`ph-${i}`}
              count={item.count}
              indentLevel={item.indentLevel}
              isSelected={editorState.hasSelection && (() => {
                const [selFirst, selLast] = editorState.getSelectedLineRange()
                return item.startIndex <= selLast && item.endIndex >= selFirst
              })()}
              onGutterDragStart={() => {
                gutterAnchorRef.current = [item.startIndex, item.endIndex]
                selectLineRange(item.startIndex, item.endIndex)
              }}
              onGutterDragUpdate={() => {
                const [anchorStart, anchorEnd] = gutterAnchorRef.current
                if (anchorStart < 0) return
                selectLineRange(Math.min(anchorStart, item.startIndex), Math.max(anchorEnd, item.endIndex))
              }}
            />
          ) : (
            <div key={item.realIndex} data-line-index={item.realIndex} style={fadedIndices.has(item.realIndex) ? { opacity: 0.4 } : undefined}>
              <EditorLine
                lineIndex={item.realIndex}
                controller={controller}
                editorState={editorState}
                directiveResults={directiveResults}
                onDirectiveEdit={handleDirectiveEdit}
                onDirectiveRefresh={refreshDirective}
                onViewNoteClick={handleViewNoteClick}
                onDragStart={handleDragStart}
                onGutterDragStart={handleGutterDragStart}
                onGutterDragUpdate={handleGutterDragUpdate}
                onMoveStart={handleMoveStart}
              />
            </div>
          ),
        )}
      </div>
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
