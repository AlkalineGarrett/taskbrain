package org.alkaline.taskbrain.dsl.cache

import org.alkaline.taskbrain.data.Note
import org.alkaline.taskbrain.dsl.runtime.values.NumberVal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for transitive dependency collection and merging.
 * Phase 7: Transitive dependency merging.
 */
class TransitiveDependencyTest {

    private lateinit var collector: TransitiveDependencyCollector

    @Before
    fun setUp() {
        collector = TransitiveDependencyCollector()
    }

    // region TransitiveDependencyCollector Basic Tests

    @Test
    fun `collector starts not collecting`() {
        assertFalse(collector.isCollecting())
        assertEquals(0, collector.depth())
    }

    @Test
    fun `startDirective begins collection`() {
        collector.startDirective("hash1")

        assertTrue(collector.isCollecting())
        assertEquals(1, collector.depth())
    }

    @Test
    fun `finishDirective ends collection`() {
        collector.startDirective("hash1")
        collector.finishDirective()

        assertFalse(collector.isCollecting())
        assertEquals(0, collector.depth())
    }

    @Test
    fun `finishDirective returns base dependencies when empty`() {
        val baseDeps = DirectiveDependencies.EMPTY.copy(dependsOnPath = true)
        collector.startDirective("hash1", baseDeps)

        val result = collector.finishDirective()

        assertTrue(result.dependsOnPath)
    }

    @Test
    fun `abortDirective ends collection and returns current dependencies`() {
        collector.startDirective("hash1")
        collector.recordFirstLineAccess("note1")

        val result = collector.abortDirective()

        assertFalse(collector.isCollecting())
        assertTrue(result.firstLineNotes.contains("note1"))
    }

    @Test
    fun `reset clears all state`() {
        collector.startDirective("hash1")
        collector.startDirective("hash2")
        collector.reset()

        assertFalse(collector.isCollecting())
        assertEquals(0, collector.depth())
    }

    // endregion

    // region Content Access Recording

    @Test
    fun `recordFirstLineAccess adds note to firstLineNotes`() {
        collector.startDirective("hash1")
        collector.recordFirstLineAccess("note1")
        collector.recordFirstLineAccess("note2")

        val result = collector.finishDirective()

        assertEquals(setOf("note1", "note2"), result.firstLineNotes)
    }

    @Test
    fun `recordNonFirstLineAccess adds note to nonFirstLineNotes`() {
        collector.startDirective("hash1")
        collector.recordNonFirstLineAccess("note1")

        val result = collector.finishDirective()

        assertEquals(setOf("note1"), result.nonFirstLineNotes)
    }

    @Test
    fun `recordFindUsage sets dependsOnNoteExistence`() {
        collector.startDirective("hash1")
        collector.recordFindUsage()

        val result = collector.finishDirective()

        assertTrue(result.dependsOnNoteExistence)
    }

    @Test
    fun `recordHierarchyDependency adds to hierarchyDeps`() {
        collector.startDirective("hash1")
        val dep = HierarchyDependency(
            path = HierarchyPath.Up,
            resolvedNoteId = "parent1",
            field = NoteField.NAME,
            fieldHash = "abc123"
        )
        collector.recordHierarchyDependency(dep)

        val result = collector.finishDirective()

        assertEquals(1, result.hierarchyDeps.size)
        assertEquals(dep, result.hierarchyDeps[0])
    }

    // endregion

    // region Transitive Dependency Merging

    @Test
    fun `addReferencedDependencies merges with current`() {
        collector.startDirective("hash1", DirectiveDependencies.EMPTY.copy(dependsOnPath = true))

        val referencedDeps = DirectiveDependencies.EMPTY.copy(
            dependsOnModified = true,
            firstLineNotes = setOf("refNote1")
        )
        collector.addReferencedDependencies(referencedDeps)

        val result = collector.finishDirective()

        assertTrue(result.dependsOnPath)
        assertTrue(result.dependsOnModified)
        assertTrue(result.firstLineNotes.contains("refNote1"))
    }

    @Test
    fun `addNestedViewDependencies merges with current`() {
        collector.startDirective("hash1")

        val nestedDeps = DirectiveDependencies.EMPTY.copy(
            dependsOnCreated = true,
            nonFirstLineNotes = setOf("nestedNote1")
        )
        collector.addNestedViewDependencies(nestedDeps)

        val result = collector.finishDirective()

        assertTrue(result.dependsOnCreated)
        assertTrue(result.nonFirstLineNotes.contains("nestedNote1"))
    }

    @Test
    fun `multiple transitive dependencies are all merged`() {
        collector.startDirective("hash1")

        collector.addReferencedDependencies(
            DirectiveDependencies.EMPTY.copy(dependsOnPath = true)
        )
        collector.addReferencedDependencies(
            DirectiveDependencies.EMPTY.copy(dependsOnModified = true)
        )
        collector.addNestedViewDependencies(
            DirectiveDependencies.EMPTY.copy(dependsOnCreated = true)
        )

        val result = collector.finishDirective()

        assertTrue(result.dependsOnPath)
        assertTrue(result.dependsOnModified)
        assertTrue(result.dependsOnCreated)
    }

    // endregion

    // region Nested Directive Collection

    @Test
    fun `nested directives maintain separate contexts`() {
        collector.startDirective("outer")
        collector.recordFirstLineAccess("outerNote")

        collector.startDirective("inner")
        collector.recordFirstLineAccess("innerNote")
        assertEquals(2, collector.depth())

        val innerResult = collector.finishDirective()
        assertEquals(1, collector.depth())

        assertEquals(setOf("innerNote"), innerResult.firstLineNotes)
    }

    @Test
    fun `nested directive dependencies propagate to parent`() {
        collector.startDirective("outer")

        collector.startDirective("inner")
        collector.recordFirstLineAccess("innerNote")
        collector.finishDirective()

        val outerResult = collector.finishDirective()

        // Inner dependencies should propagate to outer
        assertTrue(outerResult.firstLineNotes.contains("innerNote"))
    }

    @Test
    fun `deeply nested directives propagate correctly`() {
        collector.startDirective("level1")
        collector.recordFirstLineAccess("note1")

        collector.startDirective("level2")
        collector.recordFirstLineAccess("note2")

        collector.startDirective("level3")
        collector.recordFirstLineAccess("note3")
        collector.finishDirective()  // level3

        collector.finishDirective()  // level2

        val level1Result = collector.finishDirective()

        // All notes should propagate to level1
        assertEquals(setOf("note1", "note2", "note3"), level1Result.firstLineNotes)
    }

    // endregion

    // region DirectiveDependencyRegistry Tests

    @Test
    fun `registry stores and retrieves dependencies`() {
        val registry = DirectiveDependencyRegistry()
        val deps = DirectiveDependencies.EMPTY.copy(dependsOnPath = true)

        registry.register("hash1", deps)

        assertEquals(deps, registry.get("hash1"))
    }

    @Test
    fun `registry returns null for unknown key`() {
        val registry = DirectiveDependencyRegistry()

        assertNull(registry.get("unknown"))
    }

    @Test
    fun `registry contains checks correctly`() {
        val registry = DirectiveDependencyRegistry()
        registry.register("hash1", DirectiveDependencies.EMPTY)

        assertTrue(registry.contains("hash1"))
        assertFalse(registry.contains("unknown"))
    }

    @Test
    fun `registry remove works`() {
        val registry = DirectiveDependencyRegistry()
        registry.register("hash1", DirectiveDependencies.EMPTY)
        registry.remove("hash1")

        assertFalse(registry.contains("hash1"))
    }

    @Test
    fun `registry clear removes all`() {
        val registry = DirectiveDependencyRegistry()
        registry.register("hash1", DirectiveDependencies.EMPTY)
        registry.register("hash2", DirectiveDependencies.EMPTY)
        registry.clear()

        assertEquals(0, registry.size())
    }

    // endregion

    // region HierarchyDependencyResolver Tests

    private val testNotes = listOf(
        Note(id = "root", content = "Root Note", path = "root"),
        Note(id = "child", content = "Child Note", path = "root/child"),
        Note(id = "grandchild", content = "Grandchild Note", path = "root/child/grandchild")
    )

    @Test
    fun `resolver resolves Up pattern`() {
        val pattern = HierarchyAccessPattern(HierarchyPath.Up, null)
        val currentNote = testNotes.find { it.id == "grandchild" }!!

        val result = HierarchyDependencyResolver.resolvePattern(pattern, currentNote, testNotes)

        assertEquals(HierarchyPath.Up, result.path)
        assertEquals("child", result.resolvedNoteId)
        assertNull(result.field)
        assertNull(result.fieldHash)
    }

    @Test
    fun `resolver resolves UpN pattern`() {
        val pattern = HierarchyAccessPattern(HierarchyPath.UpN(2), null)
        val currentNote = testNotes.find { it.id == "grandchild" }!!

        val result = HierarchyDependencyResolver.resolvePattern(pattern, currentNote, testNotes)

        assertEquals("root", result.resolvedNoteId)
    }

    @Test
    fun `resolver resolves Root pattern`() {
        val pattern = HierarchyAccessPattern(HierarchyPath.Root, null)
        val currentNote = testNotes.find { it.id == "grandchild" }!!

        val result = HierarchyDependencyResolver.resolvePattern(pattern, currentNote, testNotes)

        assertEquals("root", result.resolvedNoteId)
    }

    @Test
    fun `resolver includes field hash when field specified`() {
        val pattern = HierarchyAccessPattern(HierarchyPath.Up, NoteField.NAME)
        val currentNote = testNotes.find { it.id == "grandchild" }!!

        val result = HierarchyDependencyResolver.resolvePattern(pattern, currentNote, testNotes)

        assertEquals(NoteField.NAME, result.field)
        assertNotNull(result.fieldHash)
    }

    @Test
    fun `resolver returns null resolvedNoteId when parent not found`() {
        val pattern = HierarchyAccessPattern(HierarchyPath.Up, null)
        val currentNote = testNotes.find { it.id == "root" }!!

        val result = HierarchyDependencyResolver.resolvePattern(pattern, currentNote, testNotes)

        assertNull(result.resolvedNoteId)
    }

    @Test
    fun `resolvePatterns handles multiple patterns`() {
        val patterns = listOf(
            HierarchyAccessPattern(HierarchyPath.Up, null),
            HierarchyAccessPattern(HierarchyPath.Root, NoteField.PATH)
        )
        val currentNote = testNotes.find { it.id == "grandchild" }!!

        val results = HierarchyDependencyResolver.resolvePatterns(patterns, currentNote, testNotes)

        assertEquals(2, results.size)
        assertEquals("child", results[0].resolvedNoteId)
        assertEquals("root", results[1].resolvedNoteId)
    }

    // endregion

    // region CachedResultBuilder Tests

    @Test
    fun `builder creates metadata hashes for dependencies`() {
        val deps = DirectiveDependencies.EMPTY.copy(
            dependsOnPath = true,
            dependsOnModified = true,
            dependsOnNoteExistence = true
        )
        val builder = CachedResultBuilder(deps, testNotes)

        val hashes = builder.buildMetadataHashes()

        assertNotNull(hashes.pathHash)
        assertNotNull(hashes.modifiedHash)
        assertNotNull(hashes.existenceHash)
        assertNull(hashes.createdHash)
        assertNull(hashes.viewedHash)
    }

    @Test
    fun `builder creates content hashes for accessed notes`() {
        val deps = DirectiveDependencies.EMPTY.copy(
            firstLineNotes = setOf("root", "child"),
            nonFirstLineNotes = setOf("root")
        )
        val builder = CachedResultBuilder(deps, testNotes)

        val hashes = builder.buildContentHashes()

        assertEquals(2, hashes.size)
        assertNotNull(hashes["root"]?.firstLineHash)
        assertNotNull(hashes["root"]?.nonFirstLineHash)
        assertNotNull(hashes["child"]?.firstLineHash)
        assertNull(hashes["child"]?.nonFirstLineHash)
    }

    @Test
    fun `buildSuccess creates complete cached result`() {
        val deps = DirectiveDependencies.EMPTY.copy(
            dependsOnPath = true,
            firstLineNotes = setOf("root")
        )
        val builder = CachedResultBuilder(deps, testNotes)

        val result = builder.buildSuccess(NumberVal(42.0))

        assertTrue(result.isSuccess)
        assertEquals(42.0, (result.result as NumberVal).value, 0.001)
        assertTrue(result.dependencies.dependsOnPath)
        assertNotNull(result.metadataHashes.pathHash)
        assertNotNull(result.noteContentHashes["root"])
    }

    @Test
    fun `buildError creates complete cached error result`() {
        val deps = DirectiveDependencies.EMPTY.copy(dependsOnPath = true)
        val builder = CachedResultBuilder(deps, testNotes)
        val error = SyntaxError("Parse error")

        val result = builder.buildError(error)

        assertTrue(result.isError)
        assertEquals(error, result.error)
        assertTrue(result.dependencies.dependsOnPath)
    }

    // endregion

    // region Integration Scenarios

    @Test
    fun `scenario - view with nested find inherits dependencies`() {
        // Simulates: view(find(path: "inbox")) where find has path dependency
        collector.startDirective("view-directive")

        // find() is called
        val findDeps = DirectiveDependencies.EMPTY.copy(
            dependsOnPath = true,
            dependsOnNoteExistence = true
        )
        collector.addReferencedDependencies(findDeps)

        // Notes are rendered
        collector.recordFirstLineAccess("note1")
        collector.recordFirstLineAccess("note2")

        val result = collector.finishDirective()

        assertTrue(result.dependsOnPath)
        assertTrue(result.dependsOnNoteExistence)
        assertEquals(setOf("note1", "note2"), result.firstLineNotes)
    }

    @Test
    fun `scenario - directive references lambda with dependencies`() {
        // Simulates: directiveA calls lambdaB which has its own dependencies
        collector.startDirective("directiveA")

        // Lambda B is resolved with its dependencies
        val lambdaBDeps = DirectiveDependencies.EMPTY.copy(
            dependsOnModified = true
        )
        collector.addReferencedDependencies(lambdaBDeps)

        val result = collector.finishDirective()

        assertTrue(result.dependsOnModified)
    }

    @Test
    fun `scenario - nested views merge dependencies`() {
        // Simulates: viewA renders noteB which contains viewC
        collector.startDirective("viewA")
        collector.recordFirstLineAccess("noteB")

        // viewC is rendered inside noteB
        collector.startDirective("viewC")
        collector.recordFirstLineAccess("noteD")
        val viewCDeps = collector.finishDirective()

        // viewC deps are added as nested view dependencies
        collector.addNestedViewDependencies(viewCDeps)

        val viewAResult = collector.finishDirective()

        // viewA should have dependencies on both noteB and noteD
        assertEquals(setOf("noteB", "noteD"), viewAResult.firstLineNotes)
    }

    // endregion
}
