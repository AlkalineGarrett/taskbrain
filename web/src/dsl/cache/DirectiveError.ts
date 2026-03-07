/**
 * Represents an error that occurred during directive execution.
 * Categorized by whether they are deterministic (cacheable) or not.
 */
export interface DirectiveError {
  kind: DirectiveErrorKind
  message: string
  position?: number
  isDeterministic: boolean
}

export type DirectiveErrorKind =
  | 'SyntaxError'
  | 'TypeError'
  | 'ArgumentError'
  | 'FieldAccessError'
  | 'ValidationError'
  | 'UnknownIdentifierError'
  | 'CircularDependencyError'
  | 'ArithmeticError'
  | 'NetworkError'
  | 'TimeoutError'
  | 'ResourceUnavailableError'
  | 'PermissionError'
  | 'ExternalServiceError'

function deterministicError(kind: DirectiveErrorKind, message: string, position?: number): DirectiveError {
  return { kind, message, position, isDeterministic: true }
}

function nonDeterministicError(kind: DirectiveErrorKind, message: string, position?: number): DirectiveError {
  return { kind, message, position, isDeterministic: false }
}

/**
 * Classify an error message into the appropriate DirectiveError.
 */
export function classifyError(message: string, position?: number): DirectiveError {
  const msg = message.toLowerCase()

  if (msg.includes('unknown function or variable')) return deterministicError('UnknownIdentifierError', message, position)
  if (msg.includes('requires') && msg.includes('argument')) return deterministicError('ArgumentError', message, position)
  if (msg.includes('must be a') || msg.includes('got ')) return deterministicError('TypeError', message, position)
  if (msg.includes('missing')) return deterministicError('ArgumentError', message, position)
  if (msg.includes('unknown property') || msg.includes('unknown method')) return deterministicError('FieldAccessError', message, position)
  if (msg.includes('circular')) return deterministicError('CircularDependencyError', message, position)
  if (msg.includes('division by zero') || msg.includes('modulo by zero')) return deterministicError('ArithmeticError', message, position)
  if (msg.includes('note not found') || msg.includes('does not exist')) return nonDeterministicError('ResourceUnavailableError', message, position)
  if (msg.includes('failed to create note')) return nonDeterministicError('ExternalServiceError', message, position)
  if (msg.includes('syntax') || msg.includes('parse') || msg.includes('unexpected')) return deterministicError('SyntaxError', message, position)
  if (msg.includes('network') || msg.includes('connection')) return nonDeterministicError('NetworkError', message, position)
  if (msg.includes('timeout')) return nonDeterministicError('TimeoutError', message, position)
  if (msg.includes('permission') || msg.includes('access denied')) return nonDeterministicError('PermissionError', message, position)
  if (msg.includes('validation') || msg.includes('bare time') || msg.includes('requires button')) return deterministicError('ValidationError', message, position)

  // Default to deterministic (conservative - will cache)
  return deterministicError('TypeError', message, position)
}
