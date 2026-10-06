/**
 * Test helpers for the de-identification specs: an in-memory E2E assembler
 * (the TypeScript twin of {@code assemble_file} in fixtures/generate_fixtures.py)
 * and fixture loaders.
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

export const TYPE_PATIENT = 9
export const TYPE_ACQ_INFO = 10
export const TYPE_PRE_DATA = 3
export const TYPE_BSCAN_META = 10004
export const TYPE_IMAGE = 1073741824

export interface ChunkSpec {
  type: number
  patientDbId?: number
  studyId?: number
  seriesId?: number
  sliceId?: number
  ind?: number
  payload: Uint8Array
}

function ascii(s: string, size: number): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < Math.min(s.length, size); i++) out[i] = s.charCodeAt(i) & 0xff
  return out
}

export function utf16le(s: string): Uint8Array {
  const out = new Uint8Array(s.length * 2)
  for (let i = 0; i < s.length; i++) {
    out[i * 2] = s.charCodeAt(i) & 0xff
    out[i * 2 + 1] = s.charCodeAt(i) >> 8
  }
  return out
}

export interface PatientFields {
  first?: string
  surname?: string
  title?: string
  birthdate?: number
  sex?: string
  pid?: string
}

export function patientPayload(f: PatientFields): Uint8Array {
  const out = new Uint8Array(127)
  out.set(ascii(f.first ?? '', 31), 0)
  out.set(ascii(f.surname ?? '', 51), 31)
  out.set(ascii(f.title ?? '', 15), 82)
  new DataView(out.buffer).setUint32(97, f.birthdate ?? 0, true)
  out.set(ascii(f.sex ?? '', 1), 101)
  out.set(ascii(f.pid ?? '', 25), 102)
  return out
}

export const PHI = {
  first: 'Maximilian',
  surname: 'Mustermann',
  title: 'Dr.',
  birthdate: 19700101,
  sex: 'M',
  pid: 'MRN-778899',
}
export const LABEL = 'HAE-042'

/** Lay out a minimal E2E: header, one main directory, entries, then header+payload per chunk. */
export function assembleE2e(chunks: ChunkSpec[]): Uint8Array {
  const FILE_HEADER = 36
  const MAIN_DIR = 52
  const ENTRY = 44
  const CHUNK_HEADER = 60
  const entriesStart = FILE_HEADER + MAIN_DIR
  let cursor = entriesStart + ENTRY * chunks.length
  const starts: number[] = []
  for (const c of chunks) {
    starts.push(cursor)
    cursor += CHUNK_HEADER + c.payload.length
  }
  const out = new Uint8Array(cursor)
  const dv = new DataView(out.buffer)
  out.set(ascii('CMDb', 12), 0)
  dv.setUint32(12, 1, true)
  out.set(ascii('MDbDir', 12), FILE_HEADER)
  dv.setUint32(FILE_HEADER + 12, 1, true)
  dv.setUint32(FILE_HEADER + 36, chunks.length, true)
  dv.setUint32(FILE_HEADER + 40, FILE_HEADER, true)
  dv.setUint32(FILE_HEADER + 44, 0, true)
  chunks.forEach((c, i) => {
    const e = entriesStart + ENTRY * i
    dv.setUint32(e + 0, 1, true)
    dv.setUint32(e + 4, starts[i]!, true)
    dv.setUint32(e + 8, CHUNK_HEADER + c.payload.length, true)
    dv.setUint32(e + 16, c.patientDbId ?? 1, true)
    dv.setUint32(e + 20, c.studyId ?? 0, true)
    dv.setUint32(e + 24, c.seriesId ?? 0, true)
    dv.setInt32(e + 28, c.sliceId ?? -1, true)
    dv.setUint32(e + 36, c.type, true)
    const h = starts[i]!
    out.set(ascii('MDbChunk', 12), h)
    dv.setUint32(h + 20, 1, true)
    dv.setUint32(h + 24, CHUNK_HEADER + c.payload.length, true)
    dv.setUint32(h + 32, c.patientDbId ?? 1, true)
    dv.setUint32(h + 36, c.studyId ?? 0, true)
    dv.setUint32(h + 40, c.seriesId ?? 0, true)
    dv.setInt32(h + 44, c.sliceId ?? -1, true)
    dv.setUint16(h + 48, c.ind ?? 0, true)
    dv.setUint32(h + 52, c.type, true)
    out.set(c.payload, h + CHUNK_HEADER)
  })
  return out
}

export function preDataPayload(side: 'R' | 'L'): Uint8Array {
  const out = new Uint8Array(5)
  out[4] = side.charCodeAt(0)
  return out
}

export function bscanMetaPayload(numImages: number, ticks: bigint): Uint8Array {
  const out = new Uint8Array(104)
  const dv = new DataView(out.buffer)
  dv.setUint32(64, numImages, true)
  dv.setBigUint64(88, ticks, true)
  return out
}

export function fixtureBytes(name: string): Uint8Array {
  return new Uint8Array(readFileSync(resolve(process.cwd(), 'src/lib/__tests__/fixtures', name)))
}

export function asFile(bytes: Uint8Array, name = 'x.bin'): File {
  return new File([bytes as BlobPart], name, { type: 'application/octet-stream' })
}

export async function fileBytes(blob: Blob): Promise<Uint8Array> {
  const f = blob as File & { arrayBuffer?: () => Promise<ArrayBuffer> }
  if (typeof f.arrayBuffer === 'function') return new Uint8Array(await f.arrayBuffer())
  return new Promise((res, rej) => {
    const r = new FileReader()
    r.onload = () => res(new Uint8Array(r.result as ArrayBuffer))
    r.onerror = () => rej(r.error)
    r.readAsArrayBuffer(blob)
  })
}

/** An 8-bit fundus (SLO) image chunk: 20-byte image struct (size,type,unknown,height,width) + pixels. */
export function fundusChunk(width = 8, height = 6): ChunkSpec {
  const payload = new Uint8Array(20 + width * height)
  const dv = new DataView(payload.buffer)
  dv.setUint32(0, width * height, true)
  dv.setUint32(12, height, true)
  dv.setUint32(16, width, true)
  for (let i = 0; i < width * height; i++) payload[20 + i] = (i * 5) & 0xff
  return { type: TYPE_IMAGE, patientDbId: 7, studyId: 30, seriesId: 300, ind: 0, payload }
}

/** A standard OD volume + patient chunk + optional extra chunks. */
export function e2eWith(patient: PatientFields, extra: ChunkSpec[] = []): Uint8Array {
  return assembleE2e([
    { type: TYPE_PATIENT, patientDbId: 7, payload: patientPayload(patient) },
    { type: TYPE_PRE_DATA, patientDbId: 7, studyId: 30, seriesId: 301, payload: preDataPayload('R') },
    {
      type: TYPE_BSCAN_META, patientDbId: 7, studyId: 30, seriesId: 301, sliceId: 0,
      // 2024-05-06T12:00:00Z as Windows ticks.
      payload: bscanMetaPayload(25, 133_594_704_000_000_000n),
    },
    ...extra,
  ])
}
