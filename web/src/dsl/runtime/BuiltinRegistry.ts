import type { DslValue } from './DslValue'
import type { Arguments } from './Arguments'
import type { Environment } from './Environment'
import { getDateFunctions } from '../builtins/DateFunctions'
import { getCharacterConstants } from '../builtins/CharacterConstants'
import { getArithmeticFunctions } from '../builtins/ArithmeticFunctions'
import { getComparisonFunctions } from '../builtins/ComparisonFunctions'
import { getPatternFunctions } from '../builtins/PatternFunctions'
import { getNoteFunctions } from '../builtins/NoteFunctions'
import { getListFunctions } from '../builtins/ListFunctions'
import { getSortConstants } from '../builtins/SortConstants'
import { getActionFunctions } from '../builtins/ActionFunctions'

export interface BuiltinFunction {
  name: string
  isDynamic?: boolean
  call: (args: Arguments, env: Environment) => DslValue | Promise<DslValue>
}

const functions = new Map<string, BuiltinFunction>()
let initialized = false

function ensureInitialized(): void {
  if (initialized) return
  initialized = true

  const allFunctions: BuiltinFunction[] = [
    ...getDateFunctions(),
    ...getCharacterConstants(),
    ...getArithmeticFunctions(),
    ...getComparisonFunctions(),
    ...getPatternFunctions(),
    ...getNoteFunctions(),
    ...getListFunctions(),
    ...getSortConstants(),
    ...getActionFunctions(),
  ]

  for (const fn of allFunctions) {
    functions.set(fn.name, fn)
  }
}

export const BuiltinRegistry = {
  get(name: string): BuiltinFunction | null {
    ensureInitialized()
    return functions.get(name) ?? null
  },

  has(name: string): boolean {
    ensureInitialized()
    return functions.has(name)
  },

  isDynamic(name: string): boolean {
    ensureInitialized()
    return functions.get(name)?.isDynamic ?? false
  },

  allNames(): Set<string> {
    ensureInitialized()
    return new Set(functions.keys())
  },
}
