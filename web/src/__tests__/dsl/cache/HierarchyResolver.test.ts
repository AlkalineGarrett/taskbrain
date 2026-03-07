import { describe, it, expect } from 'vitest'
import { resolveHierarchyPath, findParent, findRoot } from '../../../dsl/cache/HierarchyResolver'
import type { Note } from '../../../data/Note'
import type { HierarchyPath } from '../../../dsl/cache/DirectiveDependencies'

function makeNote(id: string, path: string): Note {
  return {
    id,
    userId: 'user1',
    parentNoteId: null,
    content: `Note ${id}`,
    createdAt: null,
    updatedAt: null,
    lastAccessedAt: null,
    tags: [],
    containedNotes: [],
    state: null,
    path,
  }
}

describe('HierarchyResolver', () => {
  const root = makeNote('root', 'projects')
  const child = makeNote('child', 'projects/web')
  const grandchild = makeNote('grandchild', 'projects/web/cache')
  const greatGrandchild = makeNote('greatGrandchild', 'projects/web/cache/lru')
  const allNotes = [root, child, grandchild, greatGrandchild]

  describe('findParent', () => {
    it('finds the parent note', () => {
      const parent = findParent(grandchild, allNotes)
      expect(parent).not.toBeNull()
      expect(parent!.id).toBe('child')
    })

    it('finds parent of child', () => {
      const parent = findParent(child, allNotes)
      expect(parent).not.toBeNull()
      expect(parent!.id).toBe('root')
    })

    it('returns null for root note (no parent)', () => {
      const parent = findParent(root, allNotes)
      expect(parent).toBeNull()
    })

    it('returns null when parent path does not match any note', () => {
      const orphan = makeNote('orphan', 'unknown/orphan')
      const parent = findParent(orphan, allNotes)
      expect(parent).toBeNull()
    })
  })

  describe('findRoot', () => {
    it('finds the root note from grandchild', () => {
      const rootNote = findRoot(grandchild, allNotes)
      expect(rootNote).not.toBeNull()
      expect(rootNote!.id).toBe('root')
    })

    it('finds the root note from child', () => {
      const rootNote = findRoot(child, allNotes)
      expect(rootNote).not.toBeNull()
      expect(rootNote!.id).toBe('root')
    })

    it('returns root itself when called on root', () => {
      const rootNote = findRoot(root, allNotes)
      expect(rootNote).not.toBeNull()
      expect(rootNote!.id).toBe('root')
    })

    it('returns null for empty path', () => {
      const noPath = makeNote('nopath', '')
      const rootNote = findRoot(noPath, allNotes)
      expect(rootNote).toBeNull()
    })
  })

  describe('resolveHierarchyPath', () => {
    it('resolves Up path to parent', () => {
      const path: HierarchyPath = { kind: 'Up' }
      const result = resolveHierarchyPath(path, grandchild, allNotes)
      expect(result).not.toBeNull()
      expect(result!.id).toBe('child')
    })

    it('resolves UpN path to ancestor', () => {
      const path: HierarchyPath = { kind: 'UpN', levels: 2 }
      const result = resolveHierarchyPath(path, grandchild, allNotes)
      expect(result).not.toBeNull()
      expect(result!.id).toBe('root')
    })

    it('resolves UpN with too many levels to null', () => {
      const path: HierarchyPath = { kind: 'UpN', levels: 10 }
      const result = resolveHierarchyPath(path, grandchild, allNotes)
      expect(result).toBeNull()
    })

    it('resolves Root path', () => {
      const path: HierarchyPath = { kind: 'Root' }
      const result = resolveHierarchyPath(path, greatGrandchild, allNotes)
      expect(result).not.toBeNull()
      expect(result!.id).toBe('root')
    })

    it('resolves Up from root to null', () => {
      const path: HierarchyPath = { kind: 'Up' }
      const result = resolveHierarchyPath(path, root, allNotes)
      expect(result).toBeNull()
    })
  })
})
