/**
 * A located directive in note content.
 */
export interface FoundDirective {
  sourceText: string
  startOffset: number
  endOffset: number
}

/**
 * Creates a unique key for a directive based on its line identity and position.
 * Produces "lineId:startOffset" which is stable across line reordering.
 *
 * @param lineId The line's effective ID — either a Firestore noteId or a temporary UUID.
 *   Must never be line-index-based.
 */
export function directiveKey(lineId: string, startOffset: number): string {
  return `${lineId}:${startOffset}`
}

/**
 * FNV-1a 64-bit hash of directive source text for use as cache key component.
 * Combined with noteId to form the full cache key: `noteId:hash`.
 * Identical algorithm on Android (Kotlin) and Web (TypeScript) for cross-platform consistency.
 */
const FNV_OFFSET = BigInt('0xcbf29ce484222325')
const FNV_PRIME = BigInt('0x00000100000001b3')
const MASK_64 = BigInt('0xffffffffffffffff')

export function directiveHash(sourceText: string): string {
  let hash = FNV_OFFSET
  for (let i = 0; i < sourceText.length; i++) {
    hash = (hash ^ BigInt(sourceText.charCodeAt(i))) & MASK_64
    hash = (hash * FNV_PRIME) & MASK_64
  }
  return hash.toString(16).padStart(16, '0')
}

/**
 * Extracts the startOffset from a directive key regardless of format.
 */
export function startOffsetFromKey(key: string): number | undefined {
  const lastColon = key.lastIndexOf(':')
  if (lastColon < 0) return undefined
  const parsed = parseInt(key.substring(lastColon + 1), 10)
  return isNaN(parsed) ? undefined : parsed
}

/**
 * Find all directives in the given content.
 * Handles nested brackets for lambda syntax: [lambda[...]]
 */
export function findDirectives(content: string): FoundDirective[] {
  const directives: FoundDirective[] = []
  let i = 0

  while (i < content.length) {
    if (content[i] === '[') {
      // Escaped bracket [[ at top level → literal [, skip both
      if (i + 1 < content.length && content[i + 1] === '[') {
        i += 2
        continue
      }

      const startOffset = i
      let depth = 1
      i++

      // Inside a directive, brackets are tracked normally (no escaping)
      while (i < content.length && depth > 0) {
        if (content[i] === '[') depth++
        else if (content[i] === ']') depth--
        i++
      }

      if (depth === 0) {
        directives.push({
          sourceText: content.substring(startOffset, i),
          startOffset,
          endOffset: i,
        })
      }
    } else if (content[i] === ']' && i + 1 < content.length && content[i + 1] === ']') {
      // Escaped bracket ]] outside directive → literal ], skip both
      i += 2
    } else {
      i++
    }
  }

  return directives
}

/**
 * Check if the given text contains any directives.
 */
export function containsDirectives(content: string): boolean {
  return findDirectives(content).length > 0
}

/**
 * Compute a SHA-256 hash of directive source text.
 */
export async function hashDirective(sourceText: string): Promise<string> {
  const encoder = new TextEncoder()
  const data = encoder.encode(sourceText)
  const hashBuffer = await crypto.subtle.digest('SHA-256', data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('')
}
