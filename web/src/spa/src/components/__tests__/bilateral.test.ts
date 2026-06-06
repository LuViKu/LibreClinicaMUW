/**
 * Phase E.6 ophth-bilateral — detection + grouping unit specs.
 *
 * Pins three pieces of load-bearing behaviour for the helper that the
 * CrfEntryView consumes:
 *
 *  1. OD/OS prefixed items collapse into a 3-column row keyed by the
 *     shared OID suffix. The OD slot comes from the OD_… item (which
 *     the layout will render on the clinician's LEFT) and the OS slot
 *     from OS_… (renders RIGHT). Reversing this binding would silently
 *     swap eyes in the audit-significant entry path, so the test
 *     locks the side-of-bind onto the OID prefix.
 *  2. OU_… items render as a "both eyes" single-cell row.
 *  3. Items without an eye prefix fall through unchanged, preserving
 *     the existing layout for non-ophthalmology CRFs.
 */
import { describe, expect, it } from 'vitest'

import {
  groupBilateralItems,
  parseEyePrefix,
  stripEyeMarker,
  deriveRowLabel,
} from '../bilateral'
import type { CrfItem } from '@/types/crf'

function mkItem(oid: string, label: string, dataType: CrfItem['dataType'] = 'string'): CrfItem {
  return {
    oid,
    label,
    dataType,
    required: false,
  } as unknown as CrfItem
}

describe('parseEyePrefix', () => {
  it('returns null for non-eye OIDs', () => {
    expect(parseEyePrefix('I_HEIGHT_CM')).toBeNull()
    expect(parseEyePrefix('ODC')).toBeNull()
    expect(parseEyePrefix('OS')).toBeNull()
  })

  it('extracts OD/OS/OU + the shared suffix', () => {
    expect(parseEyePrefix('OD_BCVA_LETTERS')).toEqual({ eye: 'OD', suffix: 'BCVA_LETTERS' })
    expect(parseEyePrefix('OS_BCVA_LETTERS')).toEqual({ eye: 'OS', suffix: 'BCVA_LETTERS' })
    expect(parseEyePrefix('OU_IOP')).toEqual({ eye: 'OU', suffix: 'IOP' })
  })
})

describe('stripEyeMarker', () => {
  it('strips leading eye markers', () => {
    expect(stripEyeMarker('OD BCVA letters')).toBe('BCVA letters')
    expect(stripEyeMarker('OD — BCVA letters')).toBe('BCVA letters')
    expect(stripEyeMarker('OS: Tonometry')).toBe('Tonometry')
  })

  it('strips trailing eye markers', () => {
    expect(stripEyeMarker('BCVA letters (OD)')).toBe('BCVA letters')
    expect(stripEyeMarker('Tonometry — OS')).toBe('Tonometry')
  })

  it('leaves non-marker labels untouched', () => {
    expect(stripEyeMarker('Visual acuity')).toBe('Visual acuity')
  })
})

describe('deriveRowLabel', () => {
  it('uses the shared cleaned label when both sides agree', () => {
    const od = mkItem('OD_BCVA', 'OD BCVA letters')
    const os = mkItem('OS_BCVA', 'OS BCVA letters')
    expect(deriveRowLabel('BCVA', od, os)).toBe('BCVA letters')
  })

  it('falls back to the available side when only one is present', () => {
    const od = mkItem('OD_BCVA', 'OD BCVA letters')
    expect(deriveRowLabel('BCVA', od, null)).toBe('BCVA letters')
  })

  it('falls back to the OID suffix when sides disagree', () => {
    const od = mkItem('OD_BCVA', 'OD BCVA letters')
    const os = mkItem('OS_BCVA', 'OS Snellen line')
    expect(deriveRowLabel('BCVA_LETTERS', od, os)).toBe('BCVA LETTERS')
  })
})

describe('groupBilateralItems', () => {
  it('pairs OD + OS items into a bilateral row keyed by suffix', () => {
    const rows = groupBilateralItems([
      mkItem('OD_BCVA_LETTERS', 'OD BCVA letters', 'integer'),
      mkItem('OS_BCVA_LETTERS', 'OS BCVA letters', 'integer'),
    ])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('bilateral')
    if (rows[0].kind === 'bilateral') {
      expect(rows[0].key).toBe('BCVA_LETTERS')
      // Side-of-bind: OD goes in the OD slot (which the layout
      // renders LEFT). Reversing this would silently swap eyes.
      expect(rows[0].od?.oid).toBe('OD_BCVA_LETTERS')
      expect(rows[0].os?.oid).toBe('OS_BCVA_LETTERS')
      expect(rows[0].label).toBe('BCVA letters')
    }
  })

  it('preserves declaration order across multiple bilateral pairs', () => {
    const rows = groupBilateralItems([
      mkItem('OD_BCVA', 'OD BCVA', 'integer'),
      mkItem('OD_IOP', 'OD IOP', 'real'),
      mkItem('OS_BCVA', 'OS BCVA', 'integer'),
      mkItem('OS_IOP', 'OS IOP', 'real'),
    ])
    expect(rows).toHaveLength(2)
    expect(rows[0].kind === 'bilateral' && rows[0].key).toBe('BCVA')
    expect(rows[1].kind === 'bilateral' && rows[1].key).toBe('IOP')
  })

  it('emits an OD-only bilateral row when the OS pair is absent', () => {
    const rows = groupBilateralItems([
      mkItem('OD_BCVA_LETTERS', 'OD BCVA letters', 'integer'),
    ])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('bilateral')
    if (rows[0].kind === 'bilateral') {
      expect(rows[0].od?.oid).toBe('OD_BCVA_LETTERS')
      expect(rows[0].os).toBeNull()
    }
  })

  it('emits an OS-only bilateral row when the OD pair is absent', () => {
    const rows = groupBilateralItems([
      mkItem('OS_BCVA_LETTERS', 'OS BCVA letters', 'integer'),
    ])
    expect(rows[0].kind).toBe('bilateral')
    if (rows[0].kind === 'bilateral') {
      expect(rows[0].od).toBeNull()
      expect(rows[0].os?.oid).toBe('OS_BCVA_LETTERS')
    }
  })

  it('renders OU items as a both-eyes row that spans both eye columns', () => {
    const rows = groupBilateralItems([mkItem('OU_VISION_DESCRIPTION', 'Both eyes — vision')])
    expect(rows[0].kind).toBe('both-eyes')
    if (rows[0].kind === 'both-eyes') {
      expect(rows[0].key).toBe('VISION_DESCRIPTION')
      expect(rows[0].item.oid).toBe('OU_VISION_DESCRIPTION')
    }
  })

  it('keeps items without an eye prefix as single one-column rows', () => {
    const rows = groupBilateralItems([
      mkItem('I_HEIGHT_CM', 'Height (cm)', 'integer'),
      mkItem('I_WEIGHT_KG', 'Weight (kg)', 'real'),
    ])
    expect(rows).toHaveLength(2)
    expect(rows.every((r) => r.kind === 'single')).toBe(true)
  })

  it('mixes single + bilateral + both-eyes rows in declaration order', () => {
    const rows = groupBilateralItems([
      mkItem('I_VISIT_DATE', 'Visit date', 'date'),
      mkItem('OD_BCVA', 'OD BCVA', 'integer'),
      mkItem('OS_BCVA', 'OS BCVA', 'integer'),
      mkItem('OU_DESCRIPTION', 'Both eyes — narrative', 'string'),
      mkItem('I_NOTES', 'Notes', 'string'),
    ])
    expect(rows.map((r) => r.kind)).toEqual([
      'single',
      'bilateral',
      'both-eyes',
      'single',
    ])
  })

  it('handles interleaved OD/OS items (authoring tool may not emit them sequentially)', () => {
    const rows = groupBilateralItems([
      mkItem('OD_BCVA', 'OD BCVA', 'integer'),
      mkItem('OD_IOP', 'OD IOP', 'real'),
      mkItem('OS_IOP', 'OS IOP', 'real'),
      mkItem('OS_BCVA', 'OS BCVA', 'integer'),
    ])
    expect(rows).toHaveLength(2)
    // First row was opened by OD_BCVA, so it stays in slot 0
    expect(rows[0].kind === 'bilateral' && rows[0].key).toBe('BCVA')
    if (rows[0].kind === 'bilateral') {
      expect(rows[0].od?.oid).toBe('OD_BCVA')
      expect(rows[0].os?.oid).toBe('OS_BCVA')
    }
    expect(rows[1].kind === 'bilateral' && rows[1].key).toBe('IOP')
  })
})
