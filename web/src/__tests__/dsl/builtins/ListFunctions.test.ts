import { describe, it, expect } from 'vitest'
import { Lexer } from '@/dsl/language/Lexer'
import { Parser } from '@/dsl/language/Parser'
import { Executor } from '@/dsl/runtime/Executor'
import { Environment } from '@/dsl/runtime/Environment'
import type { DslValue, ListVal } from '@/dsl/runtime/DslValue'
import {
  toDisplayString,
  numberVal,
  stringVal,
  dateVal,
  timeVal,
  dateTimeVal,
  booleanVal,
  UNDEFINED,
  listVal,
} from '@/dsl/runtime/DslValue'
import { sortCompareValues } from '@/dsl/builtins/ListFunctions'

function execute(source: string, env?: Environment): DslValue {
  const tokens = new Lexer(source).tokenize()
  const directive = new Parser(tokens, source).parseDirective()
  return new Executor().execute(directive, env ?? Environment.create())
}

function display(source: string, env?: Environment): string {
  return toDisplayString(execute(source, env))
}

function executeList(source: string, env?: Environment): ListVal {
  const result = execute(source, env)
  expect(result.kind).toBe('ListVal')
  return result as ListVal
}

describe('ListFunctions', () => {
  describe('sort', () => {
    it('should sort empty list', () => {
      const result = executeList('[sort(list())]')
      expect(result.items).toHaveLength(0)
    })

    it('should sort single element list', () => {
      const result = executeList('[sort(list(1))]')
      expect(result.items).toHaveLength(1)
      expect(toDisplayString(result.items[0]!)).toBe('1')
    })

    it('should sort multiple numbers ascending', () => {
      const result = executeList('[sort(list(3, 1, 2))]')
      expect(result.items.map(toDisplayString)).toEqual(['1', '2', '3'])
    })

    it('should sort multiple numbers descending', () => {
      const result = executeList('[sort(list(3, 1, 2), order: descending)]')
      expect(result.items.map(toDisplayString)).toEqual(['3', '2', '1'])
    })

    it('should sort with key lambda', () => {
      const result = executeList('[sort(list(3, 1, 2), key: [neg(i)])]')
      // neg reverses the sign, so sorting by neg gives descending order
      expect(result.items.map(toDisplayString)).toEqual(['3', '2', '1'])
    })

    it('should sort strings alphabetically', () => {
      const result = executeList('[sort(list("banana", "apple", "cherry"))]')
      expect(result.items.map(toDisplayString)).toEqual(['apple', 'banana', 'cherry'])
    })

    it('should sort with ascending order explicitly', () => {
      const result = executeList('[sort(list(3, 1, 2), order: ascending)]')
      expect(result.items.map(toDisplayString)).toEqual(['1', '2', '3'])
    })
  })

  describe('value type comparison', () => {
    it('should compare numbers', () => {
      expect(sortCompareValues(numberVal(1), numberVal(2))).toBeLessThan(0)
      expect(sortCompareValues(numberVal(2), numberVal(1))).toBeGreaterThan(0)
      expect(sortCompareValues(numberVal(1), numberVal(1))).toBe(0)
    })

    it('should compare strings', () => {
      expect(sortCompareValues(stringVal('a'), stringVal('b'))).toBeLessThan(0)
      expect(sortCompareValues(stringVal('b'), stringVal('a'))).toBeGreaterThan(0)
      expect(sortCompareValues(stringVal('a'), stringVal('a'))).toBe(0)
    })

    it('should compare dates', () => {
      expect(sortCompareValues(dateVal('2024-01-01'), dateVal('2024-01-02'))).toBeLessThan(0)
      expect(sortCompareValues(dateVal('2024-01-02'), dateVal('2024-01-01'))).toBeGreaterThan(0)
      expect(sortCompareValues(dateVal('2024-01-01'), dateVal('2024-01-01'))).toBe(0)
    })

    it('should compare times', () => {
      expect(sortCompareValues(timeVal('10:00:00'), timeVal('11:00:00'))).toBeLessThan(0)
      expect(sortCompareValues(timeVal('11:00:00'), timeVal('10:00:00'))).toBeGreaterThan(0)
      expect(sortCompareValues(timeVal('10:00:00'), timeVal('10:00:00'))).toBe(0)
    })

    it('should compare datetimes', () => {
      expect(sortCompareValues(dateTimeVal('2024-01-01T10:00:00'), dateTimeVal('2024-01-01T11:00:00'))).toBeLessThan(0)
      expect(sortCompareValues(dateTimeVal('2024-01-01T11:00:00'), dateTimeVal('2024-01-01T10:00:00'))).toBeGreaterThan(0)
    })

    it('should compare booleans', () => {
      expect(sortCompareValues(booleanVal(false), booleanVal(true))).toBeLessThan(0)
      expect(sortCompareValues(booleanVal(true), booleanVal(false))).toBeGreaterThan(0)
      expect(sortCompareValues(booleanVal(true), booleanVal(true))).toBe(0)
    })

    it('should handle undefined values - sort first', () => {
      expect(sortCompareValues(UNDEFINED, numberVal(1))).toBeLessThan(0)
      expect(sortCompareValues(numberVal(1), UNDEFINED)).toBeGreaterThan(0)
      expect(sortCompareValues(UNDEFINED, UNDEFINED)).toBe(0)
    })

    it('should order different types by type precedence', () => {
      // boolean < number < string < date
      expect(sortCompareValues(booleanVal(true), numberVal(1))).toBeLessThan(0)
      expect(sortCompareValues(numberVal(1), stringVal('a'))).toBeLessThan(0)
      expect(sortCompareValues(stringVal('a'), dateVal('2024-01-01'))).toBeLessThan(0)
    })
  })

  describe('first', () => {
    it('should return first element of non-empty list', () => {
      const result = execute('[first(list(10, 20, 30))]')
      expect(result.kind).toBe('NumberVal')
      expect(toDisplayString(result)).toBe('10')
    })

    it('should return undefined for empty list', () => {
      const result = execute('[first(list())]')
      expect(result.kind).toBe('UndefinedVal')
    })

    it('should work with sort - first of sorted list', () => {
      const result = execute('[first(sort(list(3, 1, 2)))]')
      expect(result.kind).toBe('NumberVal')
      expect(toDisplayString(result)).toBe('1')
    })
  })

  describe('list creation', () => {
    it('should create list of numbers', () => {
      const result = executeList('[list(1, 2, 3)]')
      expect(result.items).toHaveLength(3)
      expect(result.items.map(toDisplayString)).toEqual(['1', '2', '3'])
    })

    it('should create list of strings', () => {
      const result = executeList('[list("a", "b", "c")]')
      expect(result.items).toHaveLength(3)
      expect(result.items.map(toDisplayString)).toEqual(['a', 'b', 'c'])
    })

    it('should create mixed type list', () => {
      const result = executeList('[list(1, "hello", 3.14)]')
      expect(result.items).toHaveLength(3)
      expect(result.items[0]!.kind).toBe('NumberVal')
      expect(result.items[1]!.kind).toBe('StringVal')
      expect(result.items[2]!.kind).toBe('NumberVal')
    })

    it('should create empty list', () => {
      const result = executeList('[list()]')
      expect(result.items).toHaveLength(0)
    })
  })

  describe('constants', () => {
    it('should evaluate ascending constant', () => {
      const result = execute('[ascending]')
      expect(result.kind).toBe('StringVal')
      expect(toDisplayString(result)).toBe('ascending')
    })

    it('should evaluate descending constant', () => {
      const result = execute('[descending]')
      expect(result.kind).toBe('StringVal')
      expect(toDisplayString(result)).toBe('descending')
    })
  })

  describe('integration', () => {
    it('should sort numbers and get first', () => {
      const result = execute('[first(sort(list(5, 3, 8, 1, 4)))]')
      expect(toDisplayString(result)).toBe('1')
    })

    it('should sort numbers descending and get first', () => {
      const result = execute('[first(sort(list(5, 3, 8, 1, 4), order: descending))]')
      expect(toDisplayString(result)).toBe('8')
    })

    it('should display empty list', () => {
      expect(display('[list()]')).toBe('[]')
    })

    it('should display non-empty list', () => {
      expect(display('[list(1, 2, 3)]')).toBe('[1, 2, 3]')
    })
  })
})
