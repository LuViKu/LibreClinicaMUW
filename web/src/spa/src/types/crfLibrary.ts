/**
 * Phase E A8.3 — CRF library + event-CRF assignment types.
 */

export interface CrfVersion {
  oid: string
  name: string
  description: string
  revisionNotes: string
  status: string
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
