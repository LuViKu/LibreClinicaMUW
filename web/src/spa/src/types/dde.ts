/**
 * Phase E.6 dde — blind double-data-entry types.
 *
 * Wire-shape mirrors the four DTOs landed in the backend cluster:
 *   DdePassDto              ← GET  /api/v1/eventCrfs/{id}/dde-pass
 *   DdeCommitRequest        → POST /api/v1/eventCrfs/{id}/dde-commit
 *   DdeCommitResponse       ← (same)
 *   DdeConflictsDto         ← GET  /api/v1/eventCrfs/{id}/dde-conflicts
 *   DdeReconcileRequest     → POST /api/v1/eventCrfs/{id}/dde-conflicts/{itemOid}/resolve
 *
 * The DDE marker also rides embedded in {@code CrfEntryDto.dde}; that
 * block tells the SPA which pass variant of {@code CrfEntryView} to
 * render. We re-export the same DdeBlock type here so the view + store
 * layers can both refer to it without crossing into the generated
 * openapi types directly.
 */

/**
 * Which pass the caller is starting (or "done" when the DDE workflow
 * is already settled).
 */
export type DdePass = '1' | '2' | 'reconcile' | 'done'

/**
 * Per-EventCRF marker block. Lives on {@code CrfEntry.dde} when the
 * parent event_definition_crf has {@code double_entry=true}; null
 * for single-pass studies.
 */
export interface DdeBlock {
  pass: DdePass
  /** Numeric user id of the IDE clerk (0 when unknown). */
  idePass1ClerkId: number
}

/** Wire response of GET /api/v1/eventCrfs/{id}/dde-pass. */
export interface DdePassResponse {
  eventCrfOid: string
  pass: DdePass
  idePass1ClerkId: number
  /** Always 0 for pass=1 / pass=2; ≥0 for pass=reconcile. */
  mismatchCount: number
}

/** Body of POST /api/v1/eventCrfs/{id}/dde-commit. */
export interface DdeCommitRequest {
  values: Record<string, unknown>
}

/** Wire response of POST /api/v1/eventCrfs/{id}/dde-commit. */
export interface DdeCommitResponse {
  eventCrfOid: string
  mismatchCount: number
  status: 'dde-complete' | 'dde-conflicts'
  lastSavedAt: string
}

/** One conflict row inside DdeConflictsDto. */
export interface DdeConflictItem {
  itemOid: string
  label: string
  /** Pass-1 (IDE) value, string-serialised. */
  ideValue: string
  /** Pass-2 (DDE) value, string-serialised. */
  ddeValue: string
  /** True once a DM/Admin has resolved this row. */
  resolved: boolean
  /** null | 'ide' | 'dde' | 'manual' (set when resolved). */
  winner: string | null
}

/** Wire response of GET /api/v1/eventCrfs/{id}/dde-conflicts. */
export interface DdeConflicts {
  eventCrfOid: string
  subjectId: string
  crfName: string
  items: DdeConflictItem[]
}

/** winner alternative when a DM picks the canonical value. */
export type DdeReconcileWinner = 'ide' | 'dde' | 'manual'

/** Body of POST /api/v1/eventCrfs/{id}/dde-conflicts/{itemOid}/resolve. */
export interface DdeReconcileRequest {
  winner: DdeReconcileWinner
  /** Required when winner === 'manual'; ignored otherwise. */
  value?: string
  /** Short justification (required, GCP). */
  reasonForChange: string
}

/** Wire response of the resolve endpoint (200 + URI body, not 204). */
export interface DdeResolveResponse {
  /** Empty string when reconciliation is complete; otherwise hint URI. */
  nextItem: string
}
