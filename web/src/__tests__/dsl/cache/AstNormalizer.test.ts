import { describe, it, expect } from 'vitest'
import { normalize, computeCacheKey } from '../../../dsl/cache/AstNormalizer'
import type { Expression } from '../../../dsl/language/Expression'

function numLit(value: number, position = 0): Expression {
  return { kind: 'NumberLiteral', value, position }
}

function strLit(value: string, position = 0): Expression {
  return { kind: 'StringLiteral', value, position }
}

function varRef(name: string, position = 0): Expression {
  return { kind: 'VariableRef', name, position }
}

function selfRef(position = 0): Expression {
  return { kind: 'CurrentNoteRef', position }
}

function prop(target: Expression, property: string, position = 0): Expression {
  return { kind: 'PropertyAccess', target, property, position }
}

function call(name: string, args: Expression[] = [], namedArgs: { name: string; value: Expression; position: number }[] = [], position = 0): Expression {
  return { kind: 'CallExpr', name, args, namedArgs, position }
}

function methodCall(target: Expression, methodName: string, args: Expression[] = [], namedArgs: { name: string; value: Expression; position: number }[] = [], position = 0): Expression {
  return { kind: 'MethodCall', target, methodName, args, namedArgs, position }
}

function lambda(params: string[], body: Expression, position = 0): Expression {
  return { kind: 'LambdaExpr', params, body, position }
}

function stmtList(statements: Expression[], position = 0): Expression {
  return { kind: 'StatementList', statements, position }
}

function onceExpr(body: Expression, position = 0): Expression {
  return { kind: 'OnceExpr', body, position }
}

function refreshExpr(body: Expression, position = 0): Expression {
  return { kind: 'RefreshExpr', body, position }
}

describe('AstNormalizer', () => {
  describe('normalize literals', () => {
    it('normalizes number literal', () => {
      expect(normalize(numLit(42))).toBe('NUM(42)')
    })

    it('normalizes string literal', () => {
      expect(normalize(strLit('hello'))).toBe('STR(hello)')
    })

    it('normalizes string with special characters', () => {
      const result = normalize(strLit('line1\nline2'))
      expect(result).toBe('STR(line1\\nline2)')
    })

    it('normalizes string with quotes', () => {
      const result = normalize(strLit('say "hi"'))
      expect(result).toBe('STR(say \\"hi\\")')
    })
  })

  describe('normalize references', () => {
    it('normalizes current note ref', () => {
      expect(normalize(selfRef())).toBe('SELF')
    })

    it('normalizes variable ref', () => {
      expect(normalize(varRef('x'))).toBe('VAR(x)')
    })
  })

  describe('normalize property access', () => {
    it('normalizes property on self', () => {
      expect(normalize(prop(selfRef(), 'name'))).toBe('PROP(SELF,name)')
    })

    it('normalizes chained property access', () => {
      const expr = prop(prop(selfRef(), 'up'), 'name')
      expect(normalize(expr)).toBe('PROP(PROP(SELF,up),name)')
    })
  })

  describe('normalize function calls', () => {
    it('normalizes function call with no args', () => {
      expect(normalize(call('find'))).toBe('CALL(find,)')
    })

    it('normalizes function call with positional args', () => {
      expect(normalize(call('find', [strLit('test')]))).toBe('CALL(find,STR(test))')
    })

    it('normalizes function call with named args sorted', () => {
      const result = normalize(call('find', [], [
        { name: 'z_param', value: numLit(1), position: 0 },
        { name: 'a_param', value: numLit(2), position: 0 },
      ]))
      expect(result).toBe('CALL(find,a_param=NUM(2),z_param=NUM(1))')
    })
  })

  describe('normalize method calls', () => {
    it('normalizes method call on self', () => {
      const expr = methodCall(selfRef(), 'up', [numLit(2)])
      expect(normalize(expr)).toBe('METHOD(SELF,up,NUM(2))')
    })
  })

  describe('normalize lambdas', () => {
    it('normalizes lambda expression', () => {
      const expr = lambda(['x', 'y'], varRef('x'))
      expect(normalize(expr)).toBe('LAMBDA(x,y,VAR(x))')
    })
  })

  describe('normalize execution blocks', () => {
    it('normalizes once expression', () => {
      const expr = onceExpr(call('date'))
      expect(normalize(expr)).toBe('ONCE(CALL(date,))')
    })

    it('normalizes refresh expression', () => {
      const expr = refreshExpr(call('time'))
      expect(normalize(expr)).toBe('REFRESH(CALL(time,))')
    })
  })

  describe('normalize statement list', () => {
    it('normalizes multiple statements', () => {
      const expr = stmtList([numLit(1), numLit(2)])
      expect(normalize(expr)).toBe('STMTS(NUM(1);NUM(2))')
    })
  })

  describe('normalize patterns', () => {
    it('normalizes pattern with char class', () => {
      const expr: Expression = {
        kind: 'PatternExpr',
        elements: [{ kind: 'CharClass', type: 'DIGIT' as const, position: 0 }],
        position: 0,
      }
      expect(normalize(expr)).toBe('PATTERN(CHAR(DIGIT))')
    })

    it('normalizes pattern with literal', () => {
      const expr: Expression = {
        kind: 'PatternExpr',
        elements: [{ kind: 'PatternLiteral', value: 'abc', position: 0 }],
        position: 0,
      }
      expect(normalize(expr)).toBe('PATTERN(LIT(abc))')
    })

    it('normalizes pattern with quantifier', () => {
      const expr: Expression = {
        kind: 'PatternExpr',
        elements: [{
          kind: 'Quantified',
          element: { kind: 'CharClass', type: 'DIGIT' as const, position: 0 },
          quantifier: { kind: 'Exact', n: 3 },
          position: 0,
        }],
        position: 0,
      }
      expect(normalize(expr)).toBe('PATTERN(QUANT(CHAR(DIGIT),x3))')
    })

    it('normalizes pattern with range quantifier', () => {
      const expr: Expression = {
        kind: 'PatternExpr',
        elements: [{
          kind: 'Quantified',
          element: { kind: 'CharClass', type: 'LETTER' as const, position: 0 },
          quantifier: { kind: 'Range', min: 1, max: 5 },
          position: 0,
        }],
        position: 0,
      }
      expect(normalize(expr)).toBe('PATTERN(QUANT(CHAR(LETTER),1..5))')
    })

    it('normalizes pattern with any quantifier', () => {
      const expr: Expression = {
        kind: 'PatternExpr',
        elements: [{
          kind: 'Quantified',
          element: { kind: 'CharClass', type: 'ANY' as const, position: 0 },
          quantifier: { kind: 'Any' },
          position: 0,
        }],
        position: 0,
      }
      expect(normalize(expr)).toBe('PATTERN(QUANT(CHAR(ANY),*))')
    })
  })

  describe('cache key generation', () => {
    it('produces same key for same expression', () => {
      const expr1 = call('find', [strLit('test')])
      const expr2 = call('find', [strLit('test')])
      expect(computeCacheKey(expr1)).toBe(computeCacheKey(expr2))
    })

    it('produces different keys for different expressions', () => {
      const expr1 = call('find', [strLit('test1')])
      const expr2 = call('find', [strLit('test2')])
      expect(computeCacheKey(expr1)).not.toBe(computeCacheKey(expr2))
    })

    it('returns an 8-character hex string', () => {
      const key = computeCacheKey(numLit(42))
      expect(key).toMatch(/^[0-9a-f]{8}$/)
    })
  })

  describe('position independence', () => {
    it('same expression at different positions produces same normalized form', () => {
      const expr1 = call('find', [strLit('test', 0)], [], 0)
      const expr2 = call('find', [strLit('test', 99)], [], 50)
      expect(normalize(expr1)).toBe(normalize(expr2))
    })

    it('same expression at different positions produces same cache key', () => {
      const expr1 = prop(selfRef(0), 'name', 0)
      const expr2 = prop(selfRef(100), 'name', 100)
      expect(computeCacheKey(expr1)).toBe(computeCacheKey(expr2))
    })
  })

  describe('normalize assignment', () => {
    it('normalizes variable assignment', () => {
      const expr: Expression = {
        kind: 'Assignment',
        target: varRef('x'),
        value: numLit(42),
        position: 0,
      }
      expect(normalize(expr)).toBe('ASSIGN(x,NUM(42))')
    })

    it('normalizes property assignment', () => {
      const expr: Expression = {
        kind: 'Assignment',
        target: prop(selfRef(), 'name'),
        value: strLit('hello'),
        position: 0,
      }
      expect(normalize(expr)).toBe('ASSIGN(PROP(SELF,name),STR(hello))')
    })
  })

  describe('normalize lambda invocation', () => {
    it('normalizes lambda invocation', () => {
      const lam = lambda(['x'], varRef('x'))
      const expr: Expression = {
        kind: 'LambdaInvocation',
        lambda: lam as Expression & { kind: 'LambdaExpr' },
        args: [numLit(5)],
        namedArgs: [],
        position: 0,
      }
      expect(normalize(expr)).toBe('INVOKE(LAMBDA(x,VAR(x)),NUM(5))')
    })
  })
})
