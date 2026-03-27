import { useState, useCallback, useMemo } from 'react'
import type { Note } from '@/data/Note'
import type { NoteOperations } from '@/dsl/runtime/NoteOperations'
import type { NoteMutation } from '@/dsl/runtime/NoteMutation'
import type { EditorState } from '@/editor/EditorState'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { findDirectives, directiveHash } from '@/dsl/directives/DirectiveFinder'
import { CachedDirectiveExecutor } from '@/dsl/cache/CachedDirectiveExecutor'

interface UseDirectivesOptions {
  /** The noteId of the currently loaded note (null until loaded). */
  noteId: string | null
  /** The editor state — content is read via editorState.text. */
  editorState: EditorState
  notes: Note[]
  currentNote: Note | null
  noteOperations?: NoteOperations
}

/**
 * Directive results computed synchronously as a derived value of the current state.
 * No effects, no races — results update in the same render as their inputs.
 *
 * Results are keyed by directiveHash(sourceText). The same directive appearing
 * multiple times in one note shares a single result entry.
 */
export function useDirectives({ noteId, editorState, notes, currentNote, noteOperations }: UseDirectivesOptions) {
  // Bump this to force recomputation (e.g., after clearing the cache).
  const [generation, setGeneration] = useState(0)

  const cachedExecutor = useMemo(() => new CachedDirectiveExecutor(), [])

  // Compute directive results synchronously during render.
  // Cache hits return instantly; misses execute and cache for next time.
  // noteId here is loadedNoteId — it only changes after the editor has been populated,
  // guaranteeing editorState.text reflects the correct note.
  const { results, mutations } = useMemo(() => {
    const empty = { results: new Map<string, DirectiveResult>(), mutations: [] as NoteMutation[] }
    if (!noteId || notes.length === 0) return empty

    const content = editorState.text
    if (!content) return empty

    const hashResults = new Map<string, DirectiveResult>()
    const allMutations: NoteMutation[] = []
    const lines = content.split('\n')

    for (const line of lines) {
      for (const directive of findDirectives(line)) {
        const hash = directiveHash(directive.sourceText)
        if (hashResults.has(hash)) continue
        const { result, mutations: directiveMutations } = cachedExecutor.execute(
          directive.sourceText, notes, currentNote, noteOperations,
        )
        hashResults.set(hash, result)
        allMutations.push(...directiveMutations)
      }
    }

    return { results: hashResults, mutations: allMutations }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [noteId, notes, currentNote, noteOperations, cachedExecutor, generation])

  /**
   * Invalidate the cache and recompute all directives on next render.
   * Call after saves, edits, or any operation that changes directive inputs.
   */
  const invalidateAndRecompute = useCallback(() => {
    cachedExecutor.clearAll()
    setGeneration((g) => g + 1)
  }, [cachedExecutor])

  /**
   * Refresh a single directive (clears its cache entry and recomputes).
   */
  const refreshDirective = useCallback(
    (_key: string, _sourceText: string) => {
      invalidateAndRecompute()
    },
    [invalidateAndRecompute],
  )

  return {
    results,
    mutations,
    invalidateAndRecompute,
    refreshDirective,
  }
}
