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

/* ------------------------------------------------------------------ */
/* Phase E A4 — event edit / cancel role helpers.                     */
/*                                                                    */
/* Mirrors EventEditAuthorization.java backend-side:                  */
/*   edit:   Investigator, CRC, Data Manager, Administrator           */
/*   cancel: Data Manager, Administrator                              */
/*                                                                    */
/* Plus state guards: editing is refused when status is               */
/* terminal (signed / locked); cancelling is refused for the same     */
/* terminal statuses + 'completed' (signed-off data still exists).    */
/* ------------------------------------------------------------------ */

import type { UserRole } from './auth'

export function canEditEvent(role: UserRole, status: StudyEventStatus): boolean {
  if (status === 'signed' || status === 'locked') return false
  return (
    role === 'Investigator' ||
    role === 'CRC' ||
    role === 'Data Manager' ||
    role === 'Administrator'
  )
}

export function canCancelEvent(role: UserRole, status: StudyEventStatus): boolean {
  if (status === 'signed' || status === 'locked') return false
  return role === 'Data Manager' || role === 'Administrator'
}

/** Phase E A4 — body of PUT /api/v1/events/{id}. */
export type UpdateEventRequest = components['schemas']['UpdateEventRequest']

/* ------------------------------------------------------------------ */
/* Phase E.6 — Event Detail view types.                                */
/*                                                                    */
/* Wire shape of `GET /pages/api/v1/events/{id}`. Replaces the legacy */
/* `/pages/EnterDataForStudyEvent` JSP that SubjectDetailView used to */
/* bridge into. Defined manually (not via openapi-typescript) because */
/* the generator runs against a live server and the new EventDetailDto */
/* won't appear in api.ts until the next codegen pass.                */
/* ------------------------------------------------------------------ */

/** State of one CRF slot in the Event Detail view. */
export type EventCrfRowStatus =
  | 'not-started'
  | 'data-entry-started'
  | 'completed'
  | 'stopped'
  | 'signed'
  /**
   * Phase E.6 restore-quickwins — soft-deleted via
   * {@code DELETE /api/v1/eventCrfs/{id}} (AUTO_DELETED in the DB).
   * The EventDetailView surfaces a Restore action for this state;
   * the row's data is preserved server-side.
   */
  | 'removed'

/** One row of {@link EventDetailDto.crfs}. */
export interface EventCrfRowDto {
  eventCrfId: number | null
  eventCrfOid: string | null
  crfName: string
  crfVersionName: string
  crfVersionOid: string | null
  eventDefinitionCrfId: number
  status: EventCrfRowStatus
  required: boolean
  passwordRequired: boolean
}

/** GET /pages/api/v1/events/{id} response. */
export interface EventDetailDto {
  eventId: number
  eventDefinitionOid: string
  eventDefinitionName: string
  subjectLabel: string
  subjectOid: string
  studyOid: string
  studyName: string
  dateStart: string
  status: StudyEventStatus
  ordinal: number
  repeating: boolean
  crfs: EventCrfRowDto[]
}

/* ------------------------------------------------------------------ */
/* Phase E.6 — POST /pages/api/v1/events/{id}/crfs/{edcId}:start.     */
/*                                                                    */
/* Creates a fresh event_crf row for an event_definition_crf slot     */
/* that has no entry yet, so the SPA can route to CrfEntryView        */
/* without bridging through the legacy EnterDataForStudyEvent JSP.    */
/* Manually shaped (not via openapi-typescript) for the same reason   */
/* the EventDetailDto block above is.                                 */
/* ------------------------------------------------------------------ */

/**
 * Body of POST /pages/api/v1/events/{id}/crfs/{edcId}:start.
 * Both fields optional; an omitted {@code crfVersionId} defaults
 * to the slot's {@code default_version_id}.
 */
export interface StartEventCrfRequest {
  crfVersionId?: number
}

/** Response of POST /pages/api/v1/events/{id}/crfs/{edcId}:start. */
export interface StartEventCrfResponse {
  eventCrfId: number
  eventCrfOid: string
  eventId: number
  eventDefinitionCrfId: number
  crfVersionId: number
  status: EventCrfRowStatus
}
