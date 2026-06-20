/**
 * nAMD workspace — fluid biomarker lookup.
 *
 * Port of the {@code FLUID} object in namd-data.jsx. The three retinal
 * fluid biomarkers IRF / SRF / PED — each carries:
 *   - {@code color}: design swatch (also drives polygon fills in the trend SVG).
 *   - {@code long}: long-form clinical label (rendered in legend rows).
 *   - {@code direction}: 'badUp' — increasing volume is clinically bad.
 *
 * BCVA (best-corrected visual acuity) uses 'goodUp' (rising acuity is
 * clinically good) — the delta-chip direction logic in
 * {@link NamdCompareDeltaBar} inverts the colour for that one metric.
 */

export type FluidKey = 'IRF' | 'SRF' | 'PED'

export interface FluidMeta {
  /** Swatch colour for dots, polygons, legend rows. */
  color: string
  /** Long-form clinical name (German). */
  long: string
  /** Compact German name used in the trend tooltip. */
  short: string
}

export const FLUID: Record<FluidKey, FluidMeta> = {
  IRF: { color: '#0ea5e9', long: 'Intraretinale Flüssigkeit', short: 'IRF' },
  SRF: { color: '#f59e0b', long: 'Subretinale Flüssigkeit', short: 'SRF' },
  PED: { color: '#a855f7', long: 'Pigmentepithelabhebung', short: 'PED' },
}

/** Sum of the three fluid biomarkers for a visit (in nL). */
export function totalFluid(v: { irf: number; srf: number; ped: number }): number {
  return v.irf + v.srf + v.ped
}

/** Activity-trigger sum (nL). Above {@code 20 nL} the patient is "Exsudation aktiv". */
export function activeFluid(v: { irf: number; srf: number; ped: number }): number {
  return totalFluid(v)
}

/** Activity threshold (nL) — clinical convention from the design. */
export const ACTIVITY_THRESHOLD_NL = 20
