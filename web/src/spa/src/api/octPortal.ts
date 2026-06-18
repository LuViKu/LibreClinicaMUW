/**
 * Phase E retinal-inference (Wave C) — OCT-Upload-Portal API client.
 *
 * Thin wrappers around the three {@code /pages/api/v1/public/oct-upload}
 * endpoints exposed by
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.controller.api.PublicOctUploadController}.
 * Unlike the rest of the SPA's HTTP surface (which talks to the
 * authenticated B-category adapters via {@code @/api/client}), the
 * portal is unauthenticated and runs from a route the global router
 * guard allows public. Cookies are deliberately omitted — the public
 * endpoints don't read JSESSIONID, and not sending it means the
 * existing CSRF / role guards stay clean.
 *
 * Three endpoints:
 *  - POST {@code /resolve}  — JSON → JSON; per-scan cross-study match
 *  - POST {@code /commit}   — multipart; persists the .e2e + enqueues
 *                             a retinal_inference_job row
 *  - DELETE {@code /{jobId}} — undo within 60 s
 *
 * Error model: a non-2xx response throws an {@link OctPortalError}
 * carrying the parsed `body.message` so the store can surface a
 * meaningful row-level inline error. Network failures throw a
 * standard {@link Error} with the original cause as the message.
 */

/** Backend resolve-request scan triple. Matches the controller's
 *  {@code ResolveRequestScan} record. */
export interface ResolveScanRequest {
  patientId: string
  /** ISO yyyy-MM-dd — what the controller's {@code LocalDate} parser expects. */
  scanDate: string
  laterality: 'OD' | 'OS'
}

/** One scheduled event_crf that matches the scan date. Mirrors
 *  {@code at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.EventCandidate}. */
export interface EventCandidate {
  eventCrfId: number
  definitionLabel: string
  /** ISO yyyy-MM-dd. */
  dateStart: string
  matchPolicy: string
}

/** One resolved study_subject candidate for a scan. Mirrors the
 *  controller's {@code ResolveCandidate} record. */
export interface ResolveCandidate {
  studyId: number
  studyName: string
  studyOid: string
  studySubjectId: number
  subjectLabel: string
  /** Parent-study name when the row belongs to a site, else null. */
  siteName: string | null
  matchingEvent: EventCandidate | null
}

/** Per-scan resolution outcome. The {@code state} maps 1:1 onto the
 *  ReviewRow state machine — `ambiguous` is currently UI-rendered as
 *  the same path as `suggested` but kept distinct so the SPA can
 *  light up a disambiguation picker later. */
export interface ResolveScanResult {
  /** Echoed patientId (possibly empty when the request omitted it). */
  patientId: string
  candidates: ResolveCandidate[]
  state: 'suggested' | 'novisit' | 'nopatient' | 'ambiguous'
}

export interface ResolveResponse {
  scans: ResolveScanResult[]
}

/** Per-commit response. Maps to the controller's `Map<String,Object>` body. */
export interface CommitResponse {
  jobId: number
  status: string
}

/** Multipart commit input. `eventCrfId` and `park` are mutually
 *  exclusive — the controller rejects sets of both with HTTP 400. */
export interface CommitScanRequest {
  file: File
  patientId: string
  /** ISO yyyy-MM-dd. */
  scanDate: string
  laterality: 'OD' | 'OS'
  scanIndex: number
  /** Bound when the operator picked a visit; null when park=true. */
  eventCrfId: number | null
  park: boolean
}

/** Thrown when the backend returns a non-2xx response. Carries the
 *  controller's `body.message` when present for inline display. */
export class OctPortalError extends Error {
  readonly status: number
  readonly body: unknown
  constructor(status: number, message: string, body: unknown = null) {
    super(message)
    this.name = 'OctPortalError'
    this.status = status
    this.body = body
  }
}

/** Tomcat context path for the public OCT-upload portal — kept inline
 *  rather than reusing the {@code @/api/client} constant so the
 *  unauthenticated mode is obvious at every call site. */
const BASE = '/LibreClinica/pages/api/v1/public/oct-upload'

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

/**
 * POST {@code /resolve}. Takes the operator's per-scan (PatientId,
 * scanDate, laterality) triples parsed client-side from .e2e headers
 * and returns per-scan match candidates.
 */
export async function resolveScans(
  scans: ResolveScanRequest[],
): Promise<ResolveResponse> {
  const res = await fetch(`${BASE}/resolve`, {
    method: 'POST',
    credentials: 'omit',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ scans }),
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new OctPortalError(
      res.status,
      messageFrom(body, `POST /resolve → ${res.status}`),
      body,
    )
  }
  return (body ?? { scans: [] }) as ResolveResponse
}

/**
 * POST {@code /commit}. Persists the .e2e to disk + enqueues a
 * retinal_inference_job + writes the OCT_UPLOAD_PUBLIC audit row.
 *
 * Set {@code park: true} when the operator chose "Später zuordnen" /
 * "Parken" — the controller will accept a null eventCrfId in that
 * case and stamp the job with status='PARKED'.
 */
export async function commitScan(req: CommitScanRequest): Promise<CommitResponse> {
  if (req.park === (req.eventCrfId !== null)) {
    // Mirror the controller's mutual-exclusion guard at the SPA edge
    // so the store / UI never has to deal with a 400 round-trip.
    throw new OctPortalError(
      400,
      'park=true and eventCrfId are mutually exclusive — exactly one must be supplied',
    )
  }
  const form = new FormData()
  form.append('file', req.file, req.file.name)
  form.append('patientId', req.patientId)
  form.append('scanDate', req.scanDate)
  form.append('laterality', req.laterality)
  form.append('scanIndex', String(req.scanIndex))
  if (req.eventCrfId !== null) {
    form.append('eventCrfId', String(req.eventCrfId))
  }
  form.append('park', String(req.park))

  const res = await fetch(`${BASE}/commit`, {
    method: 'POST',
    credentials: 'omit',
    headers: { Accept: 'application/json' }, // browser sets Content-Type w/ boundary
    body: form,
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new OctPortalError(
      res.status,
      messageFrom(body, `POST /commit → ${res.status}`),
      body,
    )
  }
  return (body ?? { jobId: 0, status: 'UNKNOWN' }) as CommitResponse
}

/**
 * DELETE {@code /{jobId}}. Undoes a recently-committed upload within
 * the 60 s window the controller enforces. Surfaces HTTP 410 ("undo
 * window elapsed") as an {@link OctPortalError} so the store can
 * flip the row back to `committed` rather than silently failing.
 */
export async function undoCommit(jobId: number): Promise<void> {
  const res = await fetch(`${BASE}/${jobId}`, {
    method: 'DELETE',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  })
  if (res.status === 204) return
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new OctPortalError(
      res.status,
      messageFrom(body, `DELETE /${jobId} → ${res.status}`),
      body,
    )
  }
}
