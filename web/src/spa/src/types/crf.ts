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
import type { DdeBlock } from './dde'

export type ItemDataType =
  | 'string'
  | 'integer'
  | 'real'
  | 'date'
  | 'partial-date'
  | 'select-one'
  | 'select-multi'
  | 'boolean'
  | 'file'

export type ResponseOption = Required<components['schemas']['ResponseOptionDto']>

export type CrfItem =
  Omit<Required<components['schemas']['CrfItemDto']>, 'dataType' | 'options' | 'helper' | 'min' | 'max' | 'groupOid'>
  & {
    dataType: ItemDataType
    /** Set when the type is `select-one` / `select-multi`. */
    options?: ResponseOption[]
    /** Optional helper text under the input. */
    helper?: string
    /** Inclusive numeric range when applicable. */
    min?: number
    max?: number
    /** Phase E.6: when set, the item lives inside the matching
     *  repeating group's row template rather than the top-level
     *  values map. The SPA filters items by groupOid at render time. */
    groupOid?: string | null
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

/**
 * Phase E.6 — a saved row inside a repeating item group. `ordinal` is
 * 1-based and matches `item_data.ordinal`; `values` is keyed by item OID
 * just like the top-level {@link CrfValues} map.
 */
export type CrfGroupRow = {
  ordinal: number
  values: CrfValues
}

/**
 * Phase E.6 — a repeating item group. The SPA renders one
 * {@code RepeatingGroupSection} per entry; each row binds its values
 * via the store's {@code setValueInRow} action.
 */
export type CrfItemGroup = {
  oid: string
  label: string
  repeatMax: number
  /** Item OIDs that belong to this group, in display order. */
  itemOids: string[]
  rows: CrfGroupRow[]
}

/**
 * Phase E.6 — server-supplied file-upload metadata + caps. Surfaced
 * directly inside the {@link CrfEntry} so the dropzone widget can show
 * the cap in its helper text and pre-validate before the multipart POST.
 */
export type CrfFileMetadata = {
  /** Max bytes per upload; server enforces, SPA pre-checks. */
  maxFileBytes: number
  /** Comma-joined extension allowlist (e.g. "pdf,jpg,jpeg,png"). */
  fileExtensions: string
}

export type CrfEntry =
  Omit<Required<components['schemas']['CrfEntryDto']>, 'schema' | 'values' | 'status' | 'lastSavedAt' | 'groups' | 'maxFileBytes' | 'fileExtensions' | 'requiresReasonForChange' | 'dde'>
  & {
    schema: CrfSchema
    values: CrfValues
    status: CrfEntryStatus
    /** ISO-8601 instant of the last successful save. */
    lastSavedAt: string | null
    /** Phase E.6: repeating item groups + their saved rows. */
    groups: CrfItemGroup[]
    /** Phase E.6: server-side caps mirrored into the SPA. */
    maxFileBytes: number
    fileExtensions: string
    /**
     * Phase E.6 admin-rfc — true when the CRF is past
     * {@code date_completed} so subsequent edits require an RFC note.
     * The view uses this to gate the {@code ReasonForChangeModal}; the
     * store re-arms the modal whenever the backend returns 400 with
     * {@code missingReasonItemOids}.
     */
    requiresReasonForChange: boolean
    /**
     * Phase E.6 dde — non-null only when the parent
     * event_definition_crf has double_entry=true. The view picks the
     * blind-second-pass banner / reconcile-redirect from this field.
     */
    dde: DdeBlock | null
  }

/**
 * Phase E.6 — POST /pages/api/v1/eventCrfs/{id}/items body.
 * Mirrors the Java {@code SaveItemsRequest} record byte-for-byte.
 * Carries (a) RFC reasons (admin-rfc cluster) and (b) repeating-group
 * rows (crf-data-types cluster) alongside top-level values.
 */
export interface SaveItemsRequest {
  values: CrfValues
  /** Item OID → reason text. Required for every changed item once the CRF is complete. */
  reasons?: Record<string, string>
  /** Phase E.6 — per-row payloads for repeating item groups. */
  groups?: Array<{
    groupOid: string
    rowOrdinal: number
    values: Record<string, unknown>
  }>
}

/**
 * Phase E.6 admin-rfc — POST /items 400 body when the controller
 * rejects a post-complete save without enough reason coverage. The
 * SPA reads {@code missingReasonItemOids} to re-arm the modal
 * scoped to just the offending oids.
 */
export interface MissingReasonsError {
  message: string
  missingReasonItemOids: string[]
}

/* ------------------------------------------------------------------ */
/* Phase E A5 — CRF reopen role-aware helper.                         */
/*                                                                    */
/* Mirrors CrfReopenAuthorization.java backend-side:                  */
/*   permitted: Investigator, CRC, Data Manager, Administrator        */
/*   forbidden: Monitor, RA, RA2 (data entry / read-only roles)       */
/*                                                                    */
/* Plus state guards: status must be 'complete' (you can't reopen     */
/* what's already in-progress) and not 'locked' (admin-only).         */
/* ------------------------------------------------------------------ */

import type { UserRole } from './auth'

export function canReopenCrf(role: UserRole, status: CrfEntryStatus): boolean {
  if (status !== 'complete') return false
  return (
    role === 'Investigator' ||
    role === 'CRC' ||
    role === 'Data Manager' ||
    role === 'Administrator'
  )
}
