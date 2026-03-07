import { describe, it, expect } from 'vitest'
import { Lexer } from '@/dsl/language/Lexer'
import { Parser } from '@/dsl/language/Parser'
import { Executor } from '@/dsl/runtime/Executor'
import { Environment } from '@/dsl/runtime/Environment'
import { BuiltinRegistry } from '@/dsl/runtime/BuiltinRegistry'
import type { DslValue } from '@/dsl/runtime/DslValue'
import { toDisplayString } from '@/dsl/runtime/DslValue'

function execute(source: string, env?: Environment): DslValue {
  const tokens = new Lexer(source).tokenize()
  const directive = new Parser(tokens, source).parseDirective()
  return new Executor().execute(directive, env ?? Environment.create())
}

function display(source: string, env?: Environment): string {
  return toDisplayString(execute(source, env))
}

function envWithMockedTime(date: Date): Environment {
  return Environment.create().withMockedTime(date)
}

describe('DateFunctions', () => {
  describe('parse_date', () => {
    it('should parse valid ISO date', () => {
      const result = execute('[parse_date("2024-01-15")]')
      expect(result.kind).toBe('DateVal')
      expect(result).toHaveProperty('value', '2024-01-15')
    })

    it('should parse date at year boundary - Dec 31', () => {
      const result = execute('[parse_date("2024-12-31")]')
      expect(result.kind).toBe('DateVal')
      expect(result).toHaveProperty('value', '2024-12-31')
    })

    it('should parse date at year boundary - Jan 1', () => {
      const result = execute('[parse_date("2025-01-01")]')
      expect(result.kind).toBe('DateVal')
      expect(result).toHaveProperty('value', '2025-01-01')
    })

    it('should parse leap year date - Feb 29', () => {
      const result = execute('[parse_date("2024-02-29")]')
      expect(result.kind).toBe('DateVal')
      expect(result).toHaveProperty('value', '2024-02-29')
    })

    it('should throw for invalid date format - missing dashes', () => {
      expect(() => execute('[parse_date("20240115")]')).toThrow()
    })

    it('should throw for invalid date format - wrong separator', () => {
      expect(() => execute('[parse_date("2024/01/15")]')).toThrow()
    })

    it('should throw for invalid date format - partial', () => {
      expect(() => execute('[parse_date("2024-01")]')).toThrow()
    })

    it('should throw for empty string', () => {
      expect(() => execute('[parse_date("")]')).toThrow()
    })

    it('should throw for non-string argument', () => {
      expect(() => execute('[parse_date(42)]')).toThrow()
    })

    it('should throw with no arguments', () => {
      expect(() => execute('[parse_date()]')).toThrow()
    })
  })

  describe('date function - dynamic', () => {
    it('should return current date with mocked time wrapped in once', () => {
      const mocked = new Date(2025, 5, 15, 10, 30, 45) // June 15, 2025
      const env = envWithMockedTime(mocked)
      const result = execute('[once[date]]', env)
      expect(result.kind).toBe('DateVal')
      expect(result).toHaveProperty('value', '2025-06-15')
    })

    it('should return current date with mocked time wrapped in refresh', () => {
      const mocked = new Date(2025, 0, 1, 0, 0, 0) // Jan 1, 2025
      const env = envWithMockedTime(mocked)
      const result = execute('[refresh[date]]', env)
      expect(result.kind).toBe('DateVal')
      expect(result).toHaveProperty('value', '2025-01-01')
    })
  })

  describe('time function - dynamic', () => {
    it('should return current time with mocked time wrapped in once', () => {
      const mocked = new Date(2025, 0, 1, 14, 30, 45)
      const env = envWithMockedTime(mocked)
      const result = execute('[once[time]]', env)
      expect(result.kind).toBe('TimeVal')
      expect(result).toHaveProperty('value', '14:30:45')
    })
  })

  describe('datetime function - dynamic', () => {
    it('should return current datetime with mocked time wrapped in once', () => {
      const mocked = new Date(2025, 5, 15, 14, 30, 45)
      const env = envWithMockedTime(mocked)
      const result = execute('[once[datetime]]', env)
      expect(result.kind).toBe('DateTimeVal')
      expect(result).toHaveProperty('value', '2025-06-15T14:30:45')
    })
  })

  describe('BuiltinRegistry classification', () => {
    it('should classify date as dynamic', () => {
      expect(BuiltinRegistry.isDynamic('date')).toBe(true)
    })

    it('should classify time as dynamic', () => {
      expect(BuiltinRegistry.isDynamic('time')).toBe(true)
    })

    it('should classify datetime as dynamic', () => {
      expect(BuiltinRegistry.isDynamic('datetime')).toBe(true)
    })

    it('should classify parse_date as static', () => {
      expect(BuiltinRegistry.isDynamic('parse_date')).toBe(false)
    })
  })
})
