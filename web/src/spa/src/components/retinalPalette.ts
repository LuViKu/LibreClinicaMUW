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

/**
 * IOWA OCTLayerSeg surface palette (2026-06-25, DR-024 layers overlay).
 *
 * 12 surfaces total — IOWA's 11 layer interfaces in anatomical order
 * (ILM at index 0, OB_RPE at index 10) plus the standalone BM model
 * output appended at index 11. Color-blind-distinguishable; ILM red,
 * RPE band brown, BM magenta follows clinical convention.
 *
 * Indices map to the converter's actual filename short-label —
 * IOWA emits {@code NNN-Long description (SHORT).csv} and the
 * backend ({@link SegmentationEnvelopeLoader#loadLayersStack})
 * surfaces the SHORT token via the {@code X-MUW-Seg-Labels}
 * response header. {@link IOWA_LAYER_LABELS} below mirrors the
 * default ordering.
 */
export const IOWA_LAYER_COLORS: readonly string[] = [
  '#FF3030', // 001 ILM     — bright red
  '#FF9F1C', // 002 RNFL-GCL — orange
  '#FFD60A', // 003 GCL-IPL — yellow
  '#34D399', // 004 IPL-INL — green
  '#0EA5E9', // 005 INL-OPL — sky-blue
  '#3B82F6', // 006 OPL-HFL — blue
  '#8B5CF6', // 007 BMEIS   — violet
  '#A78BFA', // 008 IS#OSJ  — purple
  '#EC4899', // 009 IB_OPR  — pink
  '#92400E', // 010 IB_RPE  — dark-brown (RPE upper edge)
  '#7F1D1D', // 011 OB_RPE  — wine (RPE lower edge)
  '#DB2777', // 012 BM      — magenta (Bruch's membrane)
] as const

/**
 * Canonical IOWA short labels — exactly what the converter emits
 * inside the parentheses of {@code NNN-Long description (SHORT).csv}.
 * The 12th entry covers the BM model output appended at the end of
 * the stack by the loader. Used as a fallback when the response
 * header is empty + by the i18n lookup
 * ({@code retinal.layers.label.<LABEL>}).
 */
export const IOWA_LAYER_LABELS: readonly string[] = [
  'ILM', 'RNFL-GCL', 'GCL-IPL', 'IPL-INL', 'INL-OPL',
  'OPL-HFL', 'BMEIS', 'IS#OSJ', 'IB_OPR', 'IB_RPE', 'OB_RPE', 'BM',
] as const

/**
 * Default-visible surface indices — clinical trio for CRT:
 *   ILM = 0     (top boundary)
 *   IB_RPE = 9  (RPE upper edge — what CRT measures TO)
 *   BM = 11     (Bruch's membrane — the CRT baseline)
 * Operator toggle state persists per job-id in localStorage under
 * {@code bscan-layers-visible-${jobId}}.
 */
export const IOWA_DEFAULT_VISIBLE: readonly number[] = [0, 9, 11] as const
