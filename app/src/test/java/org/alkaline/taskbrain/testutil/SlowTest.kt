package org.alkaline.taskbrain.testutil

/**
 * JUnit Category marker interface for slow integration tests.
 *
 * Tests marked with @Category(SlowTest::class) will be excluded from the default
 * test run. To run slow tests explicitly:
 *
 *   ./gradlew slowTest
 *
 * Slow tests are appropriate for:
 * - Integration tests that involve complex state management
 * - Tests that simulate real user interactions with timing-sensitive behavior
 * - Tests that verify UI state transitions and potential race conditions
 * - Tests that require significant setup or teardown
 */
interface SlowTest
