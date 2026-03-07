import type { Expression, NamedArg, PatternElement } from '../language/Expression'

/**
 * Normalizes AST for cache key generation.
 * Produces a canonical string form that is position-independent.
 */

export function computeCacheKey(expr: Expression): string {
  const normalized = normalize(expr)
  return simpleHash(normalized)
}

export function normalize(expr: Expression): string {
  switch (expr.kind) {
    case 'NumberLiteral':
      return `NUM(${expr.value})`
    case 'StringLiteral':
      return `STR(${escapeString(expr.value)})`
    case 'CurrentNoteRef':
      return 'SELF'
    case 'VariableRef':
      return `VAR(${expr.name})`
    case 'PropertyAccess':
      return `PROP(${normalize(expr.target)},${expr.property})`
    case 'MethodCall': {
      const argsNorm = normalizeArgs(expr.args, expr.namedArgs)
      return `METHOD(${normalize(expr.target)},${expr.methodName},${argsNorm})`
    }
    case 'CallExpr': {
      const argsNorm = normalizeArgs(expr.args, expr.namedArgs)
      return `CALL(${expr.name},${argsNorm})`
    }
    case 'Assignment': {
      const targetNorm = normalizeAssignmentTarget(expr.target)
      return `ASSIGN(${targetNorm},${normalize(expr.value)})`
    }
    case 'StatementList': {
      const stmtsNorm = expr.statements.map(normalize).join(';')
      return `STMTS(${stmtsNorm})`
    }
    case 'LambdaExpr': {
      const paramsNorm = expr.params.join(',')
      return `LAMBDA(${paramsNorm},${normalize(expr.body)})`
    }
    case 'LambdaInvocation': {
      const lambdaNorm = normalize(expr.lambda)
      const argsNorm = normalizeArgs(expr.args, expr.namedArgs)
      return `INVOKE(${lambdaNorm},${argsNorm})`
    }
    case 'OnceExpr':
      return `ONCE(${normalize(expr.body)})`
    case 'RefreshExpr':
      return `REFRESH(${normalize(expr.body)})`
    case 'PatternExpr': {
      const elementsNorm = expr.elements.map(normalizePatternElement).join(',')
      return `PATTERN(${elementsNorm})`
    }
  }
}

function normalizeArgs(args: Expression[], namedArgs: NamedArg[]): string {
  const parts: string[] = []
  for (const arg of args) {
    parts.push(normalize(arg))
  }
  const sortedNamed = [...namedArgs].sort((a, b) => a.name.localeCompare(b.name))
  for (const namedArg of sortedNamed) {
    parts.push(`${namedArg.name}=${normalize(namedArg.value)}`)
  }
  return parts.join(',')
}

function normalizeAssignmentTarget(target: Expression): string {
  if (target.kind === 'VariableRef') return target.name
  if (target.kind === 'PropertyAccess') return `PROP(${normalize(target.target)},${target.property})`
  return normalize(target)
}

function normalizePatternElement(element: PatternElement): string {
  switch (element.kind) {
    case 'CharClass':
      return `CHAR(${element.type})`
    case 'PatternLiteral':
      return `LIT(${escapeString(element.value)})`
    case 'Quantified': {
      const quantNorm = (() => {
        switch (element.quantifier.kind) {
          case 'Exact': return `x${element.quantifier.n}`
          case 'Range': return `${element.quantifier.min}..${element.quantifier.max ?? ''}`
          case 'Any': return '*'
        }
      })()
      return `QUANT(${normalizePatternElement(element.element)},${quantNorm})`
    }
  }
}

function escapeString(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
}

function simpleHash(input: string): string {
  let hash = 0
  for (let i = 0; i < input.length; i++) {
    const char = input.charCodeAt(i)
    hash = ((hash << 5) - hash + char) | 0
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}
