import type { DslValue } from '../runtime/DslValue'
import type { DirectiveError } from './DirectiveError'
import type { DirectiveDependencies } from './DirectiveDependencies'
import type { ContentHashes } from './ContentHasher'
import type { MetadataHashes } from './MetadataHasher'
import { EMPTY_DEPENDENCIES } from './DirectiveDependencies'
import { EMPTY_METADATA_HASHES } from './MetadataHasher'

/**
 * A cached directive execution result with its dependencies and hashes.
 */
export interface CachedDirectiveResult {
  result: DslValue | null
  error: DirectiveError | null
  dependencies: DirectiveDependencies
  noteContentHashes: Map<string, ContentHashes>
  metadataHashes: MetadataHashes
  cachedAt: number
}

export function cachedResultSuccess(
  result: DslValue,
  dependencies: DirectiveDependencies,
  noteContentHashes: Map<string, ContentHashes> = new Map(),
  metadataHashes: MetadataHashes = EMPTY_METADATA_HASHES,
): CachedDirectiveResult {
  return { result, error: null, dependencies, noteContentHashes, metadataHashes, cachedAt: Date.now() }
}

export function cachedResultError(
  error: DirectiveError,
  dependencies: DirectiveDependencies = EMPTY_DEPENDENCIES,
  noteContentHashes: Map<string, ContentHashes> = new Map(),
  metadataHashes: MetadataHashes = EMPTY_METADATA_HASHES,
): CachedDirectiveResult {
  return { result: null, error, dependencies, noteContentHashes, metadataHashes, cachedAt: Date.now() }
}

export function isSuccessResult(cached: CachedDirectiveResult): boolean {
  return cached.result !== null
}

export function shouldRetryError(cached: CachedDirectiveResult): boolean {
  return cached.error !== null && !cached.error.isDeterministic
}
