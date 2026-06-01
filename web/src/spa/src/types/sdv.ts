/**
 * Phase E.6 — SDV (Source Data Verification) types.
 *
 * Shape follows the planned `GET /pages/api/v1/sdv?siteOid=…` adapter
 * response per the E.4 inventory. The legacy `/pages/viewAllSubjectSDVtmp`
 * already produces compatible JSON via `SDVController#getSdvData`
 * (E.4 catagory A) — the SPA can target the same endpoint with no
 * adapter once E.0 is unblocked. Until then the store hydrates from
 * mock data with the production shape.
 */

/** Verification + completion state of one CRF row in the SDV table. */
export type SdvStatus =
  | 'pending'   // CRF complete, awaiting verification
  | 'query'     // a query is currently blocking verification
  | 'verified'  // SDV complete
  | 'locked'    // CRF locked + verified (terminal)

/** "Required vs not-required for SDV" per the legacy `sdv_status` column. */
export type SdvRequirement = 'required-100' | 'required-partial' | 'not-required'

export interface SdvRow {
  /** OID of the EventCRF row in the DB — used as the v-model key. */
  eventCrfOid: string
  subjectId: string
  siteLabel: string
  /** Event label, e.g. "V1 Inclusion". */
  eventLabel: string
  /** Event start date (ISO YYYY-MM-DD). */
  eventStartDate: string
  /** CRF display name + version, e.g. "Demographics v1.0". */
  crfName: string
  crfLanguage: string
  status: SdvStatus
  requirement: SdvRequirement
  /** Number of open queries blocking verification. */
  openQueries: number
  /** ISO instant of the last data-entry edit. */
  lastUpdatedAt: string
}
