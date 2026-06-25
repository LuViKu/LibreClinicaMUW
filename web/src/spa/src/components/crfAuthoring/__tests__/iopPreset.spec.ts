/**
 * App-feedback Wave 2 (2026-06-19) — IOP preset generator spec.
 *
 * Pins:
 *   - generates 3 items (parent + IOP value child + reason child),
 *   - parent is TRISTATE_REASON with a three-option single-select,
 *   - the value child shows when parent == JA,
 *   - the reason child shows when parent == NEIN,
 *   - applyPreset materializes those 3 items into the target section.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import {
  generateIopPresetItems,
  IOP_PARENT_OPTIONS,
  IOP_PRESET_ID,
} from '@/components/crfAuthoring/presets/iopPreset'
import { PRESET_CATALOG } from '@/components/crfAuthoring/presetCatalog'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'

const identityT = (key: string): string => key

describe('iopPreset.generateIopPresetItems', () => {
  // 2026-06-25 — the IOP preset is now bilateral (OD + OS interleaved
  // so SectionCanvas's bilateral grid pairs them by eye). Items land
  // in the order:
  //   [OD-gemessen, OS-gemessen, OD-value, OS-value, OD-reason, OS-reason]

  it('emits exactly 6 items — OD + OS interleaved (parent, value, reason per eye)', () => {
    const items = generateIopPresetItems(identityT)
    expect(items).toHaveLength(6)
  })

  it('OD + OS parents are TRISTATE_REASON with Ja/Nein/Unbekannt options', () => {
    const items = generateIopPresetItems(identityT)
    for (const idx of [0, 1] as const) {
      const parent = items[idx]!
      expect(parent.dataType).toBe('TRISTATE_REASON')
      const rs = parent.responseSet
      if (!rs || 'ref' in rs) throw new Error('expected inline response set')
      expect(rs.options.map((o) => o.value)).toEqual([
        IOP_PARENT_OPTIONS.JA,
        IOP_PARENT_OPTIONS.NEIN,
        IOP_PARENT_OPTIONS.UNBEKANNT,
      ])
    }
    expect(items[0]!.oid).toBe('OD_IOP_GEMESSEN')
    expect(items[1]!.oid).toBe('OS_IOP_GEMESSEN')
  })

  it('OD + OS value children are REAL + mmHg + show-when JA against their own parent', () => {
    const items = generateIopPresetItems(identityT)
    const odValue = items[2]!
    const osValue = items[3]!
    expect(odValue.dataType).toBe('REAL')
    expect(odValue.units).toBe('mmHg')
    expect(odValue.showWhen).toEqual({
      sourceItemOid: 'OD_IOP_GEMESSEN',
      comparator: '==',
      literal: IOP_PARENT_OPTIONS.JA,
    })
    expect(osValue.dataType).toBe('REAL')
    expect(osValue.showWhen?.sourceItemOid).toBe('OS_IOP_GEMESSEN')
  })

  it('OD + OS reason children are ST textarea + show-when NEIN against their own parent', () => {
    const items = generateIopPresetItems(identityT)
    const odReason = items[4]!
    const osReason = items[5]!
    expect(odReason.dataType).toBe('ST')
    expect(odReason.responseType).toBe('textarea')
    expect(odReason.showWhen).toEqual({
      sourceItemOid: 'OD_IOP_GEMESSEN',
      comparator: '==',
      literal: IOP_PARENT_OPTIONS.NEIN,
    })
    expect(osReason.showWhen?.sourceItemOid).toBe('OS_IOP_GEMESSEN')
  })

  it('respects oidPrefix overrides on both eyes', () => {
    const items = generateIopPresetItems(identityT, { oidPrefix: 'TONO' })
    expect(items[0]!.oid).toBe('OD_TONO_GEMESSEN')
    expect(items[1]!.oid).toBe('OS_TONO_GEMESSEN')
    expect(items[2]!.oid).toBe('OD_TONO_VALUE')
    expect(items[3]!.oid).toBe('OS_TONO_VALUE')
    expect(items[2]!.showWhen?.sourceItemOid).toBe('OD_TONO_GEMESSEN')
  })
})

describe('applyPreset materialises the IOP preset into a section', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('appends 6 items (bilateral OD + OS) to the target section uid', () => {
    const store = useCrfAuthoringStore()
    const targetUid = store.draft.sections[0]!.uid
    const added = store.applyPreset(IOP_PRESET_ID, targetUid, {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    // 2026-06-25 — the IOP preset is now bilateral (OD + OS interleaved
    // so the bilateral-grid pairing in SectionCanvas can render them
    // side-by-side). Items land in the order:
    //   [OD-gemessen, OS-gemessen, OD-value, OS-value, OD-reason, OS-reason]
    expect(added).toBe(6)
    const section = store.draft.sections.find((s) => s.uid === targetUid)
    expect(section).toBeDefined()
    expect(section!.items).toHaveLength(6)
    expect(section!.items[0]!.oid).toBe('OD_IOP_GEMESSEN')
    expect(section!.items[0]!.dataType).toBe('TRISTATE_REASON')
    expect(section!.items[1]!.oid).toBe('OS_IOP_GEMESSEN')
    expect(section!.items[1]!.dataType).toBe('TRISTATE_REASON')
    expect(section!.items[2]!.dataType).toBe('REAL')
    expect(section!.items[3]!.dataType).toBe('REAL')
    expect(section!.items[4]!.dataType).toBe('ST')
    expect(section!.items[5]!.dataType).toBe('ST')
  })

  it('returns 0 on unknown preset id without mutating sections', () => {
    const store = useCrfAuthoringStore()
    const before = store.draft.sections[0]!.items.length
    const added = store.applyPreset('does-not-exist', store.draft.sections[0]!.uid, {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    expect(added).toBe(0)
    expect(store.draft.sections[0]!.items.length).toBe(before)
  })

  it('returns 0 on unknown section uid', () => {
    const store = useCrfAuthoringStore()
    const added = store.applyPreset(IOP_PRESET_ID, 'no-such-section', {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    expect(added).toBe(0)
  })
})
