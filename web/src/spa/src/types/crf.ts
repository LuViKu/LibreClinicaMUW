/**
 * Phase E.5.3 — CRF schema + entry types.
 *
 * Shape follows the Phase E.4 inventory's planned
 * `GET /pages/api/v1/eventCrfs/{id}` response and the existing
 * `CRFVersionBean` / `ItemFormMetadataBean` / `ResponseSetBean`
 * model. The SPA's CRF Entry view is written against this contract;
 * the Pinia store hydrates from mock data with the production shape
 * until the adapter lands during E.5.3's backend pass.
 *
 * Out of scope for the v0 (deferred to follow-up PRs):
 *  - Repetition groups (`ItemGroup.repeating === true`)
 *  - Rule-driven `showWhen` / `requiredWhen` predicates
 *  - Inline discrepancy threads per item
 *  - Multi-stage workflow gates (mark-complete, sign, etc.)
 */

export type ItemDataType =
  | 'string'
  | 'integer'
  | 'real'
  | 'date'
  | 'partial-date'
  | 'select-one'
  | 'select-multi'
  | 'boolean'

export interface ResponseOption {
  code: string
  label: string
}

export interface CrfItem {
  /** OID of the ItemDef. */
  oid: string
  /** Human label shown next to the input. */
  label: string
  dataType: ItemDataType
  required: boolean
  /** Set when the type is `select-one` / `select-multi`. */
  options?: ResponseOption[]
  /** Optional helper text under the input. */
  helper?: string
  /** Inclusive numeric range when applicable. */
  min?: number
  max?: number
}

export interface CrfSection {
  oid: string
  title: string
  /** Section instructions shown above the items. */
  instructions?: string
  items: CrfItem[]
}

export interface CrfSchema {
  /** OID of the CRFVersion. */
  oid: string
  name: string
  /** Friendly version tag, e.g. "v2.1". */
  version: string
  sections: CrfSection[]
}

/**
 * Raw values keyed by item oid. The view binds form inputs to these
 * via v-model. Stored as `unknown` because each item's data type is
 * carried separately by the schema.
 */
export type CrfValues = Record<string, unknown>

export type CrfEntryStatus =
  | 'not-started'
  | 'in-progress'
  | 'complete'
  | 'locked'

/** Snapshot returned by the backend adapter (planned). */
export interface CrfEntry {
  /** OID of the EventCRF row in the DB. */
  eventCrfOid: string
  subjectId: string
  eventLabel: string
  schema: CrfSchema
  values: CrfValues
  status: CrfEntryStatus
  /** ISO-8601 instant of the last successful save. */
  lastSavedAt: string | null
}
