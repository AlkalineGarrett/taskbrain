import { describe, it, expect } from 'vitest'
import { Lexer } from '../../../dsl/language/Lexer'
import { Parser } from '../../../dsl/language/Parser'
import { Executor } from '../../../dsl/runtime/Executor'
import { Environment } from '../../../dsl/runtime/Environment'
import type { DslValue } from '../../../dsl/runtime/DslValue'
import { toDisplayString } from '../../../dsl/runtime/DslValue'

function execute(source: string, env?: Environment): DslValue {
  const tokens = new Lexer(source).tokenize()
  const directive = new Parser(tokens, source).parseDirective()
  const executor = new Executor()
  const e = env ?? Environment.create()
  return executor.execute(directive, e.withExecutor(executor))
}

function display(source: string, env?: Environment): string {
  return toDisplayString(execute(source, env))
}

describe('StringFunctions', () => {
  describe('string()', () => {
    it('should concatenate string literals', () => {
      expect(display('[string("Hello" "World")]')).toBe('HelloWorld')
    })

    it('should concatenate strings with spaces in them', () => {
      expect(display('[string("Hello " "World")]')).toBe('Hello World')
    })

    it('should concatenate mixed types', () => {
      expect(display('[string("Count: " 42)]')).toBe('Count: 42')
    })

    it('should handle single argument', () => {
      expect(display('[string("Hello")]')).toBe('Hello')
    })

    it('should handle no arguments', () => {
      expect(display('[string()]')).toBe('')
    })

    it('should concatenate with commas between args', () => {
      expect(display('[string("a", "b", "c")]')).toBe('abc')
    })

    it('should handle variables', () => {
      const env = Environment.create()
      env.define('name', { kind: 'StringVal', value: 'Alice' })
      expect(display('[string("Hello " name)]', env)).toBe('Hello Alice')
    })

    it('should not trigger right-to-left nesting for function calls', () => {
      // In string(), add(1,2) should be treated as a separate arg, not nested
      expect(display('[string("Result: " add(1, 2))]')).toBe('Result: 3')
    })
  })

  describe('maybe()', () => {
    it('should return value when not undefined', () => {
      expect(display('[maybe("hello")]')).toBe('hello')
    })

    it('should return empty string for undefined', () => {
      // Access a non-existent variable — will be undefined
      const env = Environment.create()
      env.define('x', { kind: 'UndefinedVal' })
      expect(display('[maybe(x)]', env)).toBe('')
    })

    it('should preserve non-string values', () => {
      const result = execute('[maybe(42)]')
      expect(result.kind).toBe('NumberVal')
      expect(result).toHaveProperty('value', 42)
    })

    it('should throw with no arguments', () => {
      expect(() => execute('[maybe()]')).toThrow()
    })

    it('should throw with too many arguments', () => {
      expect(() => execute('[maybe("a", "b")]')).toThrow()
    })
  })

  describe('run()', () => {
    it('should return non-lambda value as-is', () => {
      expect(display('[run(42)]')).toBe('42')
    })

    it('should invoke zero-param lambda', () => {
      expect(display('[run([add(1, 2)])]')).toBe('3')
    })

    it('should throw with no arguments', () => {
      expect(() => execute('[run()]')).toThrow()
    })

    it('should throw with too many arguments', () => {
      expect(() => execute('[run("a", "b")]')).toThrow()
    })
  })
})
