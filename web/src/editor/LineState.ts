import * as LP from './LinePrefixes'

/**
 * Represents the state of a single line in the editor.
 * Handles prefix extraction, cursor position, and line operations.
 */
export class LineState {
  text: string
  cursorPosition: number
  noteIds: string[]

  constructor(text: string, cursorPosition?: number, noteIds: string[] = []) {
    this.text = text
    this.cursorPosition = clamp(cursorPosition ?? text.length, 0, text.length)
    this.noteIds = noteIds
  }

  get prefix(): string {
    return extractPrefix(this.text)
  }

  get content(): string {
    const p = this.prefix
    return p.length < this.text.length ? this.text.substring(p.length) : ''
  }

  get contentCursorPosition(): number {
    return clamp(this.cursorPosition - this.prefix.length, 0, this.content.length)
  }

  updateContent(newContent: string, newContentCursor: number): void {
    this.text = this.prefix + newContent
    this.cursorPosition = this.prefix.length + clamp(newContentCursor, 0, newContent.length)
  }

  updateFull(newText: string, newCursor: number): void {
    this.text = newText
    this.cursorPosition = clamp(newCursor, 0, newText.length)
  }

  indent(): void {
    this.text = '\t' + this.text
    this.cursorPosition = clamp(this.cursorPosition + 1, 0, this.text.length)
  }

  unindent(): boolean {
    if (this.text.startsWith('\t')) {
      this.text = this.text.substring(1)
      this.cursorPosition = Math.max(this.cursorPosition - 1, 0)
      return true
    }
    return false
  }

  toggleBullet(): void {
    const tabCount = this.text.match(/^\t*/)?.[0].length ?? 0
    const tabs = this.text.substring(0, tabCount)
    const afterTabs = this.text.substring(tabCount)

    let newAfterTabs: string
    let cursorDelta: number

    if (afterTabs.startsWith(LP.BULLET)) {
      newAfterTabs = afterTabs.substring(LP.BULLET.length)
      cursorDelta = -LP.BULLET.length
    } else if (afterTabs.startsWith(LP.CHECKBOX_UNCHECKED)) {
      newAfterTabs = LP.BULLET + afterTabs.substring(LP.CHECKBOX_UNCHECKED.length)
      cursorDelta = LP.BULLET.length - LP.CHECKBOX_UNCHECKED.length
    } else if (afterTabs.startsWith(LP.CHECKBOX_CHECKED)) {
      newAfterTabs = LP.BULLET + afterTabs.substring(LP.CHECKBOX_CHECKED.length)
      cursorDelta = LP.BULLET.length - LP.CHECKBOX_CHECKED.length
    } else {
      newAfterTabs = LP.BULLET + afterTabs
      cursorDelta = LP.BULLET.length
    }

    this.text = tabs + newAfterTabs
    this.cursorPosition = clamp(this.cursorPosition + cursorDelta, 0, this.text.length)
  }

  toggleCheckbox(): void {
    const tabCount = this.text.match(/^\t*/)?.[0].length ?? 0
    const tabs = this.text.substring(0, tabCount)
    const afterTabs = this.text.substring(tabCount)

    let newAfterTabs: string
    let cursorDelta: number

    if (afterTabs.startsWith(LP.CHECKBOX_UNCHECKED)) {
      newAfterTabs = LP.CHECKBOX_CHECKED + afterTabs.substring(LP.CHECKBOX_UNCHECKED.length)
      cursorDelta = 0
    } else if (afterTabs.startsWith(LP.CHECKBOX_CHECKED)) {
      newAfterTabs = afterTabs.substring(LP.CHECKBOX_CHECKED.length)
      cursorDelta = -LP.CHECKBOX_CHECKED.length
    } else if (afterTabs.startsWith(LP.BULLET)) {
      newAfterTabs = LP.CHECKBOX_UNCHECKED + afterTabs.substring(LP.BULLET.length)
      cursorDelta = LP.CHECKBOX_UNCHECKED.length - LP.BULLET.length
    } else {
      newAfterTabs = LP.CHECKBOX_UNCHECKED + afterTabs
      cursorDelta = LP.CHECKBOX_UNCHECKED.length
    }

    this.text = tabs + newAfterTabs
    this.cursorPosition = clamp(this.cursorPosition + cursorDelta, 0, this.text.length)
  }

  toggleCheckboxState(): void {
    const tabCount = this.text.match(/^\t*/)?.[0].length ?? 0
    const tabs = this.text.substring(0, tabCount)
    const afterTabs = this.text.substring(tabCount)

    if (afterTabs.startsWith(LP.CHECKBOX_UNCHECKED)) {
      this.text = tabs + LP.CHECKBOX_CHECKED + afterTabs.substring(LP.CHECKBOX_UNCHECKED.length)
    } else if (afterTabs.startsWith(LP.CHECKBOX_CHECKED)) {
      this.text = tabs + LP.CHECKBOX_UNCHECKED + afterTabs.substring(LP.CHECKBOX_CHECKED.length)
    }
    // Cursor position doesn't change since checkbox length is the same
  }
}

export function extractPrefix(line: string): string {
  let position = 0
  while (position < line.length && line[position] === '\t') {
    position++
  }

  const afterTabs = position < line.length ? line.substring(position) : ''
  let prefixEnd: number

  if (afterTabs.startsWith(LP.BULLET)) {
    prefixEnd = position + LP.BULLET.length
  } else if (afterTabs.startsWith(LP.CHECKBOX_UNCHECKED)) {
    prefixEnd = position + LP.CHECKBOX_UNCHECKED.length
  } else if (afterTabs.startsWith(LP.CHECKBOX_CHECKED)) {
    prefixEnd = position + LP.CHECKBOX_CHECKED.length
  } else {
    prefixEnd = position
  }

  return prefixEnd > 0 ? line.substring(0, prefixEnd) : ''
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value))
}
