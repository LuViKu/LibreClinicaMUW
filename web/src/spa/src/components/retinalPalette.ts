/**
 * Phase E.7 Wave 4 — Retinal viewer colour palette.
 *
 * Single source of truth for the biomarker / task colours shared
 * between {@link FundusOverlay}, {@link PerBscanTrace}, and
 * {@link RetinalKpiTile}. Pulled out of {@code FundusOverlay.vue} so
 * Vue SFCs can {@code import} them without having to re-export
 * arbitrary identifiers through the SFC's {@code <script setup>}
 * (Vue SFC scripts cannot re-export named bindings).
 *
 * The hexes match the Tailwind cyan-400 / amber-400 / fuchsia-500
 * stops the KPI tile borders use; keeping the literal here means we
 * don't have to resolve Tailwind tokens at runtime.
 */
export const BIOMARKER_COLORS = {
  irf: '#22d3ee',
  srf: '#f59e0b',
  ped: '#d946ef',
} as const

export type BiomarkerKey = keyof typeof BIOMARKER_COLORS
