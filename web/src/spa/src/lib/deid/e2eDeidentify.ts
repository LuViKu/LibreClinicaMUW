/**
 * Browser de-identification (layer 1) — Heidelberg {@code .e2e}.
 *
 * Rewrites the patient-data chunk (type 9) of EVERY chunk that carries one,
 * IN PLACE: the file length does not change and no byte outside the patient
 * payload is touched. Payload layout (verified against oct_converter 0.7.0
 * {@code e2e_binary.patient_id_structure}, which reads exactly 127 bytes):
 *
 * <pre>
 *   off   0  len 31  first_name   -> all zero
 *   off  31  len 51  surname      -> study label, NUL-padded
 *   off  82  len 15  title        -> all zero
 *   off  97  u32     birthdate    -> 0
 *   off 101  1 byte  sex          -> 0
 *   off 102  len 25  patient_id   -> study label, NUL-padded
 * </pre>
 *
 * The chunk header's {@code patient_db_id} is KEPT: it is a numeric internal
 * database key of the acquisition software, and the volume grouping
 * (patient_db_id, study_id, series_id) needs it. It is not a name, a hospital
 * ID or a date.
 *
 * The directory walk mirrors the one in {@code e2eParser.ts} and
 * oct_converter's reader. A chunk is treated as patient data when EITHER its
 * own header OR its directory entry says type 9.
 */
import { DeidError } from './errors'

const FILE_HEADER_BYTES = 36
const MAIN_DIRECTORY_BYTES = 52
const SUB_DIRECTORY_ENTRY_BYTES = 44
const CHUNK_HEADER_BYTES = 60

const MAIN_DIR_NUM_ENTRIES_OFFSET = 36
const MAIN_DIR_CURRENT_OFFSET = 40
const MAIN_DIR_PREV_OFFSET = 44

const SUB_ENTRY_POS_OFFSET = 0
const SUB_ENTRY_START_OFFSET = 4
const SUB_ENTRY_SIZE_OFFSET = 8
const SUB_ENTRY_TYPE_OFFSET = 36

const CHUNK_TYPE_OFFSET = 52
const CHUNK_IND_OFFSET = 48
const CHUNK_TYPE_PATIENT_DATA = 9
const CHUNK_TYPE_IMAGE = 1073741824

const MULTI_VOLUME_MAGIC = 'E2EMultipleVolumeFile'

export const E2E_FIRST_NAME = { off: 0, len: 31 } as const
export const E2E_SURNAME = { off: 31, len: 51 } as const
export const E2E_TITLE = { off: 82, len: 15 } as const
export const E2E_BIRTHDATE_OFF = 97
export const E2E_SEX_OFF = 101
export const E2E_PATIENT_ID = { off: 102, len: 25 } as const
export const E2E_PATIENT_PAYLOAD_BYTES = 127

/** The longest label the 25-byte patient_id slot holds with a NUL terminator left over. */
export const E2E_MAX_LABEL = 24

export interface E2eChunkRef {
  /** Absolute offset of the 60-byte chunk header. */
  headerOffset: number
  /** Chunk type as the chunk's own header says. */
  type: number
  /** Chunk type as the directory entry says. */
  entryType: number
}

function latin1(bytes: Uint8Array): string {
  let end = bytes.length
  while (end > 0 && bytes[end - 1] === 0) end--
  let s = ''
  for (let i = 0; i < end; i++) s += String.fromCharCode(bytes[i]!)
  return s
}

function byteSkipOf(buf: Uint8Array): number {
  if (buf.length < MULTI_VOLUME_MAGIC.length) return 0
  return latin1(buf.subarray(0, MULTI_VOLUME_MAGIC.length)) === MULTI_VOLUME_MAGIC ? 64 : 0
}

/**
 * Every chunk the directory lists, once each (several entries may point at one
 * chunk). Throws a {@link DeidError} when the header is not a Spectralis one.
 */
export function listE2eChunks(buf: Uint8Array): E2eChunkRef[] {
  if (buf.length < FILE_HEADER_BYTES + MAIN_DIRECTORY_BYTES) throw new DeidError('parse', 'e2e too short')
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength)
  const skip = byteSkipOf(buf)
  const magic0 = buf[skip]!
  if (magic0 < 0x20 || magic0 >= 0x7f) throw new DeidError('parse', 'e2e magic')

  const dirs: number[] = []
  const readDir = (off: number) => ({
    numEntries: view.getUint32(off + MAIN_DIR_NUM_ENTRIES_OFFSET, true),
    current: view.getUint32(off + MAIN_DIR_CURRENT_OFFSET, true),
    prev: view.getUint32(off + MAIN_DIR_PREV_OFFSET, true),
  })
  const first = readDir(skip + FILE_HEADER_BYTES)
  let current = first.current
  const visited = new Set<number>()
  while (current !== 0) {
    const abs = current + skip
    if (abs + MAIN_DIRECTORY_BYTES > buf.length) break
    if (visited.has(abs)) break
    visited.add(abs)
    dirs.push(abs)
    const next = readDir(abs)
    if (next.prev === current) break
    current = next.prev
  }

  const byStart = new Map<number, E2eChunkRef>()
  for (const dirOffset of dirs) {
    const dir = readDir(dirOffset)
    let entry = dirOffset + MAIN_DIRECTORY_BYTES
    for (let i = 0; i < dir.numEntries; i++) {
      if (entry + SUB_DIRECTORY_ENTRY_BYTES > buf.length) break
      const pos = view.getUint32(entry + SUB_ENTRY_POS_OFFSET, true)
      const start = view.getUint32(entry + SUB_ENTRY_START_OFFSET, true)
      void view.getUint32(entry + SUB_ENTRY_SIZE_OFFSET, true)
      const entryType = view.getUint32(entry + SUB_ENTRY_TYPE_OFFSET, true)
      entry += SUB_DIRECTORY_ENTRY_BYTES
      if (!(start > pos && start > 0)) continue
      const headerOffset = start + skip
      if (headerOffset + CHUNK_HEADER_BYTES > buf.length) continue
      const type = view.getUint32(headerOffset + CHUNK_TYPE_OFFSET, true)
      const known = byStart.get(headerOffset)
      if (known) {
        // Two entries, one chunk: remember a 9 from either.
        if (entryType === CHUNK_TYPE_PATIENT_DATA) known.entryType = entryType
        continue
      }
      byStart.set(headerOffset, { headerOffset, type, entryType })
    }
  }
  return [...byStart.values()]
}

export function isPatientChunk(c: E2eChunkRef): boolean {
  return c.type === CHUNK_TYPE_PATIENT_DATA || c.entryType === CHUNK_TYPE_PATIENT_DATA
}

export interface E2ePatientFields {
  firstName: string
  surname: string
  title: string
  patientId: string
}

function readPatientFields(buf: Uint8Array, payload: number): E2ePatientFields {
  const slice = (f: { off: number; len: number }) => latin1(buf.subarray(payload + f.off, payload + f.off + f.len)).trim()
  return {
    firstName: slice(E2E_FIRST_NAME),
    surname: slice(E2E_SURNAME),
    title: slice(E2E_TITLE),
    patientId: slice(E2E_PATIENT_ID),
  }
}

/** The study label must fit the slot and be printable ASCII (the converter decodes the slot as ASCII). */
export function assertE2eLabel(label: string): void {
  if (label.length === 0 || label.length > E2E_MAX_LABEL || !/^[\x20-\x7e]+$/.test(label) || label !== label.trim()) {
    throw new DeidError('label', 'e2e')
  }
}

export interface E2eHeaderIdentity {
  /** The {@code patient_id} slot, when every patient chunk agrees on it and it is non-empty; else null. */
  patientId: string | null
  /** Every non-empty identifying string found (names, title, ID) — for the residual sweep, never for the wire. */
  identifiers: string[]
  /** Number of patient-data chunks found. */
  patientChunks: number
}

/** Read what the patient chunks say, without changing anything. */
export function readE2eIdentity(buf: Uint8Array): E2eHeaderIdentity {
  const chunks = listE2eChunks(buf).filter(isPatientChunk)
  const ids = new Set<string>()
  const identifiers: string[] = []
  for (const c of chunks) {
    const payload = c.headerOffset + CHUNK_HEADER_BYTES
    if (payload + E2E_PATIENT_PAYLOAD_BYTES > buf.length) throw new DeidError('e2eTruncated')
    const f = readPatientFields(buf, payload)
    if (f.patientId) ids.add(f.patientId)
    for (const v of [f.firstName, f.surname, f.title, f.patientId]) if (v) identifiers.push(v)
  }
  return {
    patientId: ids.size === 1 ? [...ids][0]! : null,
    identifiers,
    patientChunks: chunks.length,
  }
}

export interface E2eStripResult {
  /** The identifying strings that were in the patient chunks (for the residual sweep). */
  identifiers: string[]
  patientChunks: number
}

/**
 * Strip every patient-data chunk of {@code buf} in place and return what was
 * there. Throws a {@link DeidError} (and may leave {@code buf} partly changed —
 * the caller discards it) when the label does not fit, no patient chunk can be
 * found, or one is truncated.
 */
export function stripE2e(buf: Uint8Array, label: string): E2eStripResult {
  assertE2eLabel(label)
  const chunks = listE2eChunks(buf).filter(isPatientChunk)
  if (chunks.length === 0) throw new DeidError('e2eNoPatientChunk')

  const identifiers: string[] = []
  const labelBytes = new Uint8Array(label.length)
  for (let i = 0; i < label.length; i++) labelBytes[i] = label.charCodeAt(i)

  // Validate every chunk before touching any, so a refusal changes nothing.
  for (const c of chunks) {
    if (c.headerOffset + CHUNK_HEADER_BYTES + E2E_PATIENT_PAYLOAD_BYTES > buf.length) throw new DeidError('e2eTruncated')
  }
  for (const c of chunks) {
    const payload = c.headerOffset + CHUNK_HEADER_BYTES
    const f = readPatientFields(buf, payload)
    for (const v of [f.firstName, f.surname, f.title, f.patientId]) if (v) identifiers.push(v)

    buf.fill(0, payload + E2E_FIRST_NAME.off, payload + E2E_FIRST_NAME.off + E2E_FIRST_NAME.len)
    buf.fill(0, payload + E2E_SURNAME.off, payload + E2E_SURNAME.off + E2E_SURNAME.len)
    buf.set(labelBytes, payload + E2E_SURNAME.off)
    buf.fill(0, payload + E2E_TITLE.off, payload + E2E_TITLE.off + E2E_TITLE.len)
    buf.fill(0, payload + E2E_BIRTHDATE_OFF, payload + E2E_BIRTHDATE_OFF + 4)
    buf[payload + E2E_SEX_OFF] = 0
    buf.fill(0, payload + E2E_PATIENT_ID.off, payload + E2E_PATIENT_ID.off + E2E_PATIENT_ID.len)
    buf.set(labelBytes, payload + E2E_PATIENT_ID.off)
  }
  return { identifiers, patientChunks: chunks.length }
}

/**
 * Preview image of an E2E, if cheap: the first fundus/SLO image chunk
 * (type 0x40000000, ind 0, 8-bit), else the first B-scan (ind 1, 16-bit,
 * stretched). Null when neither is present.
 */
export interface RawPreview {
  width: number
  height: number
  /** RGBA, width*height*4. */
  rgba: Uint8ClampedArray
}

export function extractE2ePreview(buf: Uint8Array): RawPreview | null {
  let chunks: E2eChunkRef[]
  try {
    chunks = listE2eChunks(buf)
  } catch {
    return null
  }
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength)
  let bscan: { width: number; height: number; data: number } | null = null
  for (const c of chunks) {
    if (c.type !== CHUNK_TYPE_IMAGE) continue
    const ind = view.getUint16(c.headerOffset + CHUNK_IND_OFFSET, true)
    const p = c.headerOffset + CHUNK_HEADER_BYTES
    if (p + 20 > buf.length) continue
    const height = view.getUint32(p + 12, true)
    const width = view.getUint32(p + 16, true)
    if (width === 0 || height === 0 || width > 16384 || height > 16384) continue
    const data = p + 20
    if (ind === 0) {
      if (data + width * height > buf.length) continue
      const rgba = new Uint8ClampedArray(width * height * 4)
      for (let i = 0; i < width * height; i++) {
        const v = buf[data + i]!
        rgba[i * 4] = v
        rgba[i * 4 + 1] = v
        rgba[i * 4 + 2] = v
        rgba[i * 4 + 3] = 255
      }
      return { width, height, rgba }
    }
    if (ind === 1 && bscan === null && data + width * height * 2 <= buf.length) bscan = { width, height, data }
  }
  if (!bscan) return null
  const n = bscan.width * bscan.height
  let max = 1
  for (let i = 0; i < n; i++) {
    const v = view.getUint16(bscan.data + i * 2, true)
    if (v > max && v < 0xff00) max = v
  }
  const rgba = new Uint8ClampedArray(n * 4)
  for (let i = 0; i < n; i++) {
    const v = view.getUint16(bscan.data + i * 2, true)
    const g = Math.round(255 * Math.sqrt(Math.min(v, max) / max))
    rgba[i * 4] = g
    rgba[i * 4 + 1] = g
    rgba[i * 4 + 2] = g
    rgba[i * 4 + 3] = 255
  }
  return { width: bscan.width, height: bscan.height, rgba }
}
