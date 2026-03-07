import { findDirectives } from './DirectiveFinder'

export interface DirectiveInstance {
  uuid: string
  lineIndex: number
  startOffset: number
  sourceText: string
}

export interface ParsedDirectiveLocation {
  lineIndex: number
  startOffset: number
  sourceText: string
}

let uuidCounter = 0

function generateUUID(): string {
  return `${Date.now()}-${++uuidCounter}-${Math.random().toString(36).slice(2, 9)}`
}

export function createDirectiveInstance(lineIndex: number, startOffset: number, sourceText: string): DirectiveInstance {
  return { uuid: generateUUID(), lineIndex, startOffset, sourceText }
}

/**
 * Matches new directive locations to existing instances, preserving UUIDs where possible.
 *
 * Matching algorithm (in priority order):
 * 1. Exact match: same line, same offset, same text
 * 2. Same line shift: same line, same text, different offset
 * 3. Line move: different line, same text, unique candidate
 * 4. No match: generate new UUID
 */
export function matchDirectiveInstances(
  existing: DirectiveInstance[],
  newDirectives: ParsedDirectiveLocation[],
): DirectiveInstance[] {
  if (newDirectives.length === 0) return []
  if (existing.length === 0) {
    return newDirectives.map((d) => createDirectiveInstance(d.lineIndex, d.startOffset, d.sourceText))
  }

  const result: DirectiveInstance[] = []
  const usedUUIDs = new Set<string>()
  const unmatched = [...newDirectives]

  // Pass 1: Exact match
  for (let i = unmatched.length - 1; i >= 0; i--) {
    const newDir = unmatched[i]!
    const exact = existing.find(
      (e) => !usedUUIDs.has(e.uuid) && e.lineIndex === newDir.lineIndex && e.startOffset === newDir.startOffset && e.sourceText === newDir.sourceText,
    )
    if (exact) {
      result.push({ ...exact, lineIndex: newDir.lineIndex, startOffset: newDir.startOffset })
      usedUUIDs.add(exact.uuid)
      unmatched.splice(i, 1)
    }
  }

  // Pass 2: Same line shift
  for (let i = unmatched.length - 1; i >= 0; i--) {
    const newDir = unmatched[i]!
    const sameLine = existing.find(
      (e) => !usedUUIDs.has(e.uuid) && e.lineIndex === newDir.lineIndex && e.sourceText === newDir.sourceText,
    )
    if (sameLine) {
      result.push({ ...sameLine, lineIndex: newDir.lineIndex, startOffset: newDir.startOffset })
      usedUUIDs.add(sameLine.uuid)
      unmatched.splice(i, 1)
    }
  }

  // Pass 3: Line move (unique candidate)
  for (let i = unmatched.length - 1; i >= 0; i--) {
    const newDir = unmatched[i]!
    const candidates = existing.filter((e) => !usedUUIDs.has(e.uuid) && e.sourceText === newDir.sourceText)
    if (candidates.length === 1) {
      const match = candidates[0]!
      result.push({ ...match, lineIndex: newDir.lineIndex, startOffset: newDir.startOffset })
      usedUUIDs.add(match.uuid)
      unmatched.splice(i, 1)
    }
  }

  // Pass 4: New UUIDs
  for (const newDir of unmatched) {
    result.push(createDirectiveInstance(newDir.lineIndex, newDir.startOffset, newDir.sourceText))
  }

  return result
}

/**
 * Parses all directives from content and returns their locations.
 */
export function parseAllDirectiveLocations(content: string): ParsedDirectiveLocation[] {
  const result: ParsedDirectiveLocation[] = []
  const lines = content.split('\n')
  for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
    const directives = findDirectives(lines[lineIndex]!)
    for (const directive of directives) {
      result.push({ lineIndex, startOffset: directive.startOffset, sourceText: directive.sourceText })
    }
  }
  return result
}
