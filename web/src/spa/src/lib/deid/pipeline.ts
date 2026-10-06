/**
 * Browser de-identification — the pipeline, as plain async functions over a
 * {@link File}. They run inside a Web Worker in the browser
 * ({@code deidClient.ts}) and inline in tests; the code is the same.
 *
 * Memory: a file is read ONCE per call into one buffer; the E2E strip and the
 * sweep work on that buffer in place, the hash reads it, and the result leaves
 * as a {@link Blob} (the browser may keep that on disk). DICOM needs the
 * parsed dataset next to the output while it verifies — two copies at the
 * peak, never three.
 */
import { DeidError } from './errors'
import { parseE2eBytes, type E2eScan } from '../e2eParser'
import { hintsFromBytes, type DicomHints } from '../dicomHeader'
import { extractE2ePreview, readE2eIdentity, stripE2e, type RawPreview } from './e2eDeidentify'
import { extractDicomPreview, readDicom, stripDicom } from './dicomDeidentify'
import { downscale } from './preview'
import { sweepCandidates, sweepFindsResidual } from './sweep'

export type DeidKind = 'e2e' | 'dicom'

export interface AnalyzeResult {
  kind: DeidKind
  /**
   * What the file's own header says the patient is — E2E: the patient_id slot
   * ONLY; DICOM: PatientID. For LOCAL matching against the study's labels;
   * never sent anywhere. Null when absent, empty or ambiguous.
   */
  headerPatientId: string | null
  /** E2E: one entry per volume ({@code patientId} blanked). */
  scans: E2eScan[]
  /** DICOM: eye, date, device from the header. */
  hints: DicomHints | null
  /** Preview, null when none can be rendered. */
  preview: RawPreview | null
}

export interface DeidResult {
  blob: Blob
  /** Lower-case hex SHA-256 of the STRIPPED bytes — what the server will hash. */
  sha256: string
  size: number
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as BufferSource))
  let hex = ''
  for (let i = 0; i < digest.length; i++) hex += digest[i]!.toString(16).padStart(2, '0')
  return hex
}

/** Read the file's own header for local matching and review; changes nothing. */
export async function analyzeFile(file: File, kind: DeidKind): Promise<AnalyzeResult> {
  const buf = await file.arrayBuffer()
  if (kind === 'e2e') {
    const u8 = new Uint8Array(buf)
    const id = readE2eIdentity(u8)
    if (id.patientChunks === 0) throw new DeidError('e2eNoPatientChunk')
    let scans: E2eScan[]
    try {
      scans = parseE2eBytes(u8, { headerFallback: false }).map((s) => ({ ...s, patientId: '' }))
    } catch {
      throw new DeidError('parse', 'e2e')
    }
    const raw = extractE2ePreview(u8)
    return { kind, headerPatientId: id.patientId, scans, hints: null, preview: raw ? downscale(raw) : null }
  }
  const read = await readDicom(buf)
  if (read.burnedIn) throw new DeidError('burnedIn')
  const hints = await hintsFromBytes(new Uint8Array(buf))
  const raw = await extractDicomPreview(read)
  return { kind, headerPatientId: read.patientId, scans: [], hints, preview: raw ? downscale(raw) : null }
}

/**
 * Strip, sweep, verify and hash. Any problem throws a {@link DeidError} and
 * nothing is returned — the caller does not upload.
 */
export async function deidentifyFile(file: File, kind: DeidKind, label: string): Promise<DeidResult> {
  const buf = await file.arrayBuffer()
  if (kind === 'e2e') {
    const u8 = new Uint8Array(buf)
    const { identifiers } = stripE2e(u8, label)
    if (sweepFindsResidual(u8, sweepCandidates(identifiers, label))) throw new DeidError('residual')
    const sha256 = await sha256Hex(u8)
    return { blob: new Blob([u8 as BlobPart], { type: 'application/octet-stream' }), sha256, size: u8.length }
  }
  const { output, identifiers } = await stripDicom(buf, label)
  const u8 = new Uint8Array(output)
  if (sweepFindsResidual(u8, sweepCandidates(identifiers, label))) throw new DeidError('residual')
  const sha256 = await sha256Hex(u8)
  return { blob: new Blob([u8 as BlobPart], { type: 'application/dicom' }), sha256, size: u8.length }
}
