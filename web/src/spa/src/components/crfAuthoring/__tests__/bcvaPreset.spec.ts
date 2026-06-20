/**
 * D4 (2026-06-20) — BCVA preset generator spec.
 *
 * Pins:
 *   - eight items emitted (4 per eye × 2 eyes),
 *   - bilateral structure: OD half first then OS half,
 *   - each item carries laterality + bilateralPair metadata,
 *   - letters validation: INT, units 'letters', regex accepts 0–100,
 *   - refraction sub-fields: REAL sphere + cylinder, INT axis,
 *   - applyPreset materialises all 8 items into the target section.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import {
  generateBcvaPresetItems,
  BCVA_PRESET_ID,
} from '@/components/crfAuthoring/presets/bcvaPreset'
import { PRESET_CATALOG } from '@/components/crfAuthoring/presetCatalog'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'

const identityT = (key: string): string => key

describe('bcvaPreset.generateBcvaPresetItems', () => {
  it('emits exactly 8 items (4 per eye × 2)', () => {
    const items = generateBcvaPresetItems(identityT)
    expect(items).toHaveLength(8)
  })

  it('emits OD items first then OS items', () => {
    const items = generateBcvaPresetItems(identityT)
    expect(items.slice(0, 4).every((i) => i.laterality === 'OD')).toBe(true)
    expect(items.slice(4).every((i) => i.laterality === 'OS')).toBe(true)
  })

  it('each item carries bilateralPair metadata to the opposite-eye OID', () => {
    const items = generateBcvaPresetItems(identityT)
    for (const item of items) {
      expect(item.bilateralPair).toBeDefined()
      const expectedPrefix = item.laterality === 'OD' ? 'OS_' : 'OD_'
      expect(item.bilateralPair!.startsWith(expectedPrefix)).toBe(true)
    }
  })

  it('letters item is INT in letters with a 0–100 regex', () => {
    const items = generateBcvaPresetItems(identityT)
    const letters = items.find((i) => i.oid === 'OD_BCVA_LETTERS')
    expect(letters).toBeDefined()
    expect(letters!.dataType).toBe('INT')
    expect(letters!.units).toBe('letters')
    // Spot-check the regex accepts 0, 70, 100 and rejects 101.
    const re = new RegExp(letters!.validation.regexp)
    expect(re.test('0')).toBe(true)
    expect(re.test('70')).toBe(true)
    expect(re.test('100')).toBe(true)
    expect(re.test('101')).toBe(false)
  })

  it('refraction sphere + cylinder are REAL, axis is INT 0–180', () => {
    const items = generateBcvaPresetItems(identityT)
    const sphere = items.find((i) => i.oid === 'OD_BCVA_REFRACTION_SPHERE')!
    const cylinder = items.find((i) => i.oid === 'OD_BCVA_REFRACTION_CYLINDER')!
    const axis = items.find((i) => i.oid === 'OD_BCVA_REFRACTION_AXIS')!
    expect(sphere.dataType).toBe('REAL')
    expect(cylinder.dataType).toBe('REAL')
    expect(axis.dataType).toBe('INT')
    expect(axis.units).toBe('deg')
    const re = new RegExp(axis.validation.regexp)
    expect(re.test('0')).toBe(true)
    expect(re.test('90')).toBe(true)
    expect(re.test('180')).toBe(true)
    expect(re.test('181')).toBe(false)
  })

  it('all items are text responseType (no picker/option)', () => {
    const items = generateBcvaPresetItems(identityT)
    expect(items.every((i) => i.responseType === 'text')).toBe(true)
    expect(items.every((i) => i.responseSet === null)).toBe(true)
  })
})

describe('applyPreset materialises the BCVA preset', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('appends 8 items to the target section uid', () => {
    const store = useCrfAuthoringStore()
    const targetUid = store.draft.sections[0]!.uid
    const added = store.applyPreset(BCVA_PRESET_ID, targetUid, {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    expect(added).toBe(8)
    const section = store.draft.sections.find((s) => s.uid === targetUid)
    expect(section!.items).toHaveLength(8)
    expect(section!.items[0]!.oid).toBe('OD_BCVA_LETTERS')
    expect(section!.items[4]!.oid).toBe('OS_BCVA_LETTERS')
  })
})
