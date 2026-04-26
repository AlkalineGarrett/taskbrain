/**
 * Tracks what data a directive depends on for cache invalidation.
 */
export interface DirectiveDependencies {
  /** Note IDs where first line (name) was accessed */
  firstLineNotes: Set<string>
  /** Note IDs where content beyond first line was accessed */
  nonFirstLineNotes: Set<string>

  /** Depends on note paths */
  dependsOnPath: boolean
  /** Depends on modified timestamps */
  dependsOnModified: boolean
  /** Depends on created timestamps */
  dependsOnCreated: boolean
  /** Depends on note existence (find() was used) */
  dependsOnNoteExistence: boolean
  /** Depends on all note names/first lines */
  dependsOnAllNames: boolean

  /** Dependencies on parent/ancestor notes */
  hierarchyDeps: HierarchyDependency[]
}

export const EMPTY_DEPENDENCIES: DirectiveDependencies = {
  firstLineNotes: new Set(),
  nonFirstLineNotes: new Set(),
  dependsOnPath: false,
  dependsOnModified: false,
  dependsOnCreated: false,
  dependsOnNoteExistence: false,
  dependsOnAllNames: false,
  hierarchyDeps: [],
}

export function mergeDependencies(a: DirectiveDependencies, b: DirectiveDependencies): DirectiveDependencies {
  return {
    firstLineNotes: new Set([...a.firstLineNotes, ...b.firstLineNotes]),
    nonFirstLineNotes: new Set([...a.nonFirstLineNotes, ...b.nonFirstLineNotes]),
    dependsOnPath: a.dependsOnPath || b.dependsOnPath,
    dependsOnModified: a.dependsOnModified || b.dependsOnModified,
    dependsOnCreated: a.dependsOnCreated || b.dependsOnCreated,
    dependsOnNoteExistence: a.dependsOnNoteExistence || b.dependsOnNoteExistence,
    dependsOnAllNames: a.dependsOnAllNames || b.dependsOnAllNames,
    hierarchyDeps: [...a.hierarchyDeps, ...b.hierarchyDeps],
  }
}

export function isDependenciesEmpty(deps: DirectiveDependencies): boolean {
  return (
    deps.firstLineNotes.size === 0 &&
    deps.nonFirstLineNotes.size === 0 &&
    !deps.dependsOnPath &&
    !deps.dependsOnModified &&
    !deps.dependsOnCreated &&
    !deps.dependsOnNoteExistence &&
    !deps.dependsOnAllNames &&
    deps.hierarchyDeps.length === 0
  )
}

// --- Hierarchy types ---

export type HierarchyPath =
  | { kind: 'Up' }
  | { kind: 'UpN'; levels: number }
  | { kind: 'Root' }

export enum NoteField {
  NAME = 'NAME',
  PATH = 'PATH',
  MODIFIED = 'MODIFIED',
  CREATED = 'CREATED',
}

export interface HierarchyDependency {
  path: HierarchyPath
  resolvedNoteId: string | null
  field: NoteField | null
  fieldHash: string | null
}

export interface HierarchyAccessPattern {
  path: HierarchyPath
  field: NoteField | null
}
