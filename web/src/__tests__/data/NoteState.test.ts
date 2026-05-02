import { describe, it, expect } from 'vitest'
import { isLive, NoteState } from '../../data/NoteState'

describe('isLive', () => {
  it('treats null as the canonical active state', () => {
    // Firestore's storage shape is: live notes have no state field.
    // null arrives at the deserializer and must be treated as alive,
    // otherwise every legacy note would be filtered out.
    expect(isLive(null)).toBe(true)
  })

  it('treats undefined as alive', () => {
    // The Note interface currently types `state` as `string | null`, but
    // upstream callers may still pass undefined (e.g., partially-constructed
    // test fixtures). Defensive coverage.
    expect(isLive(undefined)).toBe(true)
  })

  it('accepts the explicit active marker', () => {
    expect(isLive(NoteState.ACTIVE)).toBe(true)
  })

  it('rejects soft-deleted notes', () => {
    expect(isLive(NoteState.DELETED)).toBe(false)
  })

  it('rejects cut-delete notes', () => {
    // cut-delete docs are parked awaiting paste — they must not appear in
    // reconstructed trees. Reclaim is via paste, not via reconstruction.
    expect(isLive(NoteState.CUT_DELETE)).toBe(false)
  })

  it('rejects unknown state values conservatively', () => {
    // Forward-compatible: a future state we haven't taught reconstruction
    // about should hide the note rather than render it in an undefined way.
    expect(isLive('future-state-we-dont-know-about')).toBe(false)
  })
})
