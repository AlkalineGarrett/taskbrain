import type { DslValue, LambdaVal } from '../runtime/DslValue'
import { listVal, UNDEFINED } from '../runtime/DslValue'
import type { BuiltinFunction } from '../runtime/BuiltinRegistry'
import { ExecutionException } from '../runtime/ExecutionException'
import type { Environment } from '../runtime/Environment'
import { ASCENDING, DESCENDING } from './SortConstants'

export function getListFunctions(): BuiltinFunction[] {
  return [listFunction, sortFunction, firstFunction]
}

const listFunction: BuiltinFunction = {
  name: 'list',
  call: (args) => listVal(args.positional),
}

const sortFunction: BuiltinFunction = {
  name: 'sort',
  call: (args, env) => {
    args.assertNoUnknownNamed('sort', 'key', 'order')
    if (args.size !== 1) throw new ExecutionException(`'sort' requires exactly 1 positional argument (list), got ${args.size}`)
    const listArg = args.getPositional(0)
    if (!listArg) throw new ExecutionException("'sort' requires a list argument")
    if (listArg.kind !== 'ListVal') throw new ExecutionException(`'sort' first argument must be a list, got ${listArg.kind}`)

    const keyLambda = args.getLambda('key')
    const orderArg = args.getNamed('order')
    let isDescending = false
    if (orderArg) {
      if (orderArg.kind !== 'StringVal') throw new ExecutionException(`'sort' order must be 'ascending' or 'descending', got ${orderArg.kind}`)
      const value = orderArg.value.toLowerCase()
      if (value === ASCENDING) isDescending = false
      else if (value === DESCENDING) isDescending = true
      else throw new ExecutionException(`'sort' order must be 'ascending' or 'descending', got '${value}'`)
    }

    const sorted = sortList(listArg.items, keyLambda, env)
    return listVal(isDescending ? sorted.reverse() : sorted)
  },
}

const firstFunction: BuiltinFunction = {
  name: 'first',
  call: (args) => {
    if (args.size !== 1) throw new ExecutionException(`'first' requires exactly 1 argument (list), got ${args.size}`)
    const listArg = args.getPositional(0)
    if (!listArg) throw new ExecutionException("'first' requires a list argument")
    if (listArg.kind !== 'ListVal') throw new ExecutionException(`'first' argument must be a list, got ${listArg.kind}`)
    return listArg.items[0] ?? UNDEFINED
  },
}

function sortList(items: DslValue[], keyLambda: LambdaVal | null, env: Environment): DslValue[] {
  if (items.length === 0) return items

  if (keyLambda) {
    const executor = env.getExecutor()
    if (!executor) throw new ExecutionException("'sort' key: requires an executor in the environment")
    const itemsWithKeys = items.map((item) => ({
      item,
      key: executor.invokeLambda(keyLambda, [item]),
    }))
    itemsWithKeys.sort((a, b) => sortCompareValues(a.key, b.key))
    return itemsWithKeys.map((x) => x.item)
  }

  return [...items].sort(sortCompareValues)
}

/**
 * Compare two DslValues for sorting with type precedence.
 */
export function sortCompareValues(a: DslValue, b: DslValue): number {
  if (a.kind === 'UndefinedVal' && b.kind === 'UndefinedVal') return 0
  if (a.kind === 'UndefinedVal') return -1
  if (b.kind === 'UndefinedVal') return 1

  if (a.kind === 'NumberVal' && b.kind === 'NumberVal') return a.value - b.value
  if (a.kind === 'StringVal' && b.kind === 'StringVal') return a.value < b.value ? -1 : a.value > b.value ? 1 : 0
  if (a.kind === 'BooleanVal' && b.kind === 'BooleanVal') return Number(a.value) - Number(b.value)
  if (a.kind === 'DateVal' && b.kind === 'DateVal') return a.value < b.value ? -1 : a.value > b.value ? 1 : 0
  if (a.kind === 'TimeVal' && b.kind === 'TimeVal') return a.value < b.value ? -1 : a.value > b.value ? 1 : 0
  if (a.kind === 'DateTimeVal' && b.kind === 'DateTimeVal') return a.value < b.value ? -1 : a.value > b.value ? 1 : 0
  if (a.kind === 'NoteVal' && b.kind === 'NoteVal') return compareNotes(a, b)
  if (a.kind === 'ListVal' && b.kind === 'ListVal') return compareLists(a, b)

  return typePrecedence(a) - typePrecedence(b)
}

function compareNotes(a: DslValue & { kind: 'NoteVal' }, b: DslValue & { kind: 'NoteVal' }): number {
  const pathCmp = a.note.path < b.note.path ? -1 : a.note.path > b.note.path ? 1 : 0
  if (pathCmp !== 0) return pathCmp
  const nameA = a.note.content.split('\n')[0] ?? ''
  const nameB = b.note.content.split('\n')[0] ?? ''
  const nameCmp = nameA < nameB ? -1 : nameA > nameB ? 1 : 0
  if (nameCmp !== 0) return nameCmp
  return a.note.id < b.note.id ? -1 : a.note.id > b.note.id ? 1 : 0
}

function compareLists(a: DslValue & { kind: 'ListVal' }, b: DslValue & { kind: 'ListVal' }): number {
  const sizeCmp = a.items.length - b.items.length
  if (sizeCmp !== 0) return sizeCmp
  for (let i = 0; i < a.items.length; i++) {
    const cmp = sortCompareValues(a.items[i]!, b.items[i]!)
    if (cmp !== 0) return cmp
  }
  return 0
}

function typePrecedence(value: DslValue): number {
  switch (value.kind) {
    case 'UndefinedVal': return 0
    case 'BooleanVal': return 1
    case 'NumberVal': return 2
    case 'StringVal': return 3
    case 'DateVal': return 4
    case 'TimeVal': return 5
    case 'DateTimeVal': return 6
    case 'NoteVal': return 7
    case 'ListVal': return 8
    default: return 9
  }
}
