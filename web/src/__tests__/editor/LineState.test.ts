import { describe, it, expect } from 'vitest'
import { LineState, extractPrefix } from '../../editor/LineState'
import { BULLET, CHECKBOX_UNCHECKED, CHECKBOX_CHECKED } from '../../editor/LinePrefixes'

describe('extractPrefix', () => {
  it('plain text returns empty', () => {
    expect(extractPrefix('hello world')).toBe('')
  })

  it('bullet prefix', () => {
    expect(extractPrefix(`${BULLET}hello`)).toBe(BULLET)
  })

  it('checkbox unchecked prefix', () => {
    expect(extractPrefix(`${CHECKBOX_UNCHECKED}hello`)).toBe(CHECKBOX_UNCHECKED)
  })

  it('checkbox checked prefix', () => {
    expect(extractPrefix(`${CHECKBOX_CHECKED}hello`)).toBe(CHECKBOX_CHECKED)
  })

  it('tabs + bullet prefix', () => {
    expect(extractPrefix(`\t\t${BULLET}hello`)).toBe(`\t\t${BULLET}`)
  })

  it('tabs + checkbox prefix', () => {
    expect(extractPrefix(`\t${CHECKBOX_UNCHECKED}hello`)).toBe(`\t${CHECKBOX_UNCHECKED}`)
  })

  it('empty string', () => {
    expect(extractPrefix('')).toBe('')
  })

  it('only tabs', () => {
    expect(extractPrefix('\t\t')).toBe('\t\t')
  })

  it('only bullet', () => {
    expect(extractPrefix(BULLET)).toBe(BULLET)
  })
})

describe('LineState prefix/content split', () => {
  it('bullet line splits correctly', () => {
    const line = new LineState(`${BULLET}hello`)
    expect(line.prefix).toBe(BULLET)
    expect(line.content).toBe('hello')
  })

  it('indented checkbox line splits correctly', () => {
    const line = new LineState(`\t${CHECKBOX_UNCHECKED}task`)
    expect(line.prefix).toBe(`\t${CHECKBOX_UNCHECKED}`)
    expect(line.content).toBe('task')
  })

  it('plain text has empty prefix', () => {
    const line = new LineState('plain text')
    expect(line.prefix).toBe('')
    expect(line.content).toBe('plain text')
  })

  it('empty line has empty prefix and content', () => {
    const line = new LineState('')
    expect(line.prefix).toBe('')
    expect(line.content).toBe('')
  })
})

describe('LineState updateContent', () => {
  it('preserves prefix when updating content', () => {
    const line = new LineState(`${BULLET}old`, 0)
    line.updateContent('new', 3)
    expect(line.text).toBe(`${BULLET}new`)
    expect(line.cursorPosition).toBe(BULLET.length + 3)
  })

  it('cursor is clamped', () => {
    const line = new LineState(`${BULLET}text`, 0)
    line.updateContent('ab', 100)
    expect(line.cursorPosition).toBe(BULLET.length + 2)
  })
})

describe('LineState updateFull', () => {
  it('replaces text and cursor', () => {
    const line = new LineState('old text', 3)
    line.updateFull('new text', 5)
    expect(line.text).toBe('new text')
    expect(line.cursorPosition).toBe(5)
  })

  it('clamps cursor to text length', () => {
    const line = new LineState('abc', 0)
    line.updateFull('xy', 100)
    expect(line.cursorPosition).toBe(2)
  })
})

describe('LineState indent/unindent', () => {
  it('indent adds a tab', () => {
    const line = new LineState('hello', 3)
    line.indent()
    expect(line.text).toBe('\thello')
    expect(line.cursorPosition).toBe(4)
  })

  it('unindent removes a tab', () => {
    const line = new LineState('\thello', 4)
    const result = line.unindent()
    expect(result).toBe(true)
    expect(line.text).toBe('hello')
    expect(line.cursorPosition).toBe(3)
  })

  it('unindent returns false when no tab', () => {
    const line = new LineState('hello', 3)
    const result = line.unindent()
    expect(result).toBe(false)
    expect(line.text).toBe('hello')
  })
})

describe('LineState toggleBullet cycle', () => {
  it('none -> bullet', () => {
    const line = new LineState('text')
    line.toggleBullet()
    expect(line.text).toBe(`${BULLET}text`)
  })

  it('bullet -> none (removes bullet)', () => {
    const line = new LineState(`${BULLET}text`)
    line.toggleBullet()
    expect(line.text).toBe('text')
  })

  it('checkbox unchecked -> bullet', () => {
    const line = new LineState(`${CHECKBOX_UNCHECKED}text`)
    line.toggleBullet()
    expect(line.text).toBe(`${BULLET}text`)
  })

  it('checkbox checked -> bullet', () => {
    const line = new LineState(`${CHECKBOX_CHECKED}text`)
    line.toggleBullet()
    expect(line.text).toBe(`${BULLET}text`)
  })

  it('preserves indentation', () => {
    const line = new LineState('\t\ttext')
    line.toggleBullet()
    expect(line.text).toBe(`\t\t${BULLET}text`)
  })
})

describe('LineState toggleCheckbox cycle', () => {
  it('none -> unchecked', () => {
    const line = new LineState('text')
    line.toggleCheckbox()
    expect(line.text).toBe(`${CHECKBOX_UNCHECKED}text`)
  })

  it('unchecked -> checked', () => {
    const line = new LineState(`${CHECKBOX_UNCHECKED}text`)
    line.toggleCheckbox()
    expect(line.text).toBe(`${CHECKBOX_CHECKED}text`)
  })

  it('checked -> none', () => {
    const line = new LineState(`${CHECKBOX_CHECKED}text`)
    line.toggleCheckbox()
    expect(line.text).toBe('text')
  })

  it('bullet -> unchecked', () => {
    const line = new LineState(`${BULLET}text`)
    line.toggleCheckbox()
    expect(line.text).toBe(`${CHECKBOX_UNCHECKED}text`)
  })

  it('preserves indentation', () => {
    const line = new LineState('\ttext')
    line.toggleCheckbox()
    expect(line.text).toBe(`\t${CHECKBOX_UNCHECKED}text`)
  })
})

describe('LineState contentCursorPosition', () => {
  it('returns cursor relative to content', () => {
    const line = new LineState(`${BULLET}hello`, BULLET.length + 3)
    expect(line.contentCursorPosition).toBe(3)
  })

  it('clamps to zero when cursor is in prefix', () => {
    const line = new LineState(`${BULLET}hello`, 0)
    expect(line.contentCursorPosition).toBe(0)
  })
})
