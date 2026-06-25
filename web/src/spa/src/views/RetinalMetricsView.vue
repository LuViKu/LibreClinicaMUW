<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — Retinal scan metrics viewer.
 *
 * Centerpiece of the retinal inference workflow. Three columns on
 * lg+ viewports (KPI strip / fundus overlay / per-B-scan trace), with
 * an ETDRS sub-totals table + a download list + a raw JSON payload
 * tree below.
 *
 * <p>Lifecycle:
 *   1. {@code onMounted} → {@code loadJob(jobId)} pulls the fat DTO.
 *   2. When the job has a {@code geometryUrl}, {@code loadGeometry()}
 *      pulls geometry.json (cached by e2eUuid).
 *   3. Empty states render for non-succeeded statuses + when the
 *      primary metric is missing.
 *
 * <p>Hover wiring: the per-B-scan chart emits a slice index on hover
 * which the view forwards as {@code hoveredBscanZ} to the overlay so
 * the matching B-scan polyline highlights on the fundus image.
 */
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'
import FundusOverlay, { type EtdrsRegion } from '@/components/FundusOverlay.vue'
import RetinalArtifactList from '@/components/RetinalArtifactList.vue'
import RetinalVisitComparison from '@/components/RetinalVisitComparison.vue'
import ArmBadge from '@/components/ArmBadge.vue'
import JsonTree from '@/components/JsonTree.vue'
import type { FundusOverlayTask } from '@/components/FundusOverlay.vue'
import { useStudyArm } from '@/composables/useStudyArm'

import { useRetinalJobStore } from '@/stores/retinalJob'
import { useErrorsStore } from '@/stores/errors'
import { useSegmentationEnvelope } from '@/composables/useSegmentationEnvelope'
import type { FluidPayload, GaPayload, ThicknessPayload, RetinalJobDetail } from '@/api/retinal'
import { useJobStatusStream } from '@/composables/useJobStatusStream'

/**
 * nAMD Slice 5 — Cornerstone.js B-scan viewer is large
 * (~150 KB gzipped including the dicom-image-loader web-worker).
 * Lazy-load only when the operator opens a retinal view.
 */
const BscanViewer = defineAsyncComponent(() => import('@/components/BscanViewer.vue'))

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useRetinalJobStore()
const errors = useErrorsStore()

const jobId = computed<number>(() => Number(route.params.jobId))

const job = computed<RetinalJobDetail | null>(() => store.jobs[jobId.value] ?? null)
const geometry = computed(() => {
  const uuid = job.value?.e2eUuid ?? null
  if (uuid == null) return null
  return store.geometries[uuid] ?? null
})
const isLoading = computed<boolean>(() => !!store.loading[jobId.value])
const loadError = computed<string | null>(() => store.errors[jobId.value] ?? null)

/**
 * 2026-06-22 round 9 follow-up — German status + task labels.
 * Falls through to the raw value when the SPA hasn't shipped a
 * translation for the token (e.g. a new sidecar emits a new
 * task name).
 *
 * Computeds rather than functions called from the template — calling
 * t() inside a function invoked per render can cause vue-i18n to
 * re-track locale dependencies on every reactive pass, which the
 * StatusPill upstream caught as "recursive updates" once a third
 * StatusPill landed in the heading.
 */
const statusLabelText = computed<string>(() => {
  const status = job.value?.status
  if (!status) return ''
  const key = `retinal.status.${status}`
  const translated = t(key)
  return translated === key ? status : translated
})
const taskLabelText = computed<string>(() => {
  const task = job.value?.task
  if (!task) return ''
  const key = `retinal.task.${task}`
  const translated = t(key)
  return translated === key ? String(task).toUpperCase() : translated
})

/**
 * 2026-06-22 — artifacts surfaced in the Downloads section. Drops
 * derived presentation PNGs (projection composite + per-biomarker
 * + per-slice seg overlays) so the list is just the canonical
 * scientific output: fluidseg.npz / fluid_labels.npy / etc. The
 * PNGs still serve via the artifact-URL endpoint; they just don't
 * clutter the manual-download list.
 */
const downloadableArtifacts = computed<string[]>(() => {
  const names = job.value?.artifactNames ?? []
  return names.filter((name) =>
    !name.startsWith('projection_fluid')
    && !name.startsWith('projection_ga')
    && !name.startsWith('projection_onl')
    && !name.startsWith('projection_pr')
    && !name.startsWith('seg_bscan_'),
  )
})

const hoveredBscanZ = ref<number | null>(null)

/**
 * 2026-06-22 — ETDRS region multi-select. The operator clicks one or
 * more rings/discs on the en-face fundus to scope the biomarker
 * quantification. Owned at the view level so the selection persists
 * across slice scrubbing, SSE-driven re-fetches, and FundusOverlay
 * remounts. Empty set → show the standard 1mm / 3mm / 6mm rows.
 */
const selectedEtdrsRegions = ref<EtdrsRegion[]>([])

/** Always a defined integer for the BscanViewer's v-model — falls
 *  back to the middle slice when no hover has set hoveredBscanZ. */
const currentBscanZ = computed<number>(() => {
  if (hoveredBscanZ.value != null) return hoveredBscanZ.value
  const n = geometry.value?.bscan?.dim_z_bscans ?? 0
  return Math.max(0, Math.floor(n / 2))
})

/**
 * nAMD Slice 6 — honour-system arm gate. When the subject is in
 * Arm B (`AI_HIDDEN`) we hide the KPI strip, en-face overlay layer,
 * and visit-to-visit comparison panel. The B-scan navigator + bbox
 * + ETDRS rings stay visible — those don't expose AI output.
 */
const armGate = useStudyArm(() => job.value?.subjectArm ?? null)
function onHoverBscan(z: number | null) {
  hoveredBscanZ.value = z
}

/* -------- Lifecycle -------------------------------------------------- */

async function load() {
  try {
    await store.loadJob(jobId.value, true)
    if (job.value?.geometryUrl != null) {
      await store.loadGeometry(jobId.value)
    }
  } catch {
    // store records the error per-job; the template surfaces it.
  }
}

onMounted(() => {
  void load()
  // 2026-06-22 — outside-click + escape close the rerun-as menu.
  // The buttons themselves use @click.stop so they don't auto-close.
  document.addEventListener('click', closeRerunMenu)
  document.addEventListener('keydown', closeRerunMenuOnEscape)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeRerunMenu)
  document.removeEventListener('keydown', closeRerunMenuOnEscape)
})

function closeRerunMenu(): void {
  rerunMenuOpen.value = false
}
function closeRerunMenuOnEscape(ev: KeyboardEvent): void {
  if (ev.key === 'Escape') rerunMenuOpen.value = false
}

watch(jobId, () => {
  void load()
})

/* -------- Derived view-model ---------------------------------------- */

const isFluid = computed(() => job.value?.task === 'fluid')
const isGa = computed(() => job.value?.task === 'ga')
const isOnl = computed(() => job.value?.task === 'onl')
const isPr = computed(() => job.value?.task === 'pr')
const isThickness = computed(() => isOnl.value || isPr.value)

const fluidPayload = computed<FluidPayload | null>(() => {
  if (!isFluid.value || !job.value) return null
  return job.value.outputPayload as unknown as FluidPayload
})
const gaPayload = computed<GaPayload | null>(() => {
  if (!isGa.value || !job.value) return null
  return job.value.outputPayload as unknown as GaPayload
})
const thicknessPayload = computed<ThicknessPayload | null>(() => {
  if (!isThickness.value || !job.value) return null
  return job.value.outputPayload as unknown as ThicknessPayload
})

interface KpiTile {
  label: string
  value: string
  unit: string
  subtitle?: string
  tone: 'irf' | 'srf' | 'ped' | 'ga' | 'thickness' | 'neutral'
}

function formatNumber(n: number | undefined | null, precision = 3): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return Number(n).toPrecision(precision)
}

const kpiTiles = computed<KpiTile[]>(() => {
  if (fluidPayload.value) {
    const b = fluidPayload.value.biomarkers ?? {
      irf_mm3: 0,
      srf_mm3: 0,
      ped_mm3: 0,
      total_mm3: 0,
    }
    const totalSlices = totalBscanCount.value
    function subtitleFor(label: 'irf' | 'srf' | 'ped', value: number): string {
      if (value <= 0) return t('retinal.kpi.subtitle.notDetected')
      if (totalSlices > 0) {
        return t('retinal.kpi.subtitle.affected', {
          count: affectedBscanCount(label),
          total: totalSlices,
        })
      }
      return ''
    }
    return [
      {
        label: t('retinal.kpi.irf'),
        value: formatNumber(b.irf_mm3),
        unit: 'mm³',
        subtitle: subtitleFor('irf', b.irf_mm3 ?? 0),
        tone: 'irf',
      },
      {
        label: t('retinal.kpi.srf'),
        value: formatNumber(b.srf_mm3),
        unit: 'mm³',
        subtitle: subtitleFor('srf', b.srf_mm3 ?? 0),
        tone: 'srf',
      },
      {
        label: t('retinal.kpi.ped'),
        value: formatNumber(b.ped_mm3),
        unit: 'mm³',
        subtitle: subtitleFor('ped', b.ped_mm3 ?? 0),
        tone: 'ped',
      },
      {
        label: t('retinal.kpi.total'),
        value: formatNumber(b.total_mm3),
        unit: 'mm³',
        subtitle: t('retinal.kpi.subtitle.totalLoad'),
        tone: 'neutral',
      },
    ]
  }
  if (gaPayload.value) {
    return [
      {
        label: t('retinal.kpi.gaArea'),
        value: formatNumber(gaPayload.value.ga_area_mm2),
        unit: 'mm²',
        tone: 'ga',
      },
    ]
  }
  if (thicknessPayload.value) {
    const tp = thicknessPayload.value
    const validRatio =
      tp.total_ascans > 0
        ? t('retinal.kpi.validAscans', { valid: tp.valid_ascans, total: tp.total_ascans })
        : undefined
    return [
      {
        label: isOnl.value ? t('retinal.kpi.onlThickness') : t('retinal.kpi.prThickness'),
        value: formatNumber(tp.thickness_mean_um),
        unit: 'µm',
        subtitle: validRatio,
        tone: 'thickness',
      },
    ]
  }
  return []
})

/* -------- ETDRS sub-totals table ------------------------------------ */

interface EtdrsRow {
  label: string
  values: string[]
  /** 'circle' = cumulative central disc (1/3/6 mm). 'ring' = disjoint
   *  annulus (1-3 / 3-6 mm). Drives the Kreis/Ring section split in
   *  the rendered table. */
  type: 'circle' | 'ring'
}

/* -------- Thickness from surface_y envelope (PR / ONL) -------------- */
/**
 * 2026-06-23 — Shared per-jobId fetch of the segmentation envelope.
 * The BscanViewer + FundusOverlay already use this; the metrics view
 * leans on the same module-level cache so reading the envelope here
 * is free (no extra network hop).
 *
 * <p>Used to derive the per-ETDRS-ring mean thickness for the PR / ONL
 * tasks. The runner only emits a global thickness_mean_um in the
 * payload; per-ring breakdowns are computed SPA-side from the raw
 * surface_y data + the fundus geometry, matching the
 * raw-data-no-PNG architectural direction.
 */
const segEnvelope = useSegmentationEnvelope(computed(() => jobId.value)).envelope

/**
 * Pre-derived per-(z, x) thickness in µm, packed as Float32Array. Returns
 * null when the envelope isn't a 2-surface surface_y volume or the
 * geometry's axial spacing isn't available. Sentinel zeros indicate
 * "no detection" at that A-scan (either surface == 0 or lower < upper).
 */
const thicknessGrid = computed<{ data: Float32Array; nBscans: number; cols: number } | null>(() => {
  const env = segEnvelope.value
  if (!env || env.kind !== 'surface_y' || env.shape.length !== 3) return null
  const nSurfaces = env.shape[0] ?? 0
  const nBscans = env.shape[1] ?? 0
  const cols = env.shape[2] ?? 0
  if (nSurfaces < 2 || nBscans <= 0 || cols <= 0) return null
  const axialMm = geometry.value?.bscan?.pixel_axial_mm ?? 0
  if (axialMm <= 0) return null
  const data = env.data as Float32Array
  const upperBase = 0
  const lowerBase = nBscans * cols
  const out = new Float32Array(nBscans * cols)
  for (let z = 0; z < nBscans; z++) {
    const rowBase = z * cols
    for (let x = 0; x < cols; x++) {
      const idx = rowBase + x
      const u = data[upperBase + idx] ?? 0
      const l = data[lowerBase + idx] ?? 0
      out[idx] = (u > 0 && l > u) ? (l - u) * axialMm * 1000 : 0
    }
  }
  return { data: out, nBscans, cols }
})

/**
 * Aggregate thickness across all A-scans whose fundus distance to the
 * fovea falls in {@code [rMinMm, rMaxMm)}. Returns {sum, count} so
 * downstream code can average without losing the underlying weights.
 */
function thicknessRingAggregate(rMinMm: number, rMaxMm: number): { sum: number; count: number } {
  const grid = thicknessGrid.value
  const geo = geometry.value
  if (!grid || !geo) return { sum: 0, count: 0 }
  const fovea = geo.fovea_estimate_fundus_px
  const lateralMmPerPx = geo.fundus?.lateral_mm_per_px ?? 0
  const positions = geo.bscan_positions_fundus_px ?? []
  if (lateralMmPerPx <= 0 || positions.length === 0 || !fovea) return { sum: 0, count: 0 }
  const { data, nBscans, cols } = grid
  const maxZ = Math.min(nBscans, positions.length, geo.bscan?.dim_z_bscans ?? nBscans)
  let sum = 0
  let count = 0
  for (let z = 0; z < maxZ; z++) {
    const pos = positions.find((p) => p.z === z)
    if (!pos) continue
    const dx = pos.x2 - pos.x1
    const dy = pos.y2 - pos.y1
    const rowBase = z * cols
    for (let x = 0; x < cols; x++) {
      const t = cols > 1 ? x / (cols - 1) : 0
      const fx = pos.x1 + t * dx
      const fy = pos.y1 + t * dy
      const distMm = Math.hypot(fx - fovea.x, fy - fovea.y) * lateralMmPerPx
      if (distMm < rMinMm || distMm >= rMaxMm) continue
      const v = data[rowBase + x]
      if (v <= 0) continue
      sum += v
      count++
    }
  }
  return { sum, count }
}

const etdrsHeaders = computed<string[]>(() => {
  if (fluidPayload.value) return [t('retinal.etdrs.colRing'), t('retinal.etdrs.colIrf'), t('retinal.etdrs.colSrf'), t('retinal.etdrs.colPed'), t('retinal.etdrs.colTotal')]
  if (gaPayload.value) return [t('retinal.etdrs.colRing'), t('retinal.etdrs.colGaArea')]
  if (isThickness.value) return [t('retinal.etdrs.colRing'), t('retinal.etdrs.colThicknessUm')]
  return []
})

const etdrsRows = computed<EtdrsRow[]>(() => {
  if (fluidPayload.value) {
    const m = fluidPayload.value.etdrs_mm3
    if (m == null) return []
    const keys = ['irf', 'srf', 'ped', 'total']
    const ring13 = ringDiff(keys, m.central_3mm as unknown as Contribution, m.central_1mm as unknown as Contribution)
    const ring36 = ringDiff(keys, m.central_6mm as unknown as Contribution, m.central_3mm as unknown as Contribution)
    function row(
      label: string,
      type: 'circle' | 'ring',
      c: Contribution | { irf?: number; srf?: number; ped?: number; total?: number },
    ): EtdrsRow {
      const cc = c as unknown as Contribution
      return {
        label,
        type,
        values: [
          formatNumber(cc.irf),
          formatNumber(cc.srf),
          formatNumber(cc.ped),
          formatNumber(cc.total),
        ],
      }
    }
    return [
      row(t('retinal.etdrs.ringLabel', { mm: 1 }), 'circle', m.central_1mm ?? {}),
      row(t('retinal.etdrs.ringLabel', { mm: 3 }), 'circle', m.central_3mm ?? {}),
      row(t('retinal.etdrs.ringLabel', { mm: 6 }), 'circle', m.central_6mm ?? {}),
      row(t('retinal.etdrs.ringLabelRange', { from: 1, to: 3 }), 'ring', ring13),
      row(t('retinal.etdrs.ringLabelRange', { from: 3, to: 6 }), 'ring', ring36),
    ]
  }
  if (gaPayload.value) {
    const m = gaPayload.value.etdrs_mm2
    if (m == null) return []
    // 2026-06-22 — also derive the disjoint annular rings (1–3 mm /
    // 3–6 mm) by subtraction so GA gets the same five-row table as
    // fluid, matching the operator's selection menu.
    const ring13 = Math.max(0, (m.central_3mm ?? 0) - (m.central_1mm ?? 0))
    const ring36 = Math.max(0, (m.central_6mm ?? 0) - (m.central_3mm ?? 0))
    return [
      { label: t('retinal.etdrs.ringLabel', { mm: 1 }), type: 'circle', values: [formatNumber(m.central_1mm)] },
      { label: t('retinal.etdrs.ringLabel', { mm: 3 }), type: 'circle', values: [formatNumber(m.central_3mm)] },
      { label: t('retinal.etdrs.ringLabel', { mm: 6 }), type: 'circle', values: [formatNumber(m.central_6mm)] },
      { label: t('retinal.etdrs.ringLabelRange', { from: 1, to: 3 }), type: 'ring', values: [formatNumber(ring13)] },
      { label: t('retinal.etdrs.ringLabelRange', { from: 3, to: 6 }), type: 'ring', values: [formatNumber(ring36)] },
    ]
  }
  if (isThickness.value) {
    // 2026-06-23 — Layer-thickness ETDRS table derived in-browser from
    // the surface_y envelope + the fundus geometry. The runner doesn't
    // emit a per-ring thickness, but everything we need is already on
    // hand: per-A-scan layer thickness (lower - upper) * axial_mm and
    // each A-scan's fundus-mm position via bscan_positions_fundus_px.
    // Each row reports the MEAN µm thickness inside the ring; the
    // cumulative rows (1/3/6 mm) and the annular rows (1-3 / 3-6 mm)
    // are computed independently — for thickness they're NOT additive,
    // unlike fluid mm³ contributions.
    const r05 = thicknessRingAggregate(0, 0.5)
    const r15 = thicknessRingAggregate(0, 1.5)
    const r30 = thicknessRingAggregate(0, 3.0)
    const r0515 = thicknessRingAggregate(0.5, 1.5)
    const r1530 = thicknessRingAggregate(1.5, 3.0)
    function mean(a: { sum: number; count: number }): string {
      return a.count > 0 ? formatNumber(a.sum / a.count) : '—'
    }
    return [
      { label: t('retinal.etdrs.ringLabel', { mm: 1 }), type: 'circle', values: [mean(r05)] },
      { label: t('retinal.etdrs.ringLabel', { mm: 3 }), type: 'circle', values: [mean(r15)] },
      { label: t('retinal.etdrs.ringLabel', { mm: 6 }), type: 'circle', values: [mean(r30)] },
      { label: t('retinal.etdrs.ringLabelRange', { from: 1, to: 3 }), type: 'ring', values: [mean(r0515)] },
      { label: t('retinal.etdrs.ringLabelRange', { from: 3, to: 6 }), type: 'ring', values: [mean(r1530)] },
    ]
  }
  return []
})

/** Rows grouped by Kreis (cumulative discs) vs Ring (disjoint annuli). */
const etdrsRowGroups = computed<{ type: 'circle' | 'ring'; rows: EtdrsRow[] }[]>(() => {
  const circles = etdrsRows.value.filter((r) => r.type === 'circle')
  const rings = etdrsRows.value.filter((r) => r.type === 'ring')
  const out: { type: 'circle' | 'ring'; rows: EtdrsRow[] }[] = []
  if (circles.length) out.push({ type: 'circle', rows: circles })
  if (rings.length) out.push({ type: 'ring', rows: rings })
  return out
})

/* -------- FundusOverlay task discriminator -------------------------- */

const overlayTask = computed<FundusOverlayTask>(() => {
  const t = job.value?.task
  if (t === 'fluid' || t === 'onl' || t === 'pr' || t === 'ga') return t
  return 'fluid'
})

/* -------- ETDRS region biomarker sums (2026-06-22) ----------------- */

/**
 * Biomarker contributions per disjoint ETDRS region.
 *
 * <p>The runner reports CUMULATIVE central discs:
 * {@code central_1mm}, {@code central_3mm}, {@code central_6mm}.
 * We derive the disjoint annular rings + the bbox-corner remainder
 * by subtraction so the operator can multi-select non-overlapping
 * areas and we can sum them without double-counting.
 *
 * <ul>
 *   <li>center   = central_1mm</li>
 *   <li>ring_1_3 = central_3mm − central_1mm</li>
 *   <li>ring_3_6 = central_6mm − central_3mm</li>
 *   <li>corners  = full-volume − central_6mm</li>
 * </ul>
 *
 * <p>Contribution is a generic biomarker→value map so the same code
 * path serves fluid (irf / srf / ped / total in mm³) and GA
 * (ga_area in mm²). {@link biomarkerDefsForTask} drives the chip
 * row + units in the selection panel.
 */
type Contribution = Record<string, number>
type RegionBreakdown = Record<EtdrsRegion, Contribution>

function ringDiff(keys: string[], a: Contribution | undefined, b: Contribution | undefined): Contribution {
  const out: Contribution = {}
  for (const k of keys) {
    out[k] = Math.max(0, (a?.[k] ?? 0) - (b?.[k] ?? 0))
  }
  return out
}

interface BiomarkerDef {
  key: string
  label: string
  /** Tailwind text-color class for the chip label. */
  toneClass: string
  /** Unit string shown after the sum value. */
  unit: 'mm³' | 'mm²' | 'µm'
  /**
   * Optional mean-of-pair aggregation. When set, the chip value is
   * computed as (Σ contributions[sumKey]) / (Σ contributions[countKey])
   * across selected regions, rather than the default sum-across-regions.
   * Used for thickness where ring concatenation must average — not add —
   * per-A-scan thickness.
   */
  meanOf?: { sumKey: string; countKey: string }
}

const FLUID_BIOMARKER_DEFS: BiomarkerDef[] = [
  { key: 'irf', label: 'IRF', toneClass: 'text-cyan-700', unit: 'mm³' },
  { key: 'srf', label: 'SRF', toneClass: 'text-amber-700', unit: 'mm³' },
  { key: 'ped', label: 'PED', toneClass: 'text-fuchsia-700', unit: 'mm³' },
  { key: 'total', label: '∑', toneClass: 'text-slate-500', unit: 'mm³' },
]

const GA_BIOMARKER_DEFS: BiomarkerDef[] = [
  { key: 'ga_area', label: 'GA', toneClass: 'text-fuchsia-700', unit: 'mm²' },
]

const THICKNESS_BIOMARKER_DEFS = computed<BiomarkerDef[]>(() => [
  {
    key: 'thickness',
    label: t('retinal.etdrs.selectionLabelThickness'),
    toneClass: 'text-sky-700',
    unit: 'µm',
    meanOf: { sumKey: 'thickness_sum', countKey: 'thickness_count' },
  },
])

const selectionBiomarkerDefs = computed<BiomarkerDef[]>(() => {
  if (isFluid.value) return FLUID_BIOMARKER_DEFS
  if (isGa.value) return GA_BIOMARKER_DEFS
  if (isThickness.value) return THICKNESS_BIOMARKER_DEFS.value
  return []
})

const regionBreakdown = computed<RegionBreakdown | null>(() => {
  if (fluidPayload.value?.etdrs_mm3) {
    const e = fluidPayload.value.etdrs_mm3
    const keys = ['irf', 'srf', 'ped', 'total']
    const fullVolume: Contribution = {
      irf: fluidPayload.value.biomarkers?.irf_mm3 ?? 0,
      srf: fluidPayload.value.biomarkers?.srf_mm3 ?? 0,
      ped: fluidPayload.value.biomarkers?.ped_mm3 ?? 0,
      total: fluidPayload.value.biomarkers?.total_mm3 ?? 0,
    }
    return {
      center: ringDiff(keys, e.central_1mm as unknown as Contribution, undefined),
      ring_1_3: ringDiff(keys, e.central_3mm as unknown as Contribution, e.central_1mm as unknown as Contribution),
      ring_3_6: ringDiff(keys, e.central_6mm as unknown as Contribution, e.central_3mm as unknown as Contribution),
      corners: ringDiff(keys, fullVolume, e.central_6mm as unknown as Contribution),
    }
  }
  if (gaPayload.value?.etdrs_mm2) {
    // GA payload exposes scalars at each ring (not a per-biomarker
    // object). Lift to the shared {ga_area: number} shape so the
    // ringDiff helper + selection chips treat it uniformly.
    const e = gaPayload.value.etdrs_mm2
    const wrap = (v: number | undefined): Contribution => ({ ga_area: v ?? 0 })
    const fullVolume: Contribution = { ga_area: gaPayload.value.ga_area_mm2 ?? 0 }
    return {
      center: ringDiff(['ga_area'], wrap(e.central_1mm), undefined),
      ring_1_3: ringDiff(['ga_area'], wrap(e.central_3mm), wrap(e.central_1mm)),
      ring_3_6: ringDiff(['ga_area'], wrap(e.central_6mm), wrap(e.central_3mm)),
      corners: ringDiff(['ga_area'], fullVolume, wrap(e.central_6mm)),
    }
  }
  if (isThickness.value && thicknessGrid.value) {
    // For thickness (ONL/PR) we aggregate per-A-scan layer thickness
    // across the four disjoint ETDRS regions. The contribution stores
    // a {sum, count} pair so the cross-region rollup in selectedSum
    // can re-divide to produce a true mean — straight summation would
    // bias toward whichever region holds the largest A-scan count.
    // Corners (everything outside the 6 mm disc) uses Infinity as
    // the outer bound so the bbox edges are included.
    const wrap = (a: { sum: number; count: number }): Contribution =>
      ({ thickness_sum: a.sum, thickness_count: a.count })
    return {
      center:   wrap(thicknessRingAggregate(0,   0.5)),
      ring_1_3: wrap(thicknessRingAggregate(0.5, 1.5)),
      ring_3_6: wrap(thicknessRingAggregate(1.5, 3.0)),
      corners:  wrap(thicknessRingAggregate(3.0, Infinity)),
    }
  }
  return null
})

/**
 * Display-ready biomarker values across all currently-selected
 * regions. For sum-aggregation defs (fluid, GA) the value is the
 * straight sum of contributions; for mean-aggregation defs (thickness)
 * it is (Σ sumKey)/(Σ countKey), which preserves correct averaging
 * across regions of unequal A-scan counts. Null when nothing is
 * selected or the breakdown isn't available — the template hides
 * the summary panel in that case.
 */
const selectedSum = computed<Contribution | null>(() => {
  if (selectedEtdrsRegions.value.length === 0) return null
  const bd = regionBreakdown.value
  if (!bd) return null
  const contributions: Contribution[] = []
  for (const id of selectedEtdrsRegions.value) {
    const r = bd[id]
    if (r) contributions.push(r)
  }
  if (contributions.length === 0) return null
  const out: Contribution = {}
  for (const def of selectionBiomarkerDefs.value) {
    if (def.meanOf) {
      let s = 0
      let n = 0
      for (const c of contributions) {
        s += c[def.meanOf.sumKey] ?? 0
        n += c[def.meanOf.countKey] ?? 0
      }
      out[def.key] = n > 0 ? s / n : 0
    } else {
      let s = 0
      for (const c of contributions) s += c[def.key] ?? 0
      out[def.key] = s
    }
  }
  return out
})

/** Localised label for an ETDRS region — used in the selection chip row. */
function regionLabel(id: EtdrsRegion): string {
  return t(`retinal.fundusOverlay.region.${id}`)
}

/* -------- Header laterality long-form -------------------------------- */
/**
 * "OS · linkes Auge" / "OD · rechtes Auge". Falls back to the raw
 * laterality token when the locale doesn't carry a long form for it.
 */
const lateralityLongText = computed<string>(() => {
  const lat = job.value?.laterality
  if (!lat) return ''
  const key = `retinal.header.lateralityLong.${lat}`
  const translated = t(key)
  return translated === key ? String(lat) : translated
})

/* -------- KPI tile subtitles (design-spec hints) -------------------- */
/**
 * 2026-06-22 — count of B-scans where at least one biomarker voxel
 * exists, by label. Drives the PED card's "{count} von {total} B-Scans
 * betroffen" subtitle. Reads from the fluid payload's
 * `per_bscan_mm2[label][]` series (already populated by the cluster
 * runner). Returns 0 when the series is missing — silent degrade
 * since the subtitle is informational only.
 */
function affectedBscanCount(label: 'irf' | 'srf' | 'ped'): number {
  const series = (fluidPayload.value?.per_bscan_mm2 ?? {})[label]
  if (!Array.isArray(series)) return 0
  return series.reduce<number>((n, v) => (Number(v) > 1e-9 ? n + 1 : n), 0)
}

const totalBscanCount = computed<number>(
  () => geometry.value?.bscan?.dim_z_bscans ?? 0,
)

/**
 * 2026-06-22 — biomarker-tone → Tailwind classes for the inline
 * metric-card variant. Kept inline (not via RetinalKpiTile) so the
 * design's mockup styling (rounded-2xl, absolute left bar, larger
 * value font) lives in one place — and so the existing
 * RetinalKpiTile component, with subtly different styling, can stay
 * untouched for other call sites + the histoire story.
 */
function metricBarClass(tone: KpiTile['tone']): string {
  switch (tone) {
    case 'irf': return 'bg-cyan-400'
    case 'srf': return 'bg-amber-400'
    case 'ped': return 'bg-fuchsia-500'
    case 'ga': return 'bg-pink-500'
    case 'thickness': return 'bg-sky-500'
    case 'neutral':
    default: return 'bg-muw-blue'
  }
}
function metricLabelClass(tone: KpiTile['tone']): string {
  switch (tone) {
    case 'irf': return 'text-cyan-700'
    case 'srf': return 'text-amber-700'
    case 'ped': return 'text-fuchsia-700'
    case 'ga': return 'text-pink-700'
    case 'thickness': return 'text-sky-700'
    case 'neutral':
    default: return 'text-slate-500'
  }
}

/* -------- Display formatting helpers -------------------------------- */

function formatIsoDate(iso: string | null): string {
  if (!iso) return '—'
  // Show date + HH:MM in UTC. Operators ask for the run time the same
  // way they'd inspect a sidecar log, so we don't localise here.
  return iso.slice(0, 16).replace('T', ' ')
}

function emptyStateMessage(status: string | null | undefined): string | null {
  if (!status) return null
  if (status === 'queued') return t('retinal.empty.awaitingSidecar')
  if (status === 'preprocessing') return t('retinal.empty.preprocessing')
  if (status === 'segmenting') return t('retinal.empty.segmenting')
  if (status === 'failed') return t('retinal.empty.failed')
  return null
}

const inflightMessage = computed(() => emptyStateMessage(job.value?.status))

/* ------------------------------------------------------------- */
/* 2026-06-19 retry — operator re-dispatch of a failed job.      */
/*                                                               */
/* Posts to /retinal-jobs/{id}/retry; the store optimistically   */
/* patches status → 'remote_pending' so the SSE stream reopens   */
/* and the live indicator pops back on. Any failure stays in the */
/* loadError ref so the regular error banner surfaces it; we     */
/* don't toast here because the view's own state already         */
/* communicates the new status.                                  */
/* ------------------------------------------------------------- */
const retrying = computed<boolean>(() => !!store.retryInflight[jobId.value])
// 2026-06-25 — explicit retry feedback. Success drives a dismissable banner;
// failures go through the GlobalErrorToast. The inline loadError banner only
// renders when the job failed to load (v-else-if="loadError && !job"), so a
// retry failure on an already-open job would otherwise vanish silently.
const retryNotice = ref<boolean>(false)
async function onRetry(): Promise<void> {
  try {
    await store.retryJob(jobId.value)
    retryNotice.value = true
  } catch (e) {
    const status = (e as { status?: number }).status
    const baseMsg = t('retinal.retry.error')
    const fullMsg = status ? `${baseMsg} — HTTP ${status}` : baseMsg
    errors.push(
      e instanceof Error ? Object.assign(new Error(fullMsg), { cause: e }) : new Error(fullMsg),
      'retinal.retry',
    )
  }
}

/* ------------------------------------------------------------- */
/* 2026-06-22 — rerun-as: dispatch the same e2e + scan as a      */
/* different inference task. Operator picks from a dropdown next */
/* to the retry button; on success we navigate to the new job's  */
/* metrics view so the next page-load tracks the new SSE stream. */
/* ------------------------------------------------------------- */
// 2026-06-25 — `layers` returns the IOWA 11-surface stack + BM in
// one job (feeds the BscanViewer layers overlay + the CRT compute).
// `bm` is intentionally NOT here; `layers` already covers it.
// Mirrors ALLOWED_RERUN_TASKS in RetinalResultsApiController.java.
type RerunTask = 'fluid' | 'ga' | 'onl' | 'pr' | 'layers'
const RERUN_TASKS: readonly RerunTask[] = ['fluid', 'ga', 'onl', 'pr', 'layers'] as const
const rerunMenuOpen = ref(false)
const rerunning = computed<boolean>(() => !!store.rerunAsInflight[jobId.value])

/** Tasks the operator can pick — everything except the current job's task. */
const rerunCandidates = computed<RerunTask[]>(() => {
  const current = job.value?.task
  return RERUN_TASKS.filter((t) => t !== current)
})

/**
 * Last-action notice for the rerun-as flow. Drives a brief banner at
 * the top of the metrics view (auto-cleared on slice / jobId change).
 * Three shapes:
 *   - success: a new job was enqueued + we navigated to it
 *   - duplicate: a job for this scan + task already existed; we
 *     navigated to it instead of creating a twin
 *   - error: handled via the GlobalErrorToast; we don't double-render
 */
type RerunNotice =
  | { kind: 'success'; task: RerunTask; jobId: number }
  | { kind: 'duplicate'; task: RerunTask; jobId: number }
const rerunNotice = ref<RerunNotice | null>(null)

watch(jobId, () => { rerunNotice.value = null; retryNotice.value = false })

async function onRerunAs(task: RerunTask): Promise<void> {
  rerunMenuOpen.value = false
  let outcome: RerunNotice | null = null
  try {
    const newJobId = await store.rerunJobAs(jobId.value, task)
    outcome = { kind: 'success', task, jobId: newJobId }
    await router.push(`/retinal-jobs/${newJobId}`)
  } catch (e) {
    // 2026-06-22 — surface every rerun-as failure through the global
    // error toast so the operator gets immediate feedback. Without
    // this, a 500 / SQL-constraint / sidecar failure would silently
    // close the dropdown and leave the source view unchanged, looking
    // like a no-op.
    //
    // ApiError carries the server response on `.body`, not `.payload`.
    // Pull the existingJobId for the 409 duplicate path + the server's
    // own message text for the rest so the toast prefix is actionable.
    const apiErr = e as {
      status?: number
      body?: { existingJobId?: number; message?: string } | string | null
    }
    const body = (apiErr.body && typeof apiErr.body === 'object') ? apiErr.body : null
    const existing = body?.existingJobId
    if (typeof existing === 'number' && existing > 0) {
      outcome = { kind: 'duplicate', task, jobId: existing }
      await router.push(`/retinal-jobs/${existing}`)
    } else {
      const status = apiErr.status
      const serverMsg = body?.message ?? (typeof apiErr.body === 'string' ? apiErr.body : '')
      const baseMsg = t('retinal.rerunAs.error')
      const parts = [baseMsg]
      if (status) parts.push(`HTTP ${status}`)
      if (serverMsg) parts.push(serverMsg)
      const fullMsg = parts.join(' — ')
      errors.push(e instanceof Error
        ? Object.assign(new Error(fullMsg), { cause: e })
        : new Error(fullMsg),
      'retinal.rerunAs')
    }
  } finally {
    if (outcome) rerunNotice.value = outcome
  }
}

const showJson = ref(false)

/* ------------------------------------------------------------- */
/* Wave 2A — Real-time job-status SSE subscriber.                */
/*                                                               */
/* While the job is in an in-flight state we subscribe to the    */
/* SSE stream and re-fetch the DTO when the backend pushes a     */
/* terminal `done`. Heartbeats keep the connection up without    */
/* triggering a re-fetch — useJobStatusStream surfaces those     */
/* via the connected flag, not the status ref.                   */
/*                                                               */
/* The composable closes the stream on any of:                   */
/*   - jobId watch returning null;                               */
/*   - the enabled flag flipping false (terminal state).         */
/*   - component unmount.                                        */
/* ------------------------------------------------------------- */
const IN_FLIGHT_STATUSES = new Set([
  'remote_pending',
  'queued',
  'screening',
  'screened',
  'preprocessing',
  'segmenting',
])

const streamEnabled = computed<boolean>(() => {
  const status = job.value?.status
  return status != null && IN_FLIGHT_STATUSES.has(status)
})

const streamJobId = computed<number | null>(() =>
  streamEnabled.value ? jobId.value : null,
)

const { connected: liveConnected } = useJobStatusStream(streamJobId, {
  enabled: streamEnabled,
  onStatus: (e) => {
    // The store's DTO carries the authoritative payload (e.g.
    // metrics, artifacts). The SSE event just tells us "something
    // changed" — re-fetch when the status hits a terminal state.
    if (e.status === 'done' || e.status === 'failed') {
      void store.loadJob(jobId.value, true)
    }
  },
})
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('retinal.nav.home') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 min-w-0 px-8 py-7">
      <div class="max-w-[1200px] mx-auto">
        <p v-if="isLoading && !job" class="text-slate-500 italic" data-testid="retinal-view-loading">
          {{ t('retinal.loading') }}
        </p>

        <div
          v-else-if="loadError && !job"
          class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 text-xs text-rose-800"
          data-testid="retinal-view-error"
        >
          {{ t('retinal.failedToLoad', { error: loadError }) }}
        </div>

        <template v-else-if="job">
          <!-- ════════ Page header ════════ -->
          <div class="flex items-start justify-between gap-6 mb-5">
            <div class="min-w-0">
              <div class="text-[12.5px] text-slate-500 mb-1.5">
                {{ t('retinal.header.eventCrfLabel', { id: job.eventCrfId, jobId: job.jobId }) }}
              </div>
              <h1
                class="font-serif text-[26px] font-semibold tracking-tight text-slate-900 leading-none mb-2.5"
                data-testid="retinal-view-heading"
              >
                {{ t('retinal.header.title') }}
              </h1>
              <div class="flex items-center gap-2 flex-wrap">
                <StatusPill variant="info">{{ lateralityLongText || String(job.laterality) }}</StatusPill>
                <StatusPill v-if="job.task" variant="neutral">{{ taskLabelText }}</StatusPill>
                <StatusPill
                  v-if="job.status === 'succeeded'"
                  variant="success"
                >{{ statusLabelText }}</StatusPill>
                <StatusPill
                  v-else-if="job.status === 'failed'"
                  variant="danger"
                >{{ statusLabelText }}</StatusPill>
                <StatusPill v-else variant="warning">{{ statusLabelText }}</StatusPill>
                <!-- Wave 2A — Live indicator. Visible while the SSE
                     stream is connected (job in flight + handshake
                     done). The pulse is cosmetic; the i18n key is the
                     SR-readable label. -->
                <span
                  v-if="liveConnected"
                  class="inline-flex items-center gap-1.5 text-xs text-emerald-700"
                  data-testid="retinal-view-live-indicator"
                  :title="t('retinal.live.indicator')"
                >
                  <span aria-hidden="true" class="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                  {{ t('retinal.live.indicator') }}
                </span>
              </div>
              <div class="flex items-center gap-x-5 gap-y-1 flex-wrap mt-3 text-[12.5px] text-slate-500">
                <span>{{ t('retinal.header.modelLabel') }}
                  <span class="font-mono text-slate-700">{{ job.modelVersion ?? '—' }}</span>
                </span>
                <span class="text-slate-300">·</span>
                <span>{{ t('retinal.header.runLabel') }}
                  <span class="font-mono text-slate-700">{{ formatIsoDate(job.completedAt ?? job.enqueuedAt) }}</span>
                </span>
                <span class="text-slate-300">·</span>
                <span class="inline-flex items-center gap-2">{{ t('retinal.header.confidenceLabel') }}
                  <span class="inline-flex items-center gap-1.5">
                    <span class="w-20 h-1.5 rounded-full bg-slate-200 overflow-hidden inline-block align-middle">
                      <span
                        class="block h-full rounded-full bg-muw-blue"
                        :style="{ width: `${Math.round(Math.max(0, Math.min(1, job.confidence ?? 0)) * 100)}%` }"
                      />
                    </span>
                    <span class="font-semibold text-slate-700 tabular-nums">
                      {{ job.confidence != null ? job.confidence.toFixed(2) : '—' }}
                    </span>
                  </span>
                </span>
              </div>
            </div>
            <div class="flex items-center gap-2.5 shrink-0">
              <button
                type="button"
                class="px-3.5 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                :disabled="retrying"
                data-testid="retinal-view-retry"
                @click="onRetry"
              >
                <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M3 2v6h6M21 12a9 9 0 1 1-3-6.7L21 8" />
                </svg>
                {{ retrying ? t('retinal.retry.inflight') : t('retinal.retry.cta') }}
              </button>

              <!-- 2026-06-22 — "Re-run with different task" dropdown.
                   The button itself toggles the menu; each menu item
                   posts to /rerun-as and routes the operator to the
                   new job's metrics view. Closes on outside-click via
                   the document-level handler in onMounted. -->
              <div class="relative" data-testid="retinal-view-rerun-as">
                <button
                  type="button"
                  class="px-3.5 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                  :disabled="rerunning || rerunCandidates.length === 0"
                  :aria-expanded="rerunMenuOpen"
                  aria-haspopup="menu"
                  @click.stop="rerunMenuOpen = !rerunMenuOpen"
                >
                  <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5" />
                  </svg>
                  {{ rerunning ? t('retinal.rerunAs.inflight') : t('retinal.rerunAs.cta') }}
                  <svg class="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                </button>
                <div
                  v-if="rerunMenuOpen"
                  class="absolute right-0 mt-1.5 w-52 bg-white rounded-lg border border-slate-200 shadow-lg z-20 py-1.5"
                  role="menu"
                  data-testid="retinal-view-rerun-as-menu"
                  @click.stop
                >
                  <div class="px-3 py-1.5 text-[10px] uppercase tracking-[0.08em] font-semibold text-slate-400">
                    {{ t('retinal.rerunAs.menuHeader') }}
                  </div>
                  <button
                    v-for="task in rerunCandidates"
                    :key="`rerun-${task}`"
                    type="button"
                    role="menuitem"
                    class="w-full text-left px-3 py-1.5 text-[13px] font-medium text-slate-700 hover:bg-slate-50 inline-flex items-center justify-between"
                    :data-testid="`retinal-view-rerun-as-${task}`"
                    @click="onRerunAs(task)"
                  >
                    <span>{{ t(`retinal.task.${task}`) }}</span>
                    <span class="font-mono text-[10px] text-slate-400 uppercase">{{ task }}</span>
                  </button>
                </div>
              </div>

              <a
                href="#retinal-downloads"
                class="px-3.5 py-2 text-[13px] font-semibold bg-muw-blue text-white rounded-lg hover:bg-muw-blue-700 inline-flex items-center gap-2 shadow-[0_1px_2px_rgba(17,29,78,.18)]"
                data-testid="retinal-view-download-all"
              >
                <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
                </svg>
                {{ t('retinal.header.downloadAll') }}
              </a>
            </div>
          </div>

          <!-- 2026-06-22 — Rerun-as outcome banner. Replaces the
               previous silent close-the-dropdown behaviour: the
               operator now sees a confirmation when a new job is
               enqueued, or a "navigated to existing twin" notice
               when the dedup gate fired. The banner is dismissable
               and auto-clears on jobId change. Failures go through
               GlobalErrorToast (see onRerunAs catch). -->
          <div
            v-if="rerunNotice"
            class="mb-5 rounded-2xl border px-4 py-3 text-xs flex items-center gap-3"
            :class="rerunNotice.kind === 'duplicate'
              ? 'border-amber-200 bg-amber-50 text-amber-900'
              : 'border-emerald-200 bg-emerald-50 text-emerald-900'"
            data-testid="retinal-view-rerun-notice"
          >
            <span class="flex-1">
              <template v-if="rerunNotice.kind === 'success'">
                {{ t('retinal.rerunAs.successBanner', {
                    task: t(`retinal.task.${rerunNotice.task}`),
                    jobId: rerunNotice.jobId,
                  }) }}
              </template>
              <template v-else>
                {{ t('retinal.rerunAs.duplicateBanner', {
                    task: t(`retinal.task.${rerunNotice.task}`),
                    jobId: rerunNotice.jobId,
                  }) }}
              </template>
            </span>
            <button
              type="button"
              class="text-[11px] underline hover:no-underline"
              data-testid="retinal-view-rerun-notice-dismiss"
              @click="rerunNotice = null"
            >{{ t('common.dismiss') }}</button>
          </div>

          <!-- Retry success banner. Re-dispatch confirmation so the operator
               gets explicit feedback; failures surface via GlobalErrorToast
               (see onRetry catch). Dismissable + auto-clears on jobId change. -->
          <div
            v-if="retryNotice"
            class="mb-5 rounded-2xl border border-emerald-200 bg-emerald-50 text-emerald-900 px-4 py-3 text-xs flex items-center gap-3"
            data-testid="retinal-view-retry-notice"
          >
            <span class="flex-1">{{ t('retinal.retry.successBanner') }}</span>
            <button
              type="button"
              class="text-[11px] underline hover:no-underline"
              data-testid="retinal-view-retry-notice-dismiss"
              @click="retryNotice = false"
            >{{ t('common.dismiss') }}</button>
          </div>

          <!-- Empty / in-flight banner -->
          <div
            v-if="inflightMessage"
            class="mb-5 rounded-2xl border px-4 py-3 text-xs"
            :class="job.status === 'failed'
              ? 'border-rose-200 bg-rose-50 text-rose-900'
              : 'border-amber-200 bg-amber-50 text-amber-900'"
            data-testid="retinal-view-inflight"
          >
            {{ inflightMessage }}
          </div>
          <div
            v-else-if="!job.primaryMetric"
            class="mb-5 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900"
            data-testid="retinal-view-no-metric"
          >
            {{ t('retinal.empty.noMetric') }}
          </div>

          <!-- Slice 6 — honour-system arm badge -->
          <div v-if="armGate.showBadge.value" class="mb-4">
            <ArmBadge :arm="job.subjectArm" />
            <p class="mt-1.5 text-xs text-slate-500">
              {{ t('retinal.arm.blindedExplain') }}
            </p>
          </div>

          <!-- Slice 4 — visit-to-visit comparison panel -->
          <RetinalVisitComparison
            v-if="job.task === 'fluid' && job.status === 'done' && !armGate.hideAi.value"
            :job-id="job.jobId"
          />

          <!-- ════════ Metric cards — 4-card horizontal strip ════════ -->
          <div
            v-if="!armGate.hideAi.value && kpiTiles.length"
            class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-5"
            data-testid="retinal-view-kpis"
          >
            <div
              v-for="tile in kpiTiles"
              :key="tile.label"
              class="relative rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] pl-5 pr-4 py-3.5 overflow-hidden"
              data-testid="retinal-kpi-tile"
            >
              <span class="absolute left-0 top-0 bottom-0 w-1.5" :class="metricBarClass(tile.tone)" />
              <div class="text-[11px] font-semibold uppercase tracking-[0.08em]" :class="metricLabelClass(tile.tone)">
                {{ tile.label }}
              </div>
              <div class="flex items-baseline gap-1 mt-1">
                <span class="text-[24px] font-semibold text-slate-900 tabular-nums leading-none">{{ tile.value }}</span>
                <span class="text-[12px] text-slate-400">{{ tile.unit }}</span>
              </div>
              <div v-if="tile.subtitle" class="text-[11px] text-slate-400 mt-1.5">{{ tile.subtitle }}</div>
            </div>
          </div>
          <div
            v-else-if="!armGate.hideAi.value"
            class="mb-5 bg-slate-50 border border-dashed border-slate-300 rounded-2xl px-4 py-3 text-xs text-slate-500"
            data-testid="retinal-view-kpis"
          >
            {{ t('retinal.empty.noKpi') }}
          </div>

          <!-- ════════ Viewer row: en-face (5) + B-scan (7) ════════ -->
          <div class="grid grid-cols-1 lg:grid-cols-12 gap-5 mb-5 items-stretch">
            <!-- En-face fundus locator -->
            <section
              class="lg:col-span-5 rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] flex flex-col"
              data-testid="retinal-view-fundus"
            >
              <div class="flex items-center justify-between px-5 pt-4 pb-3 border-b border-slate-100">
                <h3 class="text-[12px] font-semibold uppercase tracking-[0.1em] text-muw-blue">
                  {{ t('retinal.fundus.header') }}
                </h3>
                <span class="text-[11px] text-slate-400">{{ t('retinal.fundus.bscanPosition') }}</span>
              </div>
              <div class="p-4 flex-1 flex items-center">
                <div
                  v-if="!job.fundusUrl || !geometry"
                  class="aspect-square w-full bg-slate-100 border border-dashed border-slate-300 rounded-xl flex items-center justify-center text-xs text-slate-500"
                >
                  {{ t('retinal.empty.fundusNotAvailable') }}
                </div>
                <div v-else class="w-full">
                  <FundusOverlay
                    :fundus-url="job.fundusUrl"
                    :geometry="geometry"
                    :payload="job.outputPayload"
                    :task="overlayTask"
                    :laterality="job.laterality"
                    :hovered-bscan-z="currentBscanZ"
                    :job-id="job.jobId"
                    :artifact-names="armGate.hideAi.value ? [] : job.artifactNames"
                    :selected-regions="selectedEtdrsRegions"
                    @hover-bscan="onHoverBscan"
                    @update:selected-regions="(r: EtdrsRegion[]) => selectedEtdrsRegions = r"
                  />
                </div>
              </div>

              <!-- 2026-06-22 — selection summary. Lives in the fundus
                   column right below the overlay so the chips +
                   biomarker sum visually attach to the regions the
                   operator just clicked. Hidden when nothing is
                   selected — a hint occupies the same vertical space
                   so the column height doesn't jump on first click. -->
              <div
                v-if="selectedSum"
                class="px-5 py-3 border-t border-slate-100 bg-muw-sky-50/60 flex flex-col gap-2"
                data-testid="retinal-view-etdrs-selection"
              >
                <div class="flex items-center gap-2 flex-wrap min-w-0">
                  <span class="text-[11px] uppercase tracking-[0.08em] font-semibold text-muw-blue">
                    {{ t('retinal.etdrs.selectionHeader') }}
                  </span>
                  <button
                    v-for="r in selectedEtdrsRegions"
                    :key="`chip-${r}`"
                    type="button"
                    class="inline-flex items-center gap-1.5 rounded-full bg-white border border-muw-sky-200 text-muw-sky-700 text-[11px] font-semibold px-2.5 py-0.5 hover:bg-muw-sky-50"
                    :data-testid="`retinal-view-etdrs-chip-${r}`"
                    @click="selectedEtdrsRegions = selectedEtdrsRegions.filter((x) => x !== r)"
                  >
                    {{ regionLabel(r) }}
                    <span aria-hidden="true" class="text-slate-400">×</span>
                  </button>
                  <button
                    type="button"
                    class="text-[11px] text-slate-500 hover:text-muw-blue underline ml-1"
                    data-testid="retinal-view-etdrs-clear"
                    @click="selectedEtdrsRegions = []"
                  >
                    {{ t('retinal.etdrs.selectionClear') }}
                  </button>
                </div>
                <!-- Task-aware biomarker chips. Fluid shows IRF /
                     SRF / PED / ∑ in mm³; GA shows GA in mm². The
                     last biomarker in the list gets pushed to the
                     end with `ml-auto` so the sum aligns right. -->
                <div class="flex items-center gap-x-4 gap-y-1 flex-wrap text-[12.5px] font-mono tabular-nums text-slate-700">
                  <span
                    v-for="(def, idx) in selectionBiomarkerDefs"
                    :key="def.key"
                    :class="idx === selectionBiomarkerDefs.length - 1 && selectionBiomarkerDefs.length > 1 ? 'ml-auto' : ''"
                  >
                    <span :class="['font-semibold', def.toneClass]">{{ def.label }}</span>
                    {{ formatNumber(selectedSum[def.key]) }}<template v-if="idx === selectionBiomarkerDefs.length - 1"> {{ def.unit }}</template>
                  </span>
                </div>
              </div>
              <div
                v-else
                class="px-5 py-3 border-t border-slate-100 text-[11px] text-slate-400"
              >
                {{ t('retinal.etdrs.selectionHint') }}
              </div>
            </section>

            <!-- B-Scan navigator -->
            <div class="lg:col-span-7 flex flex-col">
              <BscanViewer
                v-if="job.bscanDcmUrl && (geometry?.bscan?.dim_z_bscans ?? 0) > 0"
                :bscan-dcm-url="job.bscanDcmUrl"
                :n-bscans="geometry!.bscan!.dim_z_bscans"
                :model-value="currentBscanZ"
                :job-id="job.jobId"
                class="flex-1"
                @update:model-value="(z) => hoveredBscanZ = z"
              />
              <div
                v-else
                class="flex-1 rounded-2xl border border-slate-200 bg-slate-50 flex items-center justify-center text-xs text-slate-500 italic min-h-[300px]"
              >
                {{ t('retinal.empty.bscanNotAvailable') }}
              </div>
            </div>
          </div>

          <!-- ════════ ETDRS + Downloads row ════════ -->
          <div class="grid grid-cols-1 lg:grid-cols-12 gap-5 mb-5">
            <section
              v-if="etdrsRows.length"
              class="lg:col-span-7 rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] overflow-clip"
              data-testid="retinal-view-etdrs"
            >
              <div class="px-5 pt-4 pb-3 border-b border-slate-100">
                <h3 class="text-[12px] font-semibold uppercase tracking-[0.1em] text-muw-blue">
                  {{ t('retinal.etdrs.header') }}
                </h3>
              </div>
              <DenseTable :bordered="false">
                <template v-for="(group, gIdx) in etdrsRowGroups" :key="group.type">
                  <!-- Section sub-header. The very first group's row
                       carries the unit/column headers in the right-aligned
                       cells (so the page doesn't need a dedicated
                       column-header row above). Subsequent group headers
                       only carry the section label. -->
                  <tr
                    class="bg-slate-50/70 border-t border-slate-100 text-left text-slate-400 text-[11px] uppercase tracking-[0.06em]"
                    :data-testid="`retinal-etdrs-section-${group.type}`"
                  >
                    <th
                      scope="col"
                      class="px-5 py-2 font-semibold text-slate-500 tracking-[0.08em]"
                    >
                      {{ group.type === 'circle' ? t('retinal.etdrs.sectionCircle') : t('retinal.etdrs.sectionRing') }}
                    </th>
                    <th
                      v-for="h in etdrsHeaders.slice(1)"
                      :key="h"
                      scope="col"
                      class="px-5 py-2 font-semibold text-right"
                    >
                      <span v-if="gIdx === 0">{{ h }}</span>
                    </th>
                  </tr>
                  <tr
                    v-for="row in group.rows"
                    :key="row.label"
                    data-testid="retinal-etdrs-row"
                    class="border-t border-slate-100"
                  >
                    <td class="px-5 py-3 font-medium text-slate-700 text-[13px]">{{ row.label }}</td>
                    <td
                      v-for="(value, idx) in row.values"
                      :key="`${row.label}-${idx}`"
                      class="px-5 py-3 text-[12.5px] tabular-nums font-mono text-right text-slate-700"
                    >
                      {{ value }}
                    </td>
                  </tr>
                </template>
              </DenseTable>
            </section>

            <RetinalArtifactList
              id="retinal-downloads"
              class="lg:col-span-5"
              :class="etdrsRows.length ? '' : 'lg:col-span-12'"
              :job-id="job.jobId"
              :artifact-names="downloadableArtifacts"
              :companion-names="job.companionNames"
            />
          </div>

          <!-- Raw output payload (collapsible) -->
          <section
            class="rounded-2xl border border-slate-200 bg-white shadow-[0_1px_2px_rgba(17,29,78,.04)] overflow-clip mb-6"
            data-testid="retinal-view-json"
          >
            <button
              type="button"
              class="w-full px-5 py-3.5 flex items-center justify-between text-[12px] font-semibold uppercase tracking-[0.1em] text-muw-blue hover:bg-slate-50"
              :aria-expanded="showJson"
              @click="showJson = !showJson"
            >
              <span>{{ t('retinal.jsonTree.rawPayloadHeader') }}</span>
              <span class="text-slate-400">{{ showJson ? '▾' : '▸' }}</span>
            </button>
            <div v-if="showJson" class="p-5 bg-slate-50 overflow-auto max-h-[40rem] border-t border-slate-100">
              <JsonTree :value="job.outputPayload" />
            </div>
          </section>

          <RouterLink
            v-if="job.eventCrfId"
            to="/subjects"
            class="inline-flex items-center gap-2 text-[13px] font-medium text-muw-blue hover:text-muw-blue-700"
          >
            <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M19 12H5M12 19l-7-7 7-7" />
            </svg>
            {{ t('retinal.nav.backToSubjects') }}
          </RouterLink>
        </template>
      </div>
    </main>
  </div>
</template>
