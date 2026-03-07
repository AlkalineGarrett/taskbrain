import { describe, it, expect } from 'vitest'
import {
  directiveResultSuccess,
  directiveResultFailure,
  directiveResultWarning,
  directiveResultToDisplayString,
  directiveResultToValue,
  isComputed,
  DirectiveWarningType,
} from '../../../dsl/directives/DirectiveResult'
import type { DirectiveResult } from '../../../dsl/directives/DirectiveResult'
import { numberVal, stringVal, booleanVal, listVal, UNDEFINED } from '../../../dsl/runtime/DslValue'

describe('directiveResultSuccess', () => {
  it('creates success result with serialized value', () => {
    const result = directiveResultSuccess(numberVal(42))
    expect(result.error).toBeNull()
    expect(result.warning).toBeNull()
    expect(result.result).not.toBeNull()
    expect(result.collapsed).toBe(true)
  })

  it('supports non-collapsed success', () => {
    const result = directiveResultSuccess(stringVal('hello'), false)
    expect(result.collapsed).toBe(false)
  })
})

describe('directiveResultFailure', () => {
  it('creates failure result with error message', () => {
    const result = directiveResultFailure('something went wrong')
    expect(result.error).toBe('something went wrong')
    expect(result.result).toBeNull()
    expect(result.warning).toBeNull()
    expect(result.collapsed).toBe(true)
  })

  it('supports non-collapsed failure', () => {
    const result = directiveResultFailure('err', false)
    expect(result.collapsed).toBe(false)
  })
})

describe('directiveResultWarning', () => {
  it('creates warning result for uncalled lambda', () => {
    const result = directiveResultWarning(DirectiveWarningType.NO_EFFECT_LAMBDA)
    expect(result.warning).toBe(DirectiveWarningType.NO_EFFECT_LAMBDA)
    expect(result.result).toBeNull()
    expect(result.error).toBeNull()
  })

  it('creates warning result for unused pattern', () => {
    const result = directiveResultWarning(DirectiveWarningType.NO_EFFECT_PATTERN)
    expect(result.warning).toBe(DirectiveWarningType.NO_EFFECT_PATTERN)
  })
})

describe('directiveResultToValue', () => {
  it('deserializes number value', () => {
    const dr = directiveResultSuccess(numberVal(42))
    const val = directiveResultToValue(dr)
    expect(val).not.toBeNull()
    expect(val!.kind).toBe('NumberVal')
    if (val!.kind === 'NumberVal') {
      expect(val!.value).toBe(42)
    }
  })

  it('deserializes string value', () => {
    const dr = directiveResultSuccess(stringVal('hello'))
    const val = directiveResultToValue(dr)
    expect(val).not.toBeNull()
    expect(val!.kind).toBe('StringVal')
    if (val!.kind === 'StringVal') {
      expect(val!.value).toBe('hello')
    }
  })

  it('deserializes boolean value', () => {
    const dr = directiveResultSuccess(booleanVal(true))
    const val = directiveResultToValue(dr)
    expect(val).not.toBeNull()
    expect(val!.kind).toBe('BooleanVal')
    if (val!.kind === 'BooleanVal') {
      expect(val!.value).toBe(true)
    }
  })

  it('deserializes list value', () => {
    const dr = directiveResultSuccess(listVal([numberVal(1), numberVal(2)]))
    const val = directiveResultToValue(dr)
    expect(val).not.toBeNull()
    expect(val!.kind).toBe('ListVal')
    if (val!.kind === 'ListVal') {
      expect(val!.items).toHaveLength(2)
    }
  })

  it('returns null for failure result', () => {
    const dr = directiveResultFailure('error')
    expect(directiveResultToValue(dr)).toBeNull()
  })

  it('returns null for warning result', () => {
    const dr = directiveResultWarning(DirectiveWarningType.NO_EFFECT_LAMBDA)
    expect(directiveResultToValue(dr)).toBeNull()
  })
})

describe('directiveResultToDisplayString', () => {
  it('displays error message', () => {
    const dr = directiveResultFailure('bad input')
    expect(directiveResultToDisplayString(dr)).toBe('Error: bad input')
  })

  it('displays warning message', () => {
    const dr = directiveResultWarning(DirectiveWarningType.NO_EFFECT_LAMBDA)
    expect(directiveResultToDisplayString(dr)).toBe('Warning: Uncalled lambda has no effect')
  })

  it('displays computed value', () => {
    const dr = directiveResultSuccess(numberVal(42))
    expect(directiveResultToDisplayString(dr)).toBe('42')
  })

  it('displays string value', () => {
    const dr = directiveResultSuccess(stringVal('hello world'))
    expect(directiveResultToDisplayString(dr)).toBe('hello world')
  })

  it('displays fallback for empty result', () => {
    const dr: DirectiveResult = { result: null, executedAt: null, error: null, warning: null, collapsed: true }
    expect(directiveResultToDisplayString(dr)).toBe('...')
  })

  it('displays custom fallback', () => {
    const dr: DirectiveResult = { result: null, executedAt: null, error: null, warning: null, collapsed: true }
    expect(directiveResultToDisplayString(dr, 'loading')).toBe('loading')
  })

  it('displays list value', () => {
    const dr = directiveResultSuccess(listVal([numberVal(1), numberVal(2)]))
    expect(directiveResultToDisplayString(dr)).toBe('[1, 2]')
  })

  it('displays boolean value', () => {
    const dr = directiveResultSuccess(booleanVal(false))
    expect(directiveResultToDisplayString(dr)).toBe('false')
  })

  it('displays undefined value', () => {
    const dr = directiveResultSuccess(UNDEFINED)
    expect(directiveResultToDisplayString(dr)).toBe('undefined')
  })
})

describe('isComputed', () => {
  it('returns true for success result', () => {
    const dr = directiveResultSuccess(numberVal(1))
    expect(isComputed(dr)).toBe(true)
  })

  it('returns false for failure result', () => {
    const dr = directiveResultFailure('error')
    expect(isComputed(dr)).toBe(false)
  })

  it('returns false for warning result', () => {
    const dr = directiveResultWarning(DirectiveWarningType.NO_EFFECT_LAMBDA)
    expect(isComputed(dr)).toBe(false)
  })

  it('returns false for empty result', () => {
    const dr: DirectiveResult = { result: null, executedAt: null, error: null, warning: null, collapsed: true }
    expect(isComputed(dr)).toBe(false)
  })
})
