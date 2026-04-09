// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import {
  parseInternalLines,
  parseMarkdownLines,
  parseHtmlLines,
  parseClipboardContent,
} from '../../editor/ClipboardParser'

describe('parseInternalLines', () => {
  it('parses plain text', () => {
    expect(parseInternalLines('hello')).toEqual([
      { indent: 0, bullet: '', content: 'hello' },
    ])
  })

  it('parses bullet prefix', () => {
    expect(parseInternalLines('• item')).toEqual([
      { indent: 0, bullet: '• ', content: 'item' },
    ])
  })

  it('parses checkbox prefixes', () => {
    expect(parseInternalLines('☐ todo')).toEqual([
      { indent: 0, bullet: '☐ ', content: 'todo' },
    ])
    expect(parseInternalLines('☑ done')).toEqual([
      { indent: 0, bullet: '☑ ', content: 'done' },
    ])
  })

  it('parses indentation', () => {
    expect(parseInternalLines('\t\t• nested')).toEqual([
      { indent: 2, bullet: '• ', content: 'nested' },
    ])
  })

  it('parses multiple lines', () => {
    expect(parseInternalLines('• one\n• two\n\t☐ three')).toEqual([
      { indent: 0, bullet: '• ', content: 'one' },
      { indent: 0, bullet: '• ', content: 'two' },
      { indent: 1, bullet: '☐ ', content: 'three' },
    ])
  })

  it('parses line with only indent (no bullet)', () => {
    expect(parseInternalLines('\tcontent')).toEqual([
      { indent: 1, bullet: '', content: 'content' },
    ])
  })
})

describe('parseMarkdownLines', () => {
  it('parses dash bullets', () => {
    expect(parseMarkdownLines('- item one\n- item two')).toEqual([
      { indent: 0, bullet: '• ', content: 'item one' },
      { indent: 0, bullet: '• ', content: 'item two' },
    ])
  })

  it('parses asterisk bullets', () => {
    expect(parseMarkdownLines('* item')).toEqual([
      { indent: 0, bullet: '• ', content: 'item' },
    ])
  })

  it('parses unchecked checkbox', () => {
    expect(parseMarkdownLines('- [ ] todo')).toEqual([
      { indent: 0, bullet: '☐ ', content: 'todo' },
    ])
  })

  it('parses checked checkbox', () => {
    expect(parseMarkdownLines('- [x] done')).toEqual([
      { indent: 0, bullet: '☑ ', content: 'done' },
    ])
    expect(parseMarkdownLines('- [X] done')).toEqual([
      { indent: 0, bullet: '☑ ', content: 'done' },
    ])
  })

  it('parses indentation from spaces', () => {
    expect(parseMarkdownLines('  - nested\n    - deeper')).toEqual([
      { indent: 1, bullet: '• ', content: 'nested' },
      { indent: 2, bullet: '• ', content: 'deeper' },
    ])
  })

  it('strips numbered list prefix', () => {
    expect(parseMarkdownLines('1. first\n2. second')).toEqual([
      { indent: 0, bullet: '', content: 'first' },
      { indent: 0, bullet: '', content: 'second' },
    ])
  })
})

describe('parseHtmlLines', () => {
  it('parses simple unordered list', () => {
    const html = '<ul><li>one</li><li>two</li></ul>'
    expect(parseHtmlLines(html)).toEqual([
      { indent: 0, bullet: '• ', content: 'one' },
      { indent: 0, bullet: '• ', content: 'two' },
    ])
  })

  it('parses nested lists with indentation', () => {
    const html = '<ul><li>parent<ul><li>child</li></ul></li></ul>'
    expect(parseHtmlLines(html)).toEqual([
      { indent: 0, bullet: '• ', content: 'parent' },
      { indent: 1, bullet: '• ', content: 'child' },
    ])
  })

  it('parses ordered list with no bullet', () => {
    const html = '<ol><li>first</li><li>second</li></ol>'
    expect(parseHtmlLines(html)).toEqual([
      { indent: 0, bullet: '', content: 'first' },
      { indent: 0, bullet: '', content: 'second' },
    ])
  })

  it('returns null for non-list HTML', () => {
    expect(parseHtmlLines('<p>just text</p>')).toBeNull()
  })

  it('parses checkbox inputs', () => {
    const html = '<ul><li><input type="checkbox">todo</li><li><input type="checkbox" checked>done</li></ul>'
    expect(parseHtmlLines(html)).toEqual([
      { indent: 0, bullet: '☐ ', content: 'todo' },
      { indent: 0, bullet: '☑ ', content: 'done' },
    ])
  })
})

describe('parseClipboardContent', () => {
  it('uses HTML parsing when HTML has lists', () => {
    const html = '<ul><li>one</li></ul>'
    const result = parseClipboardContent('one', html)
    expect(result[0]!.bullet).toBe('• ')
  })

  it('falls back to markdown when text has markers', () => {
    const result = parseClipboardContent('- item one\n- item two', null)
    expect(result).toEqual([
      { indent: 0, bullet: '• ', content: 'item one' },
      { indent: 0, bullet: '• ', content: 'item two' },
    ])
  })

  it('falls back to internal format for plain text', () => {
    const result = parseClipboardContent('hello world', null)
    expect(result).toEqual([
      { indent: 0, bullet: '', content: 'hello world' },
    ])
  })

  it('falls back to internal format for internal prefix text', () => {
    const result = parseClipboardContent('☐ one\n☐ two', null)
    expect(result).toEqual([
      { indent: 0, bullet: '☐ ', content: 'one' },
      { indent: 0, bullet: '☐ ', content: 'two' },
    ])
  })

  it('ignores HTML without lists', () => {
    const result = parseClipboardContent('plain text', '<p>plain text</p>')
    expect(result).toEqual([
      { indent: 0, bullet: '', content: 'plain text' },
    ])
  })

  it('normalizes CRLF line endings', () => {
    const result = parseClipboardContent('a\r\nb\r\nc', null)
    expect(result.length).toBe(3)
    expect(result[0]!.content).toBe('a')
    expect(result[1]!.content).toBe('b')
    expect(result[2]!.content).toBe('c')
  })

  it('normalizes bare CR line endings', () => {
    const result = parseClipboardContent('a\rb\rc', null)
    expect(result.length).toBe(3)
    expect(result[0]!.content).toBe('a')
    expect(result[1]!.content).toBe('b')
    expect(result[2]!.content).toBe('c')
  })
})
