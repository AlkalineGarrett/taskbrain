/**
 * Pure tree algorithms for converting between Firestore's tree structure
 * and the editor's flat indented-line format.
 *
 * Load path: Tree → flat indented lines (flattenTreeToLines)
 * Save path: Flat indented lines → tree (buildTreeFromLines)
 */

import type { Note, NoteLine } from './Note'

export interface TreeSaveNode {
  noteId: string | null
  content: string
  parentNoteId: string
  containedNoteIds: string[]
}

export interface TreeSaveData {
  rootContent: string
  rootContainedNoteIds: string[]
  nodes: TreeSaveNode[]
}

/**
 * Converts a tree of notes into flat indented lines for the editor.
 *
 * The root note is a container — its content is the title line (no tabs).
 * Root's direct children appear at depth 0 (no tabs). Sub-children get 1 tab. Etc.
 * Spacers (empty strings in containedNotes) appear at the depth of their siblings.
 */
export function flattenTreeToLines(rootNote: Note, descendants: Note[]): NoteLine[] {
  const lookup = new Map<string, Note>()
  for (const d of descendants) {
    lookup.set(d.id, d)
  }

  const lines: NoteLine[] = []

  function dfs(note: Note, depth: number) {
    const prefix = '\t'.repeat(depth)
    lines.push({ content: prefix + note.content, noteId: note.id })

    for (const childId of note.containedNotes) {
      if (childId === '') {
        lines.push({ content: '\t'.repeat(depth + 1), noteId: null })
      } else {
        const child = lookup.get(childId)
        if (child) {
          dfs(child, depth + 1)
        }
      }
    }
  }

  // Root line (title, no tabs)
  lines.push({ content: rootNote.content, noteId: rootNote.id })

  // Root's children start at depth 0 — root is a container, not an indentation level
  for (const childId of rootNote.containedNotes) {
    if (childId === '') {
      lines.push({ content: '', noteId: null })
    } else {
      const child = lookup.get(childId)
      if (child) {
        dfs(child, 0)
      }
    }
  }

  return lines
}

/**
 * Converts flat indented editor lines back into tree relationships for saving.
 *
 * Tabs determine parentage: each line's parent is the nearest preceding line
 * with one fewer tab level.
 */
export function buildTreeFromLines(rootNoteId: string, trackedLines: NoteLine[]): TreeSaveData {
  if (trackedLines.length === 0) {
    return { rootContent: '', rootContainedNoteIds: [], nodes: [] }
  }

  const rootContent = trackedLines[0]!.content.replace(/^\t+/, '')

  interface StackEntry {
    depth: number
    noteId: string
    containedNoteIds: string[]
  }

  const stack: StackEntry[] = [{ depth: 0, noteId: rootNoteId, containedNoteIds: [] }]
  const nodes: TreeSaveNode[] = []

  for (let i = 1; i < trackedLines.length; i++) {
    const line = trackedLines[i]!
    const tabMatch = line.content.match(/^\t*/)
    const depth = tabMatch ? tabMatch[0]!.length : 0
    const content = line.content.replace(/^\t+/, '')

    // Pop stack until top has depth < current
    while (stack.length > 1 && stack[stack.length - 1]!.depth >= depth) {
      stack.pop()
    }

    const parent = stack[stack.length - 1]!

    if (content === '') {
      // Spacer: add empty string to parent's containedNoteIds
      parent.containedNoteIds.push('')
    } else {
      const noteId = line.noteId
      const nodeContainedIds: string[] = []

      nodes.push({
        noteId,
        content,
        parentNoteId: parent.noteId,
        containedNoteIds: nodeContainedIds,
      })

      const effectiveId = noteId ?? `placeholder_${i}`
      parent.containedNoteIds.push(effectiveId)
      stack.push({ depth, noteId: effectiveId, containedNoteIds: nodeContainedIds })
    }
  }

  return {
    rootContent,
    rootContainedNoteIds: stack[0]!.containedNoteIds,
    nodes,
  }
}

/**
 * Detects whether descendants are in old format (flat, no rootNoteId).
 */
export function isOldFormat(descendants: Note[]): boolean {
  if (descendants.length === 0) return false
  return descendants.every((d) => d.rootNoteId == null)
}
