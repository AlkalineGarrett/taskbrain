import { extractPrefix } from './LineState'
import { BULLET, CHECKBOX_UNCHECKED, CHECKBOX_CHECKED } from './LinePrefixes'

/**
 * A parsed line from clipboard content, decomposed into structural parts.
 */
export interface ParsedLine {
  indent: number    // number of tab levels
  bullet: string    // '' | '• ' | '☐ ' | '☑ '
  content: string   // text after prefix
}

// --- Internal format parsing ---

export function parseInternalLines(text: string): ParsedLine[] {
  return text.split('\n').map(parseInternalLine)
}

function parseInternalLine(line: string): ParsedLine {
  const prefix = extractPrefix(line)
  const indent = prefix.match(/^\t*/)?.[0].length ?? 0
  const afterTabs = prefix.slice(indent)
  const bullet = [BULLET, CHECKBOX_UNCHECKED, CHECKBOX_CHECKED].find(b => afterTabs === b) ?? ''
  const content = line.slice(prefix.length)
  return { indent, bullet, content }
}

// --- Markdown detection & parsing ---

const MD_BULLET = /^(\s*)([-*])\s/
const MD_CHECKBOX_UNCHECKED = /^(\s*)[-*]\s\[\s\]\s/
const MD_CHECKBOX_CHECKED = /^(\s*)[-*]\s\[[xX]\]\s/
const MD_NUMBERED = /^(\s*)\d+\.\s/

function looksLikeMarkdown(text: string): boolean {
  return text.split('\n').some(line =>
    MD_BULLET.test(line) || MD_NUMBERED.test(line),
  )
}

export function parseMarkdownLines(text: string): ParsedLine[] {
  return text.split('\n').map(parseMarkdownLine)
}

function parseMarkdownLine(line: string): ParsedLine {
  const leadingSpaces = line.match(/^(\s*)/)?.[1] ?? ''
  // Convert spaces to indent level (2 or 4 spaces per level)
  const spaceCount = leadingSpaces.replace(/\t/g, '    ').length
  const indent = Math.floor(spaceCount / 2)

  // Try each pattern once, most specific first
  const patterns: [RegExp, string][] = [
    [MD_CHECKBOX_CHECKED, CHECKBOX_CHECKED],
    [MD_CHECKBOX_UNCHECKED, CHECKBOX_UNCHECKED],
    [MD_BULLET, BULLET],
    [MD_NUMBERED, ''],
  ]

  for (const [pattern, bullet] of patterns) {
    const match = line.match(pattern)
    if (match) {
      return { indent, bullet, content: line.slice(match[0].length) }
    }
  }

  return { indent, bullet: '', content: line.trimStart() }
}

// --- HTML parsing ---

export function parseHtmlLines(html: string): ParsedLine[] | null {
  try {
    const parser = new DOMParser()
    const doc = parser.parseFromString(html, 'text/html')
    const body = doc.body
    if (!body) return null

    // Only use HTML parsing if there are list elements
    if (!body.querySelector('ul, ol')) return null

    const lines: ParsedLine[] = []
    walkHtmlNodes(body, 0, 'ul', lines)
    return lines.length > 0 ? lines : null
  } catch {
    return null
  }
}

function walkHtmlNodes(
  node: Node,
  depth: number,
  listType: 'ul' | 'ol',
  lines: ParsedLine[],
): void {
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      const text = child.textContent?.trim() ?? ''
      if (text) {
        lines.push({ indent: Math.max(0, depth - 1), bullet: '', content: text })
      }
      continue
    }

    if (!(child instanceof Element)) continue
    const tag = child.tagName.toLowerCase()

    if (tag === 'ul' || tag === 'ol') {
      walkHtmlNodes(child, depth + 1, tag as 'ul' | 'ol', lines)
    } else if (tag === 'li') {
      const bullet = listType === 'ul' ? detectLiBullet(child) : ''
      const indent = Math.max(0, depth - 1)
      const textContent = extractLiText(child)
      if (textContent || bullet) {
        lines.push({ indent, bullet, content: textContent })
      }
      // Process nested lists within this <li>
      for (const nested of Array.from(child.children)) {
        const nestedTag = nested.tagName.toLowerCase()
        if (nestedTag === 'ul' || nestedTag === 'ol') {
          walkHtmlNodes(nested, depth + 1, nestedTag as 'ul' | 'ol', lines)
        }
      }
    } else if (tag === 'p' || tag === 'div') {
      const text = child.textContent?.trim() ?? ''
      if (text) {
        lines.push({ indent: Math.max(0, depth - 1), bullet: '', content: text })
      }
    } else if (tag === 'br') {
      // Line break — only add empty line if there are existing lines
      if (lines.length > 0) {
        lines.push({ indent: 0, bullet: '', content: '' })
      }
    }
  }
}

function detectLiBullet(li: Element): string {
  // Check for checkbox input
  const checkbox = li.querySelector('input[type="checkbox"]')
  if (checkbox) {
    const checked = (checkbox as HTMLInputElement).checked ||
      checkbox.hasAttribute('checked')
    return checked ? CHECKBOX_CHECKED : CHECKBOX_UNCHECKED
  }
  // Check for data-checked attribute (Notion, some editors)
  if (li.hasAttribute('data-checked')) {
    return li.getAttribute('data-checked') === 'true' ? CHECKBOX_CHECKED : CHECKBOX_UNCHECKED
  }
  return BULLET
}

function extractLiText(li: Element): string {
  // Get direct text content, excluding nested ul/ol
  const parts: string[] = []
  for (const child of Array.from(li.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      const text = child.textContent?.trim() ?? ''
      if (text) parts.push(text)
    } else if (child instanceof Element) {
      const tag = child.tagName.toLowerCase()
      if (tag !== 'ul' && tag !== 'ol' && tag !== 'input') {
        const text = child.textContent?.trim() ?? ''
        if (text) parts.push(text)
      }
    }
  }
  return parts.join(' ')
}

// --- Top-level parser ---

export function parseClipboardContent(plainText: string, html: string | null): ParsedLine[] {
  // Normalize line endings (CRLF → LF, CR → LF)
  plainText = plainText.replace(/\r\n/g, '\n').replace(/\r/g, '\n')

  // Try HTML first if it contains lists
  if (html) {
    const htmlLines = parseHtmlLines(html)
    if (htmlLines) return htmlLines
  }

  // Try markdown detection
  if (looksLikeMarkdown(plainText)) {
    return parseMarkdownLines(plainText)
  }

  // Default: parse as internal format
  return parseInternalLines(plainText)
}

// --- Helpers ---

export function parsedLineHasPrefix(line: ParsedLine): boolean {
  return line.indent > 0 || line.bullet.length > 0
}

export function buildLineText(line: ParsedLine): string {
  return '\t'.repeat(line.indent) + line.bullet + line.content
}
