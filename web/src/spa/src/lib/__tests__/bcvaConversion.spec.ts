import { describe, expect, it } from 'vitest'

import {
  decimalToLetters,
  formatBcva,
  lettersToBcva,
  parseBcvaInput,
} from '@/lib/bcvaConversion'

describe('bcvaConversion', () => {
  describe('decimalToLetters', () => {
    // Bailey-Lovie reference table. The base-85-letter anchor at
    // decimal 1.0 + the 50-letter-per-LogMAR-decade slope are clinical
    // canon — these rows are the spec.
    it.each([
      // [decimal, partial, expected letters]
      [1.0, 0, 85],
      [1.0, -2, 83],
      [0.8, 0, 80],
      [0.8, 2, 82],
      [0.8, -3, 77],
      [0.5, 0, 70],
      [0.5, 4, 74],
      [0.2, 0, 50],
      [0.1, 0, 35],
      [0.05, -1, 19],
      [0.02, 0, 0], // off-chart → clamped to 0
      [0, 0, 0], // zero / negative decimal
      [-0.1, 0, 0],
    ])(
      'decimal=%f, partial=%d → %d letters',
      (decimal, partial, expected) => {
        expect(decimalToLetters(decimal, partial)).toBe(expected)
      },
    )

    it('clamps the result at 100 (super-vision)', () => {
      expect(decimalToLetters(2.0, 4)).toBeLessThanOrEqual(100)
    })

    it('clamps the result at 0 (below detection)', () => {
      expect(decimalToLetters(0.001, 0)).toBe(0)
    })
  })

  describe('parseBcvaInput', () => {
    it.each([
      ['1,0', { decimal: 1.0, partial: 0 }],
      ['1.0', { decimal: 1.0, partial: 0 }],
      ['0,8', { decimal: 0.8, partial: 0 }],
      ['1,0p-2', { decimal: 1.0, partial: -2 }],
      ['1.0p-2', { decimal: 1.0, partial: -2 }],
      ['1,0-2', { decimal: 1.0, partial: -2 }],
      ['0,8+2', { decimal: 0.8, partial: 2 }],
      ['0,5+4', { decimal: 0.5, partial: 4 }],
      ['0,05p-1', { decimal: 0.05, partial: -1 }],
      ['  1,0  ', { decimal: 1.0, partial: 0 }], // whitespace tolerated
    ])('parses %s → %o', (input, expected) => {
      expect(parseBcvaInput(input)).toEqual(expected)
    })

    it.each([
      ['foo'], // gibberish
      ['3.0'], // decimal out of range
      ['1,0p-7'], // partial magnitude out of range
      ['1,0+9'],
      ['1,0p'], // partial sign without magnitude
      ['1,0p+2'], // mixed signs (not a clinical form)
      [''], // empty
      ['   '],
      ['p-2'], // partial without decimal
    ])('rejects %s', (input) => {
      expect(parseBcvaInput(input)).toBeNull()
    })
  })

  describe('formatBcva', () => {
    it.each([
      [1.0, 0, '1,0'],
      [0.8, 0, '0,8'],
      [1.0, -2, '1,0p-2'],
      [0.8, 2, '0,8+2'],
      [0.5, 4, '0,5+4'],
      [0.05, -1, '0,05p-1'],
    ])('formats (%f, %d) → %s', (decimal, partial, expected) => {
      expect(formatBcva(decimal, partial)).toBe(expected)
    })
  })

  describe('lettersToBcva', () => {
    // Snap targets pulled from the same Bailey-Lovie table — picks the
    // ladder rung at or below the target letters value.
    it.each([
      [85, { decimal: 1.0, partial: 0 }],
      [83, { decimal: 1.0, partial: -2 }],
      [82, { decimal: 0.8, partial: 2 }], // 0.8 base is 80; +2 → 82
      [70, { decimal: 0.5, partial: 0 }],
      [35, { decimal: 0.1, partial: 0 }],
    ])('snaps %d letters → %o', (letters, expected) => {
      expect(lettersToBcva(letters)).toEqual(expected)
    })

    it('round-trips through decimalToLetters for ladder rungs', () => {
      for (const d of [1.0, 0.8, 0.5, 0.2, 0.1]) {
        const letters = decimalToLetters(d, 0)
        const back = lettersToBcva(letters)
        expect(back.decimal).toBe(d)
        expect(back.partial).toBe(0)
      }
    })
  })
})
