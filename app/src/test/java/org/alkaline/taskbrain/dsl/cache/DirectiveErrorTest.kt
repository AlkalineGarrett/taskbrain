package org.alkaline.taskbrain.dsl.cache

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.FileNotFoundException

/**
 * Tests for DirectiveError classification.
 * Phase 4: Error classification for caching.
 */
class DirectiveErrorTest {

    // region Deterministic Error Types

    @Test
    fun `SyntaxError is deterministic`() {
        val error = SyntaxError("Unexpected token")
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `TypeError is deterministic`() {
        val error = TypeError("Expected number, got string")
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `ArgumentError is deterministic`() {
        val error = ArgumentError("Missing required argument 'path'")
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `FieldAccessError is deterministic`() {
        val error = FieldAccessError("Unknown property 'foo' on note")
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `ValidationError is deterministic`() {
        val error = ValidationError("Bare time value requires once[] or refresh[]")
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `UnknownIdentifierError is deterministic`() {
        val error = UnknownIdentifierError("Unknown function or variable 'xyz'")
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `CircularDependencyError is deterministic`() {
        val error = CircularDependencyError("Circular view dependency: A -> B -> A")
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `ArithmeticError is deterministic`() {
        val error = ArithmeticError("Division by zero")
        assertTrue(error.isDeterministic)
    }

    // endregion

    // region Non-Deterministic Error Types

    @Test
    fun `NetworkError is non-deterministic`() {
        val error = NetworkError("Connection refused")
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `TimeoutError is non-deterministic`() {
        val error = TimeoutError("Request timed out")
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `ResourceUnavailableError is non-deterministic`() {
        val error = ResourceUnavailableError("Note not found")
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `PermissionError is non-deterministic`() {
        val error = PermissionError("Access denied")
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `ExternalServiceError is non-deterministic`() {
        val error = ExternalServiceError("Firebase unavailable")
        assertFalse(error.isDeterministic)
    }

    // endregion

    // region Error Description

    @Test
    fun `describe includes position when provided`() {
        val error = SyntaxError("Unexpected token", position = 42)
        assertEquals("Unexpected token at position 42", error.describe())
    }

    @Test
    fun `describe works without position`() {
        val error = SyntaxError("Unexpected token")
        assertEquals("Unexpected token", error.describe())
    }

    // endregion

    // region DirectiveErrorFactory - Exception Conversion

    @Test
    fun `fromException converts UnknownHostException to NetworkError`() {
        val exception = UnknownHostException("example.com")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is NetworkError)
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `fromException converts ConnectException to NetworkError`() {
        val exception = ConnectException("Connection refused")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is NetworkError)
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `fromException converts SocketTimeoutException to TimeoutError`() {
        val exception = SocketTimeoutException("Read timed out")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is TimeoutError)
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `fromException converts FileNotFoundException to ResourceUnavailableError`() {
        val exception = FileNotFoundException("file.txt")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is ResourceUnavailableError)
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `fromException converts IllegalArgumentException to ArgumentError`() {
        val exception = IllegalArgumentException("Invalid argument")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is ArgumentError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromException converts ArithmeticException to ArithmeticError`() {
        val exception = ArithmeticException("/ by zero")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is ArithmeticError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromException converts SecurityException to PermissionError`() {
        val exception = SecurityException("Access denied")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is PermissionError)
        assertFalse(error.isDeterministic)
    }

    // endregion

    // region DirectiveErrorFactory - Message Analysis

    @Test
    fun `fromException analyzes syntax keywords in message`() {
        val exception = RuntimeException("Syntax error: unexpected token")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is SyntaxError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromException analyzes type keywords in message`() {
        val exception = RuntimeException("'foo' argument must be a string, got number")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is TypeError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromException analyzes missing argument keywords`() {
        val exception = RuntimeException("'func' requires 2 arguments")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is ArgumentError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromException analyzes circular dependency keywords`() {
        val exception = RuntimeException("Circular view dependency detected")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is CircularDependencyError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromException analyzes network keywords`() {
        val exception = RuntimeException("Network connection failed")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is NetworkError)
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `fromException analyzes timeout keywords`() {
        val exception = RuntimeException("Request timeout after 30s")
        val error = DirectiveErrorFactory.fromException(exception)

        assertTrue(error is TimeoutError)
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `fromException defaults to TypeError for unknown errors`() {
        val exception = RuntimeException("Something went wrong")
        val error = DirectiveErrorFactory.fromException(exception)

        // Defaults to deterministic (conservative)
        assertTrue(error is TypeError)
        assertTrue(error.isDeterministic)
    }

    // endregion

    // region DirectiveErrorFactory - Execution Exception Conversion

    @Test
    fun `fromExecutionException handles unknown function`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "Unknown function or variable 'xyz'"
        )

        assertTrue(error is UnknownIdentifierError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromExecutionException handles type mismatch`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "'add' first argument must be a number, got string"
        )

        assertTrue(error is TypeError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromExecutionException handles missing argument`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "'func' requires 2 arguments, got 1"
        )

        assertTrue(error is ArgumentError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromExecutionException handles unknown property`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "Unknown property 'foo' on note"
        )

        assertTrue(error is FieldAccessError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromExecutionException handles circular dependency`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "Circular view dependency: A -> B -> A"
        )

        assertTrue(error is CircularDependencyError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromExecutionException handles division by zero`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "Division by zero"
        )

        assertTrue(error is ArithmeticError)
        assertTrue(error.isDeterministic)
    }

    @Test
    fun `fromExecutionException handles note not found`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "Note not found: abc123"
        )

        assertTrue(error is ResourceUnavailableError)
        assertFalse(error.isDeterministic)
    }

    @Test
    fun `fromExecutionException handles creation failure`() {
        val error = DirectiveErrorFactory.fromExecutionException(
            "Failed to create note: Network error"
        )

        assertTrue(error is ExternalServiceError)
        assertFalse(error.isDeterministic)
    }

    // endregion

    // region Parse Exception Conversion

    @Test
    fun `fromParseException creates SyntaxError`() {
        val error = DirectiveErrorFactory.fromParseException("Expected ']'", position = 10)

        assertTrue(error.isDeterministic)
        assertEquals(10, error.position)
    }

    // endregion
}
