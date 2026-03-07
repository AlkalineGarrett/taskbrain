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
  /** Depends on viewed/accessed timestamps */
  dependsOnViewed: boolean
  /** Depends on note existence (find() was used) */
  dependsOnNoteExistence: boolean
  /** Depends on all note names/first lines */
  dependsOnAllNames: boolean

  /** Dependencies on parent/ancestor notes */
  hierarchyDeps: HierarchyDependency[]

  /** Whether this directive references the current note (.) */
  usesSelfAccess: boolean
}

export const EMPTY_DEPENDENCIES: DirectiveDependencies = {
  firstLineNotes: new Set(),
  nonFirstLineNotes: new Set(),
  dependsOnPath: false,
  dependsOnModified: false,
  dependsOnCreated: false,
  dependsOnViewed: false,
  dependsOnNoteExistence: false,
  dependsOnAllNames: false,
  hierarchyDeps: [],
  usesSelfAccess: false,
}

export function mergeDependencies(a: DirectiveDependencies, b: DirectiveDependencies): DirectiveDependencies {
  return {
    firstLineNotes: new Set([...a.firstLineNotes, ...b.firstLineNotes]),
    nonFirstLineNotes: new Set([...a.nonFirstLineNotes, ...b.nonFirstLineNotes]),
    dependsOnPath: a.dependsOnPath || b.dependsOnPath,
    dependsOnModified: a.dependsOnModified || b.dependsOnModified,
    dependsOnCreated: a.dependsOnCreated || b.dependsOnCreated,
    dependsOnViewed: a.dependsOnViewed || b.dependsOnViewed,
    dependsOnNoteExistence: a.dependsOnNoteExistence || b.dependsOnNoteExistence,
    dependsOnAllNames: a.dependsOnAllNames || b.dependsOnAllNames,
    hierarchyDeps: [...a.hierarchyDeps, ...b.hierarchyDeps],
    usesSelfAccess: a.usesSelfAccess || b.usesSelfAccess,
  }
}

export function isDependenciesEmpty(deps: DirectiveDependencies): boolean {
  return (
    deps.firstLineNotes.size === 0 &&
    deps.nonFirstLineNotes.size === 0 &&
    !deps.dependsOnPath &&
    !deps.dependsOnModified &&
    !deps.dependsOnCreated &&
    !deps.dependsOnViewed &&
    !deps.dependsOnNoteExistence &&
    !deps.dependsOnAllNames &&
    deps.hierarchyDeps.length === 0 &&
    !deps.usesSelfAccess
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
  VIEWED = 'VIEWED',
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
