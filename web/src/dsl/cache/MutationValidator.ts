import type { Expression } from '../language/Expression'

/**
 * Validates that directives follow mutation and temporal rules.
 */

export type ValidationResult =
  | { kind: 'Valid' }
  | { kind: 'BareMutation'; mutationType: string; suggestion: string }
  | { kind: 'BareTimeValue'; functionName: string; suggestion: string }

const VALID: ValidationResult = { kind: 'Valid' }

export function validationErrorMessage(result: ValidationResult): string | null {
  switch (result.kind) {
    case 'Valid': return null
    case 'BareMutation': return `${result.mutationType} requires explicit trigger. ${result.suggestion}`
    case 'BareTimeValue': return `${result.functionName} returns a time value that changes. ${result.suggestion}`
  }
}

interface ValidationContext {
  insideActionWrapper: boolean
  insideTimeWrapper: boolean
}

const MUTATION_FUNCTIONS = new Set(['new', 'maybe_new'])
const MUTATION_METHODS = new Set(['append'])
const ACTION_WRAPPER_FUNCTIONS = new Set(['button', 'schedule'])
const TIME_FUNCTIONS = new Set(['date', 'time', 'datetime'])

export function validate(expr: Expression): ValidationResult {
  return validateWithContext(expr, { insideActionWrapper: false, insideTimeWrapper: false })
}

function validateWithContext(expr: Expression, ctx: ValidationContext): ValidationResult {
  switch (expr.kind) {
    case 'NumberLiteral':
    case 'StringLiteral':
    case 'VariableRef':
    case 'CurrentNoteRef':
    case 'PatternExpr':
      return VALID

    case 'PropertyAccess':
      return validateWithContext(expr.target, ctx)

    case 'LambdaExpr':
      return validateWithContext(expr.body, ctx)

    case 'LambdaInvocation': {
      const bodyResult = validateWithContext(expr.lambda.body, ctx)
      if (bodyResult.kind !== 'Valid') return bodyResult
      for (const arg of expr.args) {
        const r = validateWithContext(arg, ctx)
        if (r.kind !== 'Valid') return r
      }
      for (const na of expr.namedArgs) {
        const r = validateWithContext(na.value, ctx)
        if (r.kind !== 'Valid') return r
      }
      return VALID
    }

    case 'OnceExpr':
      return validateWithContext(expr.body, { ...ctx, insideTimeWrapper: true })

    case 'RefreshExpr':
      return validateWithContext(expr.body, { ...ctx, insideTimeWrapper: true })

    case 'CallExpr':
      return validateCallExpr(expr, ctx)

    case 'MethodCall':
      return validateMethodCall(expr, ctx)

    case 'Assignment':
      return validateWithContext(expr.value, ctx)

    case 'StatementList':
      for (const stmt of expr.statements) {
        const r = validateWithContext(stmt, ctx)
        if (r.kind !== 'Valid') return r
      }
      return VALID
  }
}

function validateCallExpr(expr: Expression & { kind: 'CallExpr' }, ctx: ValidationContext): ValidationResult {
  if (MUTATION_FUNCTIONS.has(expr.name) && !ctx.insideActionWrapper) {
    return { kind: 'BareMutation', mutationType: `${expr.name}()`, suggestion: 'Wrap in button() or schedule() to execute.' }
  }
  if (TIME_FUNCTIONS.has(expr.name) && !ctx.insideTimeWrapper) {
    return { kind: 'BareTimeValue', functionName: `${expr.name}()`, suggestion: 'Wrap in once[...] to cache or refresh[...] to update periodically.' }
  }

  if (ACTION_WRAPPER_FUNCTIONS.has(expr.name)) {
    const firstArg = expr.args[0]
    if (firstArg) {
      const r = validateWithContext(firstArg, ctx)
      if (r.kind !== 'Valid') return r
    }
    const actionArg = expr.args[1]
    if (actionArg) {
      const r = validateWithContext(actionArg, { insideActionWrapper: true, insideTimeWrapper: true })
      if (r.kind !== 'Valid') return r
    }
    for (const na of expr.namedArgs) {
      const r = validateWithContext(na.value, ctx)
      if (r.kind !== 'Valid') return r
    }
    return VALID
  }

  for (const arg of expr.args) {
    const r = validateWithContext(arg, ctx)
    if (r.kind !== 'Valid') return r
  }
  for (const na of expr.namedArgs) {
    const r = validateWithContext(na.value, ctx)
    if (r.kind !== 'Valid') return r
  }
  return VALID
}

function validateMethodCall(expr: Expression & { kind: 'MethodCall' }, ctx: ValidationContext): ValidationResult {
  if (MUTATION_METHODS.has(expr.methodName) && !ctx.insideActionWrapper) {
    return { kind: 'BareMutation', mutationType: `.${expr.methodName}()`, suggestion: 'Wrap in button() or schedule() to execute.' }
  }

  const targetResult = validateWithContext(expr.target, ctx)
  if (targetResult.kind !== 'Valid') return targetResult

  for (const arg of expr.args) {
    const r = validateWithContext(arg, ctx)
    if (r.kind !== 'Valid') return r
  }
  for (const na of expr.namedArgs) {
    const r = validateWithContext(na.value, ctx)
    if (r.kind !== 'Valid') return r
  }
  return VALID
}

/**
 * Check if an expression contains any mutations.
 */
export function containsMutations(expr: Expression): boolean {
  switch (expr.kind) {
    case 'NumberLiteral': case 'StringLiteral': case 'VariableRef':
    case 'CurrentNoteRef': case 'PatternExpr': return false
    case 'PropertyAccess': return containsMutations(expr.target)
    case 'LambdaExpr': return containsMutations(expr.body)
    case 'LambdaInvocation':
      return containsMutations(expr.lambda.body) ||
        expr.args.some(containsMutations) || expr.namedArgs.some((na) => containsMutations(na.value))
    case 'OnceExpr': case 'RefreshExpr': return containsMutations(expr.body)
    case 'CallExpr':
      if (MUTATION_FUNCTIONS.has(expr.name) || ACTION_WRAPPER_FUNCTIONS.has(expr.name)) return true
      return expr.args.some(containsMutations) || expr.namedArgs.some((na) => containsMutations(na.value))
    case 'MethodCall':
      if (MUTATION_METHODS.has(expr.methodName)) return true
      return containsMutations(expr.target) ||
        expr.args.some(containsMutations) || expr.namedArgs.some((na) => containsMutations(na.value))
    case 'Assignment': return containsMutations(expr.value)
    case 'StatementList': return expr.statements.some(containsMutations)
  }
}
