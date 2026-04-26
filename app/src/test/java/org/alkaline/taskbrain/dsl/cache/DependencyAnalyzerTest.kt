package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.dsl.language.Lexer
import org.alkaline.taskbrain.dsl.language.Parser
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DependencyAnalyzer.
 * Phase 2: AST analysis for dependency detection.
 */
class DependencyAnalyzerTest {

    // region Field access detection - path

    @Test
    fun `detects path dependency from property access`() {
        val analysis = analyze("[.path]")
        assertTrue(analysis.dependsOnPath)
    }

    @Test
    fun `detects path dependency from find path argument`() {
        val analysis = analyze("[find(path: \"inbox\")]")
        assertTrue(analysis.dependsOnPath)
    }

    @Test
    fun `no path dependency for unrelated code`() {
        val analysis = analyze("[42]")
        assertFalse(analysis.dependsOnPath)
    }

    // endregion

    // region Field access detection - modified

    @Test
    fun `detects modified dependency from property access`() {
        val analysis = analyze("[.modified]")
        assertTrue(analysis.dependsOnModified)
    }

    @Test
    fun `detects modified in where clause`() {
        val analysis = analyze("[find(where: [i.modified.gt(\"2026-01-01\")])]")
        assertTrue(analysis.dependsOnModified)
    }

    // endregion

    // region Field access detection - created

    @Test
    fun `detects created dependency from property access`() {
        val analysis = analyze("[.created]")
        assertTrue(analysis.dependsOnCreated)
    }

    // endregion

    // region Content access detection - name

    @Test
    fun `detects first line access from name property`() {
        val analysis = analyze("[.name]")
        assertTrue(analysis.accessesFirstLine)
        assertFalse(analysis.accessesNonFirstLine)
    }

    @Test
    fun `detects first line access from find name argument`() {
        val analysis = analyze("[find(name: \"Shopping\")]")
        assertTrue(analysis.accessesFirstLine)
    }

    // endregion

    // region Content access detection - content

    @Test
    fun `detects full content access from content property`() {
        val analysis = analyze("[.content]")
        assertTrue(analysis.accessesFirstLine)
        assertTrue(analysis.accessesNonFirstLine)
    }

    // endregion

    // region find() detection

    @Test
    fun `detects note existence dependency from find`() {
        val analysis = analyze("[find(path: \"inbox\")]")
        assertTrue(analysis.dependsOnNoteExistence)
    }

    @Test
    fun `detects find with where clause`() {
        val analysis = analyze("[find(where: [i.path.startsWith(\"inbox\")])]")
        assertTrue(analysis.dependsOnNoteExistence)
        assertTrue(analysis.dependsOnPath) // from i.path access
    }

    @Test
    fun `no note existence dependency without find`() {
        val analysis = analyze("[.path]")
        assertFalse(analysis.dependsOnNoteExistence)
    }

    // endregion

    // region Hierarchy access detection - .up

    @Test
    fun `detects up hierarchy access`() {
        val analysis = analyze("[.up]")
        assertEquals(1, analysis.hierarchyAccesses.size)
        assertEquals(HierarchyPath.Up, analysis.hierarchyAccesses[0].path)
        assertNull(analysis.hierarchyAccesses[0].field)
    }

    @Test
    fun `detects up method call`() {
        val analysis = analyze("[.up()]")
        assertEquals(1, analysis.hierarchyAccesses.size)
        assertEquals(HierarchyPath.Up, analysis.hierarchyAccesses[0].path)
    }

    @Test
    fun `detects up with levels`() {
        val analysis = analyze("[.up(2)]")
        assertEquals(1, analysis.hierarchyAccesses.size)
        val path = analysis.hierarchyAccesses[0].path
        assertTrue(path is HierarchyPath.UpN)
        assertEquals(2, (path as HierarchyPath.UpN).levels)
    }

    @Test
    fun `detects up with field access`() {
        val analysis = analyze("[.up.path]")
        assertTrue(analysis.dependsOnPath)
        assertEquals(1, analysis.hierarchyAccesses.size)
        assertEquals(HierarchyPath.Up, analysis.hierarchyAccesses[0].path)
        assertEquals(NoteField.PATH, analysis.hierarchyAccesses[0].field)
    }

    @Test
    fun `detects up with name access`() {
        val analysis = analyze("[.up.name]")
        assertTrue(analysis.accessesFirstLine)
        assertEquals(1, analysis.hierarchyAccesses.size)
        assertEquals(NoteField.NAME, analysis.hierarchyAccesses[0].field)
    }

    @Test
    fun `detects chained up access`() {
        val analysis = analyze("[.up.up]")
        // Should have one access with UpN(2)
        assertEquals(1, analysis.hierarchyAccesses.size)
        val path = analysis.hierarchyAccesses[0].path
        assertTrue(path is HierarchyPath.UpN)
        assertEquals(2, (path as HierarchyPath.UpN).levels)
    }

    // endregion

    // region Hierarchy access detection - .root

    @Test
    fun `detects root hierarchy access`() {
        val analysis = analyze("[.root]")
        assertEquals(1, analysis.hierarchyAccesses.size)
        assertEquals(HierarchyPath.Root, analysis.hierarchyAccesses[0].path)
    }

    @Test
    fun `detects root with field access`() {
        val analysis = analyze("[.root.modified]")
        assertTrue(analysis.dependsOnModified)
        assertEquals(1, analysis.hierarchyAccesses.size)
        assertEquals(HierarchyPath.Root, analysis.hierarchyAccesses[0].path)
        assertEquals(NoteField.MODIFIED, analysis.hierarchyAccesses[0].field)
    }

    // endregion

    // region Statement lists

    @Test
    fun `analyzes all statements in statement list`() {
        val analysis = analyze("[a: .path; b: .modified; a]")
        assertTrue(analysis.dependsOnPath)
        assertTrue(analysis.dependsOnModified)
    }

    // endregion

    // region Lambda analysis

    @Test
    fun `analyzes lambda body`() {
        val analysis = analyze("[[i.path]]")
        assertTrue(analysis.dependsOnPath)
    }

    @Test
    fun `analyzes lambda invocation`() {
        val analysis = analyze("[f: [i.modified]; f(.)]")
        assertTrue(analysis.dependsOnModified)
    }

    // endregion

    // region Execution blocks

    @Test
    fun `analyzes once block body`() {
        val analysis = analyze("[once[.path]]")
        assertTrue(analysis.dependsOnPath)
    }

    @Test
    fun `analyzes refresh block body`() {
        val analysis = analyze("[refresh[.modified]]")
        assertTrue(analysis.dependsOnModified)
    }

    // endregion

    // region toPartialDependencies

    @Test
    fun `toPartialDependencies converts analysis to dependencies`() {
        val analysis = analyze("[find(path: \"inbox\"); .up.modified]")
        val deps = analysis.toPartialDependencies()

        assertTrue(deps.dependsOnPath)
        assertTrue(deps.dependsOnModified)
        assertTrue(deps.dependsOnNoteExistence)
        assertTrue(deps.firstLineNotes.isEmpty()) // Filled at runtime
        assertTrue(deps.hierarchyDeps.isEmpty())  // Filled at runtime
    }

    // endregion

    // region Complex expressions

    @Test
    fun `complex find with multiple dependencies`() {
        val analysis = analyze(
            "[find(path: pattern(digit*4), where: [i.modified.gt(\"2026-01-01\")])]"
        )
        assertTrue(analysis.dependsOnNoteExistence)
        assertTrue(analysis.dependsOnPath)
        assertTrue(analysis.dependsOnModified)
    }

    @Test
    fun `view inherits dependencies from input`() {
        val analysis = analyze("[view find(path: \"inbox\")]")
        assertTrue(analysis.dependsOnNoteExistence)
        assertTrue(analysis.dependsOnPath)
    }

    @Test
    fun `method call on find result`() {
        val analysis = analyze("[first(find(path: \"inbox\")).name]")
        assertTrue(analysis.dependsOnNoteExistence)
        assertTrue(analysis.dependsOnPath)
        assertTrue(analysis.accessesFirstLine)
    }

    // endregion

    // region DirectiveAnalysis.EMPTY

    @Test
    fun `EMPTY has no dependencies`() {
        val empty = DirectiveAnalysis.EMPTY
        assertFalse(empty.dependsOnPath)
        assertFalse(empty.dependsOnModified)
        assertFalse(empty.dependsOnCreated)
        assertFalse(empty.dependsOnNoteExistence)
        assertFalse(empty.accessesFirstLine)
        assertFalse(empty.accessesNonFirstLine)
        assertTrue(empty.hierarchyAccesses.isEmpty())
    }

    // endregion

    private fun analyze(code: String): DirectiveAnalysis {
        val tokens = Lexer(code).tokenize()
        val directive = Parser(tokens, code).parseDirective()
        return DependencyAnalyzer.analyze(directive.expression)
    }
}
