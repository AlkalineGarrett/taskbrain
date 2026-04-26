import { useCallback, useEffect } from 'react'
import { noteStore } from '@/data/NoteStore'
import { MutationType, type NoteMutation } from '@/dsl/runtime/NoteMutation'
import type { EditorController } from '@/editor/EditorController'
import type { EditorState } from '@/editor/EditorState'
import { LineState } from '@/editor/LineState'
import { newSentinelNoteId } from '@/data/NoteIdSentinel'

/**
 * Applies mutations produced by directive execution to the note store and,
 * when the mutation targets the currently-edited note, to the editor itself.
 *
 * For CONTENT_CHANGED on line 0, updates the existing LineState in place rather
 * than rebuilding lines — this preserves noteIds on subsequent lines so
 * parent-child relationships survive directive-driven edits.
 */
export function useDirectiveMutations(
  noteId: string | null | undefined,
  editorState: EditorState,
  controller: EditorController,
  directiveMutations: NoteMutation[],
): void {
  const handleMutations = useCallback((mutations: NoteMutation[]) => {
    // The directive runtime only emits a mutation when the affected note
    // actually changed — no need to diff against the store before forwarding.
    for (const mutation of mutations) {
      noteStore.updateNote(mutation.noteId, mutation.updatedNote)

      if (mutation.noteId === noteId) {
        switch (mutation.mutationType) {
          case MutationType.CONTENT_CHANGED: {
            const first = editorState.lines[0]
            if (first) {
              first.updateFull(mutation.updatedNote.content, mutation.updatedNote.content.length)
            }
            editorState.requestFocusUpdate()
            editorState.notifyChange()
            controller.resetUndoHistory()
            break
          }
          case MutationType.CONTENT_APPENDED: {
            if (mutation.appendedText) {
              const newLines = mutation.appendedText.split('\n')
              for (const lineText of newLines) {
                editorState.lines.push(new LineState(lineText, undefined, [newSentinelNoteId('directive')]))
              }
              editorState.requestFocusUpdate()
              editorState.notifyChange()
              controller.resetUndoHistory()
            }
            break
          }
          case MutationType.PATH_CHANGED:
            break
        }
      }
    }
  }, [noteId, editorState, controller])

  useEffect(() => {
    if (directiveMutations.length > 0) {
      handleMutations(directiveMutations)
    }
  }, [directiveMutations, handleMutations])
}
