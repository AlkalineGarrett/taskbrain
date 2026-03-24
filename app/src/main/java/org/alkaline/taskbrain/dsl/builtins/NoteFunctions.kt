package org.alkaline.taskbrain.dsl.builtins

import kotlinx.coroutines.runBlocking
import org.alkaline.taskbrain.dsl.directives.DirectiveFinder
import org.alkaline.taskbrain.dsl.runtime.Arguments
import org.alkaline.taskbrain.dsl.runtime.BooleanVal
import org.alkaline.taskbrain.dsl.runtime.BuiltinFunction
import org.alkaline.taskbrain.dsl.runtime.BuiltinRegistry
import org.alkaline.taskbrain.dsl.runtime.DslValue
import org.alkaline.taskbrain.dsl.runtime.Environment
import org.alkaline.taskbrain.dsl.runtime.ExecutionException
import org.alkaline.taskbrain.dsl.runtime.LambdaVal
import org.alkaline.taskbrain.dsl.runtime.ListVal
import org.alkaline.taskbrain.dsl.runtime.NoteOperationException
import org.alkaline.taskbrain.dsl.runtime.NoteVal
import org.alkaline.taskbrain.dsl.runtime.PatternVal
import org.alkaline.taskbrain.dsl.runtime.StringVal
import org.alkaline.taskbrain.dsl.runtime.values.ViewVal

/**
 * Note-related builtin functions.
 *
 * Milestone 5: find(path: pattern, name: pattern)
 * Milestone 7: new(path:, content:), maybe_new(path:, maybe_content:)
 * Milestone 10: view(list) for inline note content display
 */
object NoteFunctions {

    fun register(registry: BuiltinRegistry) {
        registry.register(findFunction)
        registry.register(newFunction)
        registry.register(maybeNewFunction)
        registry.register(viewFunction)
    }

    /**
     * find(path: ..., name: ..., where: ...) - Search for notes matching criteria.
     *
     * Parameters:
     * - path: A string (exact match) or pattern to match against note paths
     * - name: A string (exact match) or pattern to match against note names (first line of content)
     * - where: A lambda predicate for filtering (takes note as 'i', returns boolean)
     *
     * All parameters are optional. Multiple parameters are combined with AND logic.
     *
     * Returns: A list of matching notes (empty list if none found)
     *
     * Examples:
     *   [find(path: "2026-01-15")]                              - Exact path match
     *   [find(path: pattern(digit*4 "-" digit*2 "-" digit*2))]  - Pattern match on path
     *   [find(name: "Shopping List")]                           - Exact name match
     *   [find(name: pattern("Meeting" any*(0..)))]              - Names starting with "Meeting"
     *   [find(where: lambda[matches(i.path, pattern(digit*4))])] - Lambda filter
     *   [find(path: pattern("journal/" any*(1..)), name: pattern(digit*4 "-" digit*2 "-" digit*2))]
     *
     * Note: The notes must be pre-loaded and passed to the Environment before execution.
     * If no notes are available in the environment, returns an empty list.
     *
     * Milestone 8: Added where: lambda support.
     */
    private val findFunction = BuiltinFunction(
        name = "find",
        isDynamic = false  // Results are deterministic based on current note content
    ) { args, env ->
        val pathArg = args["path"]
        val nameArg = args["name"]
        val whereArg = args.getLambda("where")

        // Get the list of notes from the environment
        val notes = env.getNotes()
        if (notes == null || notes.isEmpty()) {
            // No notes available - return empty list
            return@BuiltinFunction ListVal(emptyList())
        }

        // Get current note ID to exclude from results (Phase 10)
        val currentNoteId = env.getCurrentNoteRaw()?.id

        // Filter notes based on path, name, and where arguments (AND logic)
        // Also exclude the current note to prevent self-reference
        val filtered = notes.filter { note ->
            // Exclude soft-deleted notes from results
            if (note.state == "deleted") return@filter false
            // Exclude current note from results
            if (currentNoteId != null && note.id == currentNoteId) {
                return@filter false
            }
            val pathMatches = matchesFilter(pathArg, note.path, "path")
            val nameMatches = matchesFilter(nameArg, getNoteName(note.content), "name")
            val whereMatches = evaluateWhereLambda(whereArg, note, env)
            pathMatches && nameMatches && whereMatches
        }

        ListVal(filtered.map { NoteVal(it) })
    }

    /**
     * Evaluate a where lambda against a note.
     * Returns true if no lambda provided, or the boolean result of the lambda.
     *
     * Milestone 8.
     */
    private fun evaluateWhereLambda(
        lambda: LambdaVal?,
        note: org.alkaline.taskbrain.data.Note,
        env: org.alkaline.taskbrain.dsl.runtime.Environment
    ): Boolean {
        if (lambda == null) return true

        val executor = env.getExecutor()
            ?: throw ExecutionException("'find' where: requires an executor in the environment")

        val result = executor.invokeLambda(lambda, listOf(NoteVal(note)))
        return (result as? BooleanVal)?.value
            ?: throw ExecutionException("'find' where: lambda must return a boolean, got ${result.typeName}")
    }

    /**
     * Get the name of a note (first line of content).
     */
    private fun getNoteName(content: String): String {
        return content.lines().firstOrNull() ?: ""
    }

    /**
     * Check if a value matches a filter (string or pattern).
     * Returns true if filter is null (no filter).
     */
    private fun matchesFilter(filter: DslValue?, value: String, paramName: String): Boolean {
        return when (filter) {
            null -> true  // No filter, include all
            is StringVal -> value == filter.value  // Exact match
            is PatternVal -> filter.matches(value)  // Pattern match
            else -> throw ExecutionException(
                "'find' $paramName argument must be a string or pattern, got ${filter.typeName}"
            )
        }
    }

    /**
     * new(path:, content:) - Create a new note at the specified path.
     *
     * Parameters:
     * - path: String - The path for the new note (required)
     * - content: String - Initial content (optional, defaults to "")
     *
     * Returns: The created NoteVal
     * Throws: Error if a note already exists at the path
     *
     * Example:
     *   [new(path: "journal/2026-01-25", content: "# Today's Journal")]
     *
     * Milestone 7.
     */
    private val newFunction = BuiltinFunction(
        name = "new",
        isDynamic = true  // Creates a new note each time
    ) { args, env ->
        val pathArg = args["path"]
            ?: throw ExecutionException("'new' requires a 'path' argument")
        val path = (pathArg as? StringVal)?.value
            ?: throw ExecutionException("'new' path argument must be a string, got ${pathArg.typeName}")

        val contentArg = args["content"]
        val content = when (contentArg) {
            null -> ""
            is StringVal -> contentArg.value
            else -> throw ExecutionException("'new' content argument must be a string, got ${contentArg.typeName}")
        }

        val ops = env.getNoteOperations()
            ?: throw ExecutionException("'new' requires note operations to be available")

        // Check if note already exists
        val exists = runBlocking { ops.noteExistsAtPath(path) }
        if (exists) {
            throw ExecutionException("Note already exists at path: $path")
        }

        // Create the note
        try {
            val note = runBlocking { ops.createNote(path, content) }
            NoteVal(note)
        } catch (e: NoteOperationException) {
            throw ExecutionException("Failed to create note: ${e.message}")
        }
    }

    /**
     * maybe_new(path:, maybe_content:) - Idempotently ensure a note exists at the path.
     *
     * Parameters:
     * - path: String - The path for the note (required)
     * - maybe_content: String - Content to use if note is created (optional, defaults to "")
     *
     * Returns: The existing or newly created NoteVal
     *
     * Behavior:
     * - If a note exists at the path, returns it (ignores maybe_content)
     * - If no note exists, creates one with maybe_content
     *
     * Example:
     *   [maybe_new(path: date, maybe_content: string("# " date))]
     *
     * Milestone 7.
     */
    private val maybeNewFunction = BuiltinFunction(
        name = "maybe_new",
        isDynamic = true  // May create a new note
    ) { args, env ->
        val pathArg = args["path"]
            ?: throw ExecutionException("'maybe_new' requires a 'path' argument")
        val path = (pathArg as? StringVal)?.value
            ?: throw ExecutionException("'maybe_new' path argument must be a string, got ${pathArg.typeName}")

        val maybeContentArg = args["maybe_content"]
        val maybeContent = when (maybeContentArg) {
            null -> ""
            is StringVal -> maybeContentArg.value
            else -> throw ExecutionException("'maybe_new' maybe_content argument must be a string, got ${maybeContentArg.typeName}")
        }

        val ops = env.getNoteOperations()
            ?: throw ExecutionException("'maybe_new' requires note operations to be available")

        // Check for existing note first
        val existing = runBlocking { ops.findByPath(path) }
        if (existing != null) {
            return@BuiltinFunction NoteVal(existing)
        }

        // Create a new note
        try {
            val note = runBlocking { ops.createNote(path, maybeContent) }
            NoteVal(note)
        } catch (e: NoteOperationException) {
            throw ExecutionException("Failed to create note: ${e.message}")
        }
    }

    /**
     * view(list) - Dynamically fetch and inline content from notes.
     *
     * Parameters:
     * - First positional argument: A list of notes to display (from find() or sort())
     *
     * Returns: A ViewVal containing the notes to be rendered inline
     *
     * Display: Notes' content is inlined as raw text, separated by dividers.
     * No path headers are shown because the first line of each note serves as its title.
     *
     * Recursion: Viewed notes' directives also execute, including nested view directives.
     *
     * Circular dependency: If a view creates a cycle (A views B views A), an error is shown.
     *
     * Examples:
     *   [view find(path: "inbox")]
     *   [view sort(find(path: pattern(digit*4 "-" digit*2 "-" digit*2)), key: lambda[parse_date(i.path)], order: descending)]
     *
     * Milestone 10.
     */
    private val viewFunction = BuiltinFunction(
        name = "view",
        isDynamic = false  // Results are deterministic based on current note content
    ) { args, env ->
        // Get the list argument
        val listArg = args[0]
            ?: throw ExecutionException("'view' requires a list of notes as argument")

        val notesList = when (listArg) {
            is ListVal -> listArg
            else -> throw ExecutionException("'view' argument must be a list, got ${listArg.typeName}")
        }

        // Extract notes from the list, validating each item is a NoteVal
        val allNotes = notesList.items.mapIndexed { index, item ->
            when (item) {
                is NoteVal -> item.note
                else -> throw ExecutionException(
                    "'view' list item at index $index must be a note, got ${item.typeName}"
                )
            }
        }

        // Filter out the current note to prevent self-viewing
        val currentNoteId = env.getCurrentNoteRaw()?.id
        val notes = if (currentNoteId != null) {
            allNotes.filter { it.id != currentNoteId }
        } else {
            allNotes
        }

        // Check for circular dependencies before processing
        for (note in notes) {
            if (env.isInViewStack(note.id)) {
                val currentPath = env.getViewStackPath()
                val cycleInfo = if (currentPath.isEmpty()) {
                    note.id
                } else {
                    "$currentPath → ${note.id}"
                }
                throw ExecutionException("Circular view dependency: $cycleInfo")
            }
        }

        // Render each note's content with directives evaluated
        // Pass the note itself so [.] references the viewed note, not the parent
        val renderedContents = notes.map { note ->
            renderNoteContent(note, env)
        }

        ViewVal(notes, renderedContents)
    }

    /**
     * Render note content by evaluating any directives it contains.
     * Sets the viewed note as the current note so [.] references work correctly.
     * Pushes the note onto the view stack to detect circular dependencies.
     *
     * Phase 1 (Caching Audit): Uses CachedDirectiveExecutor when available
     * to enable transitive dependency tracking for nested directives.
     */
    private fun renderNoteContent(viewedNote: org.alkaline.taskbrain.data.Note, env: Environment): String {
        val content = viewedNote.content

        // Find all directives in the content
        val directives = DirectiveFinder.findDirectives(content)
        if (directives.isEmpty()) {
            return content
        }

        // Push this note onto the view stack for circular dependency detection
        val viewStack = env.getViewStack() + viewedNote.id

        // Get the cached executor if available (Phase 1)
        val cachedExecutor = env.getCachedExecutor()
        val allNotes = env.getNotes() ?: emptyList()

        // Build rendered content by replacing directive source with results
        val result = StringBuilder()
        var lastEnd = 0

        for (directive in directives) {
            // Append text before this directive
            if (directive.startOffset > lastEnd) {
                result.append(content.substring(lastEnd, directive.startOffset))
            }

            // Execute the directive with the viewed note as the current note
            // This ensures [.] references the viewed note, not the parent note
            val displayValue = if (cachedExecutor != null) {
                // Phase 1: Use cached executor for transitive dependency tracking
                val cachedResult = cachedExecutor.executeCached(
                    sourceText = directive.sourceText,
                    notes = allNotes,
                    currentNote = viewedNote,
                    noteOperations = env.getNoteOperations(),
                    viewStack = viewStack
                )
                if (cachedResult.errorMessage != null) {
                    // On error, keep the source text
                    directive.sourceText
                } else {
                    cachedResult.displayValue ?: directive.sourceText
                }
            } else {
                // Fallback: Use DirectiveFinder directly (for tests without caching)
                val execResult = DirectiveFinder.executeDirective(
                    directive.sourceText,
                    allNotes,
                    viewedNote,
                    env.getNoteOperations(),
                    viewStack
                )
                if (execResult.result.error != null) {
                    directive.sourceText
                } else {
                    execResult.result.toValue()?.toDisplayString() ?: directive.sourceText
                }
            }

            result.append(displayValue)
            lastEnd = directive.endOffset
        }

        // Append remaining text after last directive
        if (lastEnd < content.length) {
            result.append(content.substring(lastEnd))
        }

        return result.toString()
    }
}
