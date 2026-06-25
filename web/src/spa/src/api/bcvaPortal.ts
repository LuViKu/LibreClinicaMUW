/**
 * 2026-06-24 user-feedback round — public BCVA-entry portal API client.
 *
 * <p>Thin wrappers around the {@code /pages/api/v1/public/bcva-entry}
 * endpoints exposed by {@code PublicBcvaEntryController}. Unlike the
 * rest of the SPA's HTTP surface (which talks to the authenticated
 * adapters through {@code @/api/client}), the portal is
 * unauthenticated and runs from a route the global router guard
 * allows public. Cookies are deliberately omitted — the public
 * endpoints don't read JSESSIONID, and not sending it means the
 * existing CSRF / role guards stay clean.
 *
 * <p>Two endpoints (the plan's 60-s undo will land in a follow-up):
 * - {@code GET /{studyOid}/visits?date=…} → list today's visits
 * - {@code POST /commit} → persist a single visit's BCVA values
 */

/** One visit on the chosen date. */
export interface BcvaPortalVisit {
  studyEventId: number
  studySubjectId: number
  subjectLabel: string
  eventDefinitionLabel: string
  /** ISO yyyy-MM-dd or null when the visit wasn't time-stamped. */
  dateStarted: string | null
  /** event_crf_id of any existing BCVA CRF on the visit, else null. */
  eventCrfId: number | null
  /** true when any of the canonical BCVA items already hold a value. */
  bcvaAlreadyEntered: boolean
}

/** Header surfaced alongside the visit list. */
export interface BcvaPortalStudyHeader {
  oid: string
  name: string
  uniqueIdentifier: string
}

export interface ListVisitsResponse {
  study: BcvaPortalStudyHeader
  date: string
  visits: BcvaPortalVisit[]
}

/** Commit payload — keys are the canonical BCVA item OIDs. */
export interface BcvaCommitRequest {
  studyEventId: number
  enteredBy: string
  values: Record<string, number | string | null>
}

export interface BcvaCommitResponse {
  eventCrfId: number
  auditId: number
  itemDataIds: number[]
}

export class BcvaPortalError extends Error {
  readonly status: number
  readonly body: unknown
  constructor(status: number, message: string, body: unknown = null) {
    super(message)
    this.name = 'BcvaPortalError'
    this.status = status
    this.body = body
  }
}

const BASE = '/LibreClinica/pages/api/v1/public/bcva-entry'

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

/** List planned + in-progress visits on the given date for the named
 *  study. */
export async function listVisits(
  studyOid: string,
  date: string,
): Promise<ListVisitsResponse> {
  const url = `${BASE}/${encodeURIComponent(studyOid)}/visits?date=${encodeURIComponent(date)}`
  const res = await fetch(url, {
    method: 'GET',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new BcvaPortalError(
      res.status,
      messageFrom(body, `GET /${studyOid}/visits → ${res.status}`),
      body,
    )
  }
  // Defensive default so the SPA can render an empty list rather
  // than throwing on unexpected response shape.
  const fallback: ListVisitsResponse = {
    study: { oid: studyOid, name: studyOid, uniqueIdentifier: '' },
    date,
    visits: [],
  }
  return (body ?? fallback) as ListVisitsResponse
}

/** Persist a single visit's BCVA submission. */
export async function commit(req: BcvaCommitRequest): Promise<BcvaCommitResponse> {
  const res = await fetch(`${BASE}/commit`, {
    method: 'POST',
    credentials: 'omit',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(req),
  })
  const body = await parseJsonOrNull(res)
  if (!res.ok) {
    throw new BcvaPortalError(
      res.status,
      messageFrom(body, `POST /commit → ${res.status}`),
      body,
    )
  }
  return body as BcvaCommitResponse
}
