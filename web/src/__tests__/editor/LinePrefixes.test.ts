import { describe, it, expect } from 'vitest'
import {
  BULLET,
  CHECKBOX_UNCHECKED,
  CHECKBOX_CHECKED,
  getPrefix,
  hasBullet,
  hasCheckbox,
  hasAnyPrefix,
  removePrefix,
  addPrefix,
} from '../../editor/LinePrefixes'

describe('getPrefix', () => {
  it('returns bullet prefix', () => {
    expect(getPrefix(`${BULLET}text`)).toBe(BULLET)
  })

  it('returns unchecked checkbox prefix', () => {
    expect(getPrefix(`${CHECKBOX_UNCHECKED}text`)).toBe(CHECKBOX_UNCHECKED)
  })

  it('returns checked checkbox prefix', () => {
    expect(getPrefix(`${CHECKBOX_CHECKED}text`)).toBe(CHECKBOX_CHECKED)
  })

  it('returns null for plain text', () => {
    expect(getPrefix('plain text')).toBeNull()
  })

  it('ignores leading tabs', () => {
    expect(getPrefix(`\t\t${BULLET}text`)).toBe(BULLET)
  })
})

describe('hasBullet', () => {
  it('returns true for bullet line', () => {
    expect(hasBullet(`${BULLET}text`)).toBe(true)
  })

  it('returns true for indented bullet line', () => {
    expect(hasBullet(`\t${BULLET}text`)).toBe(true)
  })

  it('returns false for checkbox line', () => {
    expect(hasBullet(`${CHECKBOX_UNCHECKED}text`)).toBe(false)
  })

  it('returns false for plain text', () => {
    expect(hasBullet('text')).toBe(false)
  })
})

describe('hasCheckbox', () => {
  it('returns true for unchecked', () => {
    expect(hasCheckbox(`${CHECKBOX_UNCHECKED}text`)).toBe(true)
  })

  it('returns true for checked', () => {
    expect(hasCheckbox(`${CHECKBOX_CHECKED}text`)).toBe(true)
  })

  it('returns true for indented checkbox', () => {
    expect(hasCheckbox(`\t${CHECKBOX_UNCHECKED}text`)).toBe(true)
  })

  it('returns false for bullet', () => {
    expect(hasCheckbox(`${BULLET}text`)).toBe(false)
  })

  it('returns false for plain text', () => {
    expect(hasCheckbox('text')).toBe(false)
  })
})

describe('hasAnyPrefix', () => {
  it('returns true for bullet', () => {
    expect(hasAnyPrefix(`${BULLET}text`)).toBe(true)
  })

  it('returns true for checkbox', () => {
    expect(hasAnyPrefix(`${CHECKBOX_UNCHECKED}text`)).toBe(true)
  })

  it('returns false for plain text', () => {
    expect(hasAnyPrefix('plain text')).toBe(false)
  })
})

describe('removePrefix', () => {
  it('removes bullet', () => {
    expect(removePrefix(`${BULLET}text`)).toBe('text')
  })

  it('removes checkbox', () => {
    expect(removePrefix(`${CHECKBOX_UNCHECKED}text`)).toBe('text')
  })

  it('preserves indentation when removing', () => {
    expect(removePrefix(`\t\t${BULLET}text`)).toBe('\t\ttext')
  })

  it('returns plain text unchanged', () => {
    expect(removePrefix('text')).toBe('text')
  })
})

describe('addPrefix', () => {
  it('adds bullet to plain text', () => {
    expect(addPrefix('text', BULLET)).toBe(`${BULLET}text`)
  })

  it('adds checkbox to plain text', () => {
    expect(addPrefix('text', CHECKBOX_UNCHECKED)).toBe(`${CHECKBOX_UNCHECKED}text`)
  })

  it('preserves indentation', () => {
    expect(addPrefix('\t\ttext', BULLET)).toBe(`\t\t${BULLET}text`)
  })

  it('replaces existing prefix', () => {
    expect(addPrefix(`${BULLET}text`, CHECKBOX_UNCHECKED)).toBe(`${CHECKBOX_UNCHECKED}text`)
  })
})
