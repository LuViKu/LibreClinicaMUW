/**
 * Phase E A8.3 — CRF library + event-CRF assignment types.
 */

/**
 * Phase E.6 crf-library — every status the backend can stamp on a
 * version row. Used by the view to gate per-version action buttons
 * (lock vs unlock vs restore are mutually exclusive based on the
 * current status). Pulled into a discriminated union so a typo in a
 * downstream `v.status === 'foobar'` branch fails at type-check time.
 */
export type CrfVersionStatus = 'available' | 'locked' | 'removed' | 'auto-removed' | string

export interface CrfVersion {
  oid: string
  name: string
  description: string
  revisionNotes: string
  status: CrfVersionStatus
  uploadedAt: string | null
}

export interface Crf {
  oid: string
  name: string
  description: string
  status: string
  versions: CrfVersion[]
}

export interface CreateCrfInput {
  name: string
  description?: string
}

export type SdvRequirement = 'AllREQUIRED' | 'PARTIALREQUIRED' | 'NOTREQUIRED' | 'NOTAPPLICABLE'

/**
 * Wire shape for one `event_definition_crf` row. Returned by the
 * GET/POST/PUT/DELETE
 * `/studies/{studyOid}/event-definitions/{sedOid}/crfs[/{crfOid}]`
 * endpoints.
 */
export interface EventCrfAssignment {
  crfOid: string
  crfName: string
  defaultVersionOid: string
  defaultVersionName: string
  required: boolean
  doubleEntry: boolean
  decisionCondition: boolean
  electronicSignature: boolean
  hideCrf: boolean
  sourceDataVerification: SdvRequirement
  participantForm: boolean
  allowAnonymousSubmission: boolean
  submissionUrl: string
  offline: boolean
  status: string
}

export interface EventCrfAssignmentInput {
  crfOid?: string
  defaultVersionOid?: string
  required?: boolean
  doubleEntry?: boolean
  decisionCondition?: boolean
  electronicSignature?: boolean
  hideCrf?: boolean
  sourceDataVerification?: SdvRequirement
  participantForm?: boolean
  allowAnonymousSubmission?: boolean
  submissionUrl?: string
  offline?: boolean
}

/* -------------------------------------------------------------------- */
/* Phase E.6 crf-library — version lifecycle wire shapes                  */
/* -------------------------------------------------------------------- */

/**
 * Wire shape of the 409 body returned by
 * `DELETE /api/v1/crfs/{crfOid}/versions/{versionOid}` when references
 * still block the hard remove. Lets the SPA list the offending event
 * defs + suggest the migrate dialog as remediation.
 */
export interface VersionUsageReport {
  crfOid: string
  versionOid: string
  versionName: string
  blockingEventDefinitions: EventDefinitionReference[]
  eventCrfCount: number
  sampleSubjectLabels: string[]
}

export interface EventDefinitionReference {
  studyOid: string | null
  sedOid: string
  sedName: string
}

/**
 * Wire shape of the body for
 * `POST /api/v1/crfs/{crfOid}/versions/{from}/migrate-to/{to}`. The
 * SPA's batch dialog ships this with `dryRun=true` to populate the
 * preview pane, then re-issues with `dryRun=false` to commit.
 */
export interface MigrateVersionRequest {
  sedOids?: string[]
  dryRun: boolean
}

export interface SedMigrationRow {
  studyOid: string | null
  sedOid: string
  sedName: string | null
  migrated: boolean
  reasonSkipped: string | null
}

export interface MigrateVersionResult {
  crfOid: string
  fromVersionOid: string
  toVersionOid: string
  dryRun: boolean
  totalMigrated: number
  perSed: SedMigrationRow[]
}
