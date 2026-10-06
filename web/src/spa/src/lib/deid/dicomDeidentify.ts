/**
 * Browser de-identification (layer 1) — DICOM.
 *
 * Reads the object with dcmjs (MIT, lazy-loaded: it is ~0.7 MB minified and
 * only the internet-facing deployment ever loads it), rewrites the dataset and
 * writes it back. What happens, and why it is a list rather than a profile
 * engine, mirrors {@code dicom-scp/src/dicom_scp/deidentify.py} (DR-029), which
 * is ported EXACTLY below, plus the additions the browser layer owes the
 * internet-facing deployment:
 *
 *  - PatientName and PatientID become the study label;
 *  - the sidecar's CLEARED list is emptied (kept present, value removed);
 *  - its REMOVED sequences are deleted;
 *  - ADDED here: PatientSex, PatientSize, PatientWeight, PatientBirthName, the
 *    free-text descriptions (a Clarus technician types names there),
 *    DeviceSerialNumber, StationName (InstitutionalDepartmentName and
 *    OperatorsName are already on the sidecar list);
 *  - EVERY private tag (odd group) is removed — the sidecar keeps them unless
 *    asked, the internet-facing deployment cannot vet them;
 *  - the same rules apply inside every nested sequence item;
 *  - PatientIdentityRemoved=YES and a DeidentificationMethod are stamped;
 *  - BurnedInAnnotation=YES (anywhere) is a refusal: the identity is in the
 *    pixels, which are never touched.
 *
 * Kept: UIDs, dates, laterality, modality, device manufacturer/model, geometry,
 * and the pixel data — verified byte-identical after the write.
 */
import { DeidError } from './errors'
import type { RawPreview } from './e2eDeidentify'

export const DICOM_DEIDENTIFICATION_METHOD = 'LibreClinica browser de-identification v1'

type Tag = string

/** {@code PatientName}, {@code PatientID} — set to the label. */
const T_PATIENT_NAME: Tag = '00100010'
const T_PATIENT_ID: Tag = '00100020'

/**
 * Port of {@code CLEARED} in deidentify.py (kept present, emptied), then the
 * browser layer's additions. Tag numbers are from PS3.6.
 */
const CLEARED: ReadonlySet<Tag> = new Set<Tag>([
  // --- deidentify.py CLEARED, in the same order ---
  '00100030', // PatientBirthDate
  '00100032', // PatientBirthTime
  '00101010', // PatientAge
  '00101000', // OtherPatientIDs
  '00101001', // OtherPatientNames
  '00101005', // PatientBirthName
  '00101060', // PatientMotherBirthName
  '00101040', // PatientAddress
  '00102154', // PatientTelephoneNumbers
  '00102155', // PatientTelecomInformation
  '00104000', // PatientComments
  '00100021', // IssuerOfPatientID
  '00102160', // EthnicGroup
  '00102180', // Occupation
  '00380400', // PatientInstitutionResidence
  '00101080', // MilitaryRank
  '001021F0', // PatientReligiousPreference
  '00101090', // MedicalRecordLocator
  '001021B0', // AdditionalPatientHistory
  '00080090', // ReferringPhysicianName
  '00081050', // PerformingPhysicianName
  '00081070', // OperatorsName
  '00081048', // PhysiciansOfRecord
  '00081060', // NameOfPhysiciansReadingStudy
  '00321032', // RequestingPhysician
  '00080080', // InstitutionName
  '00080081', // InstitutionAddress
  '00081040', // InstitutionalDepartmentName
  '00080050', // AccessionNumber
  '00200010', // StudyID
  // --- added by the browser layer ---
  '00100040', // PatientSex
  '00101020', // PatientSize
  '00101030', // PatientWeight
  '00081030', // StudyDescription
  '0008103E', // SeriesDescription
  '00204000', // ImageComments
  '00400254', // PerformedProcedureStepDescription
  '00321060', // RequestedProcedureDescription
  '00181030', // ProtocolName
  '00181000', // DeviceSerialNumber
  '00081010', // StationName
])

/** Port of {@code REMOVED}: sequences that only ever carry identity. */
const REMOVED: ReadonlySet<Tag> = new Set<Tag>([
  '00101002', // OtherPatientIDsSequence
  '00400275', // RequestAttributesSequence
  '00081120', // ReferencedPatientSequence
])

/** Tags whose values are collected for the residual sweep. */
const IDENTIFIER_TAGS: ReadonlySet<Tag> = new Set<Tag>([
  T_PATIENT_NAME,
  T_PATIENT_ID,
  '00101000', // OtherPatientIDs
  '00101001', // OtherPatientNames
  '00101005', // PatientBirthName
  '00101060', // PatientMotherBirthName
  '00100030', // PatientBirthDate
])

const T_BURNED_IN = '00280301'
const T_IDENTITY_REMOVED = '00120062'
const T_DEID_METHOD = '00120063'
const T_TRANSFER_SYNTAX = '00020010'

export interface DicomElement {
  vr: string
  Value?: unknown[]
  _rawValue?: unknown
}
export type DicomDataset = Record<Tag, DicomElement>
interface DicomDictLike {
  meta: DicomDataset
  dict: DicomDataset
  write(): ArrayBuffer
}
interface DcmjsLike {
  data: { DicomMessage: { readFile(b: ArrayBuffer): DicomDictLike } }
}

let dcmjsPromise: Promise<DcmjsLike> | null = null

/** Lazy-load dcmjs (one chunk, fetched only when a DICOM is de-identified). */
export function loadDcmjs(): Promise<DcmjsLike> {
  dcmjsPromise ??= import('dcmjs').then((m) => ((m as { default?: unknown }).default ?? m) as DcmjsLike)
  return dcmjsPromise
}

/** The study label as DICOM can carry it (LO, ≤ 64, no value-separator characters). */
export function assertDicomLabel(label: string): void {
  if (label.length === 0 || label.length > 64 || !/^[\x20-\x7e]+$/.test(label) || /[\\^=]/.test(label) || label !== label.trim()) {
    throw new DeidError('label', 'dicom')
  }
}

function isArrayBufferLike(x: unknown): boolean {
  if (x === null || typeof x !== 'object') return false
  const tag = Object.prototype.toString.call(x)
  return tag === '[object ArrayBuffer]' || ArrayBuffer.isView(x)
}

function toU8(x: unknown): Uint8Array {
  if (ArrayBuffer.isView(x)) return new Uint8Array(x.buffer, x.byteOffset, x.byteLength)
  return new Uint8Array(x as ArrayBuffer)
}

/** Every string a value carries — PN components, plain strings, numbers as text. */
function stringsOf(value: unknown, out: string[]): void {
  if (value === null || value === undefined) return
  if (typeof value === 'string') {
    out.push(value)
    return
  }
  if (typeof value === 'number') {
    out.push(String(value))
    return
  }
  if (Array.isArray(value)) {
    for (const v of value) stringsOf(v, out)
    return
  }
  if (isArrayBufferLike(value)) return
  if (typeof value === 'object') {
    for (const v of Object.values(value as Record<string, unknown>)) stringsOf(v, out)
  }
}

/** A name or ID plus its components (PN: family^given^middle, groups split by =, spaces). */
function withComponents(s: string): string[] {
  const out = [s]
  for (const part of s.split(/[\^=\s,]+/)) if (part) out.push(part)
  return out
}

function forEachItem(el: DicomElement, fn: (item: DicomDataset) => void): void {
  if (el.vr !== 'SQ' || !Array.isArray(el.Value)) return
  for (const item of el.Value) {
    if (item && typeof item === 'object' && !isArrayBufferLike(item)) fn(item as DicomDataset)
  }
}

/** Collect the identifying strings of the whole tree — including sequences about to be removed. */
function collectIdentifiers(ds: DicomDataset, out: string[]): void {
  for (const tag of Object.keys(ds)) {
    const el = ds[tag]!
    if (IDENTIFIER_TAGS.has(tag)) {
      const raw: string[] = []
      stringsOf(el.Value, raw)
      for (const s of raw) for (const c of withComponents(s.trim())) out.push(c)
    }
    forEachItem(el, (item) => collectIdentifiers(item, out))
  }
}

function anyBurnedIn(ds: DicomDataset): boolean {
  for (const tag of Object.keys(ds)) {
    const el = ds[tag]!
    if (tag === T_BURNED_IN) {
      const v: string[] = []
      stringsOf(el.Value, v)
      if (v.some((s) => s.trim().toUpperCase() === 'YES')) return true
    }
    let hit = false
    forEachItem(el, (item) => {
      if (!hit && anyBurnedIn(item)) hit = true
    })
    if (hit) return true
  }
  return false
}

function isOddGroup(tag: Tag): boolean {
  return (parseInt(tag.slice(0, 4), 16) & 1) === 1
}

function setLabel(ds: DicomDataset, tag: Tag, vr: 'PN' | 'LO', label: string): void {
  ds[tag] = { vr, Value: vr === 'PN' ? [{ Alphabetic: label }] : [label] }
}

/** Apply the rules to one dataset and, recursively, to its sequence items. */
function strip(ds: DicomDataset, label: string, top: boolean): void {
  for (const tag of Object.keys(ds)) {
    const el = ds[tag]!
    if (isOddGroup(tag)) {
      delete ds[tag]
      continue
    }
    if (REMOVED.has(tag)) {
      delete ds[tag]
      continue
    }
    if (tag === T_PATIENT_NAME) {
      setLabel(ds, tag, 'PN', label)
      continue
    }
    if (tag === T_PATIENT_ID) {
      setLabel(ds, tag, 'LO', label)
      continue
    }
    if (CLEARED.has(tag)) {
      el.Value = []
      delete el._rawValue
      continue
    }
    forEachItem(el, (item) => strip(item, label, false))
  }
  if (top) {
    setLabel(ds, T_PATIENT_NAME, 'PN', label)
    setLabel(ds, T_PATIENT_ID, 'LO', label)
    ds[T_IDENTITY_REMOVED] = { vr: 'CS', Value: ['YES'] }
    ds[T_DEID_METHOD] = { vr: 'LO', Value: [DICOM_DEIDENTIFICATION_METHOD] }
  }
}

/**
 * A cheap fingerprint of every pixel-data element (group 7FE0) of the
 * top-level dataset: two independent 32-bit mixes and the length. Not
 * cryptographic — it guards against the writer altering bytes by accident.
 */
export function pixelFingerprint(ds: DicomDataset): string {
  let h1 = 0x811c9dc5
  let h2 = 0x9e3779b9
  let len = 0
  for (const tag of Object.keys(ds).sort()) {
    if (!tag.startsWith('7FE0')) continue
    const el = ds[tag]!
    for (const v of el.Value ?? []) {
      if (!isArrayBufferLike(v)) continue
      const b = toU8(v)
      len += b.length
      for (let i = 0; i < b.length; i++) {
        const x = b[i]!
        h1 = Math.imul(h1 ^ x, 0x01000193)
        h2 = Math.imul(h2 + x, 0x85ebca6b) ^ (h2 >>> 13)
      }
    }
  }
  return `${len}:${(h1 >>> 0).toString(16)}:${(h2 >>> 0).toString(16)}`
}

function first(ds: DicomDataset, tag: Tag): string {
  const out: string[] = []
  stringsOf(ds[tag]?.Value, out)
  return (out[0] ?? '').trim()
}

/** Re-check the parsed OUTPUT: label in place, nothing identifying left, private tags gone. */
function verifyDataset(ds: DicomDataset, label: string, top: boolean): void {
  for (const tag of Object.keys(ds)) {
    const el = ds[tag]!
    if (isOddGroup(tag)) throw new DeidError('verify', 'private tag')
    if (REMOVED.has(tag)) throw new DeidError('verify', 'removed sequence')
    if (tag === T_PATIENT_NAME || tag === T_PATIENT_ID) {
      if (first(ds, tag) !== label) throw new DeidError('verify', 'patient tag')
    } else if (CLEARED.has(tag)) {
      const v: string[] = []
      stringsOf(el.Value, v)
      if (v.some((s) => s.trim() !== '')) throw new DeidError('verify', 'cleared tag')
    }
    forEachItem(el, (item) => verifyDataset(item, label, false))
  }
  if (top) {
    if (first(ds, T_IDENTITY_REMOVED) !== 'YES' || first(ds, T_DEID_METHOD) !== DICOM_DEIDENTIFICATION_METHOD) {
      throw new DeidError('verify', 'stamp')
    }
    if (first(ds, T_PATIENT_NAME) !== label || first(ds, T_PATIENT_ID) !== label) throw new DeidError('verify', 'patient tag')
  }
}

export interface DicomRead {
  /** PatientID of the file as sent by the camera, trimmed; null when empty/absent. Local use only. */
  patientId: string | null
  burnedIn: boolean
  /** Identifying strings, for the residual sweep. Local use only. */
  identifiers: string[]
  transferSyntax: string
  dict: DicomDictLike
}

/** Parse; the original bytes may be dropped by the caller afterwards. */
export async function readDicom(bytes: ArrayBuffer): Promise<DicomRead> {
  const dcmjs = await loadDcmjs()
  let dict: DicomDictLike
  try {
    dict = dcmjs.data.DicomMessage.readFile(bytes)
  } catch {
    throw new DeidError('parse', 'dicom')
  }
  const identifiers: string[] = []
  collectIdentifiers(dict.dict, identifiers)
  const pid = first(dict.dict, T_PATIENT_ID)
  const ts = first(dict.meta, T_TRANSFER_SYNTAX)
  return { patientId: pid || null, burnedIn: anyBurnedIn(dict.dict), identifiers, transferSyntax: ts, dict }
}

export interface DicomStripResult {
  output: ArrayBuffer
  /** Identifying strings that were in the file (local, for the sweep). */
  identifiers: string[]
}

/**
 * De-identify {@code bytes}. Throws a {@link DeidError} for a burned-in file,
 * an unwritable label, or an output that fails its own re-check (including a
 * change to the pixel data).
 */
export async function stripDicom(bytes: ArrayBuffer, label: string): Promise<DicomStripResult> {
  assertDicomLabel(label)
  const dcmjs = await loadDcmjs()
  const read = await readDicom(bytes)
  if (read.burnedIn) throw new DeidError('burnedIn')
  const identifiers = read.identifiers
  const before = pixelFingerprint(read.dict.dict)

  strip(read.dict.dict, label, true)
  let output: ArrayBuffer
  try {
    output = read.dict.write()
  } catch {
    throw new DeidError('parse', 'dicom write')
  }

  // Re-read what was written and prove the result, rather than trusting the writer.
  let again: DicomDictLike
  try {
    again = dcmjs.data.DicomMessage.readFile(output)
  } catch {
    throw new DeidError('verify', 'reread')
  }
  verifyDataset(again.dict, label, true)
  if (pixelFingerprint(again.dict) !== before) throw new DeidError('verify', 'pixel data')
  return { output, identifiers }
}

/* ------------------------------------------------------------------ */
/*  Preview                                                            */
/* ------------------------------------------------------------------ */

const NATIVE_SYNTAXES = new Set([
  '1.2.840.10008.1.2',
  '1.2.840.10008.1.2.1',
  '1.2.840.10008.1.2.1.99',
])
const JPEG_BASELINE = new Set(['1.2.840.10008.1.2.4.50', '1.2.840.10008.1.2.4.51'])

function num(ds: DicomDataset, tag: Tag, dflt: number): number {
  const v = ds[tag]?.Value?.[0]
  return typeof v === 'number' ? v : dflt
}

/** Decode image bytes with the browser (JPEG baseline frames). Null where the runtime cannot. */
async function decodeWithBrowser(bytes: Uint8Array, mime: string): Promise<RawPreview | null> {
  try {
    if (typeof createImageBitmap !== 'function') return null
    const bmp = await createImageBitmap(new Blob([bytes as BlobPart], { type: mime }))
    type Ctx2d = { drawImage: (img: ImageBitmap, x: number, y: number) => void; getImageData: (x: number, y: number, w: number, h: number) => ImageData }
    let ctx: Ctx2d | null = null
    if (typeof OffscreenCanvas !== 'undefined') {
      ctx = new OffscreenCanvas(bmp.width, bmp.height).getContext('2d') as unknown as Ctx2d | null
    } else if (typeof document !== 'undefined') {
      const c = document.createElement('canvas')
      c.width = bmp.width
      c.height = bmp.height
      ctx = c.getContext('2d') as unknown as Ctx2d | null
    }
    if (!ctx) return null
    ctx.drawImage(bmp, 0, 0)
    const img = ctx.getImageData(0, 0, bmp.width, bmp.height)
    return { width: bmp.width, height: bmp.height, rgba: img.data }
  } catch {
    return null
  }
}

/**
 * First frame as RGBA: native (uncompressed) mono/RGB frames are rendered
 * directly; JPEG-baseline frames go through the browser decoder. Anything else
 * (JPEG 2000, JPEG-LS, RLE, palette colour …) yields null and the UI shows a
 * notice instead of a picture.
 */
export async function extractDicomPreview(read: DicomRead): Promise<RawPreview | null> {
  const ds = read.dict.dict
  const px = ds['7FE00010']
  if (!px || !Array.isArray(px.Value) || px.Value.length === 0) return null
  const rows = num(ds, '00280010', 0)
  const cols = num(ds, '00280011', 0)
  if (rows <= 0 || cols <= 0) return null
  const ts = read.transferSyntax

  if (JPEG_BASELINE.has(ts)) {
    const frags = px.Value.filter(isArrayBufferLike).map(toU8)
    const frames = Math.max(1, Number(first(ds, '00280008')) || 1)
    const used = frames > 1 ? frags.slice(0, 1) : frags
    const total = used.reduce((n, f) => n + f.length, 0)
    const joined = new Uint8Array(total)
    let off = 0
    for (const f of used) {
      joined.set(f, off)
      off += f.length
    }
    return decodeWithBrowser(joined, 'image/jpeg')
  }
  if (!NATIVE_SYNTAXES.has(ts)) return null

  const spp = num(ds, '00280002', 1)
  const bits = num(ds, '00280100', 8)
  const stored = num(ds, '00280101', bits)
  const signed = num(ds, '00280103', 0) === 1
  const planar = num(ds, '00280006', 0)
  const pi = first(ds, '00280004').toUpperCase()
  if (!(bits === 8 || bits === 16) || !(spp === 1 || spp === 3) || pi.startsWith('PALETTE')) return null
  const bytesPer = bits / 8
  const frameBytes = rows * cols * spp * bytesPer
  const all = toU8(px.Value[0])
  if (all.length < frameBytes) return null
  const frame = all.subarray(0, frameBytes)
  const view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength)
  const n = rows * cols
  const rgba = new Uint8ClampedArray(n * 4)

  const sample = (idx: number): number =>
    bits === 8 ? frame[idx]! : signed ? view.getInt16(idx * 2, true) : view.getUint16(idx * 2, true)

  if (spp === 1) {
    let lo = Infinity
    let hi = -Infinity
    for (let i = 0; i < n; i++) {
      const v = sample(i)
      if (v < lo) lo = v
      if (v > hi) hi = v
    }
    const range = hi > lo ? hi - lo : 1
    const invert = pi === 'MONOCHROME1'
    void stored
    for (let i = 0; i < n; i++) {
      let g = Math.round((255 * (sample(i) - lo)) / range)
      if (invert) g = 255 - g
      rgba[i * 4] = g
      rgba[i * 4 + 1] = g
      rgba[i * 4 + 2] = g
      rgba[i * 4 + 3] = 255
    }
  } else {
    const ybr = pi.startsWith('YBR_FULL')
    for (let i = 0; i < n; i++) {
      const a = planar === 1 ? sample(i) : sample(i * 3)
      const b = planar === 1 ? sample(n + i) : sample(i * 3 + 1)
      const c = planar === 1 ? sample(2 * n + i) : sample(i * 3 + 2)
      const scale = bits === 16 ? 1 / 257 : 1
      let r = a * scale
      let g = b * scale
      let bl = c * scale
      if (ybr) {
        const y = r
        const cb = g - 128
        const cr = bl - 128
        r = y + 1.402 * cr
        g = y - 0.344136 * cb - 0.714136 * cr
        bl = y + 1.772 * cb
      }
      rgba[i * 4] = r
      rgba[i * 4 + 1] = g
      rgba[i * 4 + 2] = bl
      rgba[i * 4 + 3] = 255
    }
  }
  return { width: cols, height: rows, rgba }
}
