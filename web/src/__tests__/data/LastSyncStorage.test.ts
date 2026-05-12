import { describe, it, expect, beforeEach } from 'vitest'
import { Timestamp } from 'firebase/firestore'
import { LastSyncStorage } from '../../data/LastSyncStorage'

describe('LastSyncStorage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('returns Timestamp(0, 0) for unset user', () => {
    const ts = LastSyncStorage.read('uid-1')
    expect(ts.seconds).toBe(0)
    expect(ts.nanoseconds).toBe(0)
  })

  it('round-trips a value via ms encoding', () => {
    const original = new Timestamp(1_700_000_000, 123_000_000)
    LastSyncStorage.write('uid-1', original)
    const read = LastSyncStorage.read('uid-1')
    // ms encoding loses sub-ms precision but preserves seconds + ms.
    expect(read.seconds).toBe(1_700_000_000)
    expect(read.nanoseconds).toBe(123_000_000)
  })

  it('isolates per-user watermarks', () => {
    LastSyncStorage.write('uid-a', new Timestamp(100, 0))
    LastSyncStorage.write('uid-b', new Timestamp(200, 0))
    expect(LastSyncStorage.read('uid-a').seconds).toBe(100)
    expect(LastSyncStorage.read('uid-b').seconds).toBe(200)
  })

  it('clear() removes the watermark so the next read returns 0', () => {
    LastSyncStorage.write('uid-1', new Timestamp(500, 0))
    LastSyncStorage.clear('uid-1')
    const ts = LastSyncStorage.read('uid-1')
    expect(ts.seconds).toBe(0)
    expect(ts.nanoseconds).toBe(0)
  })
})
