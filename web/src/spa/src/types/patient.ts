/**
 * Phase E.6 — Patient Overview module types.
 *
 * Hand-typed mirrors of the sibling backend worktree's `/pages/api/v1/patients`
 * DTO surface. These types stay separate from the OpenAPI-generated
 * `types/api.ts` (which is regenerated end-to-end against the live spec)
 * because the patients endpoints are landing in a parallel worktree and
 * the generator has not yet picked them up.
 *
 * The shapes match the wire contract in the worktree brief exactly —
 * any drift between the backend DTO and this file is a bug to be fixed
 * here, not papered over at the call site.
 */

/** Gender code mirrored from the legacy `subject.gender` enum. */
export type PatientGender = 'F' | 'M' | 'O' | 'U'

/** Per-eye scope shared with the Subject Matrix `study_eye` column. */
export type PatientEye = 'OD' | 'OS' | 'OU'

/**
 * One enrolment of a patient into a study. A single patient (one human
 * being, keyed by `unique_identifier`) can have many enrolments across
 * different studies and arms.
 */
export interface PatientEnrolment {
  studyOid: string
  studyName: string
  /**
   * Study-subject label (legacy `study_subject.label` — the user-visible
   * identifier like `M-001`). Used for cross-references and pickStudy
   * navigation.
   */
  label: string
  /** OD / OS / OU scope on the source-of-truth enrolment row. */
  studyEye: PatientEye | null
  /** ISO date (YYYY-MM-DD). */
  enrolledOn: string
  /** ISO instant. Null when no event has been recorded yet. */
  lastVisitAt: string | null
}

/** Row in the patients list endpoint. */
export interface PatientListItem {
  subjectId: number
  uniqueIdentifier: string | null
  gender: PatientGender | string
  yearOfBirth: number | null
  /**
   * Phase E.6 retrospective-backfill — full PHI triplet captured at
   * enrolment. `dateOfBirth` is the canonical ISO `yyyy-MM-dd`; the
   * SPA's list view renders it as a locale-formatted column.
   * `yearOfBirth` is preserved for callers that only need the year.
   * All three are nullable for older subjects pre-dating capture.
   */
  firstName: string | null
  lastName: string | null
  dateOfBirth: string | null
  enrolments: PatientEnrolment[]
}

/**
 * One eye-transition event — when an eye moved from one study/enrolment
 * to another (e.g. OD progressed from iAMD into GA). Always carries the
 * `from` + `to` enrolment context so the timeline can render the
 * narrative without joining other lists.
 */
export interface EyeTransitionEvent {
  transitionId: number
  eye: 'OD' | 'OS'
  /** ISO instant. */
  eventAt: string
  fromStudyOid: string
  fromStudyName: string
  fromLabel: string
  toStudyOid: string
  toStudyName: string
  toLabel: string
  reason: string
}

/** Detail-endpoint shape — list-item fields + a per-eye transition log. */
export interface PatientDetail extends PatientListItem {
  eyeTransitions: EyeTransitionEvent[]
}

/** Server-paginated patients-list envelope. */
export interface PatientsListResponse {
  totalCount: number
  page: number
  pageSize: number
  patients: PatientListItem[]
}

/** Measurement data type — drives how the modal renders the series. */
export type MeasurementDataType = 'numeric' | 'categorical'

/** One data point in a per-modality, per-eye measurement series. */
export interface MeasurementPoint {
  /** ISO date (YYYY-MM-DD). */
  date: string
  /** Raw text value as captured on the CRF. */
  value: string
  /** Parsed numeric value (null for categorical or unparseable text). */
  numericValue: number | null
  studyOid: string
  studyName: string
  eventCrfId: number
  eventName: string
}

/** Per-modality, per-eye measurement series response. */
export interface MeasurementSeries {
  modalityCode: string
  dataType: MeasurementDataType
  unit: string | null
  series: MeasurementPoint[]
}

/**
 * Modality definition — mirrors the wt-modality-admin-spa sibling's
 * `useModalitiesStore` shape. We keep a local copy here because
 * cross-worktree resolution at build time is not guaranteed; if the
 * sibling lands first the local type can be replaced with a re-export
 * without churn at call sites.
 */
export interface Modality {
  modalityId: number
  code: string
  labelEn: string
  labelDe: string
  ordinal: number
  itemOidOd: string | null
  itemOidOs: string | null
  dataType: MeasurementDataType
  unit: string | null
}
