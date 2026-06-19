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
}

const props = withDefaults(defineProps<Props>(), {
  hoveredBscanZ: null,
})

const emit = defineEmits<{
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

const bscanLines = computed<BscanLine[]>(() => {
  const polylines = props.geometry.bscan_positions_fundus_px ?? []
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
  return polylines.map((p) => {
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
  return polylines.map((p) => {
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

function onLineEnter(z: number) {
  internalHoverZ.value = z
  emit('hoverBscan', z)
}

function onLineLeave() {
  internalHoverZ.value = null
  emit('hoverBscan', null)
}

function isHovered(z: number): boolean {
  return internalHoverZ.value === z
}

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

      <!-- 4. Per-B-scan indicators -->
      <g data-testid="bscan-lines">
        <g
          v-for="line in bscanLines"
          :key="`bscan-${line.z}`"
          @mouseenter="onLineEnter(line.z)"
          @mouseleave="onLineLeave"
        >
          <!-- Transparent hit area, wider stroke for hover detection. -->
          <line
            :x1="line.x1"
            :y1="line.y1"
            :x2="line.x2"
            :y2="line.y2"
            stroke="transparent"
            stroke-width="8"
            stroke-linecap="round"
            class="cursor-pointer"
          />
          <!-- Visible stroke; brighter + thicker when hovered. -->
          <line
            :x1="line.x1"
            :y1="line.y1"
            :x2="line.x2"
            :y2="line.y2"
            :stroke="line.stroke"
            :stroke-width="isHovered(line.z) ? 3 : 1.5"
            :style="{ strokeOpacity: isHovered(line.z) ? 1 : line.opacity }"
            stroke-linecap="round"
            vector-effect="non-scaling-stroke"
            :data-testid="`bscan-line-${line.z}`"
            :data-dominant="line.dominantLabel"
            pointer-events="none"
          />
        </g>
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
