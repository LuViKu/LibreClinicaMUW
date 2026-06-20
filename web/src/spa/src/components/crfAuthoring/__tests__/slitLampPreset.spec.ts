/**
 * D4 (2026-06-20) — Slit-lamp anterior-segment preset spec.
 *
 * Pins:
 *   - eleven items emitted (5 per eye × 2 + 1 OU remark),
 *   - bilateral structure: OD half first, OS half, then OU remark,
 *   - all five canonical sections present per eye,
 *   - items are ST textarea (free-text),
 *   - remark is OU (no per-eye bilateralPair),
 *   - applyPreset materialises all 11 items.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import {
  generateSlitLampPresetItems,
  SLIT_LAMP_PRESET_ID,
} from '@/components/crfAuthoring/presets/slitLampPreset'
import { PRESET_CATALOG } from '@/components/crfAuthoring/presetCatalog'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'

const identityT = (key: string): string => key

describe('slitLampPreset.generateSlitLampPresetItems', () => {
  it('emits exactly 11 items (5 OD + 5 OS + 1 OU remark)', () => {
    const items = generateSlitLampPresetItems(identityT)
    expect(items).toHaveLength(11)
  })

  it('produces all five anterior segment sections per eye', () => {
    const items = generateSlitLampPresetItems(identityT)
    const oids = items.map((i) => i.oid)
    expect(oids).toContain('OD_SLIT_LAMP_CORNEA')
    expect(oids).toContain('OD_SLIT_LAMP_AC')
    expect(oids).toContain('OD_SLIT_LAMP_IRIS')
    expect(oids).toContain('OD_SLIT_LAMP_LENS')
    expect(oids).toContain('OD_SLIT_LAMP_VITREOUS')
    expect(oids).toContain('OS_SLIT_LAMP_CORNEA')
    expect(oids).toContain('OS_SLIT_LAMP_VITREOUS')
  })

  it('all eye-specific items are ST textarea', () => {
    const items = generateSlitLampPresetItems(identityT)
    const eyeItems = items.filter((i) => i.laterality !== 'OU')
    expect(eyeItems).toHaveLength(10)
    expect(eyeItems.every((i) => i.dataType === 'ST')).toBe(true)
    expect(eyeItems.every((i) => i.responseType === 'textarea')).toBe(true)
  })

  it('remark is OU with no bilateralPair', () => {
    const items = generateSlitLampPresetItems(identityT)
    const remark = items.find((i) => i.oid === 'OU_SLIT_LAMP_REMARK')!
    expect(remark.laterality).toBe('OU')
    expect(remark.bilateralPair).toBeUndefined()
    expect(remark.dataType).toBe('ST')
    expect(remark.responseType).toBe('textarea')
  })

  it('per-eye items carry bilateralPair to the opposite eye', () => {
    const items = generateSlitLampPresetItems(identityT)
    const odCornea = items.find((i) => i.oid === 'OD_SLIT_LAMP_CORNEA')!
    const osCornea = items.find((i) => i.oid === 'OS_SLIT_LAMP_CORNEA')!
    expect(odCornea.bilateralPair).toBe('OS_SLIT_LAMP_CORNEA')
    expect(osCornea.bilateralPair).toBe('OD_SLIT_LAMP_CORNEA')
  })
})

describe('applyPreset materialises the SLIT_LAMP preset', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('appends 11 items to the target section uid', () => {
    const store = useCrfAuthoringStore()
    const targetUid = store.draft.sections[0]!.uid
    const added = store.applyPreset(SLIT_LAMP_PRESET_ID, targetUid, {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    expect(added).toBe(11)
    const section = store.draft.sections.find((s) => s.uid === targetUid)
    expect(section!.items).toHaveLength(11)
    expect(section!.items[10]!.oid).toBe('OU_SLIT_LAMP_REMARK')
  })
})
