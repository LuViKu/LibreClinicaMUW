/**
 * Phase E A8.2 — event-definition types.
 *
 * Wire shape returned by the
 * `GET/POST/PUT /api/v1/studies/{studyOid}/event-definitions[/{sedOid}]`
 * endpoints and the per-row reorder/disable surfaces. CRF assignments
 * are owned by A8.3 — they're queried via a separate types file when
 * that slice ships.
 */

export type EventType = 'scheduled' | 'unscheduled' | 'common'

export interface EventDefinition {
  /** Numeric PK — used by sub-resource endpoints (retinal-tasks, etc.). */
  sedId: number
  oid: string
  name: string
  description: string
  category: string
  type: string
  repeating: boolean
  ordinal: number
  /** Legacy Status.getName() string — "available", "removed", etc. */
  status: string
}

export interface CreateEventDefinitionInput {
  name: string
  type: EventType
  description?: string
  category?: string
  repeating?: boolean
}

export interface UpdateEventDefinitionInput {
  name?: string
  description?: string
  category?: string
  type?: EventType
  repeating?: boolean
}

/**
 * DR-034 — one row of a visit definition's imaging plan: a catalogue modality
 * this visit expects, whether it is required, which eye(s), and the inference
 * tasks a file of that modality is fanned out to once filed.
 */
export type ImagingRequirement = 'required' | 'optional'
export type ImagingLaterality = 'OD' | 'OS' | 'OU'

export interface ImagingPlanEntry {
  modalityId: number
  code: string
  labelDe: string
  labelEn: string
  device: string | null
  /** Comma-separated; tasks are only meaningful when this contains `e2e`. */
  kindsAccepted: string
  requirement: ImagingRequirement
  /** Null when any eye satisfies the entry. */
  laterality: ImagingLaterality | null
  tasks: string[]
}

/** What the PUT takes — the catalogue columns are the backend's to fill. */
export interface ImagingPlanEntryWrite {
  modalityId: number
  requirement: ImagingRequirement
  laterality: ImagingLaterality | null
  tasks: string[]
}

/** DR-035 — what applying a plan to already-filed scans did, or would do. */
export interface ImagingPlanCatchUp {
  /** OCT volumes filed at visits of this definition. */
  scans: number
  /** Existing jobs pointed at their visit. */
  attached: number
  /** Analyses started (new or revived). */
  started: number
  failed: number
  dryRun: boolean
  /** False when the server has no inference dispatcher: nothing can be started. */
  dispatcherAvailable: boolean
}
