import { describe, expect, it } from 'vitest'

import { compactDate, deidFilename, labelForFilename } from '../../deid/filename'

describe('deidFilename', () => {
  const now = new Date(2026, 9, 6) // 6 Oct 2026, local

  it('<label>_<yyyyMMdd>_<OD|OS>.<e2e|dcm> with the acquisition date', () => {
    expect(deidFilename({ label: 'HAE-042', date: '2024-05-06', laterality: 'OD', format: 'e2e', now })).toBe('HAE-042_20240506_OD.e2e')
    expect(deidFilename({ label: 'HAE-042', date: '2024-05-06', laterality: 'OS', format: 'dicom', now })).toBe('HAE-042_20240506_OS.dcm')
  })

  it('falls back to the upload date when the acquisition date is unknown', () => {
    expect(deidFilename({ label: 'M-1', date: null, laterality: 'OD', format: 'e2e', now })).toBe('M-1_20261006_OD.e2e')
    expect(compactDate('garbage', now)).toBe('20261006')
  })

  it('appends _<n> only when an ordinal is given', () => {
    expect(deidFilename({ label: 'M-1', date: '2024-01-02', laterality: 'OS', n: 2, format: 'e2e', now })).toBe('M-1_20240102_OS_2.e2e')
    expect(deidFilename({ label: 'M-1', date: '2024-01-02', laterality: 'OS', n: null, format: 'e2e', now })).toBe('M-1_20240102_OS.e2e')
  })

  it('never carries an original filename, and makes the label file-name safe', () => {
    expect(labelForFilename('AB 12/../x')).toBe('AB-12-..-x')
    const name = deidFilename({ label: 'AB 12', date: '2024-01-02', laterality: 'OD', format: 'dicom', now })
    expect(name).toBe('AB-12_20240102_OD.dcm')
  })

  it('refuses a laterality other than OD or OS', () => {
    expect(() => deidFilename({ label: 'M-1', date: null, laterality: 'OU', format: 'dicom' })).toThrow(/laterality/)
    expect(() => deidFilename({ label: 'M-1', date: null, laterality: null, format: 'dicom' })).toThrow(/laterality/)
  })
})
