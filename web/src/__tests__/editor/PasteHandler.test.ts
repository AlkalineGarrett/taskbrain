import { describe, it, expect } from 'vitest'
import { LineState } from '@/editor/LineState'
import { SELECTION_NONE, type EditorSelection } from '@/editor/EditorSelection'
import { executePaste, isFullLineSelection } from '@/editor/PasteHandler'
import type { ParsedLine } from '@/editor/ClipboardParser'

function lines(...texts: string[]): LineState[] {
  return texts.map(t => new LineState(t))
}

function lineTexts(ls: LineState[]): string[] {
  return ls.map(l => l.text)
}

function sel(start: number, end: number): EditorSelection {
  return { start, end }
}

function parsed(indent: number, bullet: string, content: string): ParsedLine {
  return { indent, bullet, content }
}

// Helper: place cursor at a position in a specific line
function withCursor(ls: LineState[], lineIndex: number, cursor: number): LineState[] {
  ls[lineIndex]!.updateFull(ls[lineIndex]!.text, cursor)
  return ls
}

describe('isFullLineSelection', () => {
  it('detects full single line selection', () => {
    const ls = lines('hello', 'world')
    // "hello" is offsets 0-5, "world" is offsets 6-11
    expect(isFullLineSelection(ls, sel(0, 5))).toBe(true)
  })

  it('detects full multi-line selection', () => {
    const ls = lines('hello', 'world')
    expect(isFullLineSelection(ls, sel(0, 11))).toBe(true)
  })

  it('rejects partial selection', () => {
    const ls = lines('hello', 'world')
    expect(isFullLineSelection(ls, sel(1, 5))).toBe(false) // doesn't start at line beginning
    expect(isFullLineSelection(ls, sel(0, 3))).toBe(false) // doesn't end at line end
  })

  it('detects selection ending at start of next line', () => {
    const ls = lines('hello', 'world')
    // offset 6 = start of "world" line, which means "hello\n" is selected
    expect(isFullLineSelection(ls, sel(0, 6))).toBe(true)
  })
})

describe('Rule 5: single-line plain text paste', () => {
  it('inserts at cursor', () => {
    const ls = withCursor(lines('hello'), 0, 5)
    const result = executePaste(ls, 0, SELECTION_NONE, [parsed(0, '', ' world')])
    expect(lineTexts(result.lines)).toEqual(['hello world'])
  })

  it('replaces selection', () => {
    const ls = lines('hello world')
    const result = executePaste(ls, 0, sel(6, 11), [parsed(0, '', 'earth')])
    expect(lineTexts(result.lines)).toEqual(['hello earth'])
  })
})

describe('Rule 3: full-line replacement', () => {
  it('replaces selected lines with pasted lines', () => {
    // "• hello" (0-7), "• world" (8-15)
    const ls = lines('• hello', '• world')
    const result = executePaste(ls, 0, sel(0, 15), [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    expect(lineTexts(result.lines)).toEqual(['☐ one', '☐ two'])
  })

  it('preserves lines before and after selection', () => {
    const ls = lines('before', '• hello', '• world', 'after')
    // "• hello" starts at offset 7, "• world" ends at offset 22
    const result = executePaste(ls, 1, sel(7, 22), [
      parsed(0, '☐ ', 'replaced'),
    ])
    expect(lineTexts(result.lines)).toEqual(['before', '☐ replaced', 'after'])
  })

  it('does not adopt destination prefix for unprefixed paste', () => {
    const ls = lines('• hello', '• world')
    const result = executePaste(ls, 0, sel(0, 15), [
      parsed(0, '', 'plain one'),
      parsed(0, '', 'plain two'),
    ])
    expect(lineTexts(result.lines)).toEqual(['plain one', 'plain two'])
  })
})

describe('Rule 2: mid-line split for multi-line paste', () => {
  it('splits at cursor and inserts pasted lines between halves', () => {
    const ls = withCursor(lines('• hello'), 0, 4) // cursor after "he" in content: "• he|llo"
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    expect(lineTexts(result.lines)).toEqual(['• he', '☐ one', '☐ two', '• llo'])
  })

  it('drops empty leading half when cursor is at start of content', () => {
    const ls = withCursor(lines('• hello'), 0, 2) // cursor at "• |hello" (prefix length = 2)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    expect(lineTexts(result.lines)).toEqual(['☐ one', '☐ two', '• hello'])
  })

  it('drops empty trailing half when cursor is at end of content', () => {
    const ls = withCursor(lines('• hello'), 0, 7) // cursor at "• hello|" (end)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    expect(lineTexts(result.lines)).toEqual(['• hello', '☐ one', '☐ two'])
  })

  it('handles partial single-line selection with split', () => {
    const ls = lines('• hello')
    // Select "ll" in "• hello": prefix is "• " (2 chars), content "hello"
    // global offsets: "• hello" -> "ll" is at positions 4-6
    const result = executePaste(ls, 0, sel(4, 6), [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    expect(lineTexts(result.lines)).toEqual(['• he', '☐ one', '☐ two', '• o'])
  })
})

describe('Rule 1: prefix merging', () => {
  it('source prefix wins when source has prefix', () => {
    const ls = withCursor(lines('• hello'), 0, 7)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    // Source ☐ wins over destination •
    expect(lineTexts(result.lines)).toEqual(['• hello', '☐ one', '☐ two'])
  })

  it('adopts destination prefix when source has no prefix', () => {
    const ls = withCursor(lines('☐ hello'), 0, 7)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '', 'one'),
      parsed(0, '', 'two'),
    ])
    // Source has no prefix -> adopts ☐ from destination
    expect(lineTexts(result.lines)).toEqual(['☐ hello', '☐ one', '☐ two'])
  })

  it('per-line prefix adoption: mixed source prefixes', () => {
    const ls = withCursor(lines('☐ hello'), 0, 7)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '• ', 'bullet'),    // has prefix -> keeps •
      parsed(0, '', 'plain'),        // no prefix -> adopts ☐
      parsed(0, '☑ ', 'checked'),   // has prefix -> keeps ☑
    ])
    expect(lineTexts(result.lines)).toEqual([
      '☐ hello', '• bullet', '☐ plain', '☑ checked',
    ])
  })
})

describe('Rule 4: relative indent shifting', () => {
  it('shifts indentation to match destination', () => {
    const ls = withCursor(lines('\t• hello'), 0, 8) // indent level 1
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(2, '☐ ', 'parent'),   // indent 2
      parsed(3, '☐ ', 'child'),    // indent 3
      parsed(2, '☐ ', 'sibling'),  // indent 2
    ])
    // delta = 1 - 2 = -1, so 2->1, 3->2, 2->1
    expect(lineTexts(result.lines)).toEqual([
      '\t• hello',
      '\t☐ parent',
      '\t\t☐ child',
      '\t☐ sibling',
    ])
  })

  it('clamps indent to zero', () => {
    const ls = withCursor(lines('• hello'), 0, 7) // indent level 0
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(1, '☐ ', 'one'),   // indent 1
      parsed(0, '☐ ', 'two'),   // indent 0 -> would become -1, clamped to 0
    ])
    // delta = 0 - 1 = -1, so 1->0, 0->0(clamped)
    expect(lineTexts(result.lines)).toEqual(['• hello', '☐ one', '☐ two'])
  })
})

describe('combined scenarios from spec', () => {
  it('multi-line paste onto prefix-only line (source has prefix)', () => {
    const ls = withCursor(lines('• '), 0, 2) // prefix-only, cursor after "• "
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    // Prefix-only line is replaced (no content to preserve)
    expect(lineTexts(result.lines)).toEqual(['☐ one', '☐ two'])
  })

  it('multi-line paste onto prefix-only line (source has no prefix)', () => {
    const ls = withCursor(lines('• '), 0, 2)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '', 'one'),
      parsed(0, '', 'two'),
    ])
    // Adopt destination bullet; prefix-only line is replaced
    expect(lineTexts(result.lines)).toEqual(['• one', '• two'])
  })

  it('external plain text paste onto checkbox line', () => {
    const ls = withCursor(lines('☐ '), 0, 2)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '', 'one'),
      parsed(0, '', 'two'),
      parsed(0, '', 'three'),
    ])
    // Prefix-only checkbox line is replaced
    expect(lineTexts(result.lines)).toEqual(['☐ one', '☐ two', '☐ three'])
  })

  it('partial multi-line selection replacement', () => {
    // "• hello" (0-7) \n "• world" (8-15)
    // Select "llo\n• wor" = offsets 4-13
    const ls = lines('• hello', '• world')
    const result = executePaste(ls, 0, sel(4, 13), [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    // Leading: "• he", trailing: "• ld"
    expect(lineTexts(result.lines)).toEqual(['• he', '☐ one', '☐ two', '• ld'])
  })

  it('paste onto empty line replaces it', () => {
    const ls = withCursor(lines(''), 0, 0)
    const result = executePaste(ls, 0, SELECTION_NONE, [
      parsed(0, '☐ ', 'one'),
      parsed(0, '☐ ', 'two'),
    ])
    // Empty line is replaced, not scooted down
    expect(lineTexts(result.lines)).toEqual(['☐ one', '☐ two'])
  })
})
