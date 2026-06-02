/**
 * Phase E.4 M11 — Study-event scheduling types.
 *
 * Wire shape of the `GET /pages/api/v1/events` adapter (cross-subject
 * event list) and the response body of `POST /pages/api/v1/events`
 * (schedule a new event). The legacy `study_event` table has no OID
 * column — the SPA carries the numeric id as a string and treats it
 * as opaque, same convention as the M5 `eventCrfOid` field.
 */

import type { components } from './api'

/** Maps 1:1 to the legacy {@link SubjectEventStatus} enum. */
export type StudyEventStatus =
  | 'scheduled'
  | 'not-scheduled'
  | 'data-entry-started'
  | 'completed'
  | 'stopped'
  | 'skipped'
  | 'locked'
  | 'signed'

export type StudyEvent =
  Omit<Required<components['schemas']['StudyEventDto']>, 'status' | 'dateEnded' | 'location'>
  & {
    status: StudyEventStatus
    dateEnded: string | null
    location: string | null
  }

/**
 * Body for POST /pages/api/v1/events.
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): derived from the
 * openapi-typescript-generated spec. The store's call site sets
 * `subjectId`/`eventDefinitionOid`/`dateStarted` from form inputs
 * before submitting, so they must be present at runtime —
 * `Required` lifts them out of the generated optional shape to
 * keep the call-site invariant explicit. `location` stays optional
 * (the form lets the user skip it).
 */
type GeneratedScheduleEventRequest =
  components['schemas']['ScheduleEventRequest']
export type ScheduleEventRequest =
  Required<Pick<GeneratedScheduleEventRequest, 'subjectId' | 'eventDefinitionOid' | 'dateStarted'>> &
  Pick<GeneratedScheduleEventRequest, 'location'>
