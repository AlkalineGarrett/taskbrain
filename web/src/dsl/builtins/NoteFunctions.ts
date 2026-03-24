import type { Note } from '@/data/Note'
import type { DslValue, LambdaVal } from '../runtime/DslValue'
import { listVal, noteVal, viewVal } from '../runtime/DslValue'
import { typeName } from '../runtime/DslValue'
import type { BuiltinFunction } from '../runtime/BuiltinRegistry'
import type { Environment } from '../runtime/Environment'
import { ExecutionException } from '../runtime/ExecutionException'
import { findDirectives } from '../directives/DirectiveFinder'

export function getNoteFunctions(): BuiltinFunction[] {
  return [findFunction, newFunction, maybeNewFunction, viewFunction]
}

const findFunction: BuiltinFunction = {
  name: 'find',
  call: (args, env) => {
    const pathArg = args.getNamed('path')
    const nameArg = args.getNamed('name')
    const whereArg = args.getLambda('where')

    const notes = env.getNotes()
    if (!notes || notes.length === 0) return listVal([])

    const currentNoteId = env.getCurrentNoteRaw()?.id

    const filtered = notes.filter((note) => {
      if (note.state === 'deleted') return false
      if (currentNoteId && note.id === currentNoteId) return false
      return (
        matchesFilter(pathArg, note.path, 'path') &&
        matchesFilter(nameArg, note.content.split('\n')[0] ?? '', 'name') &&
        evaluateWhereLambda(whereArg, note, env)
      )
    })

    return listVal(filtered.map(noteVal))
  },
}

const newFunction: BuiltinFunction = {
  name: 'new',
  isDynamic: true,
  call: async (args, env) => {
    const pathArg = args.getNamed('path')
    if (!pathArg) throw new ExecutionException("'new' requires a 'path' argument")
    if (pathArg.kind !== 'StringVal') throw new ExecutionException(`'new' path argument must be a string, got ${typeName(pathArg)}`)

    const contentArg = args.getNamed('content')
    let content = ''
    if (contentArg) {
      if (contentArg.kind !== 'StringVal') throw new ExecutionException(`'new' content argument must be a string, got ${typeName(contentArg)}`)
      content = contentArg.value
    }

    const ops = env.getNoteOperations()
    if (!ops) throw new ExecutionException("'new' requires note operations to be available")

    const exists = await ops.noteExistsAtPath(pathArg.value)
    if (exists) throw new ExecutionException(`Note already exists at path: ${pathArg.value}`)

    const note = await ops.createNote(pathArg.value, content)
    return noteVal(note)
  },
}

const maybeNewFunction: BuiltinFunction = {
  name: 'maybe_new',
  isDynamic: true,
  call: async (args, env) => {
    const pathArg = args.getNamed('path')
    if (!pathArg) throw new ExecutionException("'maybe_new' requires a 'path' argument")
    if (pathArg.kind !== 'StringVal') throw new ExecutionException(`'maybe_new' path argument must be a string, got ${typeName(pathArg)}`)

    const maybeContentArg = args.getNamed('maybe_content')
    let maybeContent = ''
    if (maybeContentArg) {
      if (maybeContentArg.kind !== 'StringVal') throw new ExecutionException(`'maybe_new' maybe_content argument must be a string, got ${typeName(maybeContentArg)}`)
      maybeContent = maybeContentArg.value
    }

    const ops = env.getNoteOperations()
    if (!ops) throw new ExecutionException("'maybe_new' requires note operations to be available")

    const existing = await ops.findByPath(pathArg.value)
    if (existing) return noteVal(existing)

    const note = await ops.createNote(pathArg.value, maybeContent)
    return noteVal(note)
  },
}

const viewFunction: BuiltinFunction = {
  name: 'view',
  call: (args, env) => {
    const listArg = args.getPositional(0)
    if (!listArg) throw new ExecutionException("'view' requires a list of notes as argument")
    if (listArg.kind !== 'ListVal') throw new ExecutionException(`'view' argument must be a list, got ${typeName(listArg)}`)

    const allNotes = listArg.items.map((item, index) => {
      if (item.kind !== 'NoteVal') throw new ExecutionException(`'view' list item at index ${index} must be a note, got ${typeName(item)}`)
      return item.note
    })

    const currentNoteId = env.getCurrentNoteRaw()?.id
    const notes = currentNoteId ? allNotes.filter((n) => n.id !== currentNoteId) : allNotes

    // Check for circular dependencies
    for (const note of notes) {
      if (env.isInViewStack(note.id)) {
        const currentPath = env.getViewStackPath()
        const cycleInfo = currentPath.length === 0 ? note.id : `${currentPath} → ${note.id}`
        throw new ExecutionException(`Circular view dependency: ${cycleInfo}`)
      }
    }

    // Render each note's content with directives evaluated
    const renderedContents = notes.map((note) => renderNoteContent(note, env))

    return viewVal(notes, renderedContents)
  },
}

function renderNoteContent(viewedNote: Note, env: Environment): string {
  const content = viewedNote.content
  const directives = findDirectives(content)
  if (directives.length === 0) return content

  const viewStack = [...env.getViewStack(), viewedNote.id]
  const cachedExecutor = env.getCachedExecutor()
  const allNotes = env.getNotes() ?? []

  let result = ''
  let lastEnd = 0

  for (const directive of directives) {
    if (directive.startOffset > lastEnd) {
      result += content.substring(lastEnd, directive.startOffset)
    }

    let displayValue: string
    if (cachedExecutor) {
      const cachedResult = cachedExecutor.executeCached(
        directive.sourceText,
        allNotes,
        viewedNote,
        env.getNoteOperations(),
        viewStack,
      )
      displayValue = cachedResult.errorMessage ? directive.sourceText : (cachedResult.displayValue ?? directive.sourceText)
    } else {
      // Fallback: keep directive source text (full execution requires Executor import which would be circular)
      displayValue = directive.sourceText
    }

    result += displayValue
    lastEnd = directive.endOffset
  }

  if (lastEnd < content.length) {
    result += content.substring(lastEnd)
  }

  return result
}

function matchesFilter(filter: DslValue | null, value: string, paramName: string): boolean {
  if (!filter) return true
  if (filter.kind === 'StringVal') return value === filter.value
  if (filter.kind === 'PatternVal') return filter.compiledRegex.test(value)
  throw new ExecutionException(`'find' ${paramName} argument must be a string or pattern, got ${typeName(filter)}`)
}

function evaluateWhereLambda(lambda: LambdaVal | null, note: Note, env: Environment): boolean {
  if (!lambda) return true
  const executor = env.getExecutor()
  if (!executor) throw new ExecutionException("'find' where: requires an executor in the environment")
  const result = executor.invokeLambda(lambda, [noteVal(note)])
  if (result.kind !== 'BooleanVal') throw new ExecutionException(`'find' where: lambda must return a boolean, got ${typeName(result)}`)
  return result.value
}
