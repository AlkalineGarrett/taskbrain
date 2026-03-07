import type { Expression } from '../language/Expression'
import type { DirectiveDependencies, HierarchyPath, HierarchyAccessPattern } from './DirectiveDependencies'
import { NoteField, EMPTY_DEPENDENCIES } from './DirectiveDependencies'

/**
 * Result of analyzing a directive AST for dependencies.
 */
export interface DirectiveAnalysis {
  usesSelfAccess: boolean
  dependsOnPath: boolean
  dependsOnModified: boolean
  dependsOnCreated: boolean
  dependsOnViewed: boolean
  dependsOnNoteExistence: boolean
  dependsOnAllNames: boolean
  accessesFirstLine: boolean
  accessesNonFirstLine: boolean
  isMutating: boolean
  hierarchyAccesses: HierarchyAccessPattern[]
}

export const EMPTY_ANALYSIS: DirectiveAnalysis = {
  usesSelfAccess: false,
  dependsOnPath: false,
  dependsOnModified: false,
  dependsOnCreated: false,
  dependsOnViewed: false,
  dependsOnNoteExistence: false,
  dependsOnAllNames: false,
  accessesFirstLine: false,
  accessesNonFirstLine: false,
  isMutating: false,
  hierarchyAccesses: [],
}

export function analysisToPartialDependencies(analysis: DirectiveAnalysis): DirectiveDependencies {
  return {
    ...EMPTY_DEPENDENCIES,
    dependsOnPath: analysis.dependsOnPath,
    dependsOnModified: analysis.dependsOnModified,
    dependsOnCreated: analysis.dependsOnCreated,
    dependsOnViewed: analysis.dependsOnViewed,
    dependsOnNoteExistence: analysis.dependsOnNoteExistence,
    dependsOnAllNames: analysis.dependsOnAllNames,
    usesSelfAccess: analysis.usesSelfAccess,
  }
}

// --- Analysis context ---

interface AnalysisContext {
  usesSelfAccess: boolean
  dependsOnPath: boolean
  dependsOnModified: boolean
  dependsOnCreated: boolean
  dependsOnViewed: boolean
  dependsOnNoteExistence: boolean
  dependsOnAllNames: boolean
  accessesFirstLine: boolean
  accessesNonFirstLine: boolean
  isMutating: boolean
  hierarchyAccesses: HierarchyAccessPattern[]
}

function newContext(): AnalysisContext {
  return {
    usesSelfAccess: false,
    dependsOnPath: false,
    dependsOnModified: false,
    dependsOnCreated: false,
    dependsOnViewed: false,
    dependsOnNoteExistence: false,
    dependsOnAllNames: false,
    accessesFirstLine: false,
    accessesNonFirstLine: false,
    isMutating: false,
    hierarchyAccesses: [],
  }
}

function contextToResult(ctx: AnalysisContext): DirectiveAnalysis {
  return { ...ctx, hierarchyAccesses: [...ctx.hierarchyAccesses] }
}

/**
 * Analyze a directive expression for dependencies.
 */
export function analyze(expr: Expression): DirectiveAnalysis {
  const ctx = newContext()
  analyzeExpression(expr, ctx)
  return contextToResult(ctx)
}

interface TargetInfo {
  isSelf: boolean
  isHierarchy: boolean
  hierarchyPath: HierarchyPath | null
}

function analyzeExpression(expr: Expression, ctx: AnalysisContext): void {
  switch (expr.kind) {
    case 'NumberLiteral':
    case 'StringLiteral':
    case 'PatternExpr':
    case 'VariableRef':
      break

    case 'CurrentNoteRef':
      ctx.usesSelfAccess = true
      break

    case 'PropertyAccess':
      analyzePropertyAccess(expr, ctx)
      break

    case 'MethodCall':
      analyzeMethodCall(expr, ctx)
      break

    case 'CallExpr':
      analyzeCallExpr(expr, ctx)
      break

    case 'Assignment':
      ctx.isMutating = true
      analyzeExpression(expr.target, ctx)
      analyzeExpression(expr.value, ctx)
      break

    case 'StatementList':
      for (const stmt of expr.statements) analyzeExpression(stmt, ctx)
      break

    case 'LambdaExpr':
      analyzeExpression(expr.body, ctx)
      break

    case 'LambdaInvocation':
      analyzeExpression(expr.lambda.body, ctx)
      for (const arg of expr.args) analyzeExpression(arg, ctx)
      for (const na of expr.namedArgs) analyzeExpression(na.value, ctx)
      break

    case 'OnceExpr':
    case 'RefreshExpr':
      analyzeExpression(expr.body, ctx)
      break
  }
}

function analyzePropertyAccess(expr: Expression & { kind: 'PropertyAccess' }, ctx: AnalysisContext): void {
  const targetInfo = analyzeTarget(expr.target, ctx)

  switch (expr.property) {
    case 'path':
      ctx.dependsOnPath = true
      if (targetInfo.isHierarchy) ctx.hierarchyAccesses.push({ path: targetInfo.hierarchyPath!, field: NoteField.PATH })
      break
    case 'modified':
      ctx.dependsOnModified = true
      if (targetInfo.isHierarchy) ctx.hierarchyAccesses.push({ path: targetInfo.hierarchyPath!, field: NoteField.MODIFIED })
      break
    case 'created':
      ctx.dependsOnCreated = true
      if (targetInfo.isHierarchy) ctx.hierarchyAccesses.push({ path: targetInfo.hierarchyPath!, field: NoteField.CREATED })
      break
    case 'viewed':
      ctx.dependsOnViewed = true
      if (targetInfo.isHierarchy) ctx.hierarchyAccesses.push({ path: targetInfo.hierarchyPath!, field: NoteField.VIEWED })
      break
    case 'name':
      ctx.accessesFirstLine = true
      if (targetInfo.isHierarchy) ctx.hierarchyAccesses.push({ path: targetInfo.hierarchyPath!, field: NoteField.NAME })
      break
    case 'content':
      ctx.accessesFirstLine = true
      ctx.accessesNonFirstLine = true
      break
    case 'up':
      if (targetInfo.isHierarchy) {
        ctx.hierarchyAccesses.push({ path: extendHierarchyPath(targetInfo.hierarchyPath!, 1), field: null })
      } else if (targetInfo.isSelf) {
        ctx.hierarchyAccesses.push({ path: { kind: 'Up' }, field: null })
      }
      break
    case 'root':
      if (targetInfo.isSelf || targetInfo.isHierarchy) {
        ctx.hierarchyAccesses.push({ path: { kind: 'Root' }, field: null })
      }
      break
  }
}

function analyzeMethodCall(expr: Expression & { kind: 'MethodCall' }, ctx: AnalysisContext): void {
  const targetInfo = analyzeTarget(expr.target, ctx)

  if (expr.methodName === 'up') {
    const levels = extractLevels(expr.args)
    const path: HierarchyPath = levels === 1 ? { kind: 'Up' } : { kind: 'UpN', levels }

    if (targetInfo.isHierarchy) {
      ctx.hierarchyAccesses.push({ path: extendHierarchyPath(targetInfo.hierarchyPath!, levels), field: null })
    } else if (targetInfo.isSelf) {
      ctx.hierarchyAccesses.push({ path, field: null })
    }
  }

  for (const arg of expr.args) analyzeExpression(arg, ctx)
  for (const na of expr.namedArgs) analyzeExpression(na.value, ctx)
}

function analyzeCallExpr(expr: Expression & { kind: 'CallExpr' }, ctx: AnalysisContext): void {
  switch (expr.name) {
    case 'find': {
      ctx.dependsOnNoteExistence = true
      const pathArg = expr.namedArgs.find((a) => a.name === 'path')
      if (pathArg) {
        ctx.dependsOnPath = true
        analyzeExpression(pathArg.value, ctx)
      }
      const nameArg = expr.namedArgs.find((a) => a.name === 'name')
      if (nameArg) {
        ctx.accessesFirstLine = true
        ctx.dependsOnAllNames = true
        analyzeExpression(nameArg.value, ctx)
      }
      const whereArg = expr.namedArgs.find((a) => a.name === 'where')
      if (whereArg) analyzeExpression(whereArg.value, ctx)
      break
    }
    case 'view':
      for (const arg of expr.args) analyzeExpression(arg, ctx)
      break
    default:
      for (const arg of expr.args) analyzeExpression(arg, ctx)
      for (const na of expr.namedArgs) analyzeExpression(na.value, ctx)
      break
  }
}

function analyzeTarget(target: Expression, ctx: AnalysisContext): TargetInfo {
  switch (target.kind) {
    case 'CurrentNoteRef':
      ctx.usesSelfAccess = true
      return { isSelf: true, isHierarchy: false, hierarchyPath: null }

    case 'PropertyAccess': {
      const parentInfo = analyzeTarget(target.target, ctx)
      if (target.property === 'up') {
        if (parentInfo.isSelf) return { isSelf: false, isHierarchy: true, hierarchyPath: { kind: 'Up' } }
        if (parentInfo.isHierarchy) return { isSelf: false, isHierarchy: true, hierarchyPath: extendHierarchyPath(parentInfo.hierarchyPath!, 1) }
        return { isSelf: false, isHierarchy: false, hierarchyPath: null }
      }
      if (target.property === 'root') {
        if (parentInfo.isSelf || parentInfo.isHierarchy) return { isSelf: false, isHierarchy: true, hierarchyPath: { kind: 'Root' } }
        return { isSelf: false, isHierarchy: false, hierarchyPath: null }
      }
      // Other property on the target chain
      analyzePropertyAccess(target, ctx)
      return { isSelf: false, isHierarchy: false, hierarchyPath: null }
    }

    case 'MethodCall': {
      const parentInfo = analyzeTarget(target.target, ctx)
      if (target.methodName === 'up') {
        const levels = extractLevels(target.args)
        const path: HierarchyPath = levels === 1 ? { kind: 'Up' } : { kind: 'UpN', levels }
        if (parentInfo.isSelf) return { isSelf: false, isHierarchy: true, hierarchyPath: path }
        if (parentInfo.isHierarchy) return { isSelf: false, isHierarchy: true, hierarchyPath: extendHierarchyPath(parentInfo.hierarchyPath!, levels) }
        return { isSelf: false, isHierarchy: false, hierarchyPath: null }
      }
      analyzeMethodCall(target, ctx)
      return { isSelf: false, isHierarchy: false, hierarchyPath: null }
    }

    default:
      analyzeExpression(target, ctx)
      return { isSelf: false, isHierarchy: false, hierarchyPath: null }
  }
}

function extractLevels(args: Expression[]): number {
  if (args.length === 0) return 1
  const first = args[0]!
  return first.kind === 'NumberLiteral' ? Math.floor(first.value) : 1
}

function extendHierarchyPath(current: HierarchyPath, additionalLevels: number): HierarchyPath {
  switch (current.kind) {
    case 'Up': return { kind: 'UpN', levels: 1 + additionalLevels }
    case 'UpN': return { kind: 'UpN', levels: current.levels + additionalLevels }
    case 'Root': return { kind: 'Root' }
  }
}
