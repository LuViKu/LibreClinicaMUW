/**
 * Phase E.7 — Study-build types.
 *
 * Shape follows the planned `GET /pages/api/v1/studies/{oid}/build-status`
 * adapter response per api-surface.md row 14. The 7-task setup tracker
 * — Create Study → CRF → Events → Groups → Rules → Sites → Users —
 * is driven by these states; each task carries `count`, `status`, and
 * a deep-link `to` route for the SPA to consume.
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): wire types derived from
 * the openapi-typescript-generated {@code components.schemas}; narrow
 * id + status literal unions stay hand-typed.
 */

import type { components } from './api'

export type StudyBuildTaskId =
  | 'create-study'
  | 'crf'
  | 'events'
  | 'groups'
  | 'rules'
  | 'sites'
  | 'users'

export type StudyBuildTaskStatus =
  | 'not-started'
  | 'in-progress'
  | 'complete'
  | 'optional'

export type StudyBuildTask =
  Omit<Required<components['schemas']['StudyBuildTaskDto']>, 'id' | 'status' | 'count' | 'to'>
  & {
    id: StudyBuildTaskId
    /** Count value summarised under the task (e.g. "12 CRFs", "3 sites"). */
    count: number | null
    status: StudyBuildTaskStatus
    /** Deep-link target inside the SPA. `null` until the supporting view ships. */
    to: string | null
  }

export type StudyBuildStatus =
  Omit<Required<components['schemas']['StudyBuildDto']>, 'tasks'>
  & {
    /** Per-task ordered list. */
    tasks: StudyBuildTask[]
  }

/**
 * Phase E A8.1 — study identity / protocol / metadata wire shape.
 *
 * Returned by `POST /api/v1/studies`, `PUT /api/v1/studies/{oid}`,
 * and the disable / restore lifecycle endpoints. Distinct from
 * {@link StudyBuildStatus} (which carries task counts) so the
 * create/edit paths and the dashboard can evolve independently.
 */
export interface StudyIdentity {
  oid: string
  name: string
  uniqueProtocolId: string
  briefSummary: string
  principalInvestigator: string
  sponsor: string
  officialTitle: string
  secondaryProtocolId: string
  collaborators: string
  protocolDescription: string
  contactEmail: string
  protocolType: string
  phase: string
  status: string
  parentStudyOid: string | null
  parentStudyName: string | null
  /**
   * Phase E.6 — ISO `yyyy-MM-dd` from `study.date_planned_start`.
   * Optional in the SPA type to bridge the api.ts regen window;
   * once the backend ships it (commit landing 2026-06-08) the
   * SubjectMatrixView footer renders the real date instead of the
   * Phase-E.4 mock literal "01-Jul-2020".
   */
  datePlannedStart?: string | null
}

/**
 * A study's status in the system administrator's study list: a stable
 * key, not a translated term. `REMOVED` was removed on purpose,
 * `AUTO_REMOVED` with its parent study.
 */
export type AdminStudyStatus =
  | 'AVAILABLE'
  | 'PENDING'
  | 'FROZEN'
  | 'LOCKED'
  | 'REMOVED'
  | 'AUTO_REMOVED'
  | 'UNAVAILABLE'
  | 'PRIVATE'
  | 'UNKNOWN'

/**
 * One study, with its sites nested, from `GET /api/v1/admin/studies`:
 * every study on the platform, removed ones included. System
 * administrators only.
 */
export interface AdminStudy {
  oid: string
  name: string
  /** The unique protocol id. */
  uniqueIdentifier: string | null
  principalInvestigator: string | null
  /** ISO `yyyy-MM-dd`. */
  createdDate: string | null
  status: AdminStudyStatus
  /** Null for a top-level study. */
  parentOid: string | null
  /** Always empty for a site. */
  sites: AdminStudy[]
}

/**
 * What removing a study would take with it, from
 * `GET /api/v1/studies/{oid}/removal-preview`: the live rows the removal
 * auto-removes, counted per kind.
 */
export interface StudyRemovalPreview {
  oid: string
  name: string
  siteNames: string[]
  roleBindings: number
  subjects: number
  groupClasses: number
  eventDefinitions: number
  events: number
  eventCrfs: number
  itemData: number
  datasets: number
}

/**
 * Phase E A8.1 — `POST /api/v1/studies` request body. Mirrors the
 * legacy CreateStudyServlet fields collapsed into a single flat
 * request.
 */
export interface CreateStudyInput {
  name: string
  uniqueProtocolId: string
  briefSummary: string
  principalInvestigator: string
  sponsor: string
  officialTitle?: string
  secondaryProtocolId?: string
  collaborators?: string
  protocolDescription?: string
  contactEmail?: string
  protocolType?: string
  phase?: string
}

/**
 * Phase E A8.4 — `POST /api/v1/studies/{parentOid}/sites` request body.
 *
 * Mirrors the backend `CreateSiteRequest` record. Sponsor + protocol
 * type + phase are inherited from the parent — the SPA doesn't
 * surface them on the site-create form.
 */
export interface CreateSiteInput {
  name: string
  uniqueProtocolId: string
  briefSummary?: string
  principalInvestigator: string
  facilityName?: string
  facilityCity?: string
  facilityState?: string
  facilityZip?: string
  facilityCountry?: string
  facilityContactName?: string
  facilityContactDegree?: string
  facilityContactPhone?: string
  facilityContactEmail?: string
  initialPrincipalInvestigatorUserId?: number
}

/**
 * Phase E A8.1 — `PUT /api/v1/studies/{oid}` request body. Every
 * field optional; `undefined`/omitted means "leave unchanged".
 */
export interface UpdateStudyInput {
  name?: string
  briefSummary?: string
  principalInvestigator?: string
  sponsor?: string
  officialTitle?: string
  secondaryProtocolId?: string
  collaborators?: string
  protocolDescription?: string
  contactEmail?: string
  protocolType?: string
  phase?: string
}
