/**
 * Browser de-identification — the name a de-identified file is uploaded under.
 *
 * {@code <label>_<yyyyMMdd>_<OD|OS>[_<n>].<e2e|dcm>}. The original filename
 * (which routinely holds a patient's name) is never sent.
 */
import { DeidError } from './errors'

export type DeidLaterality = 'OD' | 'OS'

/** The label as a file-name part: letters, digits, dot, dash, underscore; anything else becomes a dash. */
export function labelForFilename(label: string): string {
  return label.replace(/[^A-Za-z0-9._-]/g, '-')
}

/** yyyyMMdd from an ISO date, else from {@code fallback} (the upload day, local time). */
export function compactDate(iso: string | null | undefined, fallback: Date = new Date()): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '')
  if (m) return `${m[1]}${m[2]}${m[3]}`
  const y = fallback.getFullYear()
  const mo = String(fallback.getMonth() + 1).padStart(2, '0')
  const d = String(fallback.getDate()).padStart(2, '0')
  return `${y}${mo}${d}`
}

export interface DeidFilenameParts {
  label: string
  /** Acquisition date (ISO) when known; else the upload date is used. */
  date: string | null
  laterality: string | null
  /** 1-based ordinal, appended only when given (a file with several volumes). */
  n?: number | null
  format: 'e2e' | 'dicom'
  now?: Date
}

export function deidFilename(p: DeidFilenameParts): string {
  if (p.laterality !== 'OD' && p.laterality !== 'OS') throw new DeidError('laterality')
  const suffix = p.n != null && p.n > 0 ? `_${p.n}` : ''
  const ext = p.format === 'e2e' ? 'e2e' : 'dcm'
  return `${labelForFilename(p.label)}_${compactDate(p.date, p.now)}_${p.laterality}${suffix}.${ext}`
}
