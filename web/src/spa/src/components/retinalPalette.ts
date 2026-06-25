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
 * 11 surfaces in IOWA's canonical anatomical order — ILM at index 0,
 * BM at index 10. Color-blind-distinguishable; ILM red, RPE
 * dark-brown, BM magenta follows clinical convention.
 *
 * The {@link BscanViewer} surface-rendering loop indexes into this
 * array by the surface index supplied by the backend
 * ({@link SegmentationEnvelopeLoader#loadLayersStack}) and the
 * matching label comes from the {@code X-MUW-Seg-Labels} response
 * header (kept in step with {@link IOWA_LAYER_LABELS} below).
 */
export const IOWA_LAYER_COLORS: readonly string[] = [
  '#FF3030', // 001 ILM     — bright red
  '#FF9F1C', // 002 NFL     — orange
  '#FFD60A', // 003 GCL-IPL — yellow
  '#34D399', // 004 INL     — green
  '#0EA5E9', // 005 OPL     — sky-blue
  '#3B82F6', // 006 ONL     — blue
  '#8B5CF6', // 007 ELM     — violet
  '#A78BFA', // 008 IS-OS   — purple
  '#EC4899', // 009 OPR     — pink
  '#92400E', // 010 RPE     — dark-brown
  '#DB2777', // 011 BM      — magenta
] as const

/**
 * Canonical IOWA layer labels (matches the converter's NNN-LABEL.csv
 * naming). The backend forwards whatever IOWA actually emitted via the
 * {@code X-MUW-Seg-Labels} header — this list is a default / fallback
 * if the header is absent and also drives the i18n lookup
 * ({@code retinal.layers.label.<LABEL>}).
 */
export const IOWA_LAYER_LABELS: readonly string[] = [
  'ILM', 'NFL', 'GCL-IPL', 'INL', 'OPL',
  'ONL', 'ELM', 'IS-OS', 'OPR', 'RPE', 'BM',
] as const

/**
 * Default-visible surface indices (clinical trio: ILM = 0, RPE = 9,
 * BM = 10). Operator toggle state persists per job-id in localStorage
 * under {@code bscan-layers-visible-${jobId}}.
 */
export const IOWA_DEFAULT_VISIBLE: readonly number[] = [0, 9, 10] as const
