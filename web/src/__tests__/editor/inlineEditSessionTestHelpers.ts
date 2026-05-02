import type { NoteLine } from '@/data/Note'

/**
 * Build a NoteLine[] for tests that previously passed a content string to
 * `new InlineEditSession(noteId, content)`. The constructor now requires
 * pre-resolved structurally-valid lines; this helper produces them with
 * synthetic non-null descendant ids so tests don't depend on the removed
 * null-synth path.
 */
export function linesFromContent(noteId: string, content: string): NoteLine[] {
  return content.split('\n').map((c, i) => ({
    content: c,
    noteId: i === 0 ? noteId : `${noteId}-l${i}`,
  }))
}
