import { useLayoutEffect, useState, type RefObject } from 'react'
import type { DirectiveResult } from '@/dsl/directives/DirectiveResult'
import type { LineState } from '@/editor/LineState'
import { segmentLine } from '@/dsl/directives/DirectiveSegmenter'
import { mapSourceOffsetToDisplay } from '@/editor/TextMeasure'

interface UseSelectionRectsOptions {
  /** Inclusive-exclusive content-relative selection range, or null for no selection on this line. */
  contentSelection: [number, number] | null
  content: string
  line: LineState
  directiveResults: Map<string, DirectiveResult> | undefined
  /** Overlay element used by regular (non-directive) lines. Preferred when present. */
  overlayRef: RefObject<HTMLDivElement | null>
  /** Directive content element used by chip lines (when overlay is absent). */
  directiveContentRef: RefObject<HTMLDivElement | null>
}

/**
 * Computes selection-highlight rectangles for the content range on a single
 * editor line. Walks text nodes inside whichever element is rendered (overlay
 * or directive chip content), builds a Range, and returns one DOMRect per
 * visual row, expanded to the element's full line-height so the highlight
 * doesn't sit only behind the glyph cap.
 */
export function useSelectionRects({
  contentSelection,
  content,
  line,
  directiveResults,
  overlayRef,
  directiveContentRef,
}: UseSelectionRectsOptions): DOMRect[] {
  const [selectionRects, setSelectionRects] = useState<DOMRect[]>([])
  const hasContentSelection = contentSelection != null && contentSelection[0] < contentSelection[1]

  useLayoutEffect(() => {
    if (!hasContentSelection || !contentSelection) {
      if (selectionRects.length > 0) setSelectionRects([])
      return
    }

    const el = overlayRef.current ?? directiveContentRef.current
    if (!el) {
      if (selectionRects.length > 0) setSelectionRects([])
      return
    }

    let selStart = contentSelection[0]
    let selEnd = contentSelection[1]
    if (!overlayRef.current && directiveContentRef.current) {
      const segments = segmentLine(content, line.effectiveId, directiveResults ?? new Map(), line.noteIds[0])
      selStart = mapSourceOffsetToDisplay(selStart, segments)
      selEnd = mapSourceOffsetToDisplay(selEnd, segments)
    }

    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    let startNode: Node | null = null
    let startOffset = 0
    let endNode: Node | null = null
    let endOffset = 0
    let accumulated = 0
    let textNode = walker.nextNode()
    while (textNode) {
      const len = textNode.textContent?.length ?? 0
      if (!startNode && accumulated + len > selStart) {
        startNode = textNode
        startOffset = selStart - accumulated
      }
      if (accumulated + len >= selEnd) {
        endNode = textNode
        endOffset = selEnd - accumulated
        break
      }
      accumulated += len
      textNode = walker.nextNode()
    }
    if (!startNode || !endNode) {
      if (selectionRects.length > 0) setSelectionRects([])
      return
    }
    const range = document.createRange()
    range.setStart(startNode, startOffset)
    range.setEnd(endNode, endOffset)
    const elRect = el.getBoundingClientRect()
    const parsedLineHeight = parseFloat(getComputedStyle(el).lineHeight)
    const lineHeight = isNaN(parsedLineHeight) ? el.getBoundingClientRect().height : parsedLineHeight
    const rects: DOMRect[] = []
    const rawRects = range.getClientRects()
    // Range can return multiple rects per visual row; keep one per distinct top.
    const seenTops: number[] = []
    for (let i = 0; i < rawRects.length; i++) {
      const r = rawRects[i]!
      if (seenTops.some(t => Math.abs(t - r.top) < 2)) continue
      seenTops.push(r.top)
      // Expand to full line-height, centered on the glyph center.
      const glyphCenter = r.top + r.height / 2
      const top = glyphCenter - lineHeight / 2
      rects.push(new DOMRect(
        r.left - elRect.left,
        top - elRect.top,
        r.right - r.left,
        lineHeight,
      ))
    }
    setSelectionRects(rects)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contentSelection?.[0], contentSelection?.[1], content, hasContentSelection, directiveResults])

  return selectionRects
}
