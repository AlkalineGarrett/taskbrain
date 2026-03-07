import type { Note } from '@/data/Note'
import type { NoteOperations } from '../runtime/NoteOperations'
import type { DirectiveDependencies } from './DirectiveDependencies'
import type { DirectiveResult } from '../directives/DirectiveResult'
import { directiveResultSuccess, directiveResultFailure } from '../directives/DirectiveResult'
import { DirectiveCacheManager } from './DirectiveCache'
import { EditSessionManager, InvalidationReason } from './EditSessionManager'
import { analyze, analysisToPartialDependencies } from './DependencyAnalyzer'
import { EMPTY_DEPENDENCIES, mergeDependencies } from './DirectiveDependencies'
import { classifyError } from './DirectiveError'
import { cachedResultSuccess, cachedResultError } from './CachedDirectiveResult'
import type { CachedDirectiveResult } from './CachedDirectiveResult'
import { computeContentHashes } from './ContentHasher'
import { computeMetadataHashes } from './MetadataHasher'
import { Lexer } from '../language/Lexer'
import { Parser } from '../language/Parser'
import { executeDirective } from '../directives/DirectiveExecutor'

const GLOBAL_CACHE_PLACEHOLDER = '__global__'

export interface CachedExecutionResult {
  result: DirectiveResult
  cacheHit: boolean
  dependencies: DirectiveDependencies
}

/**
 * Integrates directive caching with execution.
 */
export class CachedDirectiveExecutor {
  constructor(
    private readonly cacheManager: DirectiveCacheManager = new DirectiveCacheManager(),
    private readonly editSessionManager?: EditSessionManager,
  ) {}

  execute(
    sourceText: string,
    notes: Note[],
    currentNote: Note | null,
    noteOperations?: NoteOperations,
    viewStack: string[] = [],
  ): CachedExecutionResult {
    // Step 1: Parse and analyze
    const analysisResult = this.analyzeDirective(sourceText)
    if (!analysisResult) {
      return this.executeFresh(sourceText, notes, currentNote, noteOperations, viewStack)
    }

    const { cacheKey, analysis } = analysisResult
    const noteId = currentNote?.id ?? GLOBAL_CACHE_PLACEHOLDER
    const usesSelfAccess = analysis.usesSelfAccess
    const isMutating = analysis.isMutating
    const baseDependencies = analysisToPartialDependencies(analysis)

    // Step 2: Suppress staleness during inline editing
    const shouldSuppressStaleness =
      currentNote?.id != null &&
      this.editSessionManager?.shouldSuppressInvalidation(currentNote.id) === true

    // Step 3: Mutating directives never re-execute from cache
    if (isMutating) {
      const cached = this.cacheManager.get(cacheKey, noteId, usesSelfAccess)
      if (cached) {
        return {
          result: this.cachedToDirectiveResult(cached),
          cacheHit: true,
          dependencies: cached.dependencies,
        }
      }
    }

    // Step 4: Check cache
    const cached = shouldSuppressStaleness
      ? this.cacheManager.get(cacheKey, noteId, usesSelfAccess)
      : this.cacheManager.getIfValid(cacheKey, noteId, usesSelfAccess, notes, currentNote)

    if (cached) {
      return {
        result: this.cachedToDirectiveResult(cached),
        cacheHit: true,
        dependencies: cached.dependencies,
      }
    }

    // Step 5: Cache miss - execute and cache
    return this.executeFreshWithCaching(
      sourceText, notes, currentNote, noteOperations, viewStack,
      cacheKey, baseDependencies, usesSelfAccess,
    )
  }

  private analyzeDirective(sourceText: string): { cacheKey: string; analysis: ReturnType<typeof analyze> } | null {
    try {
      const tokens = new Lexer(sourceText).tokenize()
      const directive = new Parser(tokens, sourceText).parseDirective()
      // Use source text hash as cache key (same as DirectiveFinder)
      const cacheKey = this.hashSourceText(sourceText)
      const analysis = analyze(directive.expression)
      return { cacheKey, analysis }
    } catch {
      return null
    }
  }

  private hashSourceText(sourceText: string): string {
    let hash = 0
    for (let i = 0; i < sourceText.length; i++) {
      hash = ((hash << 5) - hash + sourceText.charCodeAt(i)) | 0
    }
    return (hash >>> 0).toString(16).padStart(8, '0')
  }

  private executeFresh(
    sourceText: string,
    notes: Note[],
    currentNote: Note | null,
    noteOperations?: NoteOperations,
    viewStack: string[] = [],
  ): CachedExecutionResult {
    const result = executeDirective(sourceText, notes, currentNote, noteOperations, viewStack)
    return { result, cacheHit: false, dependencies: EMPTY_DEPENDENCIES }
  }

  private executeFreshWithCaching(
    sourceText: string,
    notes: Note[],
    currentNote: Note | null,
    noteOperations: NoteOperations | undefined,
    viewStack: string[],
    cacheKey: string,
    baseDependencies: DirectiveDependencies,
    usesSelfAccess: boolean,
  ): CachedExecutionResult {
    const result = executeDirective(sourceText, notes, currentNote, noteOperations, viewStack)
    const noteId = currentNote?.id ?? GLOBAL_CACHE_PLACEHOLDER

    // Enrich dependencies with viewed note IDs
    let finalDependencies = baseDependencies
    if (result.result) {
      const { deserializeValue } = require('../runtime/DslValue')
      try {
        const val = deserializeValue(result.result)
        if (val?.kind === 'ViewVal') {
          const viewedIds = new Set<string>(val.notes.map((n: Note) => n.id))
          finalDependencies = mergeDependencies(finalDependencies, {
            ...EMPTY_DEPENDENCIES,
            firstLineNotes: viewedIds,
            nonFirstLineNotes: viewedIds,
          })
        }
      } catch {
        // Ignore deserialization errors
      }
    }

    // Build and store cached result
    const isComputed = result.result !== null && result.error === null
    if (isComputed || (result.error && classifyError(result.error).isDeterministic)) {
      const cached = this.buildCachedResult(result, finalDependencies, notes)
      this.cacheManager.put(cacheKey, noteId, usesSelfAccess, cached)
    }

    return { result, cacheHit: false, dependencies: finalDependencies }
  }

  private buildCachedResult(
    result: DirectiveResult,
    dependencies: DirectiveDependencies,
    notes: Note[],
  ): CachedDirectiveResult {
    const contentHashes = new Map<string, { firstLineHash: string | null; nonFirstLineHash: string | null }>()

    for (const noteId of dependencies.firstLineNotes) {
      const note = notes.find((n) => n.id === noteId)
      if (note) {
        const existing = contentHashes.get(noteId) ?? { firstLineHash: null, nonFirstLineHash: null }
        contentHashes.set(noteId, { ...existing, ...computeContentHashes(note, true, false) })
      }
    }
    for (const noteId of dependencies.nonFirstLineNotes) {
      const note = notes.find((n) => n.id === noteId)
      if (note) {
        const existing = contentHashes.get(noteId) ?? { firstLineHash: null, nonFirstLineHash: null }
        contentHashes.set(noteId, { ...existing, ...computeContentHashes(note, false, true) })
      }
    }

    const metadataHashes = computeMetadataHashes(notes, dependencies)

    if (result.result !== null && result.error === null) {
      const { deserializeValue } = require('../runtime/DslValue')
      try {
        const val = deserializeValue(result.result)
        if (val) return cachedResultSuccess(val, dependencies, contentHashes, metadataHashes)
      } catch {
        // Fall through to error
      }
    }

    const error = classifyError(result.error ?? 'Unknown error')
    return cachedResultError(error, dependencies, contentHashes, metadataHashes)
  }

  private cachedToDirectiveResult(cached: CachedDirectiveResult): DirectiveResult {
    if (cached.result) {
      return directiveResultSuccess(cached.result)
    }
    return directiveResultFailure(cached.error?.message ?? 'Invalid cached result')
  }

  invalidateForChangedNotes(changedNoteIds: Set<string>): void {
    if (this.editSessionManager) {
      for (const noteId of changedNoteIds) {
        this.editSessionManager.requestInvalidation(noteId, InvalidationReason.CONTENT_CHANGED)
      }
    } else {
      for (const noteId of changedNoteIds) {
        this.cacheManager.clearNote(noteId)
      }
    }
  }

  clearAll(): void {
    this.cacheManager.clearAll()
  }
}

/**
 * Factory for creating CachedDirectiveExecutor with standard configurations.
 */
export function createInMemoryExecutor(): CachedDirectiveExecutor {
  return new CachedDirectiveExecutor(new DirectiveCacheManager())
}

export function createWithEditSupport(): CachedDirectiveExecutor {
  const cacheManager = new DirectiveCacheManager()
  const editSessionManager = new EditSessionManager(cacheManager)
  return new CachedDirectiveExecutor(cacheManager, editSessionManager)
}
