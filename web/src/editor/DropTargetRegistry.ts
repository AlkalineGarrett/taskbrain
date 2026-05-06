import type { DirectiveSegment } from '@/dsl/directives/DirectiveSegmenter'
import type { EditorState } from './EditorState'
import type { EditorController } from './EditorController'
import { hitTestLineFromPoint, type LineHitResult } from './TextMeasure'

export interface DropTarget {
  /** Source of truth for the container DOM node — read at hit-test time so
   *  re-renders that swap the underlying element don't leave a stale snapshot. */
  getContainer: () => HTMLElement | null
  getDropCursor: () => HTMLElement | null
  getState: () => EditorState | null
  getController: () => EditorController | null
  lineAttr: string
  getSegments: ((lineIndex: number) => DirectiveSegment[] | null) | null
}

export interface DropTargetHit {
  target: DropTarget
  containerEl: HTMLElement
  hit: LineHitResult
}

/**
 * Process-wide registry of editor drop targets so a move-drag started in one
 * editor (main or any embedded view) can find a target in another.
 *
 * Each editor (main + each ViewNoteSection) registers its container, drop
 * cursor, and accessors. The drag handler in `useEditorInteractions` queries
 * this registry on mousemove/mouseup to hit-test across editor boundaries.
 *
 * Mirrors the singleton pattern used by NoteStore / NoteStatsRepository — a
 * page hosts one editor surface, so a module singleton is appropriate.
 */
class DropTargetRegistryImpl {
  private readonly targets = new Set<DropTarget>()

  register(target: DropTarget): () => void {
    this.targets.add(target)
    return () => { this.targets.delete(target) }
  }

  /**
   * Find the target whose container holds the point. When multiple targets
   * contain it (the main editor's container nests the embedded views), the
   * smallest-area match wins so the embedded section beats its enclosing
   * parent. Falls back to the closest target by Chebyshev distance when the
   * cursor is outside every container.
   */
  findTargetAtPoint(clientX: number, clientY: number): DropTargetHit | null {
    let direct: { target: DropTarget; el: HTMLElement } | null = null
    let directArea = Infinity
    let nearest: { target: DropTarget; el: HTMLElement } | null = null
    let nearestDist = Infinity

    for (const t of this.targets) {
      const el = t.getContainer()
      if (!el) continue
      const rect = el.getBoundingClientRect()
      const inside =
        clientY >= rect.top && clientY < rect.bottom &&
        clientX >= rect.left && clientX < rect.right
      if (inside) {
        const area = rect.width * rect.height
        if (area < directArea) {
          directArea = area
          direct = { target: t, el }
        }
        continue
      }
      // Chebyshev distance from point to rect.
      const dy = Math.max(0, rect.top - clientY, clientY - rect.bottom)
      const dx = Math.max(0, rect.left - clientX, clientX - rect.right)
      const dist = Math.max(dx, dy)
      if (dist < nearestDist) {
        nearestDist = dist
        nearest = { target: t, el }
      }
    }

    const picked = direct ?? nearest
    if (!picked) return null
    const state = picked.target.getState()
    if (!state) return null
    const hit = hitTestLineFromPoint(
      picked.el, state.lines,
      (i) => state.getLineStartOffset(i),
      clientX, clientY, picked.target.lineAttr, picked.target.getSegments,
    )
    if (!hit) return null
    return { target: picked.target, containerEl: picked.el, hit }
  }

  hideAllDropCursorsExcept(except: DropTarget | null): void {
    for (const t of this.targets) {
      if (t === except) continue
      const cursor = t.getDropCursor()
      if (cursor) cursor.style.display = 'none'
    }
  }

  clear(): void {
    this.targets.clear()
  }
}

export const dropTargetRegistry = new DropTargetRegistryImpl()
