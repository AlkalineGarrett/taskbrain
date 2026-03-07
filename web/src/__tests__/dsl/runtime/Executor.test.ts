import { describe, it, expect } from 'vitest'
import { Lexer } from '@/dsl/language/Lexer'
import { Parser } from '@/dsl/language/Parser'
import { Executor } from '@/dsl/runtime/Executor'
import { Environment } from '@/dsl/runtime/Environment'
import { BuiltinRegistry } from '@/dsl/runtime/BuiltinRegistry'
import type { DslValue, NumberVal, StringVal, DateVal, TimeVal, DateTimeVal } from '@/dsl/runtime/DslValue'
import {
  toDisplayString,
  numberVal,
  stringVal,
  dateVal,
  timeVal,
  dateTimeVal,
  serializeValue,
  deserializeValue,
} from '@/dsl/runtime/DslValue'

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

describe('Executor', () => {
  describe('number evaluation', () => {
    it('should evaluate integer', () => {
      const result = execute('[42]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(42)
    })

    it('should evaluate decimal number', () => {
      const result = execute('[3.14]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBeCloseTo(3.14)
    })

    it('should evaluate zero', () => {
      const result = execute('[0]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(0)
    })
  })

  describe('string evaluation', () => {
    it('should evaluate simple string', () => {
      const result = execute('[" hello "]')
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe(' hello ')
    })

    it('should evaluate empty string', () => {
      const result = execute('[""]')
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('')
    })
  })

  describe('display strings', () => {
    it('should display integer without decimal', () => {
      expect(display('[42]')).toBe('42')
    })

    it('should display decimal number', () => {
      expect(display('[3.14]')).toBe('3.14')
    })

    it('should display zero', () => {
      expect(display('[0]')).toBe('0')
    })

    it('should display string as-is', () => {
      expect(display('["hello"]')).toBe('hello')
    })

    it('should display undefined', () => {
      const result = execute('[first(list())]')
      expect(toDisplayString(result)).toBe('undefined')
    })
  })

  describe('serialization round-trips', () => {
    it('should round-trip number', () => {
      const val = numberVal(42)
      const deserialized = deserializeValue(serializeValue(val))
      expect(deserialized.kind).toBe('NumberVal')
      expect((deserialized as NumberVal).value).toBe(42)
    })

    it('should round-trip decimal number', () => {
      const val = numberVal(3.14)
      const deserialized = deserializeValue(serializeValue(val))
      expect(deserialized.kind).toBe('NumberVal')
      expect((deserialized as NumberVal).value).toBeCloseTo(3.14)
    })

    it('should round-trip string', () => {
      const val = stringVal('hello world')
      const deserialized = deserializeValue(serializeValue(val))
      expect(deserialized.kind).toBe('StringVal')
      expect((deserialized as StringVal).value).toBe('hello world')
    })

    it('should round-trip date', () => {
      const val = dateVal('2024-06-15')
      const deserialized = deserializeValue(serializeValue(val))
      expect(deserialized.kind).toBe('DateVal')
      expect((deserialized as DateVal).value).toBe('2024-06-15')
    })

    it('should round-trip time', () => {
      const val = timeVal('14:30:00')
      const deserialized = deserializeValue(serializeValue(val))
      expect(deserialized.kind).toBe('TimeVal')
      expect((deserialized as TimeVal).value).toBe('14:30:00')
    })

    it('should round-trip datetime', () => {
      const val = dateTimeVal('2024-06-15T14:30:00')
      const deserialized = deserializeValue(serializeValue(val))
      expect(deserialized.kind).toBe('DateTimeVal')
      expect((deserialized as DateTimeVal).value).toBe('2024-06-15T14:30:00')
    })
  })

  describe('date/datetime/time functions', () => {
    it('should evaluate date function with mocked time', () => {
      const mocked = new Date(2025, 2, 15, 10, 30, 0) // March 15, 2025
      const env = envWithMockedTime(mocked)
      const result = execute('[once[date]]', env)
      expect(result.kind).toBe('DateVal')
      expect((result as DateVal).value).toBe('2025-03-15')
    })

    it('should evaluate time function with mocked time', () => {
      const mocked = new Date(2025, 0, 1, 8, 5, 30)
      const env = envWithMockedTime(mocked)
      const result = execute('[once[time]]', env)
      expect(result.kind).toBe('TimeVal')
      expect((result as TimeVal).value).toBe('08:05:30')
    })

    it('should evaluate datetime function with mocked time', () => {
      const mocked = new Date(2025, 11, 25, 23, 59, 59) // Dec 25, 2025
      const env = envWithMockedTime(mocked)
      const result = execute('[once[datetime]]', env)
      expect(result.kind).toBe('DateTimeVal')
      expect((result as DateTimeVal).value).toBe('2025-12-25T23:59:59')
    })

    it('should display datetime with comma separator', () => {
      const val = dateTimeVal('2025-01-15T14:30:00')
      expect(toDisplayString(val)).toBe('2025-01-15, 14:30:00')
    })
  })

  describe('character constants', () => {
    it('should evaluate qt (double quote)', () => {
      const result = execute('[qt]')
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('"')
    })

    it('should evaluate nl (newline)', () => {
      const result = execute('[nl]')
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('\n')
    })

    it('should evaluate tab', () => {
      const result = execute('[tab]')
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('\t')
    })

    it('should evaluate ret (carriage return)', () => {
      const result = execute('[ret]')
      expect(result.kind).toBe('StringVal')
      expect((result as StringVal).value).toBe('\r')
    })
  })

  describe('error handling', () => {
    it('should throw on unknown function', () => {
      expect(() => execute('[unknown_func()]')).toThrow()
    })

    it('should throw on unknown variable', () => {
      expect(() => execute('[undefined_var]')).toThrow()
    })
  })

  describe('arithmetic', () => {
    it('should add two numbers', () => {
      const result = execute('[add(3, 4)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(7)
    })

    it('should subtract two numbers', () => {
      const result = execute('[sub(10, 3)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(7)
    })

    it('should multiply two numbers', () => {
      const result = execute('[mul(3, 4)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(12)
    })

    it('should divide two numbers', () => {
      const result = execute('[div(10, 2)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(5)
    })

    it('should throw on division by zero', () => {
      expect(() => execute('[div(10, 0)]')).toThrow(/[Zz]ero/)
    })

    it('should compute modulo', () => {
      const result = execute('[mod(10, 3)]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(1)
    })

    it('should throw on modulo by zero', () => {
      expect(() => execute('[mod(10, 0)]')).toThrow(/[Zz]ero/)
    })
  })

  describe('nested calls', () => {
    it('should evaluate nested arithmetic', () => {
      const result = execute('[add(mul(2, 3), sub(10, 4))]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(12) // (2*3) + (10-4) = 6 + 6
    })

    it('should evaluate deeply nested calls', () => {
      const result = execute('[add(add(1, 2), add(3, 4))]')
      expect(result.kind).toBe('NumberVal')
      expect((result as NumberVal).value).toBe(10)
    })
  })

  describe('named arguments', () => {
    it('should handle named arguments in sort', () => {
      const result = execute('[sort(list(3, 1, 2), order: descending)]')
      expect(result.kind).toBe('ListVal')
      const items = (result as { kind: 'ListVal'; items: DslValue[] }).items
      expect(items.map(toDisplayString)).toEqual(['3', '2', '1'])
    })
  })

  describe('dynamic function classification', () => {
    it('should classify date as dynamic', () => {
      expect(BuiltinRegistry.isDynamic('date')).toBe(true)
    })

    it('should classify time as dynamic', () => {
      expect(BuiltinRegistry.isDynamic('time')).toBe(true)
    })

    it('should classify datetime as dynamic', () => {
      expect(BuiltinRegistry.isDynamic('datetime')).toBe(true)
    })

    it('should classify add as static', () => {
      expect(BuiltinRegistry.isDynamic('add')).toBe(false)
    })

    it('should classify sort as static', () => {
      expect(BuiltinRegistry.isDynamic('sort')).toBe(false)
    })

    it('should classify parse_date as static', () => {
      expect(BuiltinRegistry.isDynamic('parse_date')).toBe(false)
    })
  })

  describe('empty parentheses', () => {
    it('should allow function call with empty parens', () => {
      const result = execute('[list()]')
      expect(result.kind).toBe('ListVal')
    })

    it('should allow constant call without parens', () => {
      const result = execute('[ascending]')
      expect(result.kind).toBe('StringVal')
    })
  })
})
