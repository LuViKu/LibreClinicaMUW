/**
 * Phase E.6 — Per-eye modality baselines.
 *
 * The Subject Detail view ships a two-column table per eye showing the
 * patient-level (global) baseline next to the per-study baseline for
 * each modality (IOP, BCVA, central retinal thickness, etc.). The shape
 * here mirrors the backend's
 * `GET /pages/api/v1/subjects/{label}/eyes/{eye}/modality-baselines`
 * adapter exactly — it lives hand-typed in this file because the
 * `types/api.ts` regen is owned by the harmonization worktree and lags
 * behind the wire contract.
 *
 * Why two baselines per modality:
 *   - `global` is computed across every study the patient is enrolled
 *     in (some modalities — e.g. axial length — only get measured once
 *     in a lifetime; the operator wants to see that single value).
 *   - `perStudy` is scoped to the active study (so a fresh IOP
 *     baseline gets used as the per-study reference point even if an
 *     older IOP exists in another study).
 *
 * Each baseline carries the observation `date`, raw `value` string,
 * and an `observationCount` so the operator can sanity-check the
 * sample size behind the figure. `date` and `value` may both be
 * `null` when the modality has never been recorded for the eye in
 * scope — the view renders an em-dash in that case.
 */

export interface ModalityBaselineSnapshot {
  /** ISO `YYYY-MM-DD` — null when no observation exists. */
  date: string | null
  /** Raw value as the backend stored it (string for unit-safety). */
  value: string | null
  /** Number of observations the baseline was computed from. */
  observationCount: number
}

export interface ModalityBaseline {
  /**
   * Short modality code (e.g. `IOP`, `BCVA`, `CRT`). Stable across
   * locales — the SPA only uses this as a key, not a label.
   */
  modalityCode: string
  /** English display label (e.g. "Intraocular pressure"). */
  labelEn: string
  /** German display label (e.g. "Augeninnendruck"). */
  labelDe: string
  /** OpenClinica item OID the baseline was derived from. */
  itemOid: string
  /**
   * Whether the modality is numeric (e.g. IOP) or categorical
   * (e.g. lens-status: phakic / pseudophakic / aphakic). The SPA
   * uses this to decide whether to append the `unit` suffix.
   */
  dataType: 'numeric' | 'categorical'
  /** Display unit (e.g. `mmHg`, `logMAR`) — null for categorical. */
  unit: string | null
  /** Patient-level baseline across every study. */
  global: ModalityBaselineSnapshot
  /** Study-scoped baseline within the active study. */
  perStudy: ModalityBaselineSnapshot
}

export type ModalityBaselinesResponse = ModalityBaseline[]
