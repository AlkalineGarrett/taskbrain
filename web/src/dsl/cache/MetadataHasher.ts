import type { Note } from '@/data/Note'
import type { DirectiveDependencies } from './DirectiveDependencies'

/**
 * Collection-level metadata hashes for staleness checks.
 */
export interface MetadataHashes {
  pathHash: string | null
  modifiedHash: string | null
  createdHash: string | null
  viewedHash: string | null
  existenceHash: string | null
  allNamesHash: string | null
}

export const EMPTY_METADATA_HASHES: MetadataHashes = {
  pathHash: null,
  modifiedHash: null,
  createdHash: null,
  viewedHash: null,
  existenceHash: null,
  allNamesHash: null,
}

function simpleHash(input: string): string {
  let hash = 0
  for (let i = 0; i < input.length; i++) {
    const char = input.charCodeAt(i)
    hash = ((hash << 5) - hash + char) | 0
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

function sortedNotes(notes: Note[]): Note[] {
  return [...notes].sort((a, b) => a.id.localeCompare(b.id))
}

export function computePathHash(notes: Note[]): string {
  const values = sortedNotes(notes).map((n) => `${n.id}:${n.path}`)
  return simpleHash(values.join('\n'))
}

export function computeModifiedHash(notes: Note[]): string {
  const values = sortedNotes(notes).map((n) => {
    const ts = n.updatedAt?.toDate()?.getTime() ?? 0
    return `${n.id}:${ts}`
  })
  return simpleHash(values.join('\n'))
}

export function computeCreatedHash(notes: Note[]): string {
  const values = sortedNotes(notes).map((n) => {
    const ts = n.createdAt?.toDate()?.getTime() ?? 0
    return `${n.id}:${ts}`
  })
  return simpleHash(values.join('\n'))
}

export function computeViewedHash(notes: Note[]): string {
  const values = sortedNotes(notes).map((n) => {
    const ts = n.lastAccessedAt?.toDate()?.getTime() ?? 0
    return `${n.id}:${ts}`
  })
  return simpleHash(values.join('\n'))
}

export function computeExistenceHash(notes: Note[]): string {
  const sortedIds = notes.map((n) => n.id).sort()
  return simpleHash(sortedIds.join('\n'))
}

export function computeAllNamesHash(notes: Note[]): string {
  const values = sortedNotes(notes).map((n) => {
    const firstLine = n.content.split('\n')[0] ?? ''
    return `${n.id}:${firstLine}`
  })
  return simpleHash(values.join('\n'))
}

export function computeMetadataHashes(notes: Note[], deps: DirectiveDependencies): MetadataHashes {
  return {
    pathHash: deps.dependsOnPath ? computePathHash(notes) : null,
    modifiedHash: deps.dependsOnModified ? computeModifiedHash(notes) : null,
    createdHash: deps.dependsOnCreated ? computeCreatedHash(notes) : null,
    viewedHash: deps.dependsOnViewed ? computeViewedHash(notes) : null,
    existenceHash: deps.dependsOnNoteExistence ? computeExistenceHash(notes) : null,
    allNamesHash: deps.dependsOnAllNames ? computeAllNamesHash(notes) : null,
  }
}
