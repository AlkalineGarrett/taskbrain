import type {
  DslValue,
  NumberVal,
  StringVal,
  PatternVal,
  BooleanVal,
  LambdaVal,
} from './DslValue'
import { typeName } from './DslValue'
import { ExecutionException } from './ExecutionException'

/**
 * Container for function arguments, supporting both positional and named arguments.
 */
export class Arguments {
  constructor(
    readonly positional: DslValue[],
    readonly named: Map<string, DslValue> = new Map(),
  ) {}

  get size(): number {
    return this.positional.length
  }

  getPositional(index: number): DslValue | null {
    return this.positional[index] ?? null
  }

  getNamed(name: string): DslValue | null {
    return this.named.get(name) ?? null
  }

  require(index: number, paramName: string = `argument ${index}`): DslValue {
    const val = this.positional[index]
    if (val === undefined) throw new ExecutionException(`Missing required ${paramName}`)
    return val
  }

  requireNamed(name: string): DslValue {
    const val = this.named.get(name)
    if (val === undefined) throw new ExecutionException(`Missing required argument '${name}'`)
    return val
  }

  hasPositional(): boolean {
    return this.positional.length > 0
  }

  hasNamed(name?: string): boolean {
    if (name === undefined) return this.named.size > 0
    return this.named.has(name)
  }

  requireExactCount(count: number, funcName: string): void {
    if (this.size !== count) {
      throw new ExecutionException(`'${funcName}' requires ${count} arguments, got ${this.size}`)
    }
  }

  requireNoArgs(funcName: string): void {
    if (this.hasPositional()) {
      throw new ExecutionException(`'${funcName}' takes no arguments, got ${this.size}`)
    }
  }

  requireNumber(index: number, funcName: string, paramName: string = `argument ${index + 1}`): NumberVal {
    const arg = this.positional[index]
    if (arg === undefined) throw new ExecutionException(`'${funcName}' missing ${paramName}`)
    if (arg.kind !== 'NumberVal') {
      throw new ExecutionException(`'${funcName}' ${paramName} must be a number, got ${typeName(arg)}`)
    }
    return arg
  }

  requireString(index: number, funcName: string, paramName: string = `argument ${index + 1}`): StringVal {
    const arg = this.positional[index]
    if (arg === undefined) throw new ExecutionException(`'${funcName}' missing ${paramName}`)
    if (arg.kind !== 'StringVal') {
      throw new ExecutionException(`'${funcName}' ${paramName} must be a string, got ${typeName(arg)}`)
    }
    return arg
  }

  requirePattern(index: number, funcName: string, paramName: string = `argument ${index + 1}`): PatternVal {
    const arg = this.positional[index]
    if (arg === undefined) throw new ExecutionException(`'${funcName}' missing ${paramName}`)
    if (arg.kind !== 'PatternVal') {
      throw new ExecutionException(`'${funcName}' ${paramName} must be a pattern, got ${typeName(arg)}`)
    }
    return arg
  }

  requireBoolean(index: number, funcName: string, paramName: string = `argument ${index + 1}`): BooleanVal {
    const arg = this.positional[index]
    if (arg === undefined) throw new ExecutionException(`'${funcName}' missing ${paramName}`)
    if (arg.kind !== 'BooleanVal') {
      throw new ExecutionException(`'${funcName}' ${paramName} must be a boolean, got ${typeName(arg)}`)
    }
    return arg
  }

  requireLambda(index: number, funcName: string, paramName: string = `argument ${index + 1}`): LambdaVal {
    const arg = this.positional[index]
    if (arg === undefined) throw new ExecutionException(`'${funcName}' missing ${paramName}`)
    if (arg.kind !== 'LambdaVal') {
      throw new ExecutionException(`'${funcName}' ${paramName} must be a lambda/deferred block, got ${typeName(arg)}`)
    }
    return arg
  }

  /** Throws if any named arguments exist that aren't in the known set. */
  assertNoUnknownNamed(funcName: string, ...known: string[]): void {
    const knownSet = new Set(known)
    for (const name of this.named.keys()) {
      if (!knownSet.has(name)) {
        throw new ExecutionException(`'${funcName}' does not accept named argument '${name}'`)
      }
    }
  }

  getLambda(name: string): LambdaVal | null {
    const val = this.named.get(name)
    if (val === undefined) return null
    if (val.kind !== 'LambdaVal') return null
    return val
  }
}
