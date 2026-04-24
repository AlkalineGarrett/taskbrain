/**
 * Placeholder noteIds attached to lines that don't yet have a Firestore doc.
 *
 * A plain `null` noteId is ambiguous — it can mean "new line the user just typed"
 * (expected) or "some lossy path dropped the id" (bug). Sentinel ids disambiguate:
 *
 *  - `@typed_xxx`     — user typed a fresh line (Enter, first keystroke on empty line)
 *  - `@paste_xxx`     — pasted from clipboard
 *  - `@split_xxx`     — line split via Enter mid-line
 *  - `@agent_xxx`     — produced by the AI agent
 *  - `@directive_xxx` — produced by a directive runtime
 *  - `@surgical_xxx`  — middle line of a multi-line replace-range
 *
 * Save path: any noteId starting with the sentinel prefix is stripped and a fresh
 * Firestore doc is allocated. The origin tag is logged so fresh-doc allocations
 * can be attributed to a feature. Any `null` that reaches save is logged as an
 * error — it means an upstream path wiped a real id.
 *
 * The prefix is `@`, which never appears in Firestore's auto-generated doc ids
 * (alphanumeric only), so sentinels are trivially distinguishable at runtime.
 */

export const NOTE_ID_SENTINEL_PREFIX = '@'

export type NoteIdSentinelOrigin =
  | 'typed'
  | 'paste'
  | 'split'
  | 'agent'
  | 'directive'
  | 'surgical'

/** Create a fresh sentinel noteId tagged with the given origin. */
export function newSentinelNoteId(origin: NoteIdSentinelOrigin): string {
  const token = (crypto.randomUUID?.() ?? fallbackUuid()).replace(/-/g, '').slice(0, 10)
  return `${NOTE_ID_SENTINEL_PREFIX}${origin}_${token}`
}

export function isSentinelNoteId(id: string | null | undefined): boolean {
  return typeof id === 'string' && id.startsWith(NOTE_ID_SENTINEL_PREFIX)
}

/**
 * True iff `id` is a real Firestore doc id (non-null/undefined and not a sentinel).
 * Use this to decide whether a line already maps to an existing doc.
 */
export function isRealNoteId(id: string | null | undefined): boolean {
  return typeof id === 'string' && !id.startsWith(NOTE_ID_SENTINEL_PREFIX)
}

/** Return the origin code (e.g. "paste") for a sentinel id, or null if the id is real. */
export function originOfSentinel(id: string | null | undefined): string | null {
  if (!id || !id.startsWith(NOTE_ID_SENTINEL_PREFIX)) return null
  const rest = id.slice(NOTE_ID_SENTINEL_PREFIX.length)
  const underscore = rest.indexOf('_')
  return underscore >= 0 ? rest.slice(0, underscore) : null
}

function fallbackUuid(): string {
  // crypto.randomUUID is missing in older browsers / jsdom; good-enough fallback.
  return Array.from({ length: 4 }, () =>
    Math.floor(Math.random() * 0xffffffff).toString(16).padStart(8, '0'),
  ).join('-')
}
