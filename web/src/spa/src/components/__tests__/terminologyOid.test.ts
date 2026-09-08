/**
 * #26 Slice 3 — terminology OID marker round-trip. Pins the convention that
 * persists a text column's autocomplete system in the item OID (survives
 * save → reload → live entry with no backend column).
 */
import { describe, it, expect } from 'vitest'
import { markTerminologyOid, terminologySystemFromOid } from '@/components/terminologyOid'

describe('terminologyOid marker', () => {
  it('round-trips medication + icd10gm', () => {
    expect(markTerminologyOid('MEDS_MED', 'medication')).toBe('MEDS_MED_TXMED')
    expect(markTerminologyOid('DX_CODE', 'icd10gm')).toBe('DX_CODE_TXICD')
    expect(terminologySystemFromOid('MEDS_MED_TXMED')).toBe('medication')
    expect(terminologySystemFromOid('DX_CODE_TXICD')).toBe('icd10gm')
  })

  it('leaves unknown systems + unmarked OIDs alone', () => {
    expect(markTerminologyOid('X', 'atc')).toBe('X')
    expect(markTerminologyOid('X', undefined)).toBe('X')
    expect(terminologySystemFromOid('MEDS_MED')).toBeNull()
    expect(terminologySystemFromOid('')).toBeNull()
    expect(terminologySystemFromOid(null)).toBeNull()
  })
})
