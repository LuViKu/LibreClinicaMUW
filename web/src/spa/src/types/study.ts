/**
 * Phase E.7 — Study-build types.
 *
 * Shape follows the planned `GET /pages/api/v1/studies/{oid}/build-status`
 * adapter response per api-surface.md row 14. The 7-task setup tracker
 * — Create Study → CRF → Events → Groups → Rules → Sites → Users —
 * is driven by these states; each task carries `count`, `status`, and
 * a deep-link `to` route for the SPA to consume.
 */

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

export interface StudyBuildTask {
  id: StudyBuildTaskId
  /** Count value summarised under the task (e.g. "12 CRFs", "3 sites"). */
  count: number | null
  status: StudyBuildTaskStatus
  /** Deep-link target inside the SPA. `null` until the supporting view ships. */
  to: string | null
}

export interface StudyBuildStatus {
  studyOid: string
  studyName: string
  studyVersion: string
  /** Active site count + total enrolled subjects across all sites. */
  sites: number
  enrolledSubjects: number
  /** Per-task ordered list. */
  tasks: StudyBuildTask[]
}
