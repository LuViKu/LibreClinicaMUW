/**
 * DR-029 — a file is what its bytes say, not what the browser or the operator called it.
 */
import { describe, expect, it } from 'vitest'

import { HEAD_BYTES, sniffBytes, sniffFile } from '@/lib/fileKind'

function withMagic(magic: number[] | string, length = 64, offset = 0): Uint8Array {
  const b = new Uint8Array(length)
  const bytes = typeof magic === 'string' ? Array.from(magic, (c) => c.charCodeAt(0)) : magic
  b.set(bytes, offset)
  return b
}

describe('sniffBytes', () => {
  it('knows PNG and JPEG by their signatures', () => {
    expect(sniffBytes(withMagic([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))).toEqual({ kind: 'image', format: 'png' })
    expect(sniffBytes(withMagic([0xff, 0xd8, 0xff, 0xe0]))).toEqual({ kind: 'image', format: 'jpeg' })
  })

  it('knows a Part-10 DICOM file by the marker after the preamble', () => {
    expect(sniffBytes(withMagic('DICM', HEAD_BYTES, 128))).toEqual({ kind: 'dicom', format: 'dicom' })
    // "DICM" at the start of a file is not a DICOM file.
    expect(sniffBytes(withMagic('DICM', HEAD_BYTES, 0))).toBeNull()
  })

  it('knows the three Spectralis export magics', () => {
    for (const magic of ['CMDb', 'MDbDir', 'E2EMultipleVolumeFile']) {
      expect(sniffBytes(withMagic(magic)), magic).toEqual({ kind: 'e2e', format: 'e2e' })
    }
  })

  it('ignores the claimed type: a PNG called .jpg is a PNG, a text file called .dcm is nothing', () => {
    expect(sniffBytes(withMagic([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), 'photo.jpg')?.format).toBe('png')
    expect(sniffBytes(withMagic('hello world'), 'scan.dcm')).toBeNull()
  })

  it('grants an unknown vendor magic only under the .e2e name, and only when printable', () => {
    const head = withMagic('HRDb')
    expect(sniffBytes(head)).toBeNull()
    expect(sniffBytes(head, 'scan.bin')).toBeNull()
    expect(sniffBytes(head, 'scan.E2E')).toEqual({ kind: 'e2e', format: 'e2e' })
    expect(sniffBytes(withMagic([0x01, 0x02, 0x03]), 'scan.e2e')).toBeNull()
  })

  it('answers nothing for empty or too-short input', () => {
    expect(sniffBytes(new Uint8Array(0))).toBeNull()
    expect(sniffBytes(new Uint8Array([0xff]))).toBeNull()
  })
})

describe('sniffFile', () => {
  it('reads the head of a File object', async () => {
    const png = new File([withMagic([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 300)], 'a.jpg', { type: 'image/jpeg' })
    expect(await sniffFile(png)).toEqual({ kind: 'image', format: 'png' })
    const dicom = new File([withMagic('DICM', 512, 128)], 'export.dcm', { type: '' })
    expect(await sniffFile(dicom)).toEqual({ kind: 'dicom', format: 'dicom' })
    const text = new File(['just some notes'], 'notes.txt', { type: 'text/plain' })
    expect(await sniffFile(text)).toBeNull()
  })
})
