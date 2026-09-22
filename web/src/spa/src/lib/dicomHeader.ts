/**
 * DR-029 — what a DICOM export says about itself, read in the browser.
 *
 * A Clarus or PlexElite file names its own eye, date and device, so the
 * operator should not be asked for any of them. The header is read with
 * {@code dicom-parser} (already a dependency of the OCT viewer), stopping
 * before the pixel data; the server reads it again on commit and its
 * reading wins — this is for the row the operator reviews before uploading.
 *
 * Deliberately not read: the patient module. A clinic camera fills it from
 * the hospital system, and nothing of it belongs on the page or in the
 * request. The identity a file is filed under is the visit the operator
 * picks; the sidecar replaces what the header carried before the file is
 * kept.
 */

export interface DicomHints {
  laterality: 'OD' | 'OS' | 'OU' | null
  /** ISO yyyy-MM-dd — acquisition, else content, else study date. */
  acquisitionDate: string | null
  modality: string | null
  modelName: string | null
  /** The catalogue device key the model name folds onto, when recognisable. */
  deviceKey: string | null
  sopInstanceUid: string | null
}

const PIXEL_DATA = 'x7fe00010'

const LATERALITY: Record<string, 'OD' | 'OS' | 'OU'> = { R: 'OD', L: 'OS', B: 'OU' }

interface DataSetLike {
  string(tag: string): string | undefined
}

interface ParserLike {
  parseDicom(bytes: Uint8Array, options?: { untilTag?: string }): DataSetLike
}

function isoDate(da: string | undefined): string | null {
  const s = (da ?? '').trim()
  if (!/^\d{8}$/.test(s)) return null
  return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
}

/** Vendor spellings folded onto the imaging catalogue's device keys — mirrors the server. */
export function deviceKeyFromModel(model: string | null, manufacturer: string | null): string | null {
  const m = `${model ?? ''} ${manufacturer ?? ''}`.toLowerCase().replace(/[^a-z0-9]/g, '')
  if (!m) return null
  if (m.includes('clarus')) return 'clarus'
  if (m.includes('plexelite') || m.includes('plex')) return 'plexelite'
  if (m.includes('cirrus')) return 'cirrus'
  if (m.includes('lumo') || m.includes('optomed')) return 'optomed'
  if (m.includes('spectralis') || m.includes('heidelberg')) return 'spectralis'
  if (m.includes('remidio')) return 'remidio'
  const justModel = (model ?? '').toLowerCase().replace(/[^a-z0-9]/g, '')
  return justModel ? justModel.slice(0, 64) : null
}

/** Read the hints, or null when the bytes are not a DICOM object the parser can walk. */
export async function readDicomHints(file: File): Promise<DicomHints | null> {
  let bytes: Uint8Array
  try {
    bytes = new Uint8Array(await file.arrayBuffer())
  } catch {
    return null
  }
  return hintsFromBytes(bytes)
}

export async function hintsFromBytes(bytes: Uint8Array): Promise<DicomHints | null> {
  let parser: ParserLike
  try {
    const mod = (await import('dicom-parser')) as { default?: ParserLike } & Partial<ParserLike>
    parser = (mod.default ?? mod) as ParserLike
  } catch {
    return null
  }
  let ds: DataSetLike
  try {
    ds = parser.parseDicom(bytes, { untilTag: PIXEL_DATA })
  } catch {
    return null
  }
  const read = (tag: string): string | null => {
    try {
      const v = ds.string(tag)
      const s = (v ?? '').trim()
      return s.length > 0 ? s : null
    } catch {
      return null
    }
  }
  const lat = (read('x00200062') ?? read('x00200060') ?? '').toUpperCase()
  const model = read('x00081090')
  const manufacturer = read('x00080070')
  return {
    laterality: LATERALITY[lat] ?? null,
    acquisitionDate:
      isoDate(read('x00080022') ?? undefined)
      ?? isoDate(read('x00080023') ?? undefined)
      ?? isoDate(read('x00080020') ?? undefined),
    modality: read('x00080060'),
    modelName: model,
    deviceKey: deviceKeyFromModel(model, manufacturer),
    sopInstanceUid: read('x00080018'),
  }
}
