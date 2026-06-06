/**
 * Phase E.6 — Ophthalmology bilateral preset generator coverage.
 *
 * <p>Pins the load-bearing contract: for a selection of catalog keys,
 * {@code generateOphthSectionItems} returns exactly 2 × N items in
 * OD-first order with paired {@code OD_*} / {@code OS_*} OIDs and
 * symmetric {@code bilateralPair} cross-links. The clinical
 * OD-LEFT / OS-RIGHT convention is enforced via the {@code laterality}
 * metadata; the wire-side OID prefix carries the eye affinity through
 * to the backend.
 */
import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import {
  OPHTH_PRESET_CATALOG,
  allPresetKeys,
  findPresetEntry,
  generateBilateralPair,
  generateOphthSectionItems,
  presetEntriesByDataType,
  type Translator,
} from '@/types/ophthPreset'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'

/**
 * Identity translator — returns the i18n key verbatim. Lets the tests
 * assert on the keys without depending on the localised strings.
 */
const idT: Translator = (key) => key

describe('OPHTH_PRESET_CATALOG', () => {
  it('exports the curated ~15 bilateral measurements', () => {
    // Lock the lower bound — the brief says 15-18. The exact count is
    // not load-bearing but a smaller catalog is a sign of accidental
    // truncation.
    expect(OPHTH_PRESET_CATALOG.length).toBeGreaterThanOrEqual(15)
  })

  it('every entry has a unique key', () => {
    const keys = OPHTH_PRESET_CATALOG.map((e) => e.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('every entry is bilateral by default', () => {
    for (const entry of OPHTH_PRESET_CATALOG) {
      expect(entry.defaultBilateral).toBe(true)
    }
  })

  it('numeric entries (INT / REAL) carry an inclusive range', () => {
    for (const entry of OPHTH_PRESET_CATALOG) {
      if (entry.dataType === 'INT' || entry.dataType === 'REAL') {
        expect(entry.range).not.toBeNull()
        expect(entry.range!.min).toBeLessThan(entry.range!.max)
      }
    }
  })

  it('BL entries carry no range and no unit', () => {
    for (const entry of OPHTH_PRESET_CATALOG) {
      if (entry.dataType === 'BL') {
        expect(entry.range).toBeNull()
        expect(entry.unit).toBe('')
      }
    }
  })

  it('exposes the canonical examination presets', () => {
    const keys = allPresetKeys()
    expect(keys).toContain('BCVA_LETTERS')
    expect(keys).toContain('BCVA_SNELLEN')
    expect(keys).toContain('IOP')
    expect(keys).toContain('SPECTRALIS_DONE')
    expect(keys).toContain('PLEX_ELITE_DONE')
    expect(keys).toContain('REFRACTION_SPHERE')
    expect(keys).toContain('REFRACTION_CYLINDER')
    expect(keys).toContain('REFRACTION_AXIS')
    expect(keys).toContain('CCT')
    expect(keys).toContain('CRT')
    expect(keys).toContain('FUNDUS_PHOTO_DONE')
    expect(keys).toContain('VISUAL_FIELD_DONE')
    expect(keys).toContain('OCT_A_DONE')
    expect(keys).toContain('ACD')
    expect(keys).toContain('AXIAL_LENGTH')
  })

  it('findPresetEntry resolves known keys and returns undefined for unknowns', () => {
    expect(findPresetEntry('IOP')?.dataType).toBe('INT')
    expect(findPresetEntry('NOPE_DOES_NOT_EXIST')).toBeUndefined()
  })

  it('presetEntriesByDataType groups correctly', () => {
    const blEntries = presetEntriesByDataType('BL')
    expect(blEntries.length).toBeGreaterThanOrEqual(3)
    for (const entry of blEntries) {
      expect(entry.dataType).toBe('BL')
    }
  })
})

describe('generateBilateralPair', () => {
  it('emits OD then OS for an INT measurement', () => {
    const entry = findPresetEntry('BCVA_LETTERS')!
    const [od, os] = generateBilateralPair(entry, idT)
    expect(od.oid).toBe('OD_BCVA_LETTERS')
    expect(od.laterality).toBe('OD')
    expect(od.bilateralPair).toBe('OS_BCVA_LETTERS')
    expect(od.dataType).toBe('INT')
    expect(os.oid).toBe('OS_BCVA_LETTERS')
    expect(os.laterality).toBe('OS')
    expect(os.bilateralPair).toBe('OD_BCVA_LETTERS')
  })

  it('carries the unit through on measurement items', () => {
    const entry = findPresetEntry('IOP')!
    // Use a translator that returns a stable string for the label key
    // so we can assert on the spliced unit suffix.
    const t: Translator = (key) =>
      key === 'ophthPreset.entry.IOP.label' ? 'IOP' : key
    const [od] = generateBilateralPair(entry, t)
    expect(od.units).toBe('mmHg')
    // The descriptionLabel embeds the unit so operators see "IOP (mmHg)".
    expect(od.descriptionLabel).toBe('IOP (mmHg)')
    // The name is the OID — `\w+`-safe so the wizard's canSubmit guard accepts it.
    expect(od.name).toBe('OD_IOP')
    expect(od.name).toMatch(/^\w+$/)
  })

  it('omits the unit on BL items', () => {
    const entry = findPresetEntry('SPECTRALIS_DONE')!
    const [od, os] = generateBilateralPair(entry, idT)
    expect(od.units).toBe('')
    expect(os.units).toBe('')
    expect(od.dataType).toBe('BL')
    expect(os.dataType).toBe('BL')
  })

  it('encodes leftItemText with the eye laterality label', () => {
    const entry = findPresetEntry('IOP')!
    const [od, os] = generateBilateralPair(entry, idT)
    expect(od.leftItemText).toBe('OD')
    expect(os.leftItemText).toBe('OS')
  })

  it('attaches range validation to INT entries', () => {
    const entry = findPresetEntry('BCVA_LETTERS')!
    // Use a translator that materialises {placeholder} tokens so we can
    // assert the range bounds are spliced into the error message.
    const t: Translator = (key) => {
      if (key === 'ophthPreset.validation.intRange') return 'between {min} and {max}'
      return key
    }
    const [od] = generateBilateralPair(entry, t)
    expect(od.validation.regexp).toBe('^-?\\d+$')
    // Error message embeds the range bounds verbatim.
    expect(od.validation.errorMessage).toBe('between 0 and 100')
  })

  it('attaches a decimal-friendly regex to REAL entries', () => {
    const entry = findPresetEntry('REFRACTION_SPHERE')!
    const [od] = generateBilateralPair(entry, idT)
    expect(od.validation.regexp).toBe('^-?\\d+(\\.\\d+)?$')
  })

  it('omits validation for ST / BL entries', () => {
    const stEntry = findPresetEntry('BCVA_SNELLEN')!
    const [stOd] = generateBilateralPair(stEntry, idT)
    expect(stOd.validation).toEqual({ regexp: '', errorMessage: '' })

    const blEntry = findPresetEntry('PLEX_ELITE_DONE')!
    const [blOd] = generateBilateralPair(blEntry, idT)
    expect(blOd.validation).toEqual({ regexp: '', errorMessage: '' })
  })
})

describe('generateOphthSectionItems', () => {
  it('returns N×2 items for a selection of size N', () => {
    const selection = ['BCVA_LETTERS', 'IOP', 'SPECTRALIS_DONE']
    const items = generateOphthSectionItems(selection, idT)
    expect(items).toHaveLength(selection.length * 2)
  })

  it('produces the expected OD_ / OS_ prefix pairs in catalog order', () => {
    const items = generateOphthSectionItems(['BCVA_LETTERS', 'IOP'], idT)
    expect(items.map((it) => it.oid)).toEqual([
      'OD_BCVA_LETTERS',
      'OS_BCVA_LETTERS',
      'OD_IOP',
      'OS_IOP',
    ])
  })

  it('every emitted item has a symmetric bilateralPair cross-link', () => {
    const items = generateOphthSectionItems(['BCVA_LETTERS', 'CCT', 'OCT_A_DONE'], idT)
    const byOid = new Map(items.map((it) => [it.oid, it] as const))
    for (const item of items) {
      const pair = byOid.get(item.bilateralPair!)
      expect(pair).toBeDefined()
      expect(pair!.bilateralPair).toBe(item.oid)
      // The pair has opposite laterality.
      expect(pair!.laterality).not.toBe(item.laterality)
    }
  })

  it('skips unknown keys silently', () => {
    const items = generateOphthSectionItems(['BCVA_LETTERS', 'UNKNOWN_KEY'], idT)
    expect(items).toHaveLength(2)
    expect(items.map((it) => it.oid)).toEqual(['OD_BCVA_LETTERS', 'OS_BCVA_LETTERS'])
  })

  it('returns an empty array for an empty selection', () => {
    expect(generateOphthSectionItems([], idT)).toEqual([])
  })

  it('OD always comes before its paired OS', () => {
    const items = generateOphthSectionItems(
      ['BCVA_LETTERS', 'IOP', 'REFRACTION_SPHERE'],
      idT,
    )
    for (let i = 0; i < items.length; i += 2) {
      expect(items[i]!.laterality).toBe('OD')
      expect(items[i + 1]!.laterality).toBe('OS')
      expect(items[i]!.bilateralPair).toBe(items[i + 1]!.oid)
    }
  })
})

describe('useCrfAuthoringStore.addOphthPresetSection', () => {
  it('appends a new OPHTH_EXAM section with the generated items', () => {
    setActivePinia(createPinia())
    const store = useCrfAuthoringStore()
    const items = generateOphthSectionItems(['BCVA_LETTERS', 'IOP'], idT)

    store.addOphthPresetSection({ items, title: 'Ophthalmology examination' })

    // The seed draft has one default section + this new one.
    expect(store.draft.sections).toHaveLength(2)
    const added = store.draft.sections[1]!
    expect(added.label).toBe('OPHTH_EXAM')
    expect(added.title).toBe('Ophthalmology examination')
    expect(added.items).toHaveLength(4)
    expect(added.items.map((it) => it.oid)).toEqual([
      'OD_BCVA_LETTERS',
      'OS_BCVA_LETTERS',
      'OD_IOP',
      'OS_IOP',
    ])
    // Each seeded item gets a stable uid so vuedraggable's item-key works.
    for (const item of added.items) {
      expect(item.uid).toMatch(/^item-/)
    }
  })

  it('does not include wizard-only metadata in the wire payload', () => {
    setActivePinia(createPinia())
    const store = useCrfAuthoringStore()
    store.setVersionName('v1.0')
    store.setItemField(0, 0, 'name', 'demo') // satisfy section 0
    const items = generateOphthSectionItems(['IOP'], idT)
    store.addOphthPresetSection({ items })

    const payload = store.buildPayload() as { sections: Array<{ items: Array<Record<string, unknown>> }> }
    const ophthSection = payload.sections[1]!
    for (const item of ophthSection.items) {
      // laterality + bilateralPair are wizard-only — they must not
      // reach the backend (the OID prefix is the source of truth).
      expect(item.laterality).toBeUndefined()
      expect(item.bilateralPair).toBeUndefined()
      expect(item.oid).toMatch(/^O[DS]_/)
    }
  })
})
