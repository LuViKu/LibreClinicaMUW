/**
 * DR-025 — public image-upload portal client (Remidio FOP).
 *
 * Unauthenticated: every call uses `credentials: 'omit'` (no JSESSIONID) and
 * hits the absolute Tomcat context path, mirroring `api/octPortal.ts`. The
 * institutional reverse proxy is the only access gate.
 */

export class ImagePortalError extends Error {
  status: number
  body: unknown
  constructor(status: number, message: string, body: unknown) {
    super(message)
    this.name = 'ImagePortalError'
    this.status = status
    this.body = body
  }
}

const BASE = '/LibreClinica/pages/api/v1/public/image-upload'

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

export type ResolveState = 'suggested' | 'novisit' | 'nopatient' | 'ambiguous'

/** The visit the backend found for that label on that date, when there is one. */
export interface EventCandidate {
  studyEventId: number
  eventCrfId: number | null
  definitionLabel: string
  dateStart: string
  matchPolicy: string
}

export interface ResolveCandidate {
  studyId: number
  studyName: string
  studyOid: string
  studySubjectId: number
  subjectLabel: string
  siteName: string | null
  matchingEvent: EventCandidate | null
}

export interface ResolveResponse {
  patientId: string
  candidates: ResolveCandidate[]
  state: ResolveState
}

/** Optional on-page patient confirmation — reuses the backend StudySubjectFinder. */
export async function resolvePatient(patientId: string, studyDate?: string): Promise<ResolveResponse> {
  const res = await fetch(`${BASE}/resolve`, {
    method: 'POST',
    credentials: 'omit',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ patientId, studyDate: studyDate ?? null }),
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new ImagePortalError(res.status, messageFrom(body, `resolve → ${res.status}`), body)
  }
  return body as ResolveResponse
}

export interface CommitResult {
  imageIngestId: number
  status: string
}

export interface CommitMeta {
  patientId?: string
  laterality?: string
  studyDate?: string
  /** When set, the image is filed straight against that visit instead of the inbox. */
  studyEventId?: number
}

/**
 * Upload one JPEG/PNG. Without a studyEventId the image lands UNBOUND for the
 * reconciliation inbox; with one it is filed against that visit directly.
 */
export async function commitImage(file: File, meta: CommitMeta): Promise<CommitResult> {
  const fd = new FormData()
  fd.append('file', file)
  if (meta.patientId) fd.append('patientId', meta.patientId)
  if (meta.laterality) fd.append('laterality', meta.laterality)
  if (meta.studyDate) fd.append('studyDate', meta.studyDate)
  if (meta.studyEventId != null) fd.append('studyEventId', String(meta.studyEventId))
  const res = await fetch(`${BASE}/commit`, {
    method: 'POST',
    credentials: 'omit',
    body: fd,
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new ImagePortalError(res.status, messageFrom(body, `commit → ${res.status}`), body)
  }
  return body as CommitResult
}

/**
 * One hit from the public label-prefix lookup — label plus enough study/site
 * context to disambiguate, and nothing else (the page is unauthenticated).
 */
export interface PublicSubjectHit {
  studySubjectId: number
  label: string
  studyName: string
  siteName: string | null
}

/**
 * 2026-09-18 — label-prefix subject lookup via the anonymous portal path, the
 * sibling of the OCT portal's. Minimum 3-character prefix, at most 10 rows.
 */
export async function searchPatientsPublic(q: string, limit = 10): Promise<PublicSubjectHit[]> {
  const params = new URLSearchParams()
  params.set('q', q)
  params.set('limit', String(limit))
  const res = await fetch(`${BASE}/patients/search?${params.toString()}`, {
    method: 'GET',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new ImagePortalError(res.status, messageFrom(body, `patients/search → ${res.status}`), body)
  }
  return ((body as { subjects?: PublicSubjectHit[] } | null)?.subjects ?? [])
}

/**
 * One visit on the picker. Label, visit and study only — the page is
 * unauthenticated, so nothing more identifying travels to it.
 */
export interface PortalVisit {
  studyEventId: number
  subjectLabel: string
  eventLabel: string
  studyName: string
  time: string | null
}

/**
 * The visits scheduled for one day, mirroring what the camera's own worklist
 * screen shows. The backend serves this only when
 * `core.ingest.portal.todaysVisits` is on and answers 404 otherwise, so an
 * empty list here means "not available", never "no patients today".
 */
export async function listTodaysVisits(date?: string): Promise<PortalVisit[] | null> {
  const qs = date ? `?date=${encodeURIComponent(date)}` : ''
  const res = await fetch(`${BASE}/visits${qs}`, {
    method: 'GET',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  })
  if (res.status === 404) return null
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new ImagePortalError(res.status, messageFrom(body, `visits → ${res.status}`), body)
  }
  return (body as { visits?: PortalVisit[] } | null)?.visits ?? []
}
