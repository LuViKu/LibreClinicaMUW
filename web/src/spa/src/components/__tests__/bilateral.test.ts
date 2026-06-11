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

  it('detects an INFIX eye token (seeded Ophthalmology CRF convention)', () => {
    // The seeded Ophthalmology CRF uses OIDs with the eye token in the
    // middle (lc-muw-2026-06-05-ophth-visit-crf-seed.xml). Both
    // {@code I_VA_OD_ETDRS} and {@code I_VA_OS_ETDRS} must reduce to the
    // same pair key {@code I_VA_ETDRS} so the row joiner pairs them.
    expect(parseEyePrefix('I_VA_OD_ETDRS')).toEqual({ eye: 'OD', suffix: 'I_VA_ETDRS' })
    expect(parseEyePrefix('I_VA_OS_ETDRS')).toEqual({ eye: 'OS', suffix: 'I_VA_ETDRS' })
    expect(parseEyePrefix('I_IOP_OD')).toEqual({ eye: 'OD', suffix: 'I_IOP' })
    expect(parseEyePrefix('I_LENS_OS')).toEqual({ eye: 'OS', suffix: 'I_LENS' })
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

  it('strips the seed-CRF "Right eye (OD) — X" / "Left eye (OS) — X" patterns', () => {
    // Seeded Ophthalmology CRF labels use the verbatim long form, e.g.
    // {@code left_item_text="Right eye (OD) — ETDRS letters"}. The
    // bilateral pair joiner consumes these as the per-side label and
    // collapses both sides to the shared cleaned form.
    expect(stripEyeMarker('Right eye (OD) — ETDRS letters')).toBe('ETDRS letters')
    expect(stripEyeMarker('Left eye (OS) — ETDRS letters')).toBe('ETDRS letters')
    expect(stripEyeMarker('Right eye (OD) — IOP')).toBe('IOP')
    expect(stripEyeMarker('Right eye (OD) — Sphere')).toBe('Sphere')
    expect(stripEyeMarker('Both eyes — Refraction')).toBe('Refraction')
    expect(stripEyeMarker('Rechtes Auge (OD) — Augeninnendruck')).toBe('Augeninnendruck')
    expect(stripEyeMarker('Linkes Auge (OS) — Sphäre')).toBe('Sphäre')
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

  it('groups REFRACTION_* items into one compound-bilateral row', () => {
    // 8 items: OD/OS × Sphere/Torus/Angle/Visus. They collapse into ONE
    // 'compound-bilateral' row with 4 sub-fields, regardless of input
    // order. Each sub-field has the OD + OS pair.
    const rows = groupBilateralItems([
      mkItem('OD_REFRACTION_SPHERE', 'Refraction Sphere', 'real'),
      mkItem('OS_REFRACTION_SPHERE', 'Refraction Sphere', 'real'),
      mkItem('OD_REFRACTION_TORUS',  'Refraction Torus',  'real'),
      mkItem('OS_REFRACTION_TORUS',  'Refraction Torus',  'real'),
      mkItem('OD_REFRACTION_ANGLE',  'Refraction Angle',  'integer'),
      mkItem('OS_REFRACTION_ANGLE',  'Refraction Angle',  'integer'),
      mkItem('OD_REFRACTION_VISUS',  'Refraction Visus',  'real'),
      mkItem('OS_REFRACTION_VISUS',  'Refraction Visus',  'real'),
    ])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('compound-bilateral')
    if (rows[0].kind === 'compound-bilateral') {
      expect(rows[0].key).toBe('REFRACTION')
      expect(rows[0].label).toBe('Refraction')
      expect(rows[0].subFields).toHaveLength(4)
      expect(rows[0].subFields.map((s) => s.subKey)).toEqual([
        'SPHERE', 'TORUS', 'ANGLE', 'VISUS',
      ])
      expect(rows[0].subFields.map((s) => s.compactLabel)).toEqual([
        'Sph', 'Tor', 'Ang', 'Vis',
      ])
      for (const sub of rows[0].subFields) {
        expect(sub.od?.oid).toBe(`OD_REFRACTION_${sub.subKey}`)
        expect(sub.os?.oid).toBe(`OS_REFRACTION_${sub.subKey}`)
      }
    }
  })

  it('groups REFRACTION items even when interleaved with non-compound bilateral pairs', () => {
    const rows = groupBilateralItems([
      mkItem('OD_BCVA_LETTERS',      'BCVA letters', 'integer'),
      mkItem('OD_REFRACTION_SPHERE', 'Sphere',       'real'),
      mkItem('OS_BCVA_LETTERS',      'BCVA letters', 'integer'),
      mkItem('OS_REFRACTION_SPHERE', 'Sphere',       'real'),
      mkItem('OD_IOP',               'IOP',          'integer'),
      mkItem('OD_REFRACTION_TORUS',  'Torus',        'real'),
      mkItem('OS_IOP',               'IOP',          'integer'),
      mkItem('OS_REFRACTION_TORUS',  'Torus',        'real'),
    ])
    expect(rows).toHaveLength(3)  // BCVA, REFRACTION (single compound), IOP
    expect(rows[0].kind).toBe('bilateral')
    expect(rows[1].kind).toBe('compound-bilateral')
    expect(rows[2].kind).toBe('bilateral')
    if (rows[1].kind === 'compound-bilateral') {
      expect(rows[1].subFields).toHaveLength(2)
      expect(rows[1].subFields.map((s) => s.subKey)).toEqual(['SPHERE', 'TORUS'])
    }
  })

  it('pairs the seeded I_VA_OD_ETDRS / I_VA_OS_ETDRS into a single bilateral row', () => {
    // Locks the Ophthalmology Visit CRF seed convention: pair joiner
    // collapses the seed's infix-laterality OIDs into one bilateral row
    // and the row label is derived from the shared per-side label after
    // {@link stripEyeMarker} runs.
    const rows = groupBilateralItems([
      mkItem('I_VA_OD_ETDRS', 'Right eye (OD) — ETDRS letters', 'integer'),
      mkItem('I_VA_OS_ETDRS', 'Left eye (OS) — ETDRS letters', 'integer'),
    ])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('bilateral')
    if (rows[0].kind === 'bilateral') {
      expect(rows[0].key).toBe('I_VA_ETDRS')
      expect(rows[0].od?.oid).toBe('I_VA_OD_ETDRS')
      expect(rows[0].os?.oid).toBe('I_VA_OS_ETDRS')
      expect(rows[0].label).toBe('ETDRS letters')
    }
  })

  it('pairs the seeded I_IOP_OD / I_IOP_OS with the eye token at the END of the OID', () => {
    const rows = groupBilateralItems([
      mkItem('I_IOP_OD', 'Right eye (OD) — IOP', 'integer'),
      mkItem('I_IOP_OS', 'Left eye (OS) — IOP', 'integer'),
    ])
    expect(rows).toHaveLength(1)
    if (rows[0].kind === 'bilateral') {
      expect(rows[0].key).toBe('I_IOP')
      expect(rows[0].od?.oid).toBe('I_IOP_OD')
      expect(rows[0].os?.oid).toBe('I_IOP_OS')
      expect(rows[0].label).toBe('IOP')
    }
  })

  it('groups the seeded I_REFRACT_OD_SPH/CYL/AXIS items into a compound-bilateral row', () => {
    // Seeded Ophthalmology CRF uses REFRACT_OD_SPH / REFRACT_OS_CYL /
    // REFRACT_OD_AXIS — three sub-fields per side. After eye-token
    // removal each suffix becomes I_REFRACT_SPH / I_REFRACT_CYL /
    // I_REFRACT_AXIS, all of which match the I_REFRACT compound prefix.
    const rows = groupBilateralItems([
      mkItem('I_REFRACT_OD_SPH',  'Right eye (OD) — Sphere',   'real'),
      mkItem('I_REFRACT_OD_CYL',  'Right eye (OD) — Cylinder', 'real'),
      mkItem('I_REFRACT_OD_AXIS', 'Right eye (OD) — Axis',     'integer'),
      mkItem('I_REFRACT_OS_SPH',  'Left eye (OS) — Sphere',    'real'),
      mkItem('I_REFRACT_OS_CYL',  'Left eye (OS) — Cylinder',  'real'),
      mkItem('I_REFRACT_OS_AXIS', 'Left eye (OS) — Axis',      'integer'),
    ])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('compound-bilateral')
    if (rows[0].kind === 'compound-bilateral') {
      expect(rows[0].key).toBe('I_REFRACT')
      expect(rows[0].label).toBe('Refraktion')
      expect(rows[0].subFields.map((s) => s.subKey)).toEqual(['SPH', 'CYL', 'AXIS'])
      expect(rows[0].subFields.map((s) => s.compactLabel)).toEqual([
        'Sphäre', 'Zylinder', 'Achse',
      ])
    }
  })

  it('legacy REFRACTION_CYLINDER/AXIS keys map to Tor/Ang compact labels for back-compat', () => {
    const rows = groupBilateralItems([
      mkItem('OD_REFRACTION_CYLINDER', 'Refraction cylinder', 'real'),
      mkItem('OS_REFRACTION_CYLINDER', 'Refraction cylinder', 'real'),
      mkItem('OD_REFRACTION_AXIS',     'Refraction axis',     'integer'),
      mkItem('OS_REFRACTION_AXIS',     'Refraction axis',     'integer'),
    ])
    expect(rows).toHaveLength(1)
    if (rows[0].kind === 'compound-bilateral') {
      // Old data with CYLINDER/AXIS suffixes maps to the same compact slots
      // as TORUS/ANGLE so historical CRFs render in the same compound row.
      expect(rows[0].subFields.map((s) => s.compactLabel)).toEqual(['Tor', 'Ang'])
    }
  })
})
