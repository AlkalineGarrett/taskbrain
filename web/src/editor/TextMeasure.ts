import type { DirectiveSegment } from '@/dsl/directives/DirectiveSegmenter'
import { directiveResultToValue } from '@/dsl/directives/DirectiveResult'

/** Measures text width using a canvas context for fast, accurate results. */
let measureCanvas: HTMLCanvasElement | null = null

function getContext(font: string): CanvasRenderingContext2D {
  if (!measureCanvas) measureCanvas = document.createElement('canvas')
  const ctx = measureCanvas.getContext('2d')!
  ctx.font = font
  return ctx
}

/** Returns the character index in `text` closest to `x` pixels from the left. */
export function getCharIndexAtX(text: string, x: number, font: string): number {
  if (text.length === 0 || x <= 0) return 0
  const ctx = getContext(font)
  const fullWidth = ctx.measureText(text).width
  if (x >= fullWidth) return text.length

  // Binary search for the closest character boundary
  let lo = 0
  let hi = text.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    const midWidth = ctx.measureText(text.substring(0, mid + 1)).width
    if (midWidth <= x) {
      lo = mid + 1
    } else {
      // Check if we're closer to mid or mid+1
      const prevWidth = mid > 0 ? ctx.measureText(text.substring(0, mid)).width : 0
      if (x - prevWidth < midWidth - x) {
        hi = mid
      } else {
        lo = mid + 1
        hi = lo
      }
    }
  }
  return lo
}

/** Returns the pixel X offset for a given character index in `text`. */
export function getXAtCharIndex(text: string, charIndex: number, font: string): number {
  if (charIndex <= 0 || text.length === 0) return 0
  const ctx = getContext(font)
  return ctx.measureText(text.substring(0, charIndex)).width
}

/** Returns [start, end) bounds of the word at the given character index. */
export function getWordBoundsAt(text: string, charIndex: number): [number, number] {
  const isWordChar = (c: string) => /\w/.test(c)
  const idx = Math.min(charIndex, text.length - 1)
  if (idx < 0) return [0, 0]

  // If clicked on non-word char, select just that character
  if (!isWordChar(text[idx]!)) {
    return [idx, idx + 1]
  }

  let start = idx
  while (start > 0 && isWordChar(text[start - 1]!)) start--
  let end = idx
  while (end < text.length && isWordChar(text[end]!)) end++
  return [start, end]
}

/** Gets the computed font string from an element (for use with canvas measurement). */
export function getComputedFont(element: HTMLElement): string {
  const style = getComputedStyle(element)
  return `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
}

/**
 * Gets the character offset within a container's text content from screen coordinates,
 * using the browser's caret position API. Works correctly with wrapped text.
 */
export function getCharOffsetFromPoint(
  container: HTMLElement,
  clientX: number,
  clientY: number,
): number | null {
  let node: Node
  let offsetInNode: number

  // Standard API (Chrome 128+, Firefox, Safari 18+), then WebKit/Blink fallback
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const doc = document as any
  if (typeof doc.caretPositionFromPoint === 'function') {
    const pos = doc.caretPositionFromPoint(clientX, clientY) as { offsetNode: Node; offset: number } | null
    if (!pos) return null
    node = pos.offsetNode
    offsetInNode = pos.offset
  } else if (document.caretRangeFromPoint) {
    const range = document.caretRangeFromPoint(clientX, clientY)
    if (!range) return null
    node = range.startContainer
    offsetInNode = range.startOffset
  } else {
    return null
  }

  // If the API returned an element node (not a text node), resolve to a text position.
  // This happens when clicking at element boundaries (e.g., between spans).
  if (node.nodeType === Node.ELEMENT_NODE) {
    const children = node.childNodes
    if (offsetInNode >= children.length) {
      // Past all children — find the last text node descendant
      const lastWalker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT)
      let last: Node | null = null
      let n = lastWalker.nextNode()
      while (n) { last = n; n = lastWalker.nextNode() }
      if (last) {
        node = last
        offsetInNode = last.textContent?.length ?? 0
      }
    } else {
      const child = children[offsetInNode]
      if (child) {
        if (child.nodeType === Node.TEXT_NODE) {
          node = child
          offsetInNode = 0
        } else {
          // Find first text node in this child
          const childWalker = document.createTreeWalker(child, NodeFilter.SHOW_TEXT)
          const firstText = childWalker.nextNode()
          if (firstText) {
            node = firstText
            offsetInNode = 0
          }
        }
      }
    }
  }

  // Walk text nodes to compute total offset within the container
  let totalOffset = 0
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT)
  let textNode = walker.nextNode()
  while (textNode) {
    if (textNode === node) {
      return totalOffset + offsetInNode
    }
    totalOffset += textNode.textContent?.length ?? 0
    textNode = walker.nextNode()
  }

  // Node not found in container — check if it's outside the container entirely.
  // This can happen if caretRangeFromPoint hit a different element.
  // Fall back to nearest edge based on Y position.
  if (!container.contains(node)) {
    const rect = container.getBoundingClientRect()
    if (clientY < rect.top) return 0
    if (clientY > rect.bottom) return container.textContent?.length ?? 0
    // Same vertical range but different element — estimate from X
    if (clientX <= rect.left) return 0
    return container.textContent?.length ?? 0
  }

  return null
}

/**
 * Gets the character offset within an overlay element, temporarily hiding all sibling
 * elements (textarea, highlight layers, etc.) so that caretRangeFromPoint only hits
 * the overlay's text nodes.
 */
export function getCharOffsetHidingTextarea(
  overlay: HTMLElement,
  _textarea: HTMLElement,
  clientX: number,
  clientY: number,
): number | null {
  // Hide all siblings so caretRangeFromPoint only sees the overlay text
  const parent = overlay.parentElement
  const hiddenSiblings: HTMLElement[] = []
  if (parent) {
    for (const child of parent.children) {
      if (child !== overlay && child instanceof HTMLElement && child.style.visibility !== 'hidden') {
        child.style.visibility = 'hidden'
        hiddenSiblings.push(child)
      }
    }
  }
  const offset = getCharOffsetFromPoint(overlay, clientX, clientY)
  for (const el of hiddenSiblings) {
    el.style.visibility = ''
  }
  return offset
}

/** Gets the bounding rect of a character position within an element's text nodes. */
export function getCharRectInElement(
  element: HTMLElement,
  charIndex: number,
): DOMRect | null {
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
  let remaining = charIndex
  let textNode = walker.nextNode()
  while (textNode) {
    const len = textNode.textContent?.length ?? 0
    if (remaining <= len) {
      const range = document.createRange()
      range.setStart(textNode, remaining)
      range.setEnd(textNode, remaining)
      const rects = range.getClientRects()
      return rects[0] ?? range.getBoundingClientRect()
    }
    remaining -= len
    textNode = walker.nextNode()
  }
  return null
}

/** Pixel tolerance for comparing visual row positions. */
const VISUAL_ROW_TOLERANCE_PX = 2

/** Returns true if the given character index is on the first visual row of the element. */
export function isOnFirstVisualRow(element: HTMLElement, charIndex: number): boolean {
  if (charIndex === 0) return true
  const firstRect = getCharRectInElement(element, 0)
  const cursorRect = getCharRectInElement(element, charIndex)
  if (!firstRect || !cursorRect) return true
  return Math.abs(cursorRect.top - firstRect.top) < VISUAL_ROW_TOLERANCE_PX
}

/** Returns true if the given character index is on the last visual row of the element. */
export function isOnLastVisualRow(element: HTMLElement, charIndex: number, totalLength: number): boolean {
  if (charIndex >= totalLength) return true
  const lastRect = getCharRectInElement(element, Math.max(0, totalLength - 1))
  const cursorRect = getCharRectInElement(element, charIndex)
  if (!lastRect || !cursorRect) return true
  return Math.abs(cursorRect.top - lastRect.top) < VISUAL_ROW_TOLERANCE_PX
}

/**
 * Maps a character offset in the displayed directive content (text segments + chip text)
 * back to a character offset in the source content (with raw directive syntax).
 *
 * Display text has rendered chip text (e.g., "⏰" for alarms), while source has
 * the directive syntax (e.g., "[alarm(id)]"). This walks through segments to convert.
 */
export function mapDisplayOffsetToSource(
  displayOffset: number,
  segments: DirectiveSegment[],
): number {
  let displayPos = 0
  for (const segment of segments) {
    if (segment.kind === 'Text') {
      const segLen = segment.content.length
      if (displayOffset <= displayPos + segLen) {
        return segment.rangeStart + (displayOffset - displayPos)
      }
      displayPos += segLen
    } else {
      // Directive segment: compute the display length from what DirectiveChip renders
      const value = segment.result ? directiveResultToValue(segment.result) : null
      let chipDisplayLen: number
      if (value?.kind === 'AlarmVal') {
        chipDisplayLen = '⏰'.length
      } else if (value?.kind === 'ButtonVal') {
        chipDisplayLen = `▶ ${value.label}`.length
      } else {
        chipDisplayLen = segment.displayText.length
      }
      if (displayOffset <= displayPos + chipDisplayLen) {
        // Click landed on a directive chip — place cursor after the directive
        return segment.rangeEnd
      }
      displayPos += chipDisplayLen
    }
  }
  // Past all segments — return end of content
  const last = segments[segments.length - 1]
  return last ? last.rangeEnd : 0
}
