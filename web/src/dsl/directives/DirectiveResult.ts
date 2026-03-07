import type { DslValue } from '../runtime/DslValue'
import { deserializeValue, serializeValue, toDisplayString } from '../runtime/DslValue'

export enum DirectiveWarningType {
  NO_EFFECT_LAMBDA = 'Uncalled lambda has no effect',
  NO_EFFECT_PATTERN = 'Unused pattern has no effect',
}

/**
 * Represents a directive execution result.
 * Stored in Firestore at: notes/{noteId}/directiveResults/{directiveHash}
 */
export interface DirectiveResult {
  result: Record<string, unknown> | null // Serialized DslValue
  executedAt: unknown | null
  error: string | null
  warning: DirectiveWarningType | null
  collapsed: boolean
}

export function directiveResultToValue(dr: DirectiveResult): DslValue | null {
  if (!dr.result) return null
  try {
    return deserializeValue(dr.result as Record<string, unknown>)
  } catch {
    return null
  }
}

export function directiveResultToDisplayString(dr: DirectiveResult, fallback: string = '...'): string {
  if (dr.error) return `Error: ${dr.error}`
  if (dr.warning) return `Warning: ${dr.warning}`
  if (dr.result) {
    const val = directiveResultToValue(dr)
    return val ? toDisplayString(val) : 'null'
  }
  return fallback
}

export function isComputed(dr: DirectiveResult): boolean {
  return dr.result !== null && dr.error === null
}

export function directiveResultSuccess(value: DslValue, collapsed: boolean = true): DirectiveResult {
  return {
    result: serializeValue(value) as Record<string, unknown>,
    executedAt: null,
    error: null,
    warning: null,
    collapsed,
  }
}

export function directiveResultFailure(errorMessage: string, collapsed: boolean = true): DirectiveResult {
  return { result: null, executedAt: null, error: errorMessage, warning: null, collapsed }
}

export function directiveResultWarning(warningType: DirectiveWarningType, collapsed: boolean = true): DirectiveResult {
  return { result: null, executedAt: null, error: null, warning: warningType, collapsed }
}
