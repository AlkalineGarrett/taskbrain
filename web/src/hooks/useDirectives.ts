import { useState, useCallback, useRef } from 'react'
import type { Note } from '@/data/Note'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import { executeAllDirectives, executeDirective } from '@/dsl/directives/DirectiveExecutor'
import { hashDirective, findDirectives, directiveKey } from '@/dsl/directives/DirectiveFinder'
import { getDirectiveResults, saveDirectiveResult } from '@/dsl/directives/DirectiveResultRepository'

interface UseDirectivesOptions {
  noteId: string | null
  notes: Note[]
  currentNote: Note | null
}

export function useDirectives({ noteId, notes, currentNote }: UseDirectivesOptions) {
  const [results, setResults] = useState<Map<string, DirectiveResult>>(new Map())
  const [isExecuting, setIsExecuting] = useState(false)
  const resultsRef = useRef(results)
  resultsRef.current = results

  /**
   * Load cached results from Firestore and execute any missing directives.
   */
  const loadAndExecute = useCallback(
    async (content: string) => {
      if (!noteId) return

      setIsExecuting(true)
      try {
        // Load cached results from Firestore (keyed by hash)
        const cachedByHash = await getDirectiveResults(noteId)

        // Parse directives and map hash-based results to position-based keys
        const positionResults = new Map<string, DirectiveResult>()
        const lines = content.split('\n')

        for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
          const directives = findDirectives(lines[lineIndex]!)
          for (const directive of directives) {
            const posKey = directiveKey(lineIndex, directive.startOffset)
            const hash = await hashDirective(directive.sourceText)
            const cached = cachedByHash.get(hash)

            if (cached && cached.result !== null && cached.error === null) {
              // Use cached result (skip stale view results)
              positionResults.set(posKey, cached)
            } else {
              // Execute fresh
              const result = executeDirective(directive.sourceText, notes, currentNote)
              positionResults.set(posKey, result)
            }
          }
        }

        setResults(positionResults)
      } catch (e) {
        console.error('Error loading directives:', e)
      } finally {
        setIsExecuting(false)
      }
    },
    [noteId, notes, currentNote],
  )

  /**
   * Execute all directives fresh and save results to Firestore.
   */
  const executeAndSave = useCallback(
    async (content: string) => {
      if (!noteId) return

      setIsExecuting(true)
      try {
        // Execute all directives
        const newResults = executeAllDirectives(content, notes, currentNote)

        // Preserve collapsed state from existing results
        const mergedResults = new Map<string, DirectiveResult>()
        for (const [key, result] of newResults) {
          const existing = resultsRef.current.get(key)
          mergedResults.set(key, {
            ...result,
            collapsed: existing?.collapsed ?? result.collapsed,
          })
        }

        setResults(mergedResults)

        // Save to Firestore (keyed by hash)
        const lines = content.split('\n')
        for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
          const directives = findDirectives(lines[lineIndex]!)
          for (const directive of directives) {
            const posKey = directiveKey(lineIndex, directive.startOffset)
            const result = mergedResults.get(posKey)
            if (result) {
              const hash = await hashDirective(directive.sourceText)
              await saveDirectiveResult(noteId, hash, result)
            }
          }
        }
      } catch (e) {
        console.error('Error executing directives:', e)
      } finally {
        setIsExecuting(false)
      }
    },
    [noteId, notes, currentNote],
  )

  /**
   * Refresh a single directive by its position key.
   */
  const refreshDirective = useCallback(
    (key: string, sourceText: string) => {
      const result = executeDirective(sourceText, notes, currentNote)
      setResults((prev) => {
        const next = new Map(prev)
        next.set(key, result)
        return next
      })
    },
    [notes, currentNote],
  )

  return {
    results,
    isExecuting,
    loadAndExecute,
    executeAndSave,
    refreshDirective,
  }
}
