import type { Note } from '@/data/Note'
import type { HierarchyPath } from './DirectiveDependencies'

/**
 * Resolves hierarchy navigation paths (.up, .root) to notes.
 */

export function resolveHierarchyPath(path: HierarchyPath, note: Note, allNotes: Note[]): Note | null {
  switch (path.kind) {
    case 'Up': return findParent(note, allNotes)
    case 'UpN': return findAncestor(note, path.levels, allNotes)
    case 'Root': return findRoot(note, allNotes)
  }
}

export function findParent(note: Note, allNotes: Note[]): Note | null {
  const parentPath = getParentPath(note.path)
  if (!parentPath) return null
  return allNotes.find((n) => n.path === parentPath) ?? null
}

export function findAncestor(note: Note, levels: number, allNotes: Note[]): Note | null {
  let current: Note | null = note
  for (let i = 0; i < levels; i++) {
    current = current ? findParent(current, allNotes) : null
    if (!current) return null
  }
  return current
}

export function findRoot(note: Note, allNotes: Note[]): Note | null {
  const rootPath = getRootPath(note.path)
  if (!rootPath) return null
  return allNotes.find((n) => n.path === rootPath) ?? null
}

function getParentPath(path: string): string | null {
  const lastSlash = path.lastIndexOf('/')
  return lastSlash > 0 ? path.substring(0, lastSlash) : null
}

function getRootPath(path: string): string | null {
  if (!path) return null
  const firstSlash = path.indexOf('/')
  return firstSlash > 0 ? path.substring(0, firstSlash) : path
}
