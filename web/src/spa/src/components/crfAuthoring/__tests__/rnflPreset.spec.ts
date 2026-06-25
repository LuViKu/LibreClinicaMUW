/**
 * D4 (2026-06-20) — RNFL preset generator spec.
 *
 * Pins:
 *   - twelve items emitted (6 per eye × 2 eyes),
 *   - bilateral structure: OD half first then OS half,
 *   - global + S/T/I/N quadrants in µm + signal-quality 0–10,
 *   - validation: µm range 0–999, signal 0–10,
 *   - applyPreset materialises all 12 items.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import {
  generateRnflPresetItems,
  RNFL_PRESET_ID,
} from '@/components/crfAuthoring/presets/rnflPreset'
import { PRESET_CATALOG } from '@/components/crfAuthoring/presetCatalog'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'

const identityT = (key: string): string => key

describe('rnflPreset.generateRnflPresetItems', () => {
  it('emits exactly 12 items (6 per eye × 2)', () => {
    const items = generateRnflPresetItems(identityT)
    expect(items).toHaveLength(12)
  })

  it('produces global + S/T/I/N + signal_quality for each eye', () => {
    const items = generateRnflPresetItems(identityT)
    const oids = items.map((i) => i.oid)
    expect(oids).toContain('OD_RNFL_GLOBAL')
    expect(oids).toContain('OD_RNFL_S')
    expect(oids).toContain('OD_RNFL_T')
    expect(oids).toContain('OD_RNFL_I')
    expect(oids).toContain('OD_RNFL_N')
    expect(oids).toContain('OD_RNFL_SIGNAL_QUALITY')
    expect(oids).toContain('OS_RNFL_GLOBAL')
    expect(oids).toContain('OS_RNFL_SIGNAL_QUALITY')
  })

  it('bilateral pairs cross-link OD <-> OS', () => {
    const items = generateRnflPresetItems(identityT)
    const odGlobal = items.find((i) => i.oid === 'OD_RNFL_GLOBAL')!
    const osGlobal = items.find((i) => i.oid === 'OS_RNFL_GLOBAL')!
    expect(odGlobal.bilateralPair).toBe('OS_RNFL_GLOBAL')
    expect(osGlobal.bilateralPair).toBe('OD_RNFL_GLOBAL')
  })

  it('thickness items are INT µm with 0–999 regex', () => {
    const items = generateRnflPresetItems(identityT)
    const global = items.find((i) => i.oid === 'OD_RNFL_GLOBAL')!
    expect(global.dataType).toBe('INT')
    expect(global.units).toBe('µm')
    const re = new RegExp(global.validation.regexp)
    expect(re.test('0')).toBe(true)
    expect(re.test('120')).toBe(true)
    expect(re.test('999')).toBe(true)
    expect(re.test('1000')).toBe(false)
  })

  it('signal quality is INT 0–10', () => {
    const items = generateRnflPresetItems(identityT)
    const signal = items.find((i) => i.oid === 'OD_RNFL_SIGNAL_QUALITY')!
    expect(signal.dataType).toBe('INT')
    const re = new RegExp(signal.validation.regexp)
    expect(re.test('0')).toBe(true)
    expect(re.test('10')).toBe(true)
    expect(re.test('11')).toBe(false)
  })

  it('all RNFL items are text responseType (no picker)', () => {
    const items = generateRnflPresetItems(identityT)
    expect(items.every((i) => i.responseType === 'text')).toBe(true)
  })
})

describe('applyPreset materialises the RNFL preset', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('appends 12 items to the target section uid', () => {
    const store = useCrfAuthoringStore()
    const targetUid = store.draft.sections[0]!.uid
    const added = store.applyPreset(RNFL_PRESET_ID, targetUid, {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    expect(added).toBe(12)
    const section = store.draft.sections.find((s) => s.uid === targetUid)
    expect(section!.items).toHaveLength(12)
    expect(section!.items[0]!.oid).toBe('OD_RNFL_GLOBAL')
  })
})
