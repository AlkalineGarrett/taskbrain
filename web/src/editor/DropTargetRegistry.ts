import type { DirectiveSegment } from '@/dsl/directives/DirectiveSegmenter'
import type { EditorState } from './EditorState'
import type { EditorController } from './EditorController'
import { hitTestLineFromPoint, type LineHitResult } from './TextMeasure'

export interface DropTarget {
  containerEl: HTMLElement
  dropCursorEl: HTMLElement | null
  getState: () => EditorState | null
  getController: () => EditorController | null
  lineAttr: string
  getSegments: ((lineIndex: number) => DirectiveSegment[] | null) | null
}

export interface DropTargetHit {
  target: DropTarget
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
   * Find the target whose container holds the point. Falls back to the target
   * with the closest container when the cursor is between/outside them, so
   * dragging slightly past a container's edge still resolves to a drop point
   * inside it instead of dropping the operation.
   */
  findTargetAtPoint(clientX: number, clientY: number): DropTargetHit | null {
    let direct: DropTarget | null = null
    let nearest: DropTarget | null = null
    let nearestDist = Infinity

    for (const t of this.targets) {
      const rect = t.containerEl.getBoundingClientRect()
      if (
        clientY >= rect.top && clientY < rect.bottom &&
        clientX >= rect.left && clientX < rect.right
      ) {
        direct = t
        break
      }
      const dyTop = rect.top - clientY
      const dyBot = clientY - rect.bottom
      const dy = dyTop > 0 ? dyTop : dyBot > 0 ? dyBot : 0
      const dxL = rect.left - clientX
      const dxR = clientX - rect.right
      const dx = dxL > 0 ? dxL : dxR > 0 ? dxR : 0
      const dist = Math.max(dx, dy)
      if (dist < nearestDist) {
        nearestDist = dist
        nearest = t
      }
    }

    const target = direct ?? nearest
    if (!target) return null
    const state = target.getState()
    if (!state) return null
    const hit = hitTestLineFromPoint(
      target.containerEl, state.lines,
      (i) => state.getLineStartOffset(i),
      clientX, clientY, target.lineAttr, target.getSegments,
    )
    if (!hit) return null
    return { target, hit }
  }

  hideAllDropCursorsExcept(except: DropTarget | null): void {
    for (const t of this.targets) {
      if (t === except) continue
      if (t.dropCursorEl) t.dropCursorEl.style.display = 'none'
    }
  }

  clear(): void {
    this.targets.clear()
  }
}

export const dropTargetRegistry = new DropTargetRegistryImpl()
