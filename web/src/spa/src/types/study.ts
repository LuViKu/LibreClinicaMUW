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
