/**
 * Browser de-identification, layer 1 — DICOM (dcmjs).
 *
 * Synthetic objects are written with dcmjs itself, so the tests need no
 * binary fixture: patient module, private tags, a nested sequence carrying
 * identity, free-text descriptions, native and encapsulated pixel data.
 */
import { describe, expect, it } from 'vitest'
import dcmjs from 'dcmjs'

import { deidentifyFile, analyzeFile } from '../../deid/pipeline'
import {
  DICOM_DEIDENTIFICATION_METHOD, extractDicomPreview, pixelFingerprint, readDicom, stripDicom,
  type DicomDataset,
} from '../../deid/dicomDeidentify'
import { DeidError } from '../../deid/errors'
import { asFile, fileBytes } from './testUtils'

const { DicomMessage, DicomDict } = (dcmjs as unknown as {
  data: {
    DicomMessage: { readFile(b: ArrayBuffer): { dict: DicomDataset; meta: DicomDataset; write(): ArrayBuffer } }
    DicomDict: new (meta: DicomDataset) => { dict: DicomDataset; write(): ArrayBuffer }
  }
}).data

const LABEL = 'HAE-042'
const SOP_CLASS = '1.2.840.10008.5.1.4.1.1.77.1.5.1'
const PIXELS = new Uint8Array(4 * 4)
for (let i = 0; i < PIXELS.length; i++) PIXELS[i] = (i * 17) & 0xff

interface Opts {
  syntax?: string
  pixel?: unknown[]
  extra?: DicomDataset
  burnedIn?: string
}

function buildDicom(opts: Opts = {}): ArrayBuffer {
  const syntax = opts.syntax ?? '1.2.840.10008.1.2.1'
  const meta: DicomDataset = {
    '00020002': { vr: 'UI', Value: [SOP_CLASS] },
    '00020003': { vr: 'UI', Value: ['1.2.826.0.1.3680043.9.7133.1'] },
    '00020010': { vr: 'UI', Value: [syntax] },
  }
  const dict: DicomDataset = {
    '00080016': { vr: 'UI', Value: [SOP_CLASS] },
    '00080018': { vr: 'UI', Value: ['1.2.826.0.1.3680043.9.7133.1'] },
    '00080020': { vr: 'DA', Value: ['20240506'] },
    '00080022': { vr: 'DA', Value: ['20240506'] },
    '00080060': { vr: 'CS', Value: ['OP'] },
    '00080070': { vr: 'LO', Value: ['Optos'] },
    '00081090': { vr: 'LO', Value: ['PlexElite 9000'] },
    '00080080': { vr: 'LO', Value: ['Allgemeines Krankenhaus Wien'] },
    '00081030': { vr: 'LO', Value: ['Retina Mustermann check'] },
    '0008103E': { vr: 'LO', Value: ['OCT macula Maximilian'] },
    '00081070': { vr: 'PN', Value: [{ Alphabetic: 'Pfleger^Paula' }] },
    '00081010': { vr: 'SH', Value: ['STATION-A'] },
    '00100010': { vr: 'PN', Value: [{ Alphabetic: 'Mustermann^Maximilian' }] },
    '00100020': { vr: 'LO', Value: ['MRN-778899'] },
    '00100030': { vr: 'DA', Value: ['19700101'] },
    '00100040': { vr: 'CS', Value: ['M'] },
    '00101000': { vr: 'LO', Value: ['OLD-55512'] },
    '00101005': { vr: 'PN', Value: [{ Alphabetic: 'Geburtsname^Maxi' }] },
    '00101020': { vr: 'DS', Value: [1.8] },
    '00101030': { vr: 'DS', Value: [80] },
    '00181000': { vr: 'LO', Value: ['SN-0042'] },
    '00204000': { vr: 'LT', Value: ['Patient Mustermann was nervous'] },
    '00200060': { vr: 'CS', Value: ['R'] },
    '00091001': { vr: 'LO', Value: ['vendor private: Mustermann'] },
    '00291010': { vr: 'OB', Value: [new Uint8Array([1, 2, 3, 4]).buffer] },
    '00081120': {
      vr: 'SQ',
      Value: [{
        '00100010': { vr: 'PN', Value: [{ Alphabetic: 'Verschachtelt^Veronika' }] },
        '00100020': { vr: 'LO', Value: ['NESTED-ID-77'] },
      }],
    },
    '00540220': {
      vr: 'SQ',
      Value: [{
        '00080100': { vr: 'SH', Value: ['T-AA000'] },
        '00181030': { vr: 'LO', Value: ['Protokoll Mustermann'] },
        '00091002': { vr: 'LO', Value: ['nested private'] },
        '00101000': { vr: 'LO', Value: ['NESTED-OTHER-ID'] },
      }],
    },
    '00280002': { vr: 'US', Value: [1] },
    '00280004': { vr: 'CS', Value: ['MONOCHROME2'] },
    '00280010': { vr: 'US', Value: [4] },
    '00280011': { vr: 'US', Value: [4] },
    '00280100': { vr: 'US', Value: [8] },
    '00280101': { vr: 'US', Value: [8] },
    '00280103': { vr: 'US', Value: [0] },
    '7FE00010': { vr: 'OB', Value: opts.pixel ?? [PIXELS.slice().buffer] },
    ...(opts.burnedIn ? { '00280301': { vr: 'CS', Value: [opts.burnedIn] } } : {}),
    ...(opts.extra ?? {}),
  }
  const d = new DicomDict(meta)
  d.dict = dict
  return d.write()
}

function asBytes(buf: ArrayBuffer): Uint8Array {
  return new Uint8Array(buf)
}

function str(ds: DicomDataset, tag: string): string {
  const v = ds[tag]?.Value?.[0]
  if (v && typeof v === 'object') return String((v as { Alphabetic?: string }).Alphabetic ?? '')
  return v === undefined ? '' : String(v)
}

async function strip(buf: ArrayBuffer) {
  const { output } = await stripDicom(buf, LABEL)
  const back = DicomMessage.readFile(output)
  return { output, ds: back.dict, meta: back.meta }
}

describe('stripDicom — rules', () => {
  it('sets PatientName and PatientID to the study label', async () => {
    const { ds } = await strip(buildDicom())
    expect(str(ds, '00100010')).toBe(LABEL)
    expect(str(ds, '00100020')).toBe(LABEL)
  })

  it('empties the sidecar list and the browser additions', async () => {
    const { ds } = await strip(buildDicom())
    for (const tag of [
      '00100030', '00101000', '00101005', '00080080', '00081070', // sidecar CLEARED subset
      '00100040', '00101020', '00101030', '00081030', '0008103E', '00204000', '00181000', '00081010', // additions
    ]) {
      expect(ds[tag], tag).toBeDefined()
      expect((ds[tag]!.Value ?? []).every((v) => v === '' || v === undefined || v === null), tag).toBe(true)
    }
  })

  it('removes the identity-only sequences and every private tag, nested ones too', async () => {
    const { ds } = await strip(buildDicom())
    expect(ds['00081120']).toBeUndefined()
    expect(ds['00091001']).toBeUndefined()
    expect(ds['00291010']).toBeUndefined()
    const nested = (ds['00540220']!.Value![0]) as DicomDataset
    expect(nested['00091002']).toBeUndefined()
    expect(nested['00080100']).toBeDefined()
    expect(str(nested, '00181030')).toBe('')
    expect(str(nested, '00101000')).toBe('')
  })

  it('applies the label inside nested sequences that carry a patient module', async () => {
    const buf = buildDicom({
      extra: {
        '00081111': {
          vr: 'SQ',
          Value: [{
            '00100010': { vr: 'PN', Value: [{ Alphabetic: 'Nested^Name' }] },
            '00100020': { vr: 'LO', Value: ['N-1234'] },
          }],
        },
      },
    })
    const { ds, output } = await strip(buf)
    const item = ds['00081111']!.Value![0] as DicomDataset
    expect(str(item, '00100010')).toBe(LABEL)
    expect(str(item, '00100020')).toBe(LABEL)
    const text = new TextDecoder('latin1').decode(asBytes(output))
    expect(text).not.toContain('Nested')
    expect(text).not.toContain('N-1234')
  })

  it('stamps PatientIdentityRemoved and the method; keeps UIDs, dates, laterality, modality, device', async () => {
    const { ds } = await strip(buildDicom())
    expect(str(ds, '00120062')).toBe('YES')
    expect(str(ds, '00120063')).toBe(DICOM_DEIDENTIFICATION_METHOD)
    expect(str(ds, '00080018')).toBe('1.2.826.0.1.3680043.9.7133.1')
    expect(str(ds, '00080020')).toBe('20240506')
    expect(str(ds, '00080022')).toBe('20240506')
    expect(str(ds, '00200060')).toBe('R')
    expect(str(ds, '00080060')).toBe('OP')
    expect(str(ds, '00080070')).toBe('Optos')
    expect(str(ds, '00081090')).toBe('PlexElite 9000')
  })

  it('keeps the pixel data byte-identical (native)', async () => {
    const { ds } = await strip(buildDicom())
    const px = ds['7FE00010']!.Value![0] as ArrayBuffer
    expect(Array.from(new Uint8Array(px))).toEqual(Array.from(PIXELS))
  })

  it('keeps the pixel data byte-identical (encapsulated JPEG fragments)', async () => {
    const f1 = new Uint8Array([0xff, 0xd8, 1, 2, 3, 4, 0xff, 0xd9]).buffer
    const f2 = new Uint8Array([0xff, 0xd8, 9, 8, 7, 6, 0xff, 0xd9]).buffer
    const src = buildDicom({ syntax: '1.2.840.10008.1.2.4.50', pixel: [f1, f2] })
    const before = pixelFingerprint(DicomMessage.readFile(src).dict)
    const { ds } = await strip(src)
    expect(pixelFingerprint(ds)).toBe(before)
    expect((ds['7FE00010']!.Value as ArrayBuffer[]).map((b) => b.byteLength)).toEqual([8, 8])
  })

  it('keeps the transfer syntax', async () => {
    const { meta } = await strip(buildDicom({ syntax: '1.2.840.10008.1.2.1' }))
    expect(str(meta, '00020010')).toBe('1.2.840.10008.1.2.1')
  })

  it('refuses BurnedInAnnotation=YES, at the top level and nested', async () => {
    await expect(stripDicom(buildDicom({ burnedIn: 'YES' }), LABEL)).rejects.toMatchObject({ code: 'burnedIn' })
    const nested = buildDicom({
      extra: { '00081111': { vr: 'SQ', Value: [{ '00280301': { vr: 'CS', Value: ['YES'] } }] } },
    })
    await expect(stripDicom(nested, LABEL)).rejects.toMatchObject({ code: 'burnedIn' })
    await expect(stripDicom(buildDicom({ burnedIn: 'NO' }), LABEL)).resolves.toBeDefined()
  })

  it('refuses an unwritable label', async () => {
    for (const bad of ['', 'A^B', 'a\\b', 'x'.repeat(65), 'Zoë']) {
      await expect(stripDicom(buildDicom(), bad)).rejects.toMatchObject({ code: 'label' })
    }
  })

  it('refuses garbage', async () => {
    await expect(stripDicom(new Uint8Array(300).buffer, LABEL)).rejects.toBeInstanceOf(DeidError)
  })
})

describe('deidentifyFile (dicom) — sweep and hash', () => {
  it('produces output with none of the original identifiers anywhere', async () => {
    const res = await deidentifyFile(asFile(asBytes(buildDicom()), 'Mustermann_Max.dcm'), 'dicom', LABEL)
    const out = await fileBytes(res.blob)
    const latin = new TextDecoder('latin1').decode(out).toLowerCase()
    for (const s of ['mustermann', 'maximilian', 'mrn-778899', 'old-55512', '19700101', 'verschachtelt', 'nested-id-77', 'geburtsname']) {
      expect(latin.includes(s), s).toBe(false)
    }
    expect(res.sha256).toMatch(/^[0-9a-f]{64}$/)
    expect(res.size).toBe(out.length)
  })

  it('refuses when an original identifier survives in a tag the rules do not cover', async () => {
    // (0008,0094) ReferringPhysicianTelephoneNumbers is not on any list; a clinic stuffed the name in.
    const buf = buildDicom({ extra: { '00080094': { vr: 'SH', Value: ['MUSTERMANN'] } } })
    await expect(deidentifyFile(asFile(asBytes(buf)), 'dicom', LABEL)).rejects.toMatchObject({ code: 'residual' })
  })

  it('refuses a UTF-16LE copy of the ID inside binary data', async () => {
    const wide = new Uint8Array(24)
    'MRN-778899'.split('').forEach((c, i) => { wide[i * 2] = c.charCodeAt(0) })
    const buf = buildDicom({ extra: { '00091003': { vr: 'OB', Value: [wide.buffer] }, '00282000': { vr: 'OB', Value: [wide.buffer] } } })
    await expect(deidentifyFile(asFile(asBytes(buf)), 'dicom', LABEL)).rejects.toMatchObject({ code: 'residual' })
  })

  it('refuses burned-in annotation before anything is produced', async () => {
    await expect(deidentifyFile(asFile(asBytes(buildDicom({ burnedIn: 'YES' }))), 'dicom', LABEL))
      .rejects.toMatchObject({ code: 'burnedIn' })
  })
})

describe('analyze (dicom)', () => {
  it('reads the header PatientID for local matching and renders a native preview', async () => {
    const a = await analyzeFile(asFile(asBytes(buildDicom())), 'dicom')
    expect(a.headerPatientId).toBe('MRN-778899')
    expect(a.hints?.laterality).toBe('OD')
    expect(a.preview).not.toBeNull()
    expect(a.preview!.width).toBe(4)
    expect(a.preview!.height).toBe(4)
  })

  it('refuses BurnedInAnnotation=YES at analysis', async () => {
    await expect(analyzeFile(asFile(asBytes(buildDicom({ burnedIn: 'YES' }))), 'dicom')).rejects.toMatchObject({ code: 'burnedIn' })
  })

  it('has no preview for a transfer syntax it cannot decode (the UI shows a notice)', async () => {
    const buf = buildDicom({ syntax: '1.2.840.10008.1.2.4.90', pixel: [new Uint8Array([1, 2, 3, 4]).buffer] })
    const read = await readDicom(buf)
    expect(await extractDicomPreview(read)).toBeNull()
  })
})
