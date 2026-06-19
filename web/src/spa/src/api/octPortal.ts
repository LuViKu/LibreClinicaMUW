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
  /** ISO yyyy-MM-dd — what the controller's {@code LocalDate} parser expects.
   *  null when the .e2e file has no bscan-metadata chunk (typical of
   *  fundus-only exports). PublicOctUploadController:145 treats a null
   *  scanDate as "search by patientId only". */
  scanDate: string | null
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
  /** ISO yyyy-MM-dd. null for fundus-only files without an
   *  acquisition-time chunk — backend treats null as "use server-side
   *  default" (typically the upload timestamp). */
  scanDate: string | null
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
 * 2026-06-19 — one row in the response of
 * {@code GET /api/v1/public/oct-upload/patients/{studySubjectId}/events}.
 *
 * <p>Shape mirrors {@code StudyEventDto} so VisitPickerModal can
 * render the public + auth'd paths identically, with one addition:
 * {@code firstEventCrfId} pre-resolves the value the picker would
 * otherwise second-hop through {@code GET /events/{id}/detail} to
 * fetch. Saves one round trip + works without an authenticated
 * session (the auth'd detail endpoint requires login).
 */
export interface PublicStudyEvent {
  id: string
  eventDefinitionOid: string
  eventLabel: string
  ordinal: number
  dateStarted: string
  dateEnded: string | null
  location: string | null
  status: string
  repeating: boolean
  /** First non-removed event_crf row's id; null when the visit has
   *  no started CRF yet. The bind endpoint rejects null targets, so
   *  the picker surfaces "Keine Eingabemaske" when this is null. */
  firstEventCrfId: number | null
}

/**
 * 2026-06-19 — list events for a study_subject via the anonymous
 * public OCT-portal path. Backs the "Visite wählen" / "Studie wählen"
 * flows mounted at {@code /app/oct-upload}; without it those flows
 * 401 because the equivalent auth'd endpoint at
 * {@code /api/v1/events?subjectId=…} requires a session.
 */
export async function listPatientEventsPublic(
  studySubjectId: number,
): Promise<PublicStudyEvent[]> {
  const res = await fetch(`${BASE}/patients/${studySubjectId}/events`, {
    method: 'GET',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new OctPortalError(
      res.status,
      messageFrom(body, `GET /patients/${studySubjectId}/events → ${res.status}`),
      body,
    )
  }
  return (body ?? []) as PublicStudyEvent[]
}

/**
 * 2026-06-19 — hex SHA-256 of the .e2e bytes, computed via the
 * Web Crypto API (crypto.subtle.digest). Used by the pre-upload
 * dedup gate at {@link preflightSha256} so the operator never
 * uploads 200 MB of bytes that already exist on the server.
 *
 * <p>Reads the full file into an ArrayBuffer. For very large .e2e
 * files (>500 MB on a low-memory device) this could be slow / OOM
 * the browser; the SPA gates the call on a reasonable file-size
 * ceiling at the call site. Returns a lower-case hex string to
 * match the backend's {@code [0-9a-f]{{64}}} validator.
 */
export async function sha256OfFile(file: File): Promise<string> {
  const buf = await file.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', buf)
  const bytes = new Uint8Array(digest)
  let hex = ''
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, '0')
  }
  return hex
}

/** Response of {@code GET /api/v1/public/oct-upload/preflight}. */
export interface PreflightResponse {
  exists: boolean
  jobId: number | null
}

/**
 * 2026-06-19 — pre-upload dedup gate. The SPA calls this right after
 * client-side parsing (and computing the SHA-256) of the .e2e file
 * and BEFORE attempting an upload, so a re-upload of an already-
 * known scan short-circuits to the "Bereits hochgeladen" UX without
 * the 200 MB roundtrip + 409 dance.
 *
 * <p>The backend's commit-time uniqueness constraint still race-
 * guards against concurrent uploads, so this preflight is a UX
 * optimisation, not a security gate.
 */
export async function preflightSha256(
  sha256: string,
  scanIndex: number,
): Promise<PreflightResponse> {
  const url = `${BASE}/preflight?sha256=${encodeURIComponent(sha256)}&scanIndex=${scanIndex}`
  const res = await fetch(url, {
    method: 'GET',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new OctPortalError(
      res.status,
      messageFrom(body, `GET /preflight → ${res.status}`),
      body,
    )
  }
  return (body ?? { exists: false, jobId: null }) as PreflightResponse
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
export async function commitScan(
  req: CommitScanRequest,
  onProgress?: (pct: number) => void,
): Promise<CommitResponse> {
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
  if (req.scanDate !== null) {
    form.append('scanDate', req.scanDate)
  }
  form.append('laterality', req.laterality)
  form.append('scanIndex', String(req.scanIndex))
  if (req.eventCrfId !== null) {
    form.append('eventCrfId', String(req.eventCrfId))
  }
  form.append('park', String(req.park))

  // 2026-06-19 — switched from fetch() to XMLHttpRequest so the SPA
  // can drive a REAL upload-progress UI (fetch's Request body doesn't
  // expose progress events). The deterministic JS timer we used
  // before painted a fake fill that diverged from reality — it kept
  // growing on errors (e.g. 409 duplicate), then snapped to 100 %
  // long before the upload actually finished on slow disks.
  return new Promise<CommitResponse>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${BASE}/commit`)
    xhr.responseType = 'text'
    xhr.setRequestHeader('Accept', 'application/json')
    // XHR's upload object is the source of real progress events. The
    // browser fires `progress` until the request body is fully sent
    // to the server; the response body doesn't factor in. That's
    // exactly the UX signal we want — "the bytes are over the wire".
    xhr.upload.onprogress = (evt) => {
      if (!onProgress) return
      if (evt.lengthComputable && evt.total > 0) {
        const pct = Math.min(100, Math.max(0, (evt.loaded / evt.total) * 100))
        onProgress(pct)
      }
    }
    xhr.upload.onloadend = () => {
      // Upload bytes fully transferred — the server is now persisting
      // + INSERTing. Hold at 100 % until the response lands; the
      // store flips the row to a terminal state at that point.
      if (onProgress) onProgress(100)
    }
    xhr.onload = () => {
      const status = xhr.status
      let body: unknown = null
      try { body = xhr.responseText ? JSON.parse(xhr.responseText) : null }
      catch { body = null }
      if (status >= 200 && status < 300) {
        resolve((body ?? { jobId: 0, status: 'UNKNOWN' }) as CommitResponse)
      } else {
        reject(new OctPortalError(
          status,
          messageFrom(body, `POST /commit → ${status}`),
          body,
        ))
      }
    }
    xhr.onerror = () => reject(new OctPortalError(
      0, 'Network error during /commit', null,
    ))
    xhr.onabort = () => reject(new OctPortalError(
      0, 'Upload aborted', null,
    ))
    xhr.send(form)
  })
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
