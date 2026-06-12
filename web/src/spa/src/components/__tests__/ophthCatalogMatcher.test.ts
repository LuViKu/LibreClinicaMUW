/**
 * Phase E.6 ophth-field-catalog (2026-06-11) — OID → catalog-entry
 * matcher unit specs.
 *
 * Pins three load-bearing behaviours:
 *
 *   1. The matcher correctly strips laterality tokens (OD/OS/OU) from
 *      the OID before comparison. {@code I_OPHTH_OD_BCVA_LETTERS}
 *      resolves to the BCVA_LETTERS catalog entry.
 *   2. Token-tail comparison — the OID's trailing tokens must equal
 *      the catalog code's tokens in order. {@code I_OPHTH_BCVA_LETTERS}
 *      matches BCVA_LETTERS; {@code I_OPHTH_BCVA_LETTERS_EXTRA} does
 *      not.
 *   3. When multiple entries could match (DONE vs SPECTRALIS_OCT_DONE
 *      etc.), the LONGER code wins so the most specific binding
 *      takes precedence.
 */
import { describe, expect, it } from 'vitest'

import { delateralizeOid, findCatalogEntryByOid } from '../ophthCatalogMatcher'
import type { OphthFieldCatalogEntry } from '@/types/ophthFieldCatalog'

function mkEntry(code: string, widget: OphthFieldCatalogEntry['widget'] = 'number-stepper'): OphthFieldCatalogEntry {
  return {
    code,
    labelDe: code,
    labelEn: code,
    hintDe: null,
    hintEn: null,
    bilateral: true,
    dataType: 'integer',
    widget,
    unit: null,
    minValue: null,
    maxValue: null,
    stepValue: null,
    placeholderText: null,
    conditionalOnCode: null,
    conditionalShowWhenValue: null,
    responseOptions: [],
    modalityCode: null,
    oidPrefix: 'OPHTH',
    ordinal: 0,
  }
}

describe('delateralizeOid', () => {
  it('strips OD/OS/OU laterality tokens from the OID', () => {
    expect(delateralizeOid('I_OPHTH_OD_BCVA_LETTERS')).toEqual(['I', 'OPHTH', 'BCVA', 'LETTERS'])
    expect(delateralizeOid('I_OPHTH_OS_IOP')).toEqual(['I', 'OPHTH', 'IOP'])
    expect(delateralizeOid('OU_VISION_DESCRIPTION')).toEqual(['VISION', 'DESCRIPTION'])
  })

  it('passes through OIDs without laterality tokens', () => {
    expect(delateralizeOid('I_HEIGHT_CM')).toEqual(['I', 'HEIGHT', 'CM'])
    expect(delateralizeOid('AGE')).toEqual(['AGE'])
  })

  it('strips empty tokens from leading/trailing/doubled underscores', () => {
    expect(delateralizeOid('_I_OPHTH_OD_IOP_')).toEqual(['I', 'OPHTH', 'IOP'])
    expect(delateralizeOid('I__OPHTH__OD__IOP')).toEqual(['I', 'OPHTH', 'IOP'])
  })
})

describe('findCatalogEntryByOid', () => {
  const catalog = [
    mkEntry('BCVA_LETTERS', 'number-stepper'),
    mkEntry('BCVA_LOGMAR', 'number-stepper'),
    mkEntry('IOP', 'number-stepper'),
    mkEntry('SPECTRALIS_OCT_DONE', 'yesno'),
    mkEntry('SPECTRALIS_OCT_REASON', 'text'),
    mkEntry('REFRACTION', 'refraction'),
    mkEntry('CRT', 'number-stepper'),
    mkEntry('ACD', 'number-stepper'),
    mkEntry('LENS_STATUS', 'select-one'),
  ]

  it('resolves an OPHTH v2.0 OID by trailing-token match', () => {
    const entry = findCatalogEntryByOid('I_OPHTH_OD_BCVA_LETTERS', catalog)
    expect(entry?.code).toBe('BCVA_LETTERS')
  })

  it('resolves the OS twin of an OD item to the same catalog entry', () => {
    const entryOd = findCatalogEntryByOid('I_OPHTH_OD_BCVA_LETTERS', catalog)
    const entryOs = findCatalogEntryByOid('I_OPHTH_OS_BCVA_LETTERS', catalog)
    expect(entryOs?.code).toBe(entryOd?.code)
  })

  it('resolves single-token codes like IOP', () => {
    expect(findCatalogEntryByOid('I_OPHTH_OD_IOP', catalog)?.code).toBe('IOP')
    expect(findCatalogEntryByOid('I_IOP_OD', catalog)?.code).toBe('IOP')
  })

  it('returns null when no catalog code matches the OID tail', () => {
    expect(findCatalogEntryByOid('I_OPHTH_OD_HEIGHT_CM', catalog)).toBeNull()
    expect(findCatalogEntryByOid('AGE', catalog)).toBeNull()
  })

  it('returns null on an empty OID', () => {
    expect(findCatalogEntryByOid('', catalog)).toBeNull()
  })

  it('returns null on an empty catalog', () => {
    expect(findCatalogEntryByOid('I_OPHTH_OD_IOP', [])).toBeNull()
  })

  it('prefers the LONGER matching code when multiple catalog entries could apply', () => {
    // SPECTRALIS_OCT_DONE (3 tokens) wins over a hypothetical DONE
    // entry (1 token).
    const wider = [...catalog, mkEntry('DONE', 'yesno')]
    const entry = findCatalogEntryByOid('I_OPHTH_OD_SPECTRALIS_OCT_DONE', wider)
    expect(entry?.code).toBe('SPECTRALIS_OCT_DONE')
  })

  it('matches REFRACTION compound prefix from a compound row key', () => {
    // The bilateral grouper calls the matcher with the compound row's
    // key (e.g. 'I_OPHTH_REFRACTION'), not the per-sub-field OID. The
    // matcher should resolve to the REFRACTION catalog entry.
    expect(findCatalogEntryByOid('I_OPHTH_REFRACTION', catalog)?.code).toBe('REFRACTION')
  })

  it('does not partial-match a catalog code embedded mid-OID', () => {
    // The token-tail matcher requires the catalog code's tokens to be
    // the trailing tokens of the OID — a code embedded in the middle
    // should not produce a false positive.
    expect(findCatalogEntryByOid('I_OPHTH_OD_IOP_HISTORY', catalog)).toBeNull()
  })
})
