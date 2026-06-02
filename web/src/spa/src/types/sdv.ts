/**
 * Phase E.6 — SDV (Source Data Verification) types.
 *
 * Shape follows the planned `GET /pages/api/v1/sdv?siteOid=…` adapter
 * response per the E.4 inventory. The legacy `/pages/viewAllSubjectSDVtmp`
 * already produces compatible JSON via `SDVController#getSdvData`
 * (E.4 catagory A) — the SPA can target the same endpoint with no
 * adapter once E.0 is unblocked. Until then the store hydrates from
 * mock data with the production shape.
 *
 * Phase E.5 follow-up (2026-06-02, TODO #7): {@link SdvRow} derived
 * from {@code components['schemas']['SdvRowDto']}; narrow literal
 * unions ({@link SdvStatus}, {@link SdvRequirement}) stay hand-typed.
 */

import type { components } from './api'

/** Verification + completion state of one CRF row in the SDV table. */
export type SdvStatus =
  | 'pending'   // CRF complete, awaiting verification
  | 'query'     // a query is currently blocking verification
  | 'verified'  // SDV complete
  | 'locked'    // CRF locked + verified (terminal)

/** "Required vs not-required for SDV" per the legacy `sdv_status` column. */
export type SdvRequirement = 'required-100' | 'required-partial' | 'not-required'

export type SdvRow =
  Omit<Required<components['schemas']['SdvRowDto']>, 'status' | 'requirement'>
  & {
    status: SdvStatus
    requirement: SdvRequirement
  }

/* ------------------------------------------------------------------ */
/* Phase E A6 — un-verify role helper.                                */
/*                                                                    */
/* Mirrors SdvUnverifyAuthorization.java backend-side:                */
/*   permitted: Monitor, Data Manager, Administrator                  */
/*   forbidden: Investigator, CRC, RA, RA2                            */
/*                                                                    */
/* Status guard: row must be currently 'verified' (no point           */
/* un-verifying pending / query / locked).                            */
/* ------------------------------------------------------------------ */

import type { UserRole } from './auth'

export function canUnverifySdv(role: UserRole, status: SdvStatus): boolean {
  if (status !== 'verified') return false
  return (
    role === 'Monitor' ||
    role === 'Data Manager' ||
    role === 'Administrator'
  )
}
