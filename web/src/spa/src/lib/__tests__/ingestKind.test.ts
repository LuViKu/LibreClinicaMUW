import { describe, expect, it } from 'vitest'

import { ingestKindLabel, isDicomOctVolume } from '../ingestKind'
import deMessages from '@/locales/de.json'
import enMessages from '@/locales/en.json'

/** A tiny t() over a locale file, enough for the keys these labels use. */
function tFor(messages: Record<string, unknown>) {
  return (key: string): string => {
    let node: unknown = messages
    for (const part of key.split('.')) node = (node as Record<string, unknown>)?.[part]
    return typeof node === 'string' ? node : key
  }
}

describe('ingestKindLabel (DR-039)', () => {
  it('names a DICOM OCT volume an OCT scan, with DICOM as its format', () => {
    expect(ingestKindLabel(tFor(deMessages), { kind: 'dicom', octVolume: true })).toBe('OCT-Scan · DICOM')
    expect(ingestKindLabel(tFor(enMessages), { kind: 'dicom', octVolume: true }))
      .toBe(`${tFor(enMessages)('ingestInbox.kind.e2e')} · DICOM`)
  })

  it('names every other file by its kind', () => {
    const t = tFor(deMessages)
    expect(ingestKindLabel(t, { kind: 'e2e' })).toBe('OCT-Scan')
    expect(ingestKindLabel(t, { kind: 'dicom', octVolume: false })).toBe('DICOM-Bild')
    expect(ingestKindLabel(t, { kind: 'dicom' })).toBe('DICOM-Bild')
    expect(ingestKindLabel(t, { kind: 'image', octVolume: true })).toBe('Fundusbild')
  })

  it('only a DICOM marked as a volume is one', () => {
    expect(isDicomOctVolume({ kind: 'dicom', octVolume: true })).toBe(true)
    expect(isDicomOctVolume({ kind: 'e2e', octVolume: true })).toBe(false)
    expect(isDicomOctVolume({ kind: 'dicom' })).toBe(false)
  })
})
