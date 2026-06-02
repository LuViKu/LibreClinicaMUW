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
 * Phase E.5 follow-up (2026-06-02, TODO #7): wire types derived
 * from the openapi-typescript-generated {@code components.schemas}
 * so the SPA's call sites stay aligned with the backend record shape.
 * Narrow literal-union enums ({@link ItemDataType},
 * {@link CrfEntryStatus}) stay hand-typed and are intersected in to
 * keep the SPA's pattern-matching call sites happy.
 *
 * Out of scope for the v0 (deferred to follow-up PRs):
 *  - Repetition groups (`ItemGroup.repeating === true`)
 *  - Rule-driven `showWhen` / `requiredWhen` predicates
 *  - Inline discrepancy threads per item
 *  - Multi-stage workflow gates (mark-complete, sign, etc.)
 */

import type { components } from './api'

export type ItemDataType =
  | 'string'
  | 'integer'
  | 'real'
  | 'date'
  | 'partial-date'
  | 'select-one'
  | 'select-multi'
  | 'boolean'

export type ResponseOption = Required<components['schemas']['ResponseOptionDto']>

export type CrfItem =
  Omit<Required<components['schemas']['CrfItemDto']>, 'dataType' | 'options' | 'helper' | 'min' | 'max'>
  & {
    dataType: ItemDataType
    /** Set when the type is `select-one` / `select-multi`. */
    options?: ResponseOption[]
    /** Optional helper text under the input. */
    helper?: string
    /** Inclusive numeric range when applicable. */
    min?: number
    max?: number
  }

export type CrfSection =
  Omit<Required<components['schemas']['CrfSectionDto']>, 'instructions' | 'items'>
  & {
    /** Section instructions shown above the items. */
    instructions?: string
    items: CrfItem[]
  }

export type CrfSchema =
  Omit<Required<components['schemas']['CrfSchemaDto']>, 'sections'>
  & { sections: CrfSection[] }

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

export type CrfEntry =
  Omit<Required<components['schemas']['CrfEntryDto']>, 'schema' | 'values' | 'status' | 'lastSavedAt'>
  & {
    schema: CrfSchema
    values: CrfValues
    status: CrfEntryStatus
    /** ISO-8601 instant of the last successful save. */
    lastSavedAt: string | null
  }
