/**
 * Phase E.5 — Subject + visit-status TypeScript types.
 *
 * Shape follows the Phase E.4 inventory's planned
 * `GET /pages/api/v1/subjects` response. Until the B-category adapter
 * lands (gated on the E.0 dispatcher fix), the SPA consumes mock data
 * with this exact shape from the Pinia store so the view code is
 * already written against the production contract.
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): the wire-level types
 * ({@link Subject}, {@link SubjectDetail}, {@link EventCellSnapshot},
 * {@link EventCellDetail}) are now derived from the
 * openapi-typescript-generated {@code components.schemas} so they
 * stay aligned with the backend record shape. Narrow literal-union
 * enums ({@link Gender}, {@link EventStatus}, {@link DataEntryStage})
 * stay hand-typed and are intersected in to keep the SPA's
 * pattern-matching call sites happy (the generated record loses the
 * union narrowing — it sees these as plain {@code string}).
 */

import type { components } from './api'

export type Gender = 'F' | 'M' | 'O' | 'U'

/**
 * Phase E.6 subject-lifecycle — coarse mapping of
 * `study_subject.status_id`. Mirrors the backend's
 * `mapStudySubjectStatus` helper byte-for-byte.
 *
 *   - 'available'    — normal active row
 *   - 'removed'      — soft-deleted (DM/Admin "Show removed" toggle)
 *   - 'auto-removed' — child cascaded from a removed parent
 *   - 'locked'       — frozen for downstream edits
 *   - 'signed'       — investigator e-signature applied
 */
export type SubjectStatus =
  | 'available'
  | 'removed'
  | 'auto-removed'
  | 'locked'
  | 'signed'

/**
 * Phase E.6 subject-lifecycle — one entry per active
 * subject_group_map row carried by both the matrix list and the
 * detail endpoint. groupId may be null on OPTIONAL "not-now"
 * branches; subjectAssignment mirrors the parent group class's
 * REQUIRED / OPTIONAL marker so the SPA can render the affordance.
 *
 * Hand-typed (not derived from `components['schemas']`) because the
 * openapi regen happens out-of-band — the type lives here so the
 * SPA store + view code compile against the new shape immediately.
 */
export interface GroupAssignmentSnapshot {
  groupClassId: number
  groupClassName: string
  groupId: number | null
  groupName: string | null
  subjectAssignment: 'REQUIRED' | 'OPTIONAL'
}

/**
 * Phase E.6 subject-lifecycle — payload for the PUT
 * `/api/v1/subjects/{oid}/groups` endpoint and the optional
 * `groupAssignments` field on the AddSubject body. The backend
 * reconciles inserts + soft-deletes + group switches in one call;
 * the SPA always sends the desired final state, never deltas.
 */
export interface GroupAssignmentInput {
  groupClassId: number
  /** Null expresses the OPTIONAL "not-now" branch. */
  groupId: number | null
}

/**
 * Phase E.6 Tier 1 — ophthalmology study-eye scope.
 *
 * Mirrors the backend `study_subject.study_eye` column. `null` is the
 * legitimate "not applicable" / "not yet randomized" state.
 *   - 'OD' = right eye (oculus dexter)
 *   - 'OS' = left eye (oculus sinister)
 *   - 'OU' = both eyes (oculus uterque)
 */
export type StudyEye = 'OD' | 'OS' | 'OU'

/** Per-event CRF completion state. */
export type EventStatus =
  | 'not-scheduled'
  | 'scheduled'
  | 'in-progress'
  | 'complete'
  | 'signed'
  | 'locked'

export type EventCellSnapshot =
  Omit<Required<components['schemas']['EventCellDto']>, 'status'>
  & { status: EventStatus }

export type Subject =
  Omit<Required<components['schemas']['SubjectListItemDto']>, 'gender' | 'secondaryId' | 'yearOfBirth' | 'groupLabel' | 'events' | 'studyEye'>
  & {
    gender: Gender
    secondaryId: string | null
    yearOfBirth: number | null
    groupLabel: string | null
    events: EventCellSnapshot[]
    /**
     * Phase E.6 Tier 1 — ophthalmology study-eye scope.
     * Surfaced on the Subject Matrix so investigators can pattern-match
     * one-eye cohorts at a glance.
     */
    studyEye: StudyEye | null
    /**
     * Phase E.6 subject-lifecycle — coarse study_subject status used
     * to render Removed / Locked rows distinctly when the DM/Admin
     * "Show removed" toggle is on. Optional because the openapi
     * regen may not yet carry the field; the store falls back to
     * 'available' when null.
     */
    status?: SubjectStatus | null
    /**
     * Phase E.6 subject-lifecycle — flattened active subject_group_map
     * rows. Null is the "not loaded" signal (matrix-side N+1
     * mitigation may defer the fetch) — the view code treats null
     * as "navigate to detail for the picker".
     */
    groupAssignments?: GroupAssignmentSnapshot[] | null
  }

/* ------------------------------------------------------------------ */
/* Phase E A3 — subject lifecycle role helper.                        */
/*                                                                    */
/* Mirrors SubjectLifecycleAuthorization.java backend-side:           */
/*   permitted: Data Manager, Administrator                           */
/*   forbidden: Investigator, CRC, Monitor, RA, RA2                   */
/*                                                                    */
/* No state guard — the existing GET endpoints exclude DELETED rows   */
/* from the matrix, so SubjectDetailView only ever sees AVAILABLE     */
/* subjects. Restore lives backend-side for now (a "show removed"     */
/* filter is a separate slice).                                       */
/* ------------------------------------------------------------------ */

import type { UserRole } from './auth'

export function canManageSubjectLifecycle(role: UserRole): boolean {
  return role === 'Data Manager' || role === 'Administrator'
}

/* ------------------------------------------------------------------ */
/* Phase E A2 — subject demographics edit role helper.                */
/*                                                                    */
/* Mirrors SubjectEditAuthorization.java backend-side:                */
/*   permitted: Investigator, CRC, Data Manager, Administrator        */
/*   forbidden: Monitor, RA, RA2                                      */
/* ------------------------------------------------------------------ */

export function canEditSubject(role: UserRole): boolean {
  return (
    role === 'Investigator' ||
    role === 'CRC' ||
    role === 'Data Manager' ||
    role === 'Administrator'
  )
}

/**
 * Per-CRF data-entry stage taxonomy surfaced on the detail view.
 *
 * Maps from the legacy `event_crf.completion_status_id` via the M3
 * SubjectsApiController; see `SubjectDetailDto.EventCellDetailDto`
 * JavaDoc for the full mapping table.
 *
 * `null` means the event hasn't started yet (no `event_crf` row).
 */
export type DataEntryStage =
  | 'not-started'
  | 'data-being-entered'
  | 'initial-data-entry-completed'
  | 'validation-completed'
  | 'locked'

/**
 * Detail-view extension of {@link EventCellSnapshot} — adds the
 * scheduling + completion metadata the matrix doesn't carry.
 *
 * Populated by `GET /pages/api/v1/subjects/{oid}` (M3 adapter).
 */
export type EventCellDetail =
  Omit<Required<components['schemas']['EventCellDetailDto']>, 'status' | 'dateStart' | 'dateEnd' | 'location' | 'dataEntryStage'>
  & {
    status: EventStatus
    dateStart: string | null
    dateEnd: string | null
    location: string | null
    dataEntryStage: DataEntryStage | null
  }

/**
 * Subject Detail — superset of {@link Subject} for the dedicated
 * single-subject view. Populated by `GET /pages/api/v1/subjects/{oid}`
 * (M3 adapter). The matrix list endpoint does not return these
 * fields; the detail view fetches them separately.
 */
export type SubjectDetail =
  Omit<Required<components['schemas']['SubjectDetailDto']>, 'gender' | 'secondaryId' | 'yearOfBirth' | 'groupLabel' | 'events' | 'studyEye' | 'screeningDate' | 'eyeTransitions'>
  & {
    gender: Gender
    secondaryId: string | null
    yearOfBirth: number | null
    groupLabel: string | null
    events: EventCellDetail[]
    /**
     * Phase E.6 Tier 1 — ophthalmology study-eye scope (subject detail).
     * Null for non-ophth studies or pre-randomization subjects.
     */
    studyEye: StudyEye | null
    /**
     * Phase E.6 Tier 1 — eligibility-screening date (ISO YYYY-MM-DD).
     * Null when not recorded or no separate screening visit was run.
     */
    screeningDate: string | null
    /**
     * Phase E.6 subject-lifecycle — coarse study_subject status.
     * Optional because the openapi regen may not yet carry the
     * field; views fall back to the existing signed / locked
     * booleans when null.
     */
    status?: SubjectStatus | null
    /**
     * Phase E.6 subject-lifecycle — flattened active
     * subject_group_map rows. Always non-null on the detail
     * endpoint (the matrix-side N+1 mitigation only applies to
     * list endpoints).
     */
    groupAssignments?: GroupAssignmentSnapshot[]
    /**
     * Phase E.6 per-eye cohort transition workflow — per-eye
     * cross-references for subjects whose source enrolment was
     * downgraded (`side='source'`) or whose current enrolment was
     * created from a downgrade elsewhere (`side='target'`). The
     * SPA renders two banner blocks above the events table when
     * matching rows are present. Optional because non-ophth or
     * never-transitioned subjects don't carry it.
     */
    eyeTransitions?: EyeTransitionDto[]
  }

/* ------------------------------------------------------------------ */
/* Phase E.6 — per-eye cohort transition workflow.                    */
/*                                                                    */
/* Clinical rules:                                                    */
/*   1. Source iAMD enrolment is downgraded — OU → other eye;         */
/*      single-eye → NULL (stub row).                                 */
/*   2. GA inherits subject identity + a cross-reference banner.      */
/*      No CRF migration.                                             */
/*   3. Bilateral GA appends: a second eye progressing upgrades the   */
/*      existing GA row from OD to OU.                                */
/*                                                                    */
/* Hand-typed (not derived from `components['schemas']`) because the  */
/* openapi regen happens out-of-band — the type lives here so the    */
/* SPA store + view code compile against the new shape immediately.  */
/* ------------------------------------------------------------------ */

export interface EyeTransitionDto {
  transitionId: number
  eye: 'OD' | 'OS'
  /** Which side of the transition this row represents from the active-study subject's POV. */
  side: 'source' | 'target'
  partnerStudyOid: string
  partnerStudyName: string
  partnerLabel: string
  transitionedAt: string   // ISO instant
  reason: string
}

/**
 * Phase E.6 per-eye cohort transition — request body for
 * `POST /pages/api/v1/subjects/{label}/eyes/{eye}/transition`.
 *
 * The dialog gathers a target study OID + free-text reason; the
 * optional `targetLabel` lets the operator pre-name the new
 * downstream subject (defaults to the source label on the backend).
 */
export interface TransitionEyeRequest {
  targetStudyOid: string
  targetLabel?: string
  reason: string
}

/**
 * Phase E.6 — Candidate target-study row for the TransitionEyeDialog.
 *
 * Byte-compatible with the local stub the dialog branch inlined; this
 * canonical export removes the duplication that branch's commit
 * message flagged. See the dialog's own JSDoc for the harmonization
 * rationale.
 */
export interface StudyOption {
  oid: string
  name: string
}

/**
 * Phase E.4 M3 + M8 — sign-preflight wire types.
 *
 * Mirrors the backend `SignPreflightDto` byte-for-byte. The five
 * `id`-keyed checks correspond to the regulatory rules the M3
 * controller computes from `study_event` / `event_crf` /
 * `discrepancy_note` state plus the current user's role:
 *
 *  - `events-complete`     — all scheduled events have data
 *  - `crfs-complete`       — all required CRFs marked complete
 *  - `open-queries`        — warn-only, never blocks
 *  - `subject-not-signed`  — subject hasn't been signed yet
 *  - `user-role-can-sign`  — user is Investigator or Study Director
 *
 * `status` is `'pass'` / `'warn'` / `'fail'`. The M8 view collapses
 * these to `'pass'` / `'warn'` / `'blocker'` for the
 * {@link ConfirmationWithPreflight} primitive — see
 * `SignSubjectView.vue` for the mapping.
 */
export type PreflightCheck =
  Omit<Required<components['schemas']['CheckRow']>, 'id' | 'status'>
  & {
    id:
      | 'events-complete'
      | 'crfs-complete'
      | 'open-queries'
      | 'subject-not-signed'
      | 'user-role-can-sign'
    status: 'pass' | 'warn' | 'fail'
  }

export type SignPreflight =
  Omit<Required<components['schemas']['SignPreflightDto']>, 'checks'>
  & { checks: PreflightCheck[] }
