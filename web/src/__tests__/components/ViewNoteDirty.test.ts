import { describe, it, expect } from 'vitest'
import { isViewNoteDirty } from '../../components/viewNoteDirty'

describe('isViewNoteDirty', () => {
  it('not dirty when content matches note', () => {
    expect(isViewNoteDirty('Hello world', 'Hello world', null)).toBe(false)
  })

  it('dirty when content differs from note and lastSaved is null', () => {
    expect(isViewNoteDirty('edited text', 'original text', null)).toBe(true)
  })

  it('not dirty when content matches lastSaved (just saved)', () => {
    expect(isViewNoteDirty('saved text', 'original text', 'saved text')).toBe(false)
  })

  it('dirty again after editing past lastSaved', () => {
    expect(isViewNoteDirty('further edits', 'original text', 'saved text')).toBe(true)
  })

  it('not dirty when content matches note even if lastSaved is set', () => {
    expect(isViewNoteDirty('original text', 'original text', 'saved text')).toBe(false)
  })

  it('not dirty when all three values are the same', () => {
    expect(isViewNoteDirty('same', 'same', 'same')).toBe(false)
  })

  it('dirty with empty string edit when note has content', () => {
    expect(isViewNoteDirty('', 'has content', null)).toBe(true)
  })

  it('not dirty with empty string when note is also empty', () => {
    expect(isViewNoteDirty('', '', null)).toBe(false)
  })
})
