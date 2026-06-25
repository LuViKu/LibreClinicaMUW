/**
 * IOWA layer palette invariants for the {@link BscanViewer} overlay.
 *
 * The cluster-side {@code SegmentationEnvelopeLoader.loadLayersStack}
 * emits exactly 11 surfaces in canonical IOWA order; the palette and
 * label arrays must stay aligned with that count, and the default-
 * visible indices must point at valid slots.
 */
import { describe, expect, it } from 'vitest'

import {
  IOWA_DEFAULT_VISIBLE,
  IOWA_LAYER_COLORS,
  IOWA_LAYER_LABELS,
} from '@/components/retinalPalette'

describe('IOWA layer palette', () => {
  it('has 12 colors (11 IOWA surfaces + BM model)', () => {
    expect(IOWA_LAYER_COLORS).toHaveLength(12)
  })

  it('has 12 labels (11 IOWA short tokens + BM)', () => {
    expect(IOWA_LAYER_LABELS).toHaveLength(12)
  })

  it('all colors are valid #RRGGBB hex codes', () => {
    for (const c of IOWA_LAYER_COLORS) {
      expect(c).toMatch(/^#[0-9A-Fa-f]{6}$/)
    }
  })

  it('all colors are unique (no accidental duplicates)', () => {
    const set = new Set(IOWA_LAYER_COLORS.map((c) => c.toLowerCase()))
    expect(set.size).toBe(IOWA_LAYER_COLORS.length)
  })

  it('all labels are unique', () => {
    const set = new Set(IOWA_LAYER_LABELS)
    expect(set.size).toBe(IOWA_LAYER_LABELS.length)
  })

  it('labels match the IOWA short-token convention (no spaces, no parens)', () => {
    for (const label of IOWA_LAYER_LABELS) {
      expect(label).not.toMatch(/[\s()]/)
    }
  })

  it('default-visible indices point at ILM, IB_RPE, BM (clinical CRT trio)', () => {
    expect([...IOWA_DEFAULT_VISIBLE].sort((a, b) => a - b)).toEqual([0, 9, 11])
    expect(IOWA_LAYER_LABELS[IOWA_DEFAULT_VISIBLE[0]!]).toBe('ILM')
    expect(IOWA_LAYER_LABELS[IOWA_DEFAULT_VISIBLE[1]!]).toBe('IB_RPE')
    expect(IOWA_LAYER_LABELS[IOWA_DEFAULT_VISIBLE[2]!]).toBe('BM')
  })

  it('default-visible indices stay within the 12-surface range', () => {
    for (const idx of IOWA_DEFAULT_VISIBLE) {
      expect(idx).toBeGreaterThanOrEqual(0)
      expect(idx).toBeLessThan(IOWA_LAYER_COLORS.length)
    }
  })

  it('IOWA short labels match the converter filename convention', () => {
    // The 11 short labels are the parenthesised tokens IOWA's
    // `local_IOWA_LayerSegV3_to_CSV` emits — exact match guards
    // against drift if anyone reorders the palette.
    expect(IOWA_LAYER_LABELS.slice(0, 11)).toEqual([
      'ILM', 'RNFL-GCL', 'GCL-IPL', 'IPL-INL', 'INL-OPL',
      'OPL-HFL', 'BMEIS', 'IS#OSJ', 'IB_OPR', 'IB_RPE', 'OB_RPE',
    ])
    expect(IOWA_LAYER_LABELS[11]).toBe('BM')
  })
})
