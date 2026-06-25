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
  it('has 11 colors matching the IOWA surface count', () => {
    expect(IOWA_LAYER_COLORS).toHaveLength(11)
  })

  it('has 11 labels matching the IOWA surface count', () => {
    expect(IOWA_LAYER_LABELS).toHaveLength(11)
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

  it('labels follow the IOWA NNN-LABEL.csv naming (no spaces or parens)', () => {
    for (const label of IOWA_LAYER_LABELS) {
      expect(label).not.toMatch(/[\s()]/)
    }
  })

  it('default-visible indices point at ILM, RPE, BM (clinical CRT trio)', () => {
    expect([...IOWA_DEFAULT_VISIBLE].sort((a, b) => a - b)).toEqual([0, 9, 10])
    expect(IOWA_LAYER_LABELS[IOWA_DEFAULT_VISIBLE[0]!]).toBe('ILM')
    expect(IOWA_LAYER_LABELS[IOWA_DEFAULT_VISIBLE[1]!]).toBe('RPE')
    expect(IOWA_LAYER_LABELS[IOWA_DEFAULT_VISIBLE[2]!]).toBe('BM')
  })

  it('default-visible indices stay within the 11-surface range', () => {
    for (const idx of IOWA_DEFAULT_VISIBLE) {
      expect(idx).toBeGreaterThanOrEqual(0)
      expect(idx).toBeLessThan(IOWA_LAYER_COLORS.length)
    }
  })
})
