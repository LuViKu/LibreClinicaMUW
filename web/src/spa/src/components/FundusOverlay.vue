<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — Registration-correct fundus overlay.
 *
 * Renders the {@code fundus.png} companion under an SVG layer that
 * carries every annotation in the fundus-pixel coordinate system the
 * preprocess sidecar baked into {@code geometry.json}. Layers, drawn
 * bottom-to-top:
 *
 *   1. Fundus image — {@code <image>} with intrinsic fundus-px size.
 *   2. Scan-area outline — blue rectangle from
 *      {@code geometry.scan_bbox_fundus_px}.
 *   3. ETDRS rings — three dashed circles (0.5 / 1.5 / 3.0 mm radius,
 *      i.e. the 1 / 3 / 6 mm diameter rings clinicians read) centred
 *      on the fovea estimate.
 *   4. Per-B-scan biomarker indicators — one polyline per B-scan, the
 *      dominant biomarker's colour weighted by intensity. Hoverable.
 *   5. Fovea crosshair — small plus sign at the estimate, with a
 *      {@code <title>} tooltip distinguishing the MVP heuristic from a
 *      future "true" detection.
 *
 * <p>The SVG sets {@code viewBox="0 0 width height"} +
 * {@code preserveAspectRatio="xMidYMid meet"} + {@code width="100%"
 * height="100%"} so the browser handles all the scaling — overlays
 * stay registered no matter how the host laid out the container.
 *
 * <p>Why per-B-scan polylines instead of a single canvas heat map? An
 * SVG can hit-test individual elements for {@code @mouseenter}, and
 * the per-B-scan count for the studies we serve is in the low
 * hundreds — well below the SVG redraw budget.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GeometryJson } from '@/api/retinal'
import { artifactUrl } from '@/api/retinal'
import { BIOMARKER_COLORS } from './retinalPalette'

const { t } = useI18n()

export type FundusOverlayTask = 'fluid' | 'onl' | 'pr' | 'ga'

interface Props {
  /** Absolute URL of the {@code fundus.png} companion. */
  fundusUrl: string
  /** Parsed {@code geometry.json}. */
  geometry: GeometryJson
  /** Wave 2 output_payload (per-task shape). */
  payload: Record<string, unknown>
  /** Task discriminator selecting the per-B-scan indicator strategy. */
  task: FundusOverlayTask
  /** OD / OS — surfaces in the title/tooltip for context. */
  laterality: 'OD' | 'OS'
  /** External hover index — when the per-B-scan trace highlights a slice. */
  hoveredBscanZ?: number | null
  /**
   * Job id — used to build the artifact URL for the en-face biomarker
   * projection PNG (Wave 5, 2026-06-19). Optional so existing callsites
   * (tests, storybook) keep compiling.
   */
  jobId?: number | null
  /**
   * List of per-job artifact filenames returned in
   * {@code RetinalJobDetail.artifactNames}. The component scans this for
   * a {@code projection_<task>.png} entry and renders it as an SVG
   * {@code <image>} stretched over {@code scan_bbox_fundus_px}.
   */
  artifactNames?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  hoveredBscanZ: null,
  jobId: null,
  artifactNames: () => [],
})

// 2026-06-22 — emit removed alongside the per-B-scan stripe layer.
// FundusOverlay no longer originates hover events; the per-B-scan
// trace chart owns that signal now via the hoveredBscanZ prop.
defineEmits<{
  (e: 'hoverBscan', z: number | null): void
}>()

/* ------- Constants (kept colocated so tests can lift them) ----------- */

/** Scan-box outline colour — Tailwind blue-400, faint enough not to fight the fovea crosshair. */
const SCAN_BBOX_STROKE = '#60a5fa'

/** ETDRS ring stroke — amber-300, dashed; matches the dashed pattern Spectralis uses. */
const ETDRS_STROKE = '#facc15'

/** Fovea crosshair — slate-900 for max contrast on light fundus images. */
const FOVEA_STROKE = '#0f172a'

/** Diameter (mm) of the three ETDRS rings the clinician reads. */
const ETDRS_DIAMETERS_MM = [1, 3, 6] as const

/* ------- Derived geometry ------------------------------------------- */

const viewBox = computed(
  () => `0 0 ${props.geometry.fundus.width_px} ${props.geometry.fundus.height_px}`,
)

const lateralMmPerPx = computed(() => props.geometry.fundus.lateral_mm_per_px)

/**
 * Translate an mm distance into fundus pixels. The lateral and slice
 * mm-per-px usually differ on Spectralis volumes — for circular
 * ETDRS rings we use the lateral scale (rings are clinically
 * circular by convention; the slight aspect compression is shown
 * elsewhere via the B-scan polylines).
 */
function mmToPx(mm: number): number {
  return mm / lateralMmPerPx.value
}

/** Three radii, in fundus pixels. */
const etdrsRadiiPx = computed<Array<{ mm: number; px: number }>>(() =>
  ETDRS_DIAMETERS_MM.map((dMm) => ({ mm: dMm, px: mmToPx(dMm / 2) })),
)

const fovea = computed(() => props.geometry.fovea_estimate_fundus_px)

const scanBbox = computed(() => props.geometry.scan_bbox_fundus_px)

/* ------- En-face biomarker projection (Wave 5, 2026-06-19) ----------- */

/**
 * Per-task expected artifact filename. Runners write the projection
 * PNG into the same dir the rest of the segmentation outputs land in;
 * the Java backend lists every file in {@code bscan_masks_dir} into
 * {@code RetinalJobDetail.artifactNames}, so we just check membership.
 */
const PROJECTION_FILENAMES: Record<string, string> = {
  fluid: 'projection_fluid.png',
  ga: 'projection_ga.png',
  onl: 'projection_onl.png',
  pr: 'projection_pr.png',
}

const projectionUrl = computed<string | null>(() => {
  const expected = PROJECTION_FILENAMES[String(props.task)]
  if (!expected) return null
  if (!props.jobId || !props.artifactNames?.includes(expected)) return null
  return artifactUrl(props.jobId, expected)
})

/* ------- Per-biomarker fluid toggles (Wave 5.1, 2026-06-19) --------- */

/**
 * For the fluid task the runner now also writes one PNG per
 * biomarker (IRF / SRF / PED) alongside the composite projection PNG.
 * The SPA lets the operator toggle each layer independently — the
 * composite stays as a fallback when an older runner image is still
 * deployed and the per-biomarker artifacts aren't present.
 */
const FLUID_BIOMARKERS = ['irf', 'srf', 'ped'] as const
type FluidBiomarker = (typeof FLUID_BIOMARKERS)[number]

const FLUID_BIOMARKER_FILENAMES: Record<FluidBiomarker, string> = {
  irf: 'projection_fluid_irf.png',
  srf: 'projection_fluid_srf.png',
  ped: 'projection_fluid_ped.png',
}

/** RGB swatch for the toggle UI, kept in sync with projection.py. */
const FLUID_BIOMARKER_SWATCH: Record<FluidBiomarker, string> = {
  irf: '#38bdf8',  // sky-400
  srf: '#fb923c',  // orange-400
  ped: '#d946ef',  // fuchsia-500
}

const perBiomarkerUrls = computed<Record<FluidBiomarker, string | null>>(() => {
  const empty = { irf: null, srf: null, ped: null }
  if (props.task !== 'fluid' || !props.jobId) return empty
  const out: Record<FluidBiomarker, string | null> = { ...empty }
  for (const bm of FLUID_BIOMARKERS) {
    const fn = FLUID_BIOMARKER_FILENAMES[bm]
    if (props.artifactNames?.includes(fn)) {
      out[bm] = artifactUrl(props.jobId, fn)
    }
  }
  return out
})

/** True when at least one per-biomarker PNG is available — toggle UI shows. */
const hasPerBiomarkerProjections = computed<boolean>(
  () => Object.values(perBiomarkerUrls.value).some((v) => v != null),
)

/**
 * Per-biomarker visibility state. Defaults to all-on so existing
 * RetinalMetricsView renders pick up the new toggles without a config
 * change. Persisted to {@code sessionStorage} so navigating between
 * jobs in the same session keeps the operator's preferred layer mix.
 */
const STORAGE_KEY = 'fundus-overlay-biomarker-toggles'
function loadTogglesFromStorage(): Record<FluidBiomarker, boolean> {
  if (typeof window === 'undefined') return { irf: true, srf: true, ped: true }
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return { irf: true, srf: true, ped: true }
    const parsed = JSON.parse(raw) as Partial<Record<FluidBiomarker, boolean>>
    return {
      irf: parsed.irf ?? true,
      srf: parsed.srf ?? true,
      ped: parsed.ped ?? true,
    }
  } catch {
    return { irf: true, srf: true, ped: true }
  }
}
const biomarkerVisible = ref<Record<FluidBiomarker, boolean>>(loadTogglesFromStorage())
watch(
  biomarkerVisible,
  (val) => {
    if (typeof window !== 'undefined') {
      try {
        window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(val))
      } catch {
        /* sessionStorage may be unavailable (private mode); silently ignore */
      }
    }
  },
  { deep: true },
)


/* ------- Per-B-scan indicator computation ---------------------------- */

interface BscanLine {
  z: number
  x1: number
  y1: number
  x2: number
  y2: number
  stroke: string
  opacity: number
  dominantLabel: string
  dominantValue: number
}

/**
 * 2026-06-19 — flatten the raw bscan positions into clean horizontal
 * lines that span the scan bbox exactly. The .e2e file carries the
 * actual (slightly tilted) B-scan endpoints, but operators report a
 * cleaner UI when each indicator runs perfectly horizontally + matches
 * the visible scan rectangle width. We keep each B-scan's actual y
 * midpoint so the vertical position still reflects where that slice
 * was acquired; only the small lateral tilt + endpoint drift is
 * removed.
 */
function flattenToBboxRect(
  polylines: GeometryJson['bscan_positions_fundus_px'],
  bbox: { x: number; y: number; width: number; height: number },
): GeometryJson['bscan_positions_fundus_px'] {
  if (!bbox || bbox.width <= 0) return polylines
  const xLeft = bbox.x
  const xRight = bbox.x + bbox.width
  return polylines.map((p) => {
    const yMid = (p.y1 + p.y2) / 2
    return { z: p.z, x1: xLeft, y1: yMid, x2: xRight, y2: yMid }
  })
}

const bscanLines = computed<BscanLine[]>(() => {
  const raw = props.geometry.bscan_positions_fundus_px ?? []
  const polylines = flattenToBboxRect(raw, scanBbox.value)
  if (props.task === 'fluid') {
    return computeFluidBscanLines(polylines, props.payload)
  }
  if (props.task === 'ga') {
    return computeGaBscanLines(polylines, props.payload)
  }
  // onl / pr — no per-B-scan biomarker overlay; render thin neutral guides
  // so the operator can still see scan coverage on the fundus.
  return polylines.map((p) => ({
    z: p.z,
    x1: p.x1,
    y1: p.y1,
    x2: p.x2,
    y2: p.y2,
    stroke: '#94a3b8', // slate-400
    opacity: 0.35,
    dominantLabel: '—',
    dominantValue: 0,
  }))
})

function computeFluidBscanLines(
  polylines: GeometryJson['bscan_positions_fundus_px'],
  payload: Record<string, unknown>,
): BscanLine[] {
  // Defensive extraction — Wave 2 may emit empty arrays if the
  // segmentation produced no fluid voxels at all.
  const per = (payload['per_bscan_mm2'] ?? {}) as {
    irf?: number[]
    srf?: number[]
    ped?: number[]
  }
  const irf = per.irf ?? []
  const srf = per.srf ?? []
  const ped = per.ped ?? []
  const maxAcross = Math.max(
    ...irf,
    ...srf,
    ...ped,
    0, // guard against -Infinity when all arrays empty
  )
  const denom = maxAcross > 0 ? maxAcross : 1
  // 2026-06-22 round 9 — drop lines whose biomarker presence is
  // exactly zero across all three channels. Operator review:
  // rendering every B-scan's polyline (97 lines per casebook,
  // most of them transparent) still left the per-B-scan
  // mouse-hit areas (stroke-width=8 transparent overlay) in place,
  // and adjacent zero-value lines visually merged into a continuous
  // block when the projection PNG was absent. Filtering them out
  // collapses the layer to just the slices the segmenter actually
  // fired on, which reads as discrete stripes again.
  return polylines
    .filter((p) => {
      const z = p.z
      return (irf[z] ?? 0) > 0 || (srf[z] ?? 0) > 0 || (ped[z] ?? 0) > 0
    })
    .map((p) => {
      const z = p.z
      const i = irf[z] ?? 0
      const s = srf[z] ?? 0
      const d = ped[z] ?? 0
      let label = 'irf'
      let value = i
      let stroke: string = BIOMARKER_COLORS.irf
      if (s > value) {
        label = 'srf'
        value = s
        stroke = BIOMARKER_COLORS.srf
      }
      if (d > value) {
        label = 'ped'
        value = d
        stroke = BIOMARKER_COLORS.ped
      }
      const opacity = Math.max(0, Math.min(1, value / denom))
      return {
        z,
        x1: p.x1,
        y1: p.y1,
        x2: p.x2,
        y2: p.y2,
        stroke,
        opacity,
        dominantLabel: label.toUpperCase(),
        dominantValue: value,
      }
    })
}

function computeGaBscanLines(
  polylines: GeometryJson['bscan_positions_fundus_px'],
  payload: Record<string, unknown>,
): BscanLine[] {
  const per = (payload['per_bscan_mm2'] ?? []) as number[]
  const max = Math.max(...per, 0)
  const denom = max > 0 ? max : 1
  // Same drop-empty rule as the fluid path — only render the
  // slices the segmenter actually fired on.
  return polylines
    .filter((p) => (per[p.z] ?? 0) > 0)
    .map((p) => {
      const z = p.z
      const v = per[z] ?? 0
      const intensity = Math.max(0, Math.min(1, v / denom))
      // Magenta-to-amber ramp keyed off intensity. Cold areas drift
      // toward amber (low GA); hot areas saturate toward magenta-pink.
      const stroke = intensity > 0.5 ? '#ec4899' : '#fbbf24' // pink-500 / amber-400
      return {
        z,
        x1: p.x1,
        y1: p.y1,
        x2: p.x2,
        y2: p.y2,
        stroke,
        opacity: 0.25 + intensity * 0.75,
        dominantLabel: 'GA',
        dominantValue: v,
      }
    })
}

/* ------- Hover handling --------------------------------------------- */

const internalHoverZ = ref<number | null>(null)

// Mirror the external `hoveredBscanZ` prop into the local highlight so
// hovering the PerBscanTrace chart highlights the fundus polyline.
watch(
  () => props.hoveredBscanZ,
  (next) => {
    internalHoverZ.value = next ?? null
  },
)

// 2026-06-22 — onLineEnter / onLineLeave / isHovered removed alongside
// the per-B-scan stripe layer. Hover-driven highlighting now comes
// only from the per-B-scan trace chart via the hoveredBscanZ prop.

/**
 * 2026-06-22 round 9 — single hovered B-scan line (only when the
 * per-B-scan trace chart's mouse hover is over a slice the
 * segmenter actually fired on). Returns null otherwise so the
 * fundus overlay stays clean by default.
 */
const hoveredLine = computed<BscanLine | null>(() => {
  if (internalHoverZ.value == null) return null
  return bscanLines.value.find((l) => l.z === internalHoverZ.value) ?? null
})

/* ------- Labels ------------------------------------------------------ */

/**
 * Fovea tooltip text — distinguishes the MVP heuristic from a real
 * detection so future model improvements are visibly different.
 */
const foveaTooltip = computed(() => {
  const source = fovea.value.source ?? 'unknown'
  return t('retinal.fundusOverlay.foveaTooltip', { source })
})

/** Ring labels for the three ETDRS circles ("1 mm" / "3 mm" / "6 mm"). */
function ringLabel(diameterMm: number): string {
  return t('retinal.etdrs.ringLabel', { mm: diameterMm })
}
</script>

<template>
  <div
    class="relative w-full h-full bg-slate-900 rounded-muw overflow-clip"
    data-testid="fundus-overlay"
  >
    <!-- Per-biomarker visibility chips — only when the runner emitted
         the per-biomarker PNGs. Positioned over the top-right of the
         fundus so the operator can show / hide each layer without
         leaving the viewer. -->
    <div
      v-if="hasPerBiomarkerProjections"
      data-testid="biomarker-toggles"
      class="absolute top-2 right-2 z-10 flex gap-1.5 bg-slate-900/70 backdrop-blur-sm rounded-muw px-2 py-1.5 shadow-md"
    >
      <button
        v-for="bm in FLUID_BIOMARKERS"
        :key="`toggle-${bm}`"
        type="button"
        :data-testid="`biomarker-toggle-${bm}`"
        :aria-pressed="biomarkerVisible[bm]"
        :title="t('retinal.biomarkerToggle.tooltip', { name: bm.toUpperCase() })"
        class="flex items-center gap-1.5 px-2 py-1 rounded text-xs font-medium transition-opacity"
        :class="biomarkerVisible[bm] ? 'opacity-100 bg-slate-700/60 text-white' : 'opacity-50 bg-slate-800/40 text-slate-300 line-through'"
        @click="biomarkerVisible[bm] = !biomarkerVisible[bm]"
      >
        <span
          class="inline-block w-2.5 h-2.5 rounded-sm"
          :style="{ backgroundColor: FLUID_BIOMARKER_SWATCH[bm] }"
          aria-hidden="true"
        />
        {{ bm.toUpperCase() }}
      </button>
    </div>

    <svg
      class="block w-full h-full"
      :viewBox="viewBox"
      preserveAspectRatio="xMidYMid meet"
      role="img"
      :aria-label="t('retinal.fundusOverlay.aria', { laterality })"
    >
      <!-- 1. Fundus image -->
      <!--
        2026-06-19 — removed crossorigin="anonymous". The artifact
        endpoint at /pages/api/v1/retinal-jobs/{id}/artifacts/fundus.png
        is auth-gated by guardSession; an anonymous CORS request omits
        the JSESSIONID cookie and the backend returns 401 → the browser
        silently fails the image load. The SVG renders empty (just the
        bg-slate-900 wrapper) and the operator sees a black square with
        the overlay primitives painted on top. Since the SPA is served
        from the same origin as the API (Vite proxy in dev, single
        domain in prod), the credential-bearing same-origin request is
        what we want — no CORS needed.
      -->
      <image
        :href="fundusUrl"
        :width="geometry.fundus.width_px"
        :height="geometry.fundus.height_px"
        x="0"
        y="0"
        preserveAspectRatio="none"
      />

      <!-- 1b. En-face biomarker projection (Wave 5) — stretched to bbox.
           NB: default image-rendering (auto) lets the browser interpolate
           smoothly between B-scan rows so the per-A-scan PED region reads
           as a continuous blob across the slice direction. The data IS
           per-A-scan, but B-scans are sparsely placed on the fundus
           (~10 px apart) so without interpolation the visual gap between
           slices dominates and the projection looks like stripes.

           Wave 5.1 (2026-06-19): when the runner has emitted per-biomarker
           PNGs (one per IRF / SRF / PED), render each as a toggleable
           layer. Fall back to the single composite PNG when only the
           older artifact is present. -->
      <template v-if="hasPerBiomarkerProjections">
        <image
          v-for="bm in FLUID_BIOMARKERS"
          v-show="biomarkerVisible[bm] && perBiomarkerUrls[bm]"
          :key="`projection-${bm}`"
          :data-testid="`enface-projection-${bm}`"
          :href="perBiomarkerUrls[bm] ?? ''"
          :x="scanBbox.x"
          :y="scanBbox.y"
          :width="scanBbox.width"
          :height="scanBbox.height"
          preserveAspectRatio="none"
          opacity="0.85"
        />
      </template>
      <image
        v-else-if="projectionUrl"
        data-testid="enface-projection"
        :href="projectionUrl"
        :x="scanBbox.x"
        :y="scanBbox.y"
        :width="scanBbox.width"
        :height="scanBbox.height"
        preserveAspectRatio="none"
        opacity="0.85"
      />

      <!-- 2. Scan-area outline -->
      <rect
        data-testid="scan-bbox"
        :x="scanBbox.x"
        :y="scanBbox.y"
        :width="scanBbox.width"
        :height="scanBbox.height"
        :stroke="SCAN_BBOX_STROKE"
        stroke-width="1.5"
        fill="none"
        vector-effect="non-scaling-stroke"
      />

      <!-- 3. ETDRS rings -->
      <g data-testid="etdrs-rings">
        <template v-for="ring in etdrsRadiiPx" :key="`ring-${ring.mm}`">
          <circle
            :cx="fovea.x"
            :cy="fovea.y"
            :r="ring.px"
            :stroke="ETDRS_STROKE"
            stroke-width="1"
            stroke-dasharray="4 4"
            fill="none"
            vector-effect="non-scaling-stroke"
          />
          <text
            :x="fovea.x + ring.px + 4"
            :y="fovea.y"
            :fill="ETDRS_STROKE"
            font-size="11"
            dominant-baseline="middle"
            class="select-none"
          >{{ ringLabel(ring.mm) }}</text>
        </template>
      </g>

      <!-- 4. Hover-only B-scan indicator. 2026-06-22 round 9 follow-up
           — operator review: rendering one stripe per detected B-scan
           was clinically misleading because the stripes span the
           entire bbox width, suggesting the biomarker is present
           across the whole A-scan range when in practice it's
           localised. Without the projection PNG (per-A-scan
           fidelity), the safer default is to render NOTHING on the
           fundus by default. We still keep a single thin highlight
           line for the operator-hovered B-scan so the per-B-scan
           trace chart's hover handoff still has a visual cue on the
           fundus. -->
      <g v-if="!projectionUrl && hoveredLine" data-testid="bscan-lines">
        <line
          :x1="hoveredLine.x1"
          :y1="hoveredLine.y1"
          :x2="hoveredLine.x2"
          :y2="hoveredLine.y2"
          :stroke="hoveredLine.stroke"
          stroke-width="2"
          stroke-linecap="round"
          vector-effect="non-scaling-stroke"
          :data-testid="`bscan-line-${hoveredLine.z}`"
          :data-dominant="hoveredLine.dominantLabel"
          pointer-events="none"
          opacity="0.9"
        />
      </g>

      <!-- 5. Fovea crosshair -->
      <g data-testid="fovea-crosshair">
        <title>{{ foveaTooltip }}</title>
        <line
          :x1="fovea.x - 6"
          :y1="fovea.y"
          :x2="fovea.x + 6"
          :y2="fovea.y"
          :stroke="FOVEA_STROKE"
          stroke-width="1.5"
          vector-effect="non-scaling-stroke"
        />
        <line
          :x1="fovea.x"
          :y1="fovea.y - 6"
          :x2="fovea.x"
          :y2="fovea.y + 6"
          :stroke="FOVEA_STROKE"
          stroke-width="1.5"
          vector-effect="non-scaling-stroke"
        />
        <circle
          :cx="fovea.x"
          :cy="fovea.y"
          r="1.5"
          :fill="FOVEA_STROKE"
        />
      </g>
    </svg>
  </div>
</template>
