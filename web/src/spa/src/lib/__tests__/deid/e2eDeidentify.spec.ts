/**
 * Browser de-identification, layer 1 — Heidelberg .e2e.
 *
 * The contract (shared with the server's verification layer): in every
 * patient-data chunk first_name/title become zero bytes, surname and
 * patient_id become the study label (NUL-padded), birthdate and sex become
 * zero; in place, same length, no other byte changes; a residual sweep over
 * the whole output refuses the file when an original identifier survives.
 */
import { describe, expect, it } from 'vitest'

import { deidentifyFile } from '../../deid/pipeline'
import { DeidError } from '../../deid/errors'
import { extractE2ePreview, readE2eIdentity, stripE2e } from '../../deid/e2eDeidentify'
import { parseE2eBytes } from '../../e2eParser'
import {
  LABEL, PHI, TYPE_ACQ_INFO, TYPE_PATIENT, asFile, assembleE2e, e2eWith, fileBytes, fixtureBytes,
  patientPayload, utf16le,
} from './testUtils'

const CHUNK_HEADER = 60
/** Payload start of the (first) patient chunk in the fixtures: header(36)+dir(52)+4 entries*44 + chunk header. */
const FIXTURE_PATIENT_PAYLOAD = 36 + 52 + 4 * 44 + CHUNK_HEADER

function diffOffsets(a: Uint8Array, b: Uint8Array): number[] {
  const out: number[] = []
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) out.push(i)
  return out
}

async function code(p: Promise<unknown>): Promise<string> {
  try {
    await p
  } catch (e) {
    if (e instanceof DeidError) return e.code
    throw e
  }
  return 'none'
}

describe('stripE2e — the byte contract', () => {
  it('matches the independently generated expected file byte for byte', () => {
    const original = fixtureBytes('phi-original.e2e')
    const expected = fixtureBytes('phi-stripped.e2e')
    const work = original.slice()
    stripE2e(work, LABEL)
    expect(work.length).toBe(original.length)
    expect(Array.from(work)).toEqual(Array.from(expected))
  })

  it('zeroes first name, title, birthdate and sex; puts the label in surname and patient_id', () => {
    const work = fixtureBytes('phi-original.e2e').slice()
    stripE2e(work, LABEL)
    const p = FIXTURE_PATIENT_PAYLOAD
    const slice = (off: number, len: number) => Array.from(work.subarray(p + off, p + off + len))
    const labelPadded = (len: number) => {
      const a = new Array<number>(len).fill(0)
      for (let i = 0; i < LABEL.length; i++) a[i] = LABEL.charCodeAt(i)
      return a
    }
    expect(slice(0, 31)).toEqual(new Array(31).fill(0))
    expect(slice(31, 51)).toEqual(labelPadded(51))
    expect(slice(82, 15)).toEqual(new Array(15).fill(0))
    expect(slice(97, 4)).toEqual([0, 0, 0, 0])
    expect(slice(101, 1)).toEqual([0])
    expect(slice(102, 25)).toEqual(labelPadded(25))
  })

  it('changes nothing outside the patient payload (and keeps patient_db_id)', () => {
    const original = fixtureBytes('phi-original.e2e')
    const work = original.slice()
    stripE2e(work, LABEL)
    const changed = diffOffsets(original, work)
    expect(changed.length).toBeGreaterThan(0)
    for (const off of changed) {
      expect(off).toBeGreaterThanOrEqual(FIXTURE_PATIENT_PAYLOAD)
      expect(off).toBeLessThan(FIXTURE_PATIENT_PAYLOAD + 127)
    }
    // patient_db_id of the patient chunk header (offset 32 in the 60-byte header) is untouched.
    const hdr = FIXTURE_PATIENT_PAYLOAD - CHUNK_HEADER
    expect(Array.from(work.subarray(hdr + 32, hdr + 36))).toEqual(Array.from(original.subarray(hdr + 32, hdr + 36)))
    expect(new DataView(work.buffer).getUint32(hdr + 32, true)).toBe(7)
  })

  it('strips EVERY patient chunk', () => {
    const buf = assembleE2e([
      { type: TYPE_PATIENT, patientDbId: 7, payload: patientPayload(PHI) },
      { type: TYPE_PATIENT, patientDbId: 8, payload: patientPayload({ ...PHI, first: 'Anna', surname: 'Schmidt', pid: 'MRN-1' }) },
    ])
    const r = stripE2e(buf, LABEL)
    expect(r.patientChunks).toBe(2)
    const id = readE2eIdentity(buf)
    expect(id.patientId).toBe(LABEL)
    expect(id.identifiers.filter((v) => v !== LABEL)).toEqual([])
  })

  it('refuses a label longer than 24 characters, empty or non-ASCII, without touching the buffer', () => {
    const original = fixtureBytes('phi-original.e2e')
    for (const bad of ['X'.repeat(25), '', 'Zoë-1', ' padded ']) {
      const work = original.slice()
      expect(() => stripE2e(work, bad)).toThrow(DeidError)
      expect(Array.from(work)).toEqual(Array.from(original))
    }
    expect(() => stripE2e(original.slice(), 'X'.repeat(24))).not.toThrow()
  })

  it('refuses a file without a patient chunk, and a truncated one', () => {
    const none = assembleE2e([{ type: TYPE_ACQ_INFO, payload: new Uint8Array(40) }])
    expect(() => stripE2e(none, LABEL)).toThrow(/e2eNoPatientChunk/)
    const cut = fixtureBytes('phi-original.e2e').slice(0, FIXTURE_PATIENT_PAYLOAD + 60)
    expect(() => stripE2e(cut, LABEL)).toThrow(/e2eTruncated/)
  })

  it('refuses something that is not an E2E', () => {
    expect(() => stripE2e(new Uint8Array(500).fill(0xff), LABEL)).toThrow(DeidError)
  })
})

describe('readE2eIdentity / parser', () => {
  it('reads the patient_id slot only, and nothing when the chunks disagree', () => {
    const a = fixtureBytes('phi-original.e2e')
    expect(readE2eIdentity(a).patientId).toBe(PHI.pid)
    const two = assembleE2e([
      { type: TYPE_PATIENT, payload: patientPayload({ pid: 'A-1234' }) },
      { type: TYPE_PATIENT, payload: patientPayload({ pid: 'B-9999' }) },
    ])
    expect(readE2eIdentity(two).patientId).toBeNull()
  })

  it('without the fallback, a name in the surname slot is never taken for an ID', () => {
    const buf = e2eWith({ surname: 'EIAMD140' })
    expect(parseE2eBytes(buf, { headerFallback: false })[0]!.patientId).toBe('')
    expect(parseE2eBytes(buf)[0]!.patientId).toBe('EIAMD140')
  })

  it('still parses the stripped file: laterality, date, volume count', () => {
    const work = fixtureBytes('phi-original.e2e').slice()
    stripE2e(work, LABEL)
    const scans = parseE2eBytes(work)
    expect(scans).toHaveLength(1)
    expect(scans[0]!.patientId).toBe(LABEL)
    expect(scans[0]!.laterality).toBe('OD')
    expect(scans[0]!.nBscans).toBe(25)
    expect(scans[0]!.scanDate?.toISOString().slice(0, 10)).toBe('2024-05-06')
  })

  it('extracts the fundus image as the preview', () => {
    const p = extractE2ePreview(fixtureBytes('phi-original.e2e'))
    expect(p).not.toBeNull()
    expect(p!.width).toBe(24)
    expect(p!.height).toBe(16)
    expect(p!.rgba.length).toBe(24 * 16 * 4)
  })
})

describe('deidentifyFile (e2e) — strip, sweep, hash', () => {
  it('returns the stripped bytes, their sha-256 and finds no residual', async () => {
    const original = fixtureBytes('phi-original.e2e')
    const res = await deidentifyFile(asFile(original, 'Mustermann_Max.e2e'), 'e2e', LABEL)
    const out = await fileBytes(res.blob)
    expect(Array.from(out)).toEqual(Array.from(fixtureBytes('phi-stripped.e2e')))
    expect(res.size).toBe(original.length)
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', out as BufferSource))
    expect(res.sha256).toBe(Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join(''))
    expect(res.sha256).toMatch(/^[0-9a-f]{64}$/)
  })

  it('refuses when the surname also sits in another chunk (ASCII)', async () => {
    const extra = new Uint8Array(80)
    extra.set(new TextEncoder().encode('exported for Mustermann'), 10)
    const buf = e2eWith(PHI, [{ type: TYPE_ACQ_INFO, payload: extra }])
    expect(await code(deidentifyFile(asFile(buf), 'e2e', LABEL))).toBe('residual')
  })

  it('refuses a UTF-16LE copy of the name, and a differently cased one', async () => {
    const wide = new Uint8Array(80)
    wide.set(utf16le('Maximilian'), 6)
    expect(await code(deidentifyFile(asFile(e2eWith(PHI, [{ type: TYPE_ACQ_INFO, payload: wide }])), 'e2e', LABEL))).toBe('residual')
    const upper = new Uint8Array(80)
    upper.set(new TextEncoder().encode('MUSTERMANN'), 3)
    expect(await code(deidentifyFile(asFile(e2eWith(PHI, [{ type: TYPE_ACQ_INFO, payload: upper }])), 'e2e', LABEL))).toBe('residual')
  })

  it('refuses a copy of the hospital ID in another chunk', async () => {
    const extra = new Uint8Array(60)
    extra.set(new TextEncoder().encode(PHI.pid), 20)
    expect(await code(deidentifyFile(asFile(e2eWith(PHI, [{ type: TYPE_ACQ_INFO, payload: extra }])), 'e2e', LABEL))).toBe('residual')
  })

  it('ignores values shorter than 3 characters and values equal to the label', async () => {
    const extra = new Uint8Array(60)
    extra.set(new TextEncoder().encode('Li HAE-042'), 5)
    const buf = e2eWith({ ...PHI, first: 'Li', surname: 'Wu', pid: LABEL }, [{ type: TYPE_ACQ_INFO, payload: extra }])
    expect(await code(deidentifyFile(asFile(buf), 'e2e', LABEL))).toBe('none')
  })

  it('does not report the value in the error', async () => {
    const extra = new Uint8Array(60)
    extra.set(new TextEncoder().encode('Mustermann'), 5)
    try {
      await deidentifyFile(asFile(e2eWith(PHI, [{ type: TYPE_ACQ_INFO, payload: extra }])), 'e2e', LABEL)
      throw new Error('expected refusal')
    } catch (e) {
      expect((e as Error).message).not.toMatch(/Mustermann|Maximilian|MRN/i)
    }
  })

  it('searches a large buffer in reasonable time', async () => {
    const filler = new Uint8Array(24 * 1024 * 1024)
    for (let i = 0; i < filler.length; i += 4099) filler[i] = (i >> 3) & 0xff
    const buf = e2eWith(PHI, [{ type: 0x40000000, ind: 1, payload: filler }])
    const t0 = Date.now()
    const res = await deidentifyFile(asFile(buf), 'e2e', LABEL)
    expect(res.size).toBe(buf.length)
    expect(Date.now() - t0).toBeLessThan(20_000)
  })
})
