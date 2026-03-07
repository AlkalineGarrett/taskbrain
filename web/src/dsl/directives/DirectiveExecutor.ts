import type { Note } from '@/data/Note'
import type { NoteOperations } from '../runtime/NoteOperations'
import type { DirectiveResult } from './DirectiveResult'
import { directiveResultSuccess, directiveResultFailure, directiveResultWarning, DirectiveWarningType } from './DirectiveResult'
import { findDirectives, directiveKey, hashDirective } from './DirectiveFinder'
import { Lexer } from '../language/Lexer'
import { Parser } from '../language/Parser'
import { Executor } from '../runtime/Executor'
import { Environment } from '../runtime/Environment'
import { ExecutionException } from '../runtime/ExecutionException'
import { IdempotencyAnalyzer } from '../language'

/**
 * Execute a single directive and return its result.
 */
export function executeDirective(
  sourceText: string,
  notes: Note[],
  currentNote: Note | null,
  noteOperations?: NoteOperations,
  viewStack: string[] = [],
): DirectiveResult {
  try {
    // Strip brackets
    const lexer = new Lexer(sourceText)
    const tokens = lexer.tokenize()
    const parser = new Parser(tokens, sourceText)
    const directive = parser.parseDirective()

    // Check idempotency
    const idempotencyResult = IdempotencyAnalyzer.analyze(directive.expression)
    if (!idempotencyResult.isIdempotent) {
      return directiveResultFailure(idempotencyResult.nonIdempotentReason ?? 'Non-idempotent directive')
    }

    // Check for unwrapped mutations
    const mutationResult = IdempotencyAnalyzer.checkUnwrappedMutations(directive.expression)
    if (!mutationResult.isIdempotent) {
      return directiveResultFailure(mutationResult.nonIdempotentReason ?? 'Unwrapped mutation')
    }

    // Execute
    const env = Environment.create({
      notes,
      currentNote: currentNote ?? undefined,
      noteOperations,
      viewStack,
    })
    const executor = new Executor()
    const result = executor.execute(directive, env)

    // Check for no-effect warnings
    if (result.kind === 'LambdaVal') {
      return directiveResultWarning(DirectiveWarningType.NO_EFFECT_LAMBDA)
    }
    if (result.kind === 'PatternVal') {
      return directiveResultWarning(DirectiveWarningType.NO_EFFECT_PATTERN)
    }

    return directiveResultSuccess(result)
  } catch (e) {
    const message = e instanceof ExecutionException ? e.message : (e instanceof Error ? e.message : String(e))
    return directiveResultFailure(message ?? 'Unknown error')
  }
}

/**
 * Execute all directives in a note's content and return results keyed by position.
 */
export function executeAllDirectives(
  content: string,
  notes: Note[],
  currentNote: Note | null,
  noteOperations?: NoteOperations,
): Map<string, DirectiveResult> {
  const results = new Map<string, DirectiveResult>()
  const lines = content.split('\n')

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
    const directives = findDirectives(lines[lineIndex]!)
    for (const directive of directives) {
      const key = directiveKey(lineIndex, directive.startOffset)
      const result = executeDirective(directive.sourceText, notes, currentNote, noteOperations)
      results.set(key, result)
    }
  }

  return results
}

/**
 * Execute all directives and return results keyed by source text hash (for Firestore storage).
 */
export async function executeAndHashDirectives(
  content: string,
  notes: Note[],
  currentNote: Note | null,
  noteOperations?: NoteOperations,
): Promise<Map<string, DirectiveResult>> {
  const results = new Map<string, DirectiveResult>()
  const lines = content.split('\n')

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
    const directives = findDirectives(lines[lineIndex]!)
    for (const directive of directives) {
      const hash = await hashDirective(directive.sourceText)
      const result = executeDirective(directive.sourceText, notes, currentNote, noteOperations)
      results.set(hash, result)
    }
  }

  return results
}
