export { DirectiveCacheManager } from './DirectiveCache'
export { CachedDirectiveExecutor, createInMemoryExecutor, createWithEditSupport } from './CachedDirectiveExecutor'
export { EditSessionManager, InvalidationReason } from './EditSessionManager'
export { analyze, analysisToPartialDependencies, type DirectiveAnalysis } from './DependencyAnalyzer'
export { shouldReExecute, isStale } from './StalenessChecker'
export { validate, containsMutations, validationErrorMessage, type ValidationResult } from './MutationValidator'
export { computeCacheKey, normalize } from './AstNormalizer'
export { classifyError, type DirectiveError, type DirectiveErrorKind } from './DirectiveError'
export {
  type DirectiveDependencies,
  type HierarchyDependency,
  type HierarchyPath,
  type HierarchyAccessPattern,
  NoteField,
  EMPTY_DEPENDENCIES,
  mergeDependencies,
} from './DirectiveDependencies'
export {
  type CachedDirectiveResult,
  cachedResultSuccess,
  cachedResultError,
} from './CachedDirectiveResult'
export { hashFirstLine, hashNonFirstLine, hashField, type ContentHashes } from './ContentHasher'
export { type MetadataHashes, computeMetadataHashes, EMPTY_METADATA_HASHES } from './MetadataHasher'
export { resolveHierarchyPath, findParent, findRoot } from './HierarchyResolver'
