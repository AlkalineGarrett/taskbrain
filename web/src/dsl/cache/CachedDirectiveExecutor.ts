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
import { executeDirectiveWithMutations } from '../directives/DirectiveExecutor'
import type { NoteMutation } from '../runtime/NoteMutation'
import { directiveHash } from '../directives/DirectiveFinder'
import { deserializeValue } from '../runtime/DslValue'

const GLOBAL_CACHE_PLACEHOLDER = '__global__'

export interface CachedExecutionResult {
  result: DirectiveResult
  cacheHit: boolean
  dependencies: DirectiveDependencies
  mutations: NoteMutation[]
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
    const isMutating = analysis.isMutating
    const baseDependencies = analysisToPartialDependencies(analysis)

    // Step 2: Suppress staleness during inline editing
    const shouldSuppressStaleness =
      currentNote?.id != null &&
      this.editSessionManager?.shouldSuppressInvalidation(currentNote.id) === true

    // Step 3: Mutating directives never re-execute from cache
    if (isMutating) {
      const cached = this.cacheManager.get(cacheKey, noteId)
      if (cached) {
        return {
          result: this.cachedToDirectiveResult(cached),
          cacheHit: true,
          dependencies: cached.dependencies,
          mutations: [],
        }
      }
    }

    // Step 4: Check cache
    const cached = shouldSuppressStaleness
      ? this.cacheManager.get(cacheKey, noteId)
      : this.cacheManager.getIfValid(cacheKey, noteId, notes, currentNote)

    if (cached) {
      return {
        result: this.cachedToDirectiveResult(cached),
        cacheHit: true,
        dependencies: cached.dependencies,
        mutations: [],
      }
    }

    // Step 5: Cache miss - execute and cache
    return this.executeFreshWithCaching(
      sourceText, notes, currentNote, noteOperations, viewStack,
      cacheKey, baseDependencies,
    )
  }

  private analyzeDirective(sourceText: string): { cacheKey: string; analysis: ReturnType<typeof analyze> } | null {
    try {
      const tokens = new Lexer(sourceText).tokenize()
      const directive = new Parser(tokens, sourceText).parseDirective()
      // Use source text hash as cache key (same as DirectiveFinder)
      const cacheKey = directiveHash(sourceText)
      const analysis = analyze(directive.expression)
      return { cacheKey, analysis }
    } catch {
      return null
    }
  }

  private executeFresh(
    sourceText: string,
    notes: Note[],
    currentNote: Note | null,
    noteOperations?: NoteOperations,
    viewStack: string[] = [],
  ): CachedExecutionResult {
    const { result, mutations } = executeDirectiveWithMutations(sourceText, notes, currentNote, noteOperations, viewStack)
    return { result, cacheHit: false, dependencies: EMPTY_DEPENDENCIES, mutations }
  }

  private executeFreshWithCaching(
    sourceText: string,
    notes: Note[],
    currentNote: Note | null,
    noteOperations: NoteOperations | undefined,
    viewStack: string[],
    cacheKey: string,
    baseDependencies: DirectiveDependencies,
  ): CachedExecutionResult {
    const { result, mutations } = executeDirectiveWithMutations(sourceText, notes, currentNote, noteOperations, viewStack)
    const noteId = currentNote?.id ?? GLOBAL_CACHE_PLACEHOLDER

    // Enrich dependencies with viewed note IDs
    let finalDependencies = baseDependencies
    if (result.result) {
      // deserializeValue imported at top level
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
      this.cacheManager.put(cacheKey, noteId, cached)
    }

    return { result, cacheHit: false, dependencies: finalDependencies, mutations }
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
      // deserializeValue imported at top level
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
