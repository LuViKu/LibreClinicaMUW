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
  it('emits exactly 3 items (parent, value, reason)', () => {
    const items = generateIopPresetItems(identityT)
    expect(items).toHaveLength(3)
  })

  it('parent is TRISTATE_REASON with Ja/Nein/Unbekannt options', () => {
    const items = generateIopPresetItems(identityT)
    const parent = items[0]!
    expect(parent.dataType).toBe('TRISTATE_REASON')
    expect(parent.oid).toBe('IOP_GEMESSEN')
    const rs = parent.responseSet
    if (!rs || 'ref' in rs) throw new Error('expected inline response set')
    expect(rs.options.map((o) => o.value)).toEqual([
      IOP_PARENT_OPTIONS.JA,
      IOP_PARENT_OPTIONS.NEIN,
      IOP_PARENT_OPTIONS.UNBEKANNT,
    ])
  })

  it('value child is REAL + mmHg + show-when JA', () => {
    const items = generateIopPresetItems(identityT)
    const value = items[1]!
    expect(value.dataType).toBe('REAL')
    expect(value.units).toBe('mmHg')
    expect(value.showWhen).toEqual({
      sourceItemOid: 'IOP_GEMESSEN',
      comparator: '==',
      literal: IOP_PARENT_OPTIONS.JA,
    })
  })

  it('reason child is ST textarea + show-when NEIN', () => {
    const items = generateIopPresetItems(identityT)
    const reason = items[2]!
    expect(reason.dataType).toBe('ST')
    expect(reason.responseType).toBe('textarea')
    expect(reason.showWhen).toEqual({
      sourceItemOid: 'IOP_GEMESSEN',
      comparator: '==',
      literal: IOP_PARENT_OPTIONS.NEIN,
    })
  })

  it('respects oidPrefix overrides', () => {
    const items = generateIopPresetItems(identityT, { oidPrefix: 'TONO' })
    expect(items[0]!.oid).toBe('TONO_GEMESSEN')
    expect(items[1]!.oid).toBe('TONO_VALUE')
    expect(items[2]!.oid).toBe('TONO_REASON')
    expect(items[1]!.showWhen?.sourceItemOid).toBe('TONO_GEMESSEN')
  })
})

describe('applyPreset materialises the IOP preset into a section', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('appends 3 items to the target section uid', () => {
    const store = useCrfAuthoringStore()
    const targetUid = store.draft.sections[0]!.uid
    const added = store.applyPreset(IOP_PRESET_ID, targetUid, {
      registry: PRESET_CATALOG,
      translate: identityT,
    })
    expect(added).toBe(3)
    const section = store.draft.sections.find((s) => s.uid === targetUid)
    expect(section).toBeDefined()
    expect(section!.items).toHaveLength(3)
    expect(section!.items[0]!.oid).toBe('IOP_GEMESSEN')
    expect(section!.items[0]!.dataType).toBe('TRISTATE_REASON')
    expect(section!.items[1]!.dataType).toBe('REAL')
    expect(section!.items[2]!.dataType).toBe('ST')
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
