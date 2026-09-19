/**
 * DR-029 — client for the combined uploader, in either of its two modes.
 *
 * The same page runs with no login at {@code /upload} (the public route,
 * {@code credentials: 'omit'}, throttled) and behind one at
 * {@code /ingest-inbox/upload} (the staff route, session cookie, attributed).
 * The two backends answer in the same shapes, so the difference is one
 * parameter here and nowhere else.
 */
import type {
  EventCandidate,
  PublicStudyEvent,
  PublicSubjectHit,
  ResolveCandidate,
  ResolveScanResult,
} from '@/api/octPortal'
import { sha256OfFile } from '@/api/octPortal'
import { listDueVisits } from '@/api/events'

export type { EventCandidate, PublicStudyEvent, PublicSubjectHit, ResolveCandidate, ResolveScanResult }
export { sha256OfFile }

export type UploadMode = 'public' | 'staff'

const CONTEXT = '/LibreClinica'
const BASES: Record<UploadMode, string> = {
  public: `${CONTEXT}/pages/api/v1/public/upload`,
  staff: `${CONTEXT}/pages/api/v1/ingest/upload`,
}

function base(mode: UploadMode): string {
  return BASES[mode]
}

function credentials(mode: UploadMode): RequestCredentials {
  return mode === 'public' ? 'omit' : 'include'
}

export class UploadApiError extends Error {
  readonly status: number
  readonly body: unknown
  constructor(status: number, message: string, body: unknown = null) {
    super(message)
    this.name = 'UploadApiError'
    this.status = status
    this.body = body
  }
}

async function parseJsonOrNull(res: Response): Promise<unknown> {
  const ct = res.headers.get('content-type') ?? ''
  if (!ct.includes('application/json')) return null
  try {
    return await res.json()
  } catch {
    return null
  }
}

function messageFrom(body: unknown, fallback: string): string {
  if (body && typeof body === 'object' && 'message' in body) {
    const m = (body as { message: unknown }).message
    if (typeof m === 'string' && m.length > 0) return m
  }
  return fallback
}

async function request<T>(mode: UploadMode, path: string, init: RequestInit, fallback: string): Promise<T> {
  const res = await fetch(`${base(mode)}${path}`, { ...init, credentials: credentials(mode) })
  const body = await parseJsonOrNull(res)
  if (!res.ok) throw new UploadApiError(res.status, messageFrom(body, `${fallback} → ${res.status}`), body)
  return body as T
}

/* ---------------- identification ---------------- */

export interface ResolveRowRequest {
  patientId: string
  scanDate: string | null
  laterality: string | null
}

export interface ResolveResponse {
  scans: ResolveScanResult[]
}

/** Per-row resolution: the OCT page's request and answer shapes, on both routes. */
export function resolveRows(mode: UploadMode, scans: ResolveRowRequest[]): Promise<ResolveResponse> {
  return request<ResolveResponse>(mode, '/resolve', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ scans }),
  }, 'POST /resolve')
}

export interface PreflightResponse {
  exists: boolean
  ingestItemId: number | null
  jobId: number | null
}

/** Is this file already here? Asked before the bytes travel. */
export function preflight(mode: UploadMode, sha256: string, scanIndex?: number | null): Promise<PreflightResponse> {
  const qs = scanIndex == null ? '' : `&scanIndex=${scanIndex}`
  return request<PreflightResponse>(mode, `/preflight?sha256=${encodeURIComponent(sha256)}${qs}`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
  }, 'GET /preflight')
}

/** Label-prefix subject lookup on the public route (the staff route uses the session search). */
export async function searchPatientsPublic(q: string, limit = 10): Promise<PublicSubjectHit[]> {
  const params = new URLSearchParams({ q, limit: String(limit) })
  const body = await request<{ subjects?: PublicSubjectHit[] } | null>(
    'public', `/patients/search?${params.toString()}`,
    { method: 'GET', headers: { Accept: 'application/json' } }, 'GET /patients/search')
  return body?.subjects ?? []
}

/** A subject's visits on the public route, for the picker. */
export async function listPatientEventsPublic(studySubjectId: number): Promise<PublicStudyEvent[]> {
  const body = await request<PublicStudyEvent[] | null>(
    'public', `/patients/${studySubjectId}/events`,
    { method: 'GET', headers: { Accept: 'application/json' } }, `GET /patients/${studySubjectId}/events`)
  return body ?? []
}

/** One visit on the day list — label, visit, study, and nothing more identifying. */
export interface DayVisit {
  studyEventId: number
  studySubjectId: number | null
  subjectLabel: string
  eventLabel: string
  studyName: string
  time: string | null
}

/**
 * The visits scheduled for one day.
 *
 * Public: served only when the institution has enabled the list (the backend
 * answers 404 otherwise → null, "not available", never "nobody today").
 * Staff: the due-visits endpoint, scoped by the session's visibility.
 */
export async function listVisitsForDay(mode: UploadMode, date?: string): Promise<DayVisit[] | null> {
  if (mode === 'public') {
    const qs = date ? `?date=${encodeURIComponent(date)}` : ''
    const res = await fetch(`${base('public')}/visits${qs}`, {
      method: 'GET', credentials: 'omit', headers: { Accept: 'application/json' },
    })
    if (res.status === 404) return null
    const body = await parseJsonOrNull(res)
    if (!res.ok) throw new UploadApiError(res.status, messageFrom(body, `visits → ${res.status}`), body)
    const visits = (body as { visits?: Array<Omit<DayVisit, 'studySubjectId'>> } | null)?.visits ?? []
    return visits.map((v) => ({ ...v, studySubjectId: null }))
  }
  const day = date || localIsoToday()
  const r = await listDueVisits({ from: day, to: day })
  return r.visits.map((v) => ({
    studyEventId: v.studyEventId,
    studySubjectId: v.studySubjectId,
    subjectLabel: v.subjectLabel,
    eventLabel: v.eventLabel,
    studyName: v.studyName,
    time: v.time,
  }))
}

export function localIsoToday(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/* ---------------- the upload ---------------- */

export interface CommitRequest {
  file: File
  patientId?: string | null
  /** ISO yyyy-MM-dd. */
  scanDate?: string | null
  laterality?: string | null
  /** OCT only: which volume of a multi-acquisition file. */
  scanIndex?: number | null
  eventCrfId?: number | null
  studyEventId?: number | null
  /** OCT only: file without a visit, no inference job. */
  park?: boolean
  /** Image only: which camera, when the page knows. */
  device?: string | null
}

export interface CommitResponse {
  ingestItemId: number
  /** OCT only — the primary inference job; null for a parked scan and for every other kind. */
  jobId: number | null
  kind: string
  format: string
  status: string
  laterality?: string | null
  acquisitionDate?: string | null
  device?: string | null
  imagingModalityId?: number | null
  deidentified?: boolean
  jobs?: Array<{ jobId: number; task: string; status: string }>
}

/**
 * Upload one file. XHR rather than fetch, for the upload-progress events the
 * row's fill is driven by — a widefield export is tens of megabytes and the
 * operator deserves to see it move.
 */
export function commitFile(
  mode: UploadMode,
  req: CommitRequest,
  onProgress?: (pct: number) => void,
): Promise<CommitResponse> {
  const form = new FormData()
  form.append('file', req.file, req.file.name)
  if (req.patientId) form.append('patientId', req.patientId)
  if (req.scanDate) form.append('scanDate', req.scanDate)
  if (req.laterality) form.append('laterality', req.laterality)
  if (req.scanIndex != null) form.append('scanIndex', String(req.scanIndex))
  if (req.eventCrfId != null) form.append('eventCrfId', String(req.eventCrfId))
  if (req.studyEventId != null) form.append('studyEventId', String(req.studyEventId))
  if (req.park) form.append('park', 'true')
  if (req.device) form.append('device', req.device)

  return new Promise<CommitResponse>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${base(mode)}/commit`)
    xhr.withCredentials = mode === 'staff'
    xhr.setRequestHeader('Accept', 'application/json')
    if (onProgress) {
      xhr.upload.onprogress = (e: ProgressEvent) => {
        if (e.lengthComputable && e.total > 0) onProgress(Math.min(100, (e.loaded / e.total) * 100))
      }
      xhr.upload.onloadend = () => onProgress(100)
    }
    xhr.onerror = () => reject(new UploadApiError(0, 'Netzwerkfehler beim Upload'))
    xhr.onload = () => {
      let body: unknown = null
      try {
        body = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch {
        body = null
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body as CommitResponse)
      } else {
        reject(new UploadApiError(xhr.status, messageFrom(body, `POST /commit → ${xhr.status}`), body))
      }
    }
    xhr.send(form)
  })
}

/* ---------------- undo ---------------- */

/** Take back an image or DICOM upload within the window. */
export async function undoItem(mode: UploadMode, ingestItemId: number): Promise<void> {
  const res = await fetch(`${base(mode)}/items/${ingestItemId}`, {
    method: 'DELETE', credentials: credentials(mode), headers: { Accept: 'application/json' },
  })
  if (!res.ok) {
    const body = await parseJsonOrNull(res)
    throw new UploadApiError(res.status, messageFrom(body, `DELETE /items/${ingestItemId} → ${res.status}`), body)
  }
}

/** Take back an OCT scan's job within the window. */
export async function undoJob(mode: UploadMode, jobId: number): Promise<void> {
  const res = await fetch(`${base(mode)}/jobs/${jobId}`, {
    method: 'DELETE', credentials: credentials(mode), headers: { Accept: 'application/json' },
  })
  if (!res.ok) {
    const body = await parseJsonOrNull(res)
    throw new UploadApiError(res.status, messageFrom(body, `DELETE /jobs/${jobId} → ${res.status}`), body)
  }
}
