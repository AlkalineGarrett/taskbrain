import { describe, it, expect } from 'vitest'
import { classifyError } from '../../../dsl/cache/DirectiveError'

describe('DirectiveError', () => {
  describe('deterministic errors', () => {
    it('classifies unknown function or variable as deterministic', () => {
      const error = classifyError('Unknown function or variable: foo')
      expect(error.kind).toBe('UnknownIdentifierError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies argument error as deterministic', () => {
      const error = classifyError('find() requires 1 argument')
      expect(error.kind).toBe('ArgumentError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies type error (must be a) as deterministic', () => {
      const error = classifyError('Argument must be a number')
      expect(error.kind).toBe('TypeError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies type error (got) as deterministic', () => {
      const error = classifyError('Expected number, got string')
      expect(error.kind).toBe('TypeError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies missing argument as deterministic', () => {
      const error = classifyError('Missing required argument')
      expect(error.kind).toBe('ArgumentError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies unknown property as deterministic', () => {
      const error = classifyError('Unknown property: foo')
      expect(error.kind).toBe('FieldAccessError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies unknown method as deterministic', () => {
      const error = classifyError('Unknown method: bar')
      expect(error.kind).toBe('FieldAccessError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies circular dependency as deterministic', () => {
      const error = classifyError('Circular dependency detected')
      expect(error.kind).toBe('CircularDependencyError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies division by zero as deterministic', () => {
      const error = classifyError('Division by zero')
      expect(error.kind).toBe('ArithmeticError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies modulo by zero as deterministic', () => {
      const error = classifyError('Modulo by zero')
      expect(error.kind).toBe('ArithmeticError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies syntax error as deterministic', () => {
      const error = classifyError('Syntax error at position 5')
      expect(error.kind).toBe('SyntaxError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies parse error as deterministic', () => {
      const error = classifyError('Failed to parse expression')
      expect(error.kind).toBe('SyntaxError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies unexpected token as deterministic', () => {
      const error = classifyError('Unexpected token at position 3')
      expect(error.kind).toBe('SyntaxError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies validation error as deterministic', () => {
      const error = classifyError('Validation failed: bare time value')
      expect(error.kind).toBe('ValidationError')
      expect(error.isDeterministic).toBe(true)
    })

    it('classifies requires button error as deterministic', () => {
      const error = classifyError('This operation requires button wrapper')
      expect(error.kind).toBe('ValidationError')
      expect(error.isDeterministic).toBe(true)
    })
  })

  describe('non-deterministic errors', () => {
    it('classifies note not found as non-deterministic', () => {
      const error = classifyError('Note not found: abc123')
      expect(error.kind).toBe('ResourceUnavailableError')
      expect(error.isDeterministic).toBe(false)
    })

    it('classifies does not exist as non-deterministic', () => {
      const error = classifyError('Resource does not exist')
      expect(error.kind).toBe('ResourceUnavailableError')
      expect(error.isDeterministic).toBe(false)
    })

    it('classifies failed to create note as non-deterministic', () => {
      const error = classifyError('Failed to create note')
      expect(error.kind).toBe('ExternalServiceError')
      expect(error.isDeterministic).toBe(false)
    })

    it('classifies network error as non-deterministic', () => {
      const error = classifyError('Network error occurred')
      expect(error.kind).toBe('NetworkError')
      expect(error.isDeterministic).toBe(false)
    })

    it('classifies connection error as non-deterministic', () => {
      const error = classifyError('Connection refused')
      expect(error.kind).toBe('NetworkError')
      expect(error.isDeterministic).toBe(false)
    })

    it('classifies timeout as non-deterministic', () => {
      const error = classifyError('Request timeout')
      expect(error.kind).toBe('TimeoutError')
      expect(error.isDeterministic).toBe(false)
    })

    it('classifies permission error as non-deterministic', () => {
      const error = classifyError('Permission denied')
      expect(error.kind).toBe('PermissionError')
      expect(error.isDeterministic).toBe(false)
    })

    it('classifies access denied as non-deterministic', () => {
      const error = classifyError('Access denied to resource')
      expect(error.kind).toBe('PermissionError')
      expect(error.isDeterministic).toBe(false)
    })
  })

  describe('default classification', () => {
    it('defaults to deterministic TypeError for unknown messages', () => {
      const error = classifyError('Something completely unknown happened')
      expect(error.kind).toBe('TypeError')
      expect(error.isDeterministic).toBe(true)
    })
  })

  describe('position tracking', () => {
    it('preserves position when provided', () => {
      const error = classifyError('Syntax error', 42)
      expect(error.position).toBe(42)
    })

    it('position is undefined when not provided', () => {
      const error = classifyError('Syntax error')
      expect(error.position).toBeUndefined()
    })
  })

  describe('message preservation', () => {
    it('preserves the original message', () => {
      const msg = 'Unknown function or variable: myFunc'
      const error = classifyError(msg)
      expect(error.message).toBe(msg)
    })
  })
})
