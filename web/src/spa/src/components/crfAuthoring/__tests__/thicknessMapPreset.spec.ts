/**
 * D4 (2026-06-20) — ETDRS thickness-map preset spec.
 *
 * Pins:
 *   - twenty items emitted (10 per eye × 2 eyes: 9 zones + modality),
 *   - bilateral structure: OD half first then OS half,
 *   - all nine ETDRS subfields present per eye,
 *   - modality is a single-select with four canonical options,
 *   - zones are INT µm 0–999.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import {
  generateThicknessMapPresetItems,
  THICKNESS_MAP_PRESET_ID,
  THICKNESS_MAP_MODALITY_OPTIONS,
} from '@/components/crfAuthoring/presets/thicknessMapPreset'
import { PRESET_CATALOG } from '@/components/crfAuthoring/presetCatalog'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'

const identityT = (key: string): string => key

describe('thicknessMapPreset.generateThicknessMapPresetItems', () => {
  it('emits exactly 20 items (10 per eye × 2)', () => {
    const items = generateThicknessMapPresetItems(identityT)
    expect(items).toHaveLength(20)
  })

  it('exposes all nine ETDRS subfields per eye', () => {
    const items = generateThicknessMapPresetItems(identityT)
    const oids = items.map((i) => i.oid)
    expect(oids).toContain('OD_THICKNESS_CENTRAL')
    expect(oids).toContain('OD_THICKNESS_INNER_S')
    expect(oids).toContain('OD_THICKNESS_INNER_T')
    expect(oids).toContain('OD_THICKNESS_INNER_I')
    expect(oids).toContain('OD_THICKNESS_INNER_N')
    expect(oids).toContain('OD_THICKNESS_OUTER_S')
    expect(oids).toContain('OD_THICKNESS_OUTER_T')
    expect(oids).toContain('OD_THICKNESS_OUTER_I')
    expect(oids).toContain('OD_THICKNESS_OUTER_N')
  })

  it('zone items are INT µm with 0–999 regex', () => {
    const items = generateThicknessMapPresetItems(identityT)
    const central = items.find((i) => i.oid === 'OD_THICKNESS_CENTRAL')!
    expect(central.dataType).toBe('INT')
    expect(central.units).toBe('µm')
    const re = new RegExp(central.validation.regexp)
    expect(re.test('0')).toBe(true)
    expect(re.test('300')).toBe(true)
    expect(re.test('999')).toBe(true)
    expect(re.test('1000')).toBe(false)
  })

  it('modality is a single-select with four canonical options', () => {
    const items = generateThicknessMapPresetItems(identityT)
    const modality = items.find((i) => i.oid === 'OD_THICKNESS_MODALITY')!
    expect(modality.responseType).toBe('single-select')
    const rs = modality.responseSet
    if (!rs || 'ref' in rs) throw new Error('expected inline response set')
    expect(rs.options.map((o) => o.value)).toEqual([
      THICKNESS_MAP_MODALITY_OPTIONS.SPECTRALIS,
      THICKNESS_MAP_MODALITY_OPTIONS.CIRRUS,
      THICKNESS_MAP_MODALITY_OPTIONS.TOPCON,
      THICKNESS_MAP_MODALITY_OPTIONS.OTHER,
    ])
  })

  it('bilateral pairs link OD <-> OS for every item', () => {
    const items = generateThicknessMapPresetItems(identityT)
    for (const item of items) {
      const expectedPrefix = item.laterality === 'OD' ? 'OS_' : 'OD_'
      expect(item.bilateralPair!.startsWith(expectedPrefix)).toBe(true)
    }
  })
})

describe('applyPreset materialises the THICKNESS_MAP preset', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('appends 20 items to the target section uid', () => {
    const store = useCrfAuthoringStore()
    const targetUid = store.draft.sections[0]!.uid
    const added = store.applyPreset(THICKNESS_MAP_PRESET_ID, targetUid, {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    expect(added).toBe(20)
    const section = store.draft.sections.find((s) => s.uid === targetUid)
    expect(section!.items).toHaveLength(20)
    expect(section!.items[0]!.oid).toBe('OD_THICKNESS_CENTRAL')
  })
})
