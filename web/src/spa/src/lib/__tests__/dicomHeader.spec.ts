/**
 * DR-029 — a DICOM export names its own eye, date and device; the patient is never read.
 */
import { describe, expect, it } from 'vitest'

import { deviceKeyFromModel, hintsFromBytes, readDicomHints } from '@/lib/dicomHeader'

/** Explicit VR little endian, short-form VRs only — enough for the tags we read. */
function element(group: number, el: number, vr: string, value: string): Uint8Array {
  const padded = value.length % 2 === 0 ? value : value + (vr === 'UI' ? '\0' : ' ')
  const out = new Uint8Array(8 + padded.length)
  const view = new DataView(out.buffer)
  view.setUint16(0, group, true)
  view.setUint16(2, el, true)
  out[4] = vr.charCodeAt(0)
  out[5] = vr.charCodeAt(1)
  view.setUint16(6, padded.length, true)
  for (let i = 0; i < padded.length; i++) out[8 + i] = padded.charCodeAt(i)
  return out
}

function part10(...elements: Uint8Array[]): Uint8Array {
  const preamble = new Uint8Array(132)
  preamble.set([0x44, 0x49, 0x43, 0x4d], 128)
  const meta = element(0x0002, 0x0010, 'UI', '1.2.840.10008.1.2.1')
  const total = preamble.length + meta.length + elements.reduce((n, e) => n + e.length, 0)
  const out = new Uint8Array(total)
  let pos = 0
  for (const part of [preamble, meta, ...elements]) {
    out.set(part, pos)
    pos += part.length
  }
  return out
}

const CLARUS = part10(
  element(0x0008, 0x0018, 'UI', '1.2.826.0.1.3680043.8.498.77'),
  element(0x0008, 0x0020, 'DA', '20260901'),
  element(0x0008, 0x0022, 'DA', '20260918'),
  element(0x0008, 0x0060, 'CS', 'OP'),
  element(0x0008, 0x0070, 'LO', 'Carl Zeiss Meditec'),
  element(0x0008, 0x1090, 'LO', 'CLARUS 700'),
  element(0x0010, 0x0010, 'PN', 'Muster^Max'),
  element(0x0010, 0x0020, 'LO', '0012345678'),
  element(0x0020, 0x0062, 'CS', 'R'),
)

describe('hintsFromBytes', () => {
  it('reads eye, acquisition date, modality and device from a Clarus export', async () => {
    const hints = await hintsFromBytes(CLARUS)
    expect(hints).toEqual({
      laterality: 'OD',
      acquisitionDate: '2026-09-18',
      modality: 'OP',
      modelName: 'CLARUS 700',
      deviceKey: 'clarus',
      sopInstanceUid: '1.2.826.0.1.3680043.8.498.77',
    })
    // Nothing about the patient comes out, even though it is in there.
    expect(JSON.stringify(hints)).not.toContain('Muster')
    expect(JSON.stringify(hints)).not.toContain('0012345678')
  })

  it('falls back from acquisition to content to study date, and maps L and B', async () => {
    const left = part10(
      element(0x0008, 0x0020, 'DA', '20260901'),
      element(0x0008, 0x0023, 'DA', '20260910'),
      element(0x0020, 0x0060, 'CS', 'L'),
    )
    const hints = await hintsFromBytes(left)
    expect(hints?.laterality).toBe('OS')
    expect(hints?.acquisitionDate).toBe('2026-09-10')
    const both = part10(element(0x0008, 0x0020, 'DA', '20260901'), element(0x0020, 0x0062, 'CS', 'B'))
    expect((await hintsFromBytes(both))?.laterality).toBe('OU')
    expect((await hintsFromBytes(both))?.acquisitionDate).toBe('2026-09-01')
  })

  it('answers null for something that is not DICOM', async () => {
    expect(await hintsFromBytes(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0]))).toBeNull()
    expect(await readDicomHints(new File(['not dicom'], 'x.dcm'))).toBeNull()
  })
})

describe('deviceKeyFromModel', () => {
  it('folds vendor spellings onto the catalogue keys', () => {
    expect(deviceKeyFromModel('CLARUS 500', 'Carl Zeiss Meditec')).toBe('clarus')
    expect(deviceKeyFromModel('PLEX Elite 9000', 'Carl Zeiss Meditec')).toBe('plexelite')
    expect(deviceKeyFromModel('Lumo', 'Optomed')).toBe('optomed')
    expect(deviceKeyFromModel('Spectralis', 'Heidelberg Engineering')).toBe('spectralis')
    expect(deviceKeyFromModel('Some Camera X1', null)).toBe('somecamerax1')
    expect(deviceKeyFromModel(null, null)).toBeNull()
  })
})
