/**
 * P2-6 — due-visits client (authenticated).
 *
 * Backs the view that answers "who is expected, and who was missed". The
 * backend scopes the list to the studies the session can see, so no filtering
 * happens here.
 */
import { apiGet } from '@/api/client'

export interface DueVisit {
  studyEventId: number
  studySubjectId: number
  subjectLabel: string
  studyName: string
  eventLabel: string
  /** ISO date; null only for a visit with no start date recorded. */
  date: string | null
  /** Local time, or null when the visit carries no meaningful time of day. */
  time: string | null
  /** Projected status: 'scheduled' or 'data-entry-started'. */
  status: string
  /** The visit's date has passed and it is still open. */
  overdue: boolean
}

export interface DueVisitsResponse {
  from: string
  to: string
  visits: DueVisit[]
}

/**
 * Open visits in a date window.
 *
 * Omitting both bounds gives a window around today that reaches into the past,
 * because overdue visits are the point of the list. The backend refuses a
 * window wider than 92 days rather than silently clamping it.
 */
export function listDueVisits(params: {
  from?: string
  to?: string
  studyOid?: string
} = {}): Promise<DueVisitsResponse> {
  const qs = new URLSearchParams()
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  if (params.studyOid) qs.set('studyOid', params.studyOid)
  const suffix = qs.toString() ? `?${qs.toString()}` : ''
  return apiGet<DueVisitsResponse>(`/pages/api/v1/events/due${suffix}`)
}
