import type { Note } from './Note'
import {
  sortByUpdatedAtDescending,
} from './NoteFilteringUtils'

export interface SearchMatch {
  lineIndex: number
  matchStart: number
  matchEnd: number
}

export interface SnippetLine {
  text: string
  lineIndex: number
}

export interface ContentSnippet {
  lines: SnippetLine[]
  matches: SearchMatch[]
}

export interface NoteSearchResult {
  note: Note
  nameMatches: SearchMatch[]
  contentSnippets: ContentSnippet[]
}

const MAX_CONTENT_MATCHES = 3
const CONTEXT_LINES = 2

export function searchNotes(
  notes: Note[],
  query: string,
  searchByName: boolean,
  searchByContent: boolean,
): { active: NoteSearchResult[]; deleted: NoteSearchResult[] } {
  if (!query || (!searchByName && !searchByContent)) {
    return { active: [], deleted: [] }
  }

  const activeResults: NoteSearchResult[] = []
  const deletedResults: NoteSearchResult[] = []

  for (const note of notes) {
    if (note.parentNoteId != null) continue

    const lines = note.content.split('\n')
    const firstLine = lines[0] ?? ''
    const nameMatches = searchByName ? findMatches(firstLine, query, 0) : []
    const contentSnippets = searchByContent ? findContentSnippets(lines, query) : []

    if (nameMatches.length === 0 && contentSnippets.length === 0) continue

    const result: NoteSearchResult = { note, nameMatches, contentSnippets }
    if (note.state === 'deleted') {
      deletedResults.push(result)
    } else {
      activeResults.push(result)
    }
  }

  const sortedActive = sortByUpdatedAtDescending(activeResults.map((r) => r.note))
  const sortedDeleted = sortByUpdatedAtDescending(deletedResults.map((r) => r.note))

  return {
    active: sortedActive.map((n) => activeResults.find((r) => r.note.id === n.id)!),
    deleted: sortedDeleted.map((n) => deletedResults.find((r) => r.note.id === n.id)!),
  }
}

function findMatches(text: string, query: string, lineIndex: number): SearchMatch[] {
  const matches: SearchMatch[] = []
  const lowerText = text.toLowerCase()
  const lowerQuery = query.toLowerCase()
  let startIndex = 0
  while (true) {
    const idx = lowerText.indexOf(lowerQuery, startIndex)
    if (idx < 0) break
    matches.push({ lineIndex, matchStart: idx, matchEnd: idx + query.length })
    startIndex = idx + 1
  }
  return matches
}

function findContentSnippets(lines: string[], query: string): ContentSnippet[] {
  const allMatches: SearchMatch[] = []
  for (let i = 1; i < lines.length; i++) {
    for (const match of findMatches(lines[i]!, query, i)) {
      allMatches.push(match)
      if (allMatches.length >= MAX_CONTENT_MATCHES) break
    }
    if (allMatches.length >= MAX_CONTENT_MATCHES) break
  }
  if (allMatches.length === 0) return []

  return mergeIntoSnippets(allMatches, lines)
}

function mergeIntoSnippets(matches: SearchMatch[], lines: string[]): ContentSnippet[] {
  const snippets: ContentSnippet[] = []
  let currentStart = -1
  let currentEnd = -1
  let currentMatches: SearchMatch[] = []

  for (const match of matches) {
    const rangeStart = Math.max(1, match.lineIndex - CONTEXT_LINES)
    const rangeEnd = Math.min(lines.length - 1, match.lineIndex + CONTEXT_LINES)

    if (currentStart < 0 || rangeStart > currentEnd + 1) {
      if (currentStart >= 0) {
        snippets.push(buildSnippet(currentStart, currentEnd, currentMatches, lines))
      }
      currentStart = rangeStart
      currentEnd = rangeEnd
      currentMatches = [match]
    } else {
      currentEnd = Math.max(currentEnd, rangeEnd)
      currentMatches.push(match)
    }
  }

  if (currentStart >= 0) {
    snippets.push(buildSnippet(currentStart, currentEnd, currentMatches, lines))
  }

  return snippets
}

function buildSnippet(
  start: number,
  end: number,
  matches: SearchMatch[],
  lines: string[],
): ContentSnippet {
  const snippetLines: SnippetLine[] = []
  for (let i = start; i <= end; i++) {
    snippetLines.push({ text: lines[i] ?? '', lineIndex: i })
  }
  return { lines: snippetLines, matches }
}
