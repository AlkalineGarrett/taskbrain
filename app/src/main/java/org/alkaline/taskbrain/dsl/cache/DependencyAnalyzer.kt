package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.dsl.language.Assignment
import org.alkaline.taskbrain.dsl.language.CallExpr
import org.alkaline.taskbrain.dsl.language.CurrentNoteRef
import org.alkaline.taskbrain.dsl.language.Expression
import org.alkaline.taskbrain.dsl.language.LambdaExpr
import org.alkaline.taskbrain.dsl.language.LambdaInvocation
import org.alkaline.taskbrain.dsl.language.MethodCall
import org.alkaline.taskbrain.dsl.language.NumberLiteral
import org.alkaline.taskbrain.dsl.language.OnceExpr
import org.alkaline.taskbrain.dsl.language.PatternExpr
import org.alkaline.taskbrain.dsl.language.PropertyAccess
import org.alkaline.taskbrain.dsl.language.RefreshExpr
import org.alkaline.taskbrain.dsl.language.StatementList
import org.alkaline.taskbrain.dsl.language.StringLiteral
import org.alkaline.taskbrain.dsl.language.VariableRef

/**
 * Analyzes directive AST to detect dependencies for cache invalidation.
 *
 * AST analysis for dependency detection.
 *
 * This analyzer walks the AST and identifies:
 * - Field access (path, modified, created, viewed) - metadata dependencies
 * - Hierarchy access (.up, .root) - parent/ancestor dependencies
 * - find() usage - note existence dependency
 * - Content access (name, content) - per-note content dependencies
 */
object DependencyAnalyzer {

    /**
     * Analyze a directive expression for dependencies.
     *
     * @param expr The parsed expression to analyze
     * @return Analysis result containing dependency information
     */
    fun analyze(expr: Expression): DirectiveAnalysis {
        val context = AnalysisContext()
        analyzeExpression(expr, context)
        return context.toResult()
    }

    /**
     * Internal context for collecting dependency information during analysis.
     */
    private class AnalysisContext {
        var dependsOnPath: Boolean = false
        var dependsOnModified: Boolean = false
        var dependsOnCreated: Boolean = false
        var dependsOnNoteExistence: Boolean = false
        var dependsOnAllNames: Boolean = false
        var accessesFirstLine: Boolean = false
        var accessesNonFirstLine: Boolean = false
        var isMutating: Boolean = false
        val hierarchyAccesses = mutableListOf<HierarchyAccessPattern>()

        fun toResult(): DirectiveAnalysis {
            return DirectiveAnalysis(
                dependsOnPath = dependsOnPath,
                dependsOnModified = dependsOnModified,
                dependsOnCreated = dependsOnCreated,
                dependsOnNoteExistence = dependsOnNoteExistence,
                dependsOnAllNames = dependsOnAllNames,
                accessesFirstLine = accessesFirstLine,
                accessesNonFirstLine = accessesNonFirstLine,
                isMutating = isMutating,
                hierarchyAccesses = hierarchyAccesses.toList()
            )
        }
    }

    private fun analyzeExpression(expr: Expression, ctx: AnalysisContext) {
        when (expr) {
            is NumberLiteral, is StringLiteral, is PatternExpr -> {
                // Literals have no dependencies
            }

            is VariableRef -> {
                // Variable references themselves have no dependencies
                // (the binding expression was already analyzed)
            }

            is CurrentNoteRef -> {
                // Direct reference to current note - no dependencies to track
            }

            is PropertyAccess -> analyzePropertyAccess(expr, ctx)

            is MethodCall -> analyzeMethodCall(expr, ctx)

            is CallExpr -> analyzeCallExpr(expr, ctx)

            is Assignment -> {
                // Assignments are mutating - they modify note properties
                ctx.isMutating = true
                analyzeExpression(expr.target, ctx)
                analyzeExpression(expr.value, ctx)
            }

            is StatementList -> {
                for (stmt in expr.statements) {
                    analyzeExpression(stmt, ctx)
                }
            }

            is LambdaExpr -> {
                // Analyze lambda body
                analyzeExpression(expr.body, ctx)
            }

            is LambdaInvocation -> {
                // Analyze lambda body and arguments
                analyzeExpression(expr.lambda.body, ctx)
                for (arg in expr.args) {
                    analyzeExpression(arg, ctx)
                }
                for (namedArg in expr.namedArgs) {
                    analyzeExpression(namedArg.value, ctx)
                }
            }

            is OnceExpr -> {
                // once[...] still has dependencies on what's inside
                analyzeExpression(expr.body, ctx)
            }

            is RefreshExpr -> {
                // refresh[...] still has dependencies on what's inside
                analyzeExpression(expr.body, ctx)
            }
        }
    }

    private fun analyzePropertyAccess(expr: PropertyAccess, ctx: AnalysisContext) {
        // First, determine the target chain
        val targetInfo = analyzeTarget(expr.target, ctx)

        // Record field access based on property name
        when (expr.property) {
            "path" -> {
                ctx.dependsOnPath = true
                if (targetInfo.isHierarchy) {
                    ctx.hierarchyAccesses.add(
                        HierarchyAccessPattern(targetInfo.hierarchyPath!!, NoteField.PATH)
                    )
                }
            }
            "modified" -> {
                ctx.dependsOnModified = true
                if (targetInfo.isHierarchy) {
                    ctx.hierarchyAccesses.add(
                        HierarchyAccessPattern(targetInfo.hierarchyPath!!, NoteField.MODIFIED)
                    )
                }
            }
            "created" -> {
                ctx.dependsOnCreated = true
                if (targetInfo.isHierarchy) {
                    ctx.hierarchyAccesses.add(
                        HierarchyAccessPattern(targetInfo.hierarchyPath!!, NoteField.CREATED)
                    )
                }
            }
            "name" -> {
                ctx.accessesFirstLine = true
                if (targetInfo.isHierarchy) {
                    ctx.hierarchyAccesses.add(
                        HierarchyAccessPattern(targetInfo.hierarchyPath!!, NoteField.NAME)
                    )
                }
            }
            "content" -> {
                // Content access depends on both first line and non-first line
                ctx.accessesFirstLine = true
                ctx.accessesNonFirstLine = true
            }
            "up" -> {
                // .up as property access (e.g., note.up)
                if (targetInfo.isHierarchy) {
                    // Chained: .up.up or similar
                    val newPath = extendHierarchyPath(targetInfo.hierarchyPath!!, 1)
                    ctx.hierarchyAccesses.add(HierarchyAccessPattern(newPath, null))
                } else if (targetInfo.isSelf) {
                    // Direct: .up
                    ctx.hierarchyAccesses.add(HierarchyAccessPattern(HierarchyPath.Up, null))
                }
            }
            "root" -> {
                // .root as property access
                if (targetInfo.isSelf || targetInfo.isHierarchy) {
                    ctx.hierarchyAccesses.add(HierarchyAccessPattern(HierarchyPath.Root, null))
                }
            }
        }
    }

    private fun analyzeMethodCall(expr: MethodCall, ctx: AnalysisContext) {
        // Analyze target
        val targetInfo = analyzeTarget(expr.target, ctx)

        // Check for hierarchy methods
        when (expr.methodName) {
            "up" -> {
                // .up() or .up(n)
                val levels = extractLevels(expr.args)
                val path = if (levels == 1) HierarchyPath.Up else HierarchyPath.UpN(levels)

                if (targetInfo.isHierarchy) {
                    // Chained hierarchy: .up.up(2) etc.
                    val combinedPath = extendHierarchyPath(targetInfo.hierarchyPath!!, levels)
                    ctx.hierarchyAccesses.add(HierarchyAccessPattern(combinedPath, null))
                } else if (targetInfo.isSelf) {
                    ctx.hierarchyAccesses.add(HierarchyAccessPattern(path, null))
                }
            }
        }

        // Analyze arguments
        for (arg in expr.args) {
            analyzeExpression(arg, ctx)
        }
        for (namedArg in expr.namedArgs) {
            analyzeExpression(namedArg.value, ctx)
        }
    }

    private fun analyzeCallExpr(expr: CallExpr, ctx: AnalysisContext) {
        when (expr.name) {
            "find" -> {
                // find() creates note existence dependency
                ctx.dependsOnNoteExistence = true

                // Check for path: argument
                val pathArg = expr.namedArgs.find { it.name == "path" }
                if (pathArg != null) {
                    ctx.dependsOnPath = true
                    analyzeExpression(pathArg.value, ctx)
                }

                // Check for name: argument
                // This creates a dependency on ALL note names because find() iterates
                // through all notes to check which ones match the name criterion
                val nameArg = expr.namedArgs.find { it.name == "name" }
                if (nameArg != null) {
                    ctx.accessesFirstLine = true
                    ctx.dependsOnAllNames = true  // Global dependency on all note names
                    analyzeExpression(nameArg.value, ctx)
                }

                // Check for where: argument (lambda predicate)
                val whereArg = expr.namedArgs.find { it.name == "where" }
                if (whereArg != null) {
                    analyzeExpression(whereArg.value, ctx)
                }
            }

            "view" -> {
                // view() inherits dependencies from its input
                // The actual transitive dependencies are handled at runtime
                for (arg in expr.args) {
                    analyzeExpression(arg, ctx)
                }
            }

            else -> {
                // Analyze all arguments for any function
                for (arg in expr.args) {
                    analyzeExpression(arg, ctx)
                }
                for (namedArg in expr.namedArgs) {
                    analyzeExpression(namedArg.value, ctx)
                }
            }
        }
    }

    /**
     * Info about a property access target.
     */
    private data class TargetInfo(
        val isSelf: Boolean,
        val isHierarchy: Boolean,
        val hierarchyPath: HierarchyPath?
    )

    /**
     * Analyze the target of a property access or method call.
     * Also updates context with any dependencies found in the target.
     */
    private fun analyzeTarget(target: Expression, ctx: AnalysisContext): TargetInfo {
        return when (target) {
            is CurrentNoteRef -> {
                TargetInfo(isSelf = true, isHierarchy = false, hierarchyPath = null)
            }

            is PropertyAccess -> {
                val parentInfo = analyzeTarget(target.target, ctx)

                when (target.property) {
                    "up" -> {
                        if (parentInfo.isSelf) {
                            TargetInfo(
                                isSelf = false,
                                isHierarchy = true,
                                hierarchyPath = HierarchyPath.Up
                            )
                        } else if (parentInfo.isHierarchy) {
                            TargetInfo(
                                isSelf = false,
                                isHierarchy = true,
                                hierarchyPath = extendHierarchyPath(parentInfo.hierarchyPath!!, 1)
                            )
                        } else {
                            TargetInfo(isSelf = false, isHierarchy = false, hierarchyPath = null)
                        }
                    }
                    "root" -> {
                        if (parentInfo.isSelf || parentInfo.isHierarchy) {
                            TargetInfo(
                                isSelf = false,
                                isHierarchy = true,
                                hierarchyPath = HierarchyPath.Root
                            )
                        } else {
                            TargetInfo(isSelf = false, isHierarchy = false, hierarchyPath = null)
                        }
                    }
                    else -> {
                        // Other property - record its dependency and return non-hierarchy
                        analyzePropertyAccess(
                            PropertyAccess(target.target, target.property, target.position),
                            ctx
                        )
                        TargetInfo(isSelf = false, isHierarchy = false, hierarchyPath = null)
                    }
                }
            }

            is MethodCall -> {
                val parentInfo = analyzeTarget(target.target, ctx)

                if (target.methodName == "up") {
                    val levels = extractLevels(target.args)
                    if (parentInfo.isSelf) {
                        TargetInfo(
                            isSelf = false,
                            isHierarchy = true,
                            hierarchyPath = if (levels == 1) HierarchyPath.Up else HierarchyPath.UpN(levels)
                        )
                    } else if (parentInfo.isHierarchy) {
                        TargetInfo(
                            isSelf = false,
                            isHierarchy = true,
                            hierarchyPath = extendHierarchyPath(parentInfo.hierarchyPath!!, levels)
                        )
                    } else {
                        TargetInfo(isSelf = false, isHierarchy = false, hierarchyPath = null)
                    }
                } else {
                    // Analyze the method call
                    analyzeMethodCall(target, ctx)
                    TargetInfo(isSelf = false, isHierarchy = false, hierarchyPath = null)
                }
            }

            else -> {
                // Variable reference, function call result, etc.
                analyzeExpression(target, ctx)
                TargetInfo(isSelf = false, isHierarchy = false, hierarchyPath = null)
            }
        }
    }

    /**
     * Extract the number of levels from up() arguments.
     * Returns 1 if no argument provided.
     */
    private fun extractLevels(args: List<Expression>): Int {
        if (args.isEmpty()) return 1
        val firstArg = args.first()
        return if (firstArg is NumberLiteral) {
            firstArg.value.toInt()
        } else {
            1
        }
    }

    /**
     * Extend a hierarchy path by additional levels.
     */
    private fun extendHierarchyPath(current: HierarchyPath, additionalLevels: Int): HierarchyPath {
        return when (current) {
            is HierarchyPath.Up -> HierarchyPath.UpN(1 + additionalLevels)
            is HierarchyPath.UpN -> HierarchyPath.UpN(current.levels + additionalLevels)
            is HierarchyPath.Root -> HierarchyPath.Root // Root is already the top
        }
    }
}

/**
 * Result of analyzing a directive AST for dependencies.
 *
 * This represents the static analysis result. At runtime, additional
 * information (like specific note IDs and content hashes) is captured
 * when the directive is executed and cached.
 */
data class DirectiveAnalysis(
    /** Depends on note paths */
    val dependsOnPath: Boolean,

    /** Depends on modified timestamps */
    val dependsOnModified: Boolean,

    /** Depends on created timestamps */
    val dependsOnCreated: Boolean,

    /** Whether find() is used (implies note existence dependency) */
    val dependsOnNoteExistence: Boolean,

    /** Whether find(name: ...) is used (depends on ALL note names) */
    val dependsOnAllNames: Boolean,

    /** Whether first line (name) content is accessed */
    val accessesFirstLine: Boolean,

    /** Whether non-first-line content is accessed */
    val accessesNonFirstLine: Boolean,

    /** Whether this directive contains mutations (assignments like .name: "x") */
    val isMutating: Boolean,

    /** Hierarchy access patterns detected (.up, .root with field access) */
    val hierarchyAccesses: List<HierarchyAccessPattern>
) {
    /**
     * Convert to DirectiveDependencies with empty per-note data.
     * Per-note content hashes and hierarchy resolutions are filled in at runtime.
     */
    fun toPartialDependencies(): DirectiveDependencies = DirectiveDependencies(
        firstLineNotes = emptySet(),      // Filled at runtime
        nonFirstLineNotes = emptySet(),   // Filled at runtime
        dependsOnPath = dependsOnPath,
        dependsOnModified = dependsOnModified,
        dependsOnCreated = dependsOnCreated,
        dependsOnNoteExistence = dependsOnNoteExistence,
        dependsOnAllNames = dependsOnAllNames,
        hierarchyDeps = emptyList()       // Filled at runtime with resolved IDs
    )

    companion object {
        /** Analysis result for a directive with no dependencies */
        val EMPTY = DirectiveAnalysis(
            dependsOnPath = false,
            dependsOnModified = false,
            dependsOnCreated = false,
            dependsOnNoteExistence = false,
            dependsOnAllNames = false,
            accessesFirstLine = false,
            accessesNonFirstLine = false,
            isMutating = false,
            hierarchyAccesses = emptyList()
        )
    }
}

/**
 * A pattern of hierarchy access detected in the AST.
 * At runtime, this is used to create actual HierarchyDependency with resolved note IDs.
 */
data class HierarchyAccessPattern(
    /** The hierarchy navigation path */
    val path: HierarchyPath,
    /** Which field was accessed (null = the note itself was used) */
    val field: NoteField?
)
