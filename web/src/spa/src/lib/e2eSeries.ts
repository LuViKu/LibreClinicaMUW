/**
 * Series-level view of a multimodal Spectralis `.E2E` export, read in the browser.
 *
 * A faithful port of `muw-e2e-converter/src/muw_e2e_converter/series.py` (`describe_e2e`): the same
 * `seriesKey` numbering, ordering, `seriesModality` mapping, laterality, `acquiredAt`, frames,
 * width/height, `octVolumeIndex` and `headerLabel`. Both parsers are asserted against the shared
 * synthetic fixtures in `testdata/e2e-synthetic/`, so they cannot drift apart.
 *
 * Reads are `Blob.slice` ranges only: the chunk directory, one small header per chunk, and the
 * few metadata bodies. Pixel payloads are skipped (only their offsets are kept); a thumbnail
 * reads one image. The whole file is never read into memory.
 *
 * Privacy: of the patient chunk only the surname slot (offset 31, 51 bytes) is looked at, and it
 * is returned only when it is a plain label token. The first-name and patient-ID slots, and the
 * birth date, are never decoded; the bytes are fetched together with the chunk header (one slice)
 * but dropped before anything is parsed.
 */

export const SERIES_MODALITY_ORDER = [
  'OCT',
  'OCT_LINE',
  'IR',
  'BAF',
  'BR',
  'IRAF',
  'QAF',
  'ICGA',
  'FA',
  'OTHER',
] as const

export type SeriesModality = (typeof SERIES_MODALITY_ORDER)[number]

export interface E2eSeriesInfo {
  seriesKey: string
  seriesModality: SeriesModality
  deviceModality: string | null
  scanPattern: string | null
  laterality: 'OD' | 'OS' | null
  acquiredAt: string | null
  frames: number
  width: number
  height: number
  octVolumeIndex: number | null
}

export interface E2eDescription {
  headerPatient: 'none' | 'empty' | 'matches' | 'differs'
  headerLabel: string | null
  series: E2eSeriesInfo[]
}

const ENFACE_TOKENS = new Set(['IR', 'BAF', 'BR', 'IRAF', 'QAF', 'ICGA', 'FA'])
const LATERALITY_ORDER: Record<string, number> = { OD: 0, OS: 1, none: 2 }

const CHUNK_PATIENT = 9
const CHUNK_PRE = 3
const CHUNK_FRAME_TIME = 5
const CHUNK_LATERALITY = 11
const CHUNK_BSCAN_META = 10004
const CHUNK_SCAN_PATTERN = 9006
const CHUNK_ENFACE_MODALITY = 9007
const CHUNK_IMAGE = 1073741824
const CHUNK_HEADER_BYTES = 60
const IMAGE_HEADER_BYTES = 60 + 20 // chunk header + image_structure
const TEXT_CHUNK_CAP = 8192
const PATIENT_CHUNK_BYTES = 127
const SURNAME_OFFSET = 31
const SURNAME_BYTES = 51
/** One slice per chunk: the 60-byte header plus the 127-byte patient body (the longest fixed one). */
const CHUNK_PROBE_BYTES = CHUNK_HEADER_BYTES + PATIENT_CHUNK_BYTES
const PREFETCH_BATCH = 64
const LABEL_TOKEN = /^[!-~]{1,24}$/

/** The only way this module touches a file: ranges. `read` clamps at the end, like `Blob.slice`. */
export interface RangeReader {
  readonly size: number
  read(start: number, end: number): Promise<Uint8Array>
}

export function blobReader(blob: Blob): RangeReader {
  return {
    size: blob.size,
    async read(start, end) {
      const part = blob.slice(Math.max(0, start), Math.max(0, end))
      return new Uint8Array(await readBlob(part))
    },
  }
}

async function readBlob(part: Blob): Promise<ArrayBuffer> {
  if (typeof part.arrayBuffer === 'function') return part.arrayBuffer()
  return new Promise((resolve, reject) => {
    const fr = new FileReader()
    fr.onload = () => resolve(fr.result as ArrayBuffer)
    fr.onerror = () => reject(fr.error)
    fr.readAsArrayBuffer(part)
  })
}

interface ImageRef {
  offset: number // absolute file offset of the first pixel byte
  height: number
  width: number
}

type Ids = readonly [number, number, number]

interface ScanSeries {
  ids: Ids
  scanPattern: string | null
  deviceModality: string | null
  laterality: 'OD' | 'OS' | null
  times: bigint[] // microseconds since 1601-01-01
  bscanTime: bigint | null
  octSlots: Map<number, ImageRef>
  octExtra: ImageRef[]
  enImages: ImageRef[]
}

interface Described {
  key: string
  series: ScanSeries
  modality: SeriesModality
  octIndex: number | null
}

const idsKey = (ids: Ids) => `${ids[0]}/${ids[1]}/${ids[2]}`
const view = (b: Uint8Array) => new DataView(b.buffer, b.byteOffset, b.byteLength)

/** Python's `str.strip()` whitespace (the part that can occur in a decoded Latin-1 / UTF-16 text). */
const PY_WS =
  '\\t\\n\\u000b\\f\\r\\u001c-\\u001f \\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000'
const PY_STRIP = new RegExp(`^[${PY_WS}]+|[${PY_WS}]+$`, 'g')
const pyStrip = (s: string) => s.replace(PY_STRIP, '')

/** Exact Latin-1 (TextDecoder('latin1') is windows-1252 in browsers). */
const latin1 = (b: Uint8Array) => String.fromCharCode(...b)

/** construct `PaddedString(n, "ascii")` fails on a non-ASCII byte. */
function asciiOk(b: Uint8Array, start: number, len: number): boolean {
  for (let i = start; i < start + len; i++) if (b[i]! >= 0x80) return false
  return true
}

/** construct text struct: n_strings u32, string_size u32, then n UTF-16 strings. Null on error. */
function parseTextStruct(b: Uint8Array): string[] | null {
  if (b.length < 8) return null
  const dv = view(b)
  const n = dv.getUint32(0, true)
  const size = dv.getUint32(4, true)
  if (n * size + 8 > b.length) return null
  const out: string[] = []
  for (let i = 0; i < n; i++) {
    let raw = b.subarray(8 + i * size, 8 + (i + 1) * size)
    let end = raw.length - (raw.length % 2)
    while (end >= 2 && raw[end - 1] === 0 && raw[end - 2] === 0) end -= 2
    raw = raw.subarray(0, end)
    if (raw.length >= 2 && raw[0] === 0xff && raw[1] === 0xfe) raw = raw.subarray(2) // BOM
    try {
      out.push(new TextDecoder('utf-16le', { fatal: true }).decode(raw))
    } catch {
      return null
    }
  }
  return out
}

function textAt(parsed: string[], idx: number): string | null {
  const v = parsed[idx]
  if (v === undefined) return null
  const t = pyStrip(v).replace(/^\u0000+|\u0000+$/g, '')
  return t || null
}

const FILETIME_MIN = 126000000000000000n // 1.26e17
const FILETIME_MAX = 140000000000000000n // 1.40e17

/** FILETIME (100 ns ticks since 1601) -> microseconds since 1601, null outside 2000..2044. */
function filetimeMicros(value: bigint): bigint | null {
  if (!(FILETIME_MIN < value && value < FILETIME_MAX)) return null
  return value / 10n
}

const MICROS_1601_TO_1970 = 11644473600000000n

/** `datetime.isoformat()` of a naive datetime. */
function isoOf(micros: bigint): string {
  const unixMicros = micros - MICROS_1601_TO_1970
  const ms = Number(unixMicros / 1000n)
  const rest = Number(unixMicros % 1000n)
  const d = new Date(ms)
  const p = (n: number, w = 2) => String(n).padStart(w, '0')
  let s =
    `${p(d.getUTCFullYear(), 4)}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}` +
    `T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`
  const us = d.getUTCMilliseconds() * 1000 + rest
  if (us) s += `.${p(us, 6)}`
  return s
}

interface Scanned {
  reader: RangeReader
  skip: number
  surnames: string[]
  described: Described[]
  byKey: Map<string, Described>
}

async function scan(reader: RangeReader): Promise<Scanned> {
  const fileSize = reader.size
  const head = await reader.read(0, 21)
  const skip = latin1(head) === 'E2EMultipleVolumeFile' ? 64 : 0

  // --- directory chain (oct-converter's E2E.__init__) ---
  const mainBytes = await reader.read(skip + 36, skip + 36 + 52)
  if (mainBytes.length < 52) throw new Error('E2E: truncated directory')
  const directoryStack: number[] = []
  let current = view(mainBytes).getUint32(12 + 4 + 20 + 4, true) // 'current'
  const seen = new Set<number>()
  while (current !== 0) {
    if (seen.has(current)) throw new Error('E2E: directory loop')
    seen.add(current)
    directoryStack.push(current)
    const d = await reader.read(current + skip, current + skip + 52)
    if (d.length < 52) throw new Error('E2E: truncated directory')
    current = view(d).getUint32(12 + 4 + 20 + 4 + 4, true) // 'prev'
  }

  const chunkStack: number[] = []
  const volumeMaxSlice = new Map<string, number>()
  const volumeOrder: Ids[] = []
  for (const position of directoryStack) {
    const d = await reader.read(position + skip, position + skip + 52)
    const numEntries = view(d).getUint32(12 + 4 + 20, true)
    const bytes = await reader.read(position + skip + 52, position + skip + 52 + numEntries * 44)
    if (bytes.length < numEntries * 44) throw new Error('E2E: truncated directory')
    const dv = view(bytes)
    for (let i = 0; i < numEntries; i++) {
      const o = i * 44
      const pos = dv.getUint32(o, true)
      const start = dv.getUint32(o + 4, true)
      const ids: Ids = [dv.getUint32(o + 16, true), dv.getUint32(o + 20, true), dv.getUint32(o + 24, true)]
      const half = dv.getInt32(o + 28, true) / 2
      const k = idsKey(ids)
      const prev = volumeMaxSlice.get(k)
      if (prev === undefined) volumeOrder.push(ids)
      if (prev === undefined || half > prev) volumeMaxSlice.set(k, half)
      if (start > pos) chunkStack.push(start)
    }
  }
  const arrayVolumes = new Set<string>()
  for (const [k, n] of volumeMaxSlice) if (n > 0) arrayVolumes.add(k)

  // --- chunks, in directory order (probes are prefetched in parallel batches) ---
  const seriesMap = new Map<string, ScanSeries>()
  const surnames: string[] = []
  let octStop = false

  const seriesOf = (ids: Ids): ScanSeries => {
    const k = idsKey(ids)
    let s = seriesMap.get(k)
    if (!s) {
      s = {
        ids,
        scanPattern: null,
        deviceModality: null,
        laterality: null,
        times: [],
        bscanTime: null,
        octSlots: new Map(),
        octExtra: [],
        enImages: [],
      }
      seriesMap.set(k, s)
    }
    return s
  }
  const setLaterality = (s: ScanSeries, lat: string | null) => {
    if (s.laterality === null && (lat === 'R' || lat === 'L')) s.laterality = lat === 'R' ? 'OD' : 'OS'
  }

  for (let b0 = 0; b0 < chunkStack.length; b0 += PREFETCH_BATCH) {
    const batch = chunkStack.slice(b0, b0 + PREFETCH_BATCH)
    const probes = await Promise.all(
      batch.map((start) => reader.read(start + skip, start + skip + CHUNK_PROBE_BYTES)),
    )
    for (let i = 0; i < batch.length; i++) {
      const start = batch[i]!
      const probe = probes[i]!
      if (probe.length < CHUNK_HEADER_BYTES || !asciiOk(probe, 0, 12)) continue
      const dv = view(probe)
      const ids: Ids = [dv.getUint32(32, true), dv.getUint32(36, true), dv.getUint32(40, true)]
      const sliceId = dv.getInt32(44, true)
      const ind = dv.getUint16(48, true)
      const ctype = dv.getUint32(52, true)
      const size = dv.getUint32(24, true)
      const body = probe.subarray(CHUNK_HEADER_BYTES)

      if (ctype === CHUNK_PATIENT) {
        // Only the surname slot is looked at; the other slots are never decoded.
        if (body.length >= SURNAME_OFFSET + SURNAME_BYTES) {
          let raw = body.slice(SURNAME_OFFSET, SURNAME_OFFSET + SURNAME_BYTES)
          const nul = raw.indexOf(0)
          if (nul >= 0) raw = raw.subarray(0, nul)
          surnames.push(pyStrip(latin1(raw)))
        } else {
          surnames.push('')
        }
        continue
      }

      const series = seriesOf(ids)

      if (ctype === CHUNK_IMAGE) {
        if (body.length < 20) continue
        const bdv = view(body)
        const height = bdv.getUint32(12, true)
        const width = bdv.getUint32(16, true)
        const count = height * width
        const ref: ImageRef = { offset: start + skip + IMAGE_HEADER_BYTES, height, width }
        if (ind === 1) {
          // OCT B-scan; mirrors read_oct_volume incl. its early stop
          if (octStop) continue
          if (count === 0) {
            octStop = true
            continue
          }
          if (ref.offset + 2 * count > fileSize) continue // truncated payload
          const slot = Math.trunc(sliceId / 2)
          if (arrayVolumes.has(idsKey(ids))) series.octSlots.set(slot, ref)
          else series.octExtra.push(ref)
        } else if (ind === 0 && count > 0) {
          if (ref.offset + count <= fileSize) series.enImages.push(ref)
        }
      } else if (ctype === CHUNK_PRE) {
        // pre_data: u32 + 1 ascii char
        if (body.length >= 5 && body[4]! < 0x80) setLaterality(series, String.fromCharCode(body[4]!))
      } else if (ctype === CHUNK_LATERALITY) {
        // lat_structure: 14 bytes + 1 ascii char + 1 byte
        if (body.length >= 16 && body[14]! < 0x80) {
          setLaterality(series, String.fromCharCode(body[14]!))
        }
      } else if (ctype === CHUNK_FRAME_TIME) {
        const bytes = body.subarray(0, 12)
        if (bytes.length >= 12) {
          const when = filetimeMicros(view(bytes).getBigUint64(4, true))
          if (when !== null) series.times.push(when)
        }
      } else if (ctype === CHUNK_BSCAN_META) {
        if (body.length >= 104 && series.bscanTime === null) {
          const when = filetimeMicros(view(body).getBigUint64(88, true))
          if (when !== null) series.bscanTime = when
        }
      } else if (ctype === CHUNK_SCAN_PATTERN || ctype === CHUNK_ENFACE_MODALITY) {
        const want = Math.min(size, TEXT_CHUNK_CAP)
        const text =
          want <= body.length
            ? body.subarray(0, want)
            : await reader.read(start + skip + CHUNK_HEADER_BYTES, start + skip + CHUNK_HEADER_BYTES + want)
        const parsed = parseTextStruct(text)
        if (parsed === null) continue
        if (ctype === CHUNK_SCAN_PATTERN) {
          if (series.scanPattern === null) series.scanPattern = textAt(parsed, 0)
        } else if (series.deviceModality === null) {
          series.deviceModality = textAt(parsed, 1)
        }
      }
    }
  }

  // --- read_oct_volume() ordering -> octVolumeIndex ---
  const octOrder: Ids[] = []
  for (const ids of volumeOrder) {
    const k = idsKey(ids)
    const s = seriesMap.get(k)
    if (arrayVolumes.has(k) && s && s.octSlots.size > 0) octOrder.push(ids)
  }
  const inOrder = new Set(octOrder.map(idsKey))
  for (const [k, s] of seriesMap) if (!inOrder.has(k) && s.octExtra.length > 0) octOrder.push(s.ids)
  const octIndex = new Map(octOrder.map((ids, i) => [idsKey(ids), i]))

  // --- classify, key, sort ---
  const idsCompare = (a: Ids, b: Ids) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2]
  const present = [...seriesMap.values()]
    .filter((s) => octIndex.has(idsKey(s.ids)) || s.enImages.length > 0)
    .map((s) => s.ids)
    .sort(idsCompare)
  const described: Described[] = present.map((ids, ordinal) => {
    const s = seriesMap.get(idsKey(ids))!
    const oi = octIndex.get(idsKey(ids)) ?? null
    let modality: SeriesModality
    if (oi !== null) {
      modality = (s.scanPattern ?? '').toLowerCase().includes('volume') ? 'OCT' : 'OCT_LINE'
    } else {
      const token = pyStrip(s.deviceModality ?? '').toUpperCase()
      modality = ENFACE_TOKENS.has(token) ? (token as SeriesModality) : 'OTHER'
    }
    return { key: `s${ordinal}`, series: s, modality, octIndex: oi }
  })
  described.sort((a, b) => {
    const la = LATERALITY_ORDER[a.series.laterality ?? 'none']!
    const lb = LATERALITY_ORDER[b.series.laterality ?? 'none']!
    if (la !== lb) return la - lb
    const ma = SERIES_MODALITY_ORDER.indexOf(a.modality)
    const mb = SERIES_MODALITY_ORDER.indexOf(b.modality)
    if (ma !== mb) return ma - mb
    const ta = seriesTime(a)
    const tb = seriesTime(b)
    if ((ta === null) !== (tb === null)) return ta === null ? 1 : -1
    if (ta !== null && tb !== null && ta !== tb) return ta < tb ? -1 : 1
    return idsCompare(a.series.ids, b.series.ids)
  })
  return { reader, skip, surnames, described, byKey: new Map(described.map((d) => [d.key, d])) }
}

function seriesTime(d: Described): bigint | null {
  const s = d.series
  if (s.times.length > 0) return s.times.reduce((m, t) => (t < m ? t : m))
  return s.bscanTime
}

function octImages(s: ScanSeries): ImageRef[] {
  if (s.octSlots.size > 0) return [...s.octSlots.keys()].sort((a, b) => a - b).map((k) => s.octSlots.get(k)!)
  return s.octExtra
}

function headerLabelOf(surnames: string[]): string | null {
  if (surnames.length === 0 || new Set(surnames).size !== 1) return null
  const v = surnames[0]!
  return LABEL_TOKEN.test(v) ? v : null
}

function headerPatientOf(surnames: string[], expectedLabel: string | null | undefined) {
  const expected = pyStrip(expectedLabel ?? '')
  if (!expected) return 'none' as const
  const present = surnames.filter((v) => v)
  if (present.length === 0) return 'empty' as const
  return present.every((v) => v.toLowerCase() === expected.toLowerCase())
    ? ('matches' as const)
    : ('differs' as const)
}

/** A scanned file: the series list and, on demand, one image of a series. */
export interface E2eIndex {
  describe(expectedLabel?: string | null): E2eDescription
  /** The image a thumbnail shows (see `thumbnailPixels`), or null for an unknown key. */
  thumbnailPixels(seriesKey: string, maxSide?: number): Promise<GrayImage | null>
}

export interface GrayImage {
  width: number
  height: number
  /** One byte per pixel. */
  data: Uint8Array
}

export async function scanE2e(source: Blob | RangeReader): Promise<E2eIndex> {
  const reader = 'read' in source ? source : blobReader(source)
  const s = await scan(reader)
  return {
    describe(expectedLabel) {
      const series: E2eSeriesInfo[] = s.described.map((d) => {
        const when = seriesTime(d)
        const first = d.octIndex !== null ? octImages(d.series) : d.series.enImages
        return {
          seriesKey: d.key,
          seriesModality: d.modality,
          deviceModality: d.series.deviceModality,
          scanPattern: d.series.scanPattern,
          laterality: d.series.laterality,
          acquiredAt: when === null ? null : isoOf(when),
          frames: first.length,
          width: first[0]!.width,
          height: first[0]!.height,
          octVolumeIndex: d.octIndex,
        }
      })
      return {
        headerPatient: headerPatientOf(s.surnames, expectedLabel),
        headerLabel: headerLabelOf(s.surnames),
        series,
      }
    },
    async thumbnailPixels(seriesKey, maxSide = THUMB_SIDE) {
      const d = s.byKey.get(seriesKey)
      if (!d) return null
      const ser = d.series
      if (d.modality === 'OCT_LINE' || (d.modality === 'OCT' && ser.enImages.length === 0)) {
        const image = octImages(ser)[0]
        return image ? downscale(await readBscan(reader, image), image.width, image.height, maxSide) : null
      }
      const image = ser.enImages[0]
      if (!image) return null
      const raw = await reader.read(image.offset, image.offset + image.width * image.height)
      return downscale(raw, image.width, image.height, maxSide)
    },
  }
}

/** `describe_e2e` for a file, from range reads only. */
export async function describeE2eFile(
  source: Blob | RangeReader,
  expectedLabel?: string | null,
): Promise<E2eDescription> {
  return (await scanE2e(source)).describe(expectedLabel)
}

// ---------------------------------------------------------------- thumbnails

export const THUMB_SIDE = 256

/** oct-converter `E2E.uint16_to_ufloat16`: 6-bit exponent, 10-bit mantissa (bit-reversed). */
export function uFloat16(v: number): number {
  let mantissa = 0
  for (let i = 0; i < 10; i++) mantissa = (mantissa << 1) | ((v >> i) & 1) // bits[:10] read MSB-first
  const exponent = v >> 10
  return (1 + mantissa / 1024) * Math.pow(2, exponent - 63)
}

const FLOAT32_MAX = 3.4028234663852886e38

/** oct-converter `vol_intensity_transform` on one value, then clip and scale to 0..255 (round half even). */
export function octIntensityToByte(x: number): number {
  let y = x
  if (x === FLOAT32_MAX) y = 0
  else if (x <= 1) y = (Math.log(x + 2.44e-4) + 8.3) / 8.285
  y = Math.min(1, Math.max(0, y))
  return roundHalfEven(y * 255)
}

function roundHalfEven(x: number): number {
  const f = Math.floor(x)
  const d = x - f
  if (d < 0.5) return f
  if (d > 0.5) return f + 1
  return f % 2 === 0 ? f : f + 1
}

let octLut: Uint8Array | null = null
function lut(): Uint8Array {
  if (!octLut) {
    octLut = new Uint8Array(65536)
    for (let v = 0; v < 65536; v++) octLut[v] = octIntensityToByte(uFloat16(v))
  }
  return octLut
}

/** One B-scan as uint8 (row-major), decoded like `render_series_png` on the server. */
async function readBscan(reader: RangeReader, image: ImageRef): Promise<Uint8Array> {
  const n = image.width * image.height
  const raw = await reader.read(image.offset, image.offset + 2 * n)
  const dv = view(raw)
  const table = lut()
  const out = new Uint8Array(n)
  for (let i = 0; i < n; i++) out[i] = table[dv.getUint16(2 * i, true)]!
  return out
}

/** Box-average downscale so the longer side is at most `maxSide` (never upscales). */
export function downscale(src: Uint8Array, width: number, height: number, maxSide: number): GrayImage {
  const f = Math.max(width, height) / maxSide
  if (f <= 1) return { width, height, data: src.slice(0, width * height) }
  const w = Math.max(1, Math.round(width / f))
  const h = Math.max(1, Math.round(height / f))
  const data = new Uint8Array(w * h)
  for (let y = 0; y < h; y++) {
    const y0 = Math.floor((y * height) / h)
    const y1 = Math.max(y0 + 1, Math.floor(((y + 1) * height) / h))
    for (let x = 0; x < w; x++) {
      const x0 = Math.floor((x * width) / w)
      const x1 = Math.max(x0 + 1, Math.floor(((x + 1) * width) / w))
      let sum = 0
      for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) sum += src[yy * width + xx]!
      data[y * w + x] = Math.round(sum / ((y1 - y0) * (x1 - x0)))
    }
  }
  return { width: w, height: h, data }
}

// One scan per File, so a later thumbnail does not walk the directory again.
const cache = new WeakMap<Blob, Promise<E2eIndex>>()

export function scanE2eCached(file: Blob): Promise<E2eIndex> {
  let p = cache.get(file)
  if (!p) {
    p = scanE2e(file)
    cache.set(file, p)
    p.catch(() => cache.delete(file))
  }
  return p
}

/** Grey pixels of a series' thumbnail (about 256 px), or null when the series has no image. */
export async function decodeSeriesThumbnail(
  file: Blob,
  series: Pick<E2eSeriesInfo, 'seriesKey'>,
): Promise<GrayImage | null> {
  return (await scanE2eCached(file)).thumbnailPixels(series.seriesKey)
}

let thumbQueue: Promise<unknown> = Promise.resolve()

/**
 * A PNG data URL of the series' thumbnail (browser only; needs a canvas). Calls are served one at
 * a time, across all files, so a big export does not read a hundred images at once.
 */
export function renderSeriesThumbnail(
  file: Blob,
  series: Pick<E2eSeriesInfo, 'seriesKey'>,
): Promise<string | null> {
  const run = thumbQueue.then(() => renderNow(file, series))
  thumbQueue = run.catch(() => undefined)
  return run
}

async function renderNow(file: Blob, series: Pick<E2eSeriesInfo, 'seriesKey'>): Promise<string | null> {
  const img = await decodeSeriesThumbnail(file, series)
  if (!img) return null
  const canvas = document.createElement('canvas')
  canvas.width = img.width
  canvas.height = img.height
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  const rgba = new Uint8ClampedArray(img.width * img.height * 4)
  for (let i = 0; i < img.data.length; i++) {
    const g = img.data[i]!
    rgba[4 * i] = rgba[4 * i + 1] = rgba[4 * i + 2] = g
    rgba[4 * i + 3] = 255
  }
  ctx.putImageData(new ImageData(rgba, img.width, img.height), 0, 0)
  return canvas.toDataURL('image/png')
}

/** Drop the cached scan of a file (call when the row is removed). */
export function forgetE2e(file: Blob): void {
  cache.delete(file)
}
