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
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GeometryJson } from '@/api/retinal'
import { artifactUrl } from '@/api/retinal'
import { useSegmentationEnvelope } from '@/composables/useSegmentationEnvelope'

const { t } = useI18n()

export type FundusOverlayTask = 'fluid' | 'onl' | 'pr' | 'ga'

/**
 * 2026-06-22 — interactive ETDRS-region IDs the operator can
 * multi-select to scope the biomarker quantification.
 *
 * <ul>
 *   <li>{@code center}    — central 1 mm disc.</li>
 *   <li>{@code ring_1_3}  — annular ring between 1 mm and 3 mm.</li>
 *   <li>{@code ring_3_6}  — annular ring between 3 mm and 6 mm,
 *       visually clipped to the scan bbox since the area outside is
 *       not quantified.</li>
 *   <li>{@code corners}   — area inside the scan bbox and outside the
 *       6 mm circle (all four bbox corners; they always toggle as
 *       one logical region).</li>
 * </ul>
 */
export type EtdrsRegion = 'center' | 'ring_1_3' | 'ring_3_6' | 'corners'

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
  /**
   * 2026-06-22 — currently-selected ETDRS regions for biomarker
   * quantification. Owned by the parent (the metrics view) so the
   * selection persists across slice scrubbing + survives a fundus
   * remount. Default: empty (no selection).
   */
  selectedRegions?: EtdrsRegion[]
}

const props = withDefaults(defineProps<Props>(), {
  hoveredBscanZ: null,
  jobId: null,
  artifactNames: () => [],
  selectedRegions: () => [],
})

const emit = defineEmits<{
  (e: 'hoverBscan', z: number | null): void
  (e: 'update:selectedRegions', regions: EtdrsRegion[]): void
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
 * 2026-06-22 — current-B-scan position line tracking the BscanViewer's
 * slider via the {@code hoveredBscanZ} prop.
 *
 * <p>Linear interpolation between the scan bbox's top edge (slider z=0)
 * and its bottom edge (slider z=n_bscans-1). We deliberately do NOT
 * use {@code geometry.bscan_positions_fundus_px} here even though the
 * per-slice positions are nominally available: Heidelberg multi-pass
 * acquisitions pack additional pass entries into oct-converter's
 * bscan_data, which the preprocess sidecar emits without de-duplication.
 * The resulting positions list has more entries than {@code dim_z_bscans}
 * AND the entries are not ordered consistently with slice z, so picking
 * the polyline by z routinely lands on a different scan's geometry.
 *
 * <p>The linear-interp approach assumes raster acquisition order
 * (top→bottom of fundus) which is the Spectralis default. For non-raster
 * scan patterns (star, radial) this would need per-slice geometry — but
 * those patterns aren't in scope for the current pipeline.
 */
const currentBscanLine = computed<BscanLine | null>(() => {
  if (internalHoverZ.value == null) return null
  const bbox = scanBbox.value
  if (!bbox || bbox.width <= 0 || bbox.height <= 0) return null
  const nBscans = Number(props.geometry.bscan?.dim_z_bscans ?? 0)
  if (nBscans <= 0) return null
  const z = Math.max(0, Math.min(nBscans - 1, internalHoverZ.value))
  const frac = nBscans <= 1 ? 0 : z / (nBscans - 1)
  const y = bbox.y + frac * bbox.height
  return {
    z,
    x1: bbox.x,
    y1: y,
    x2: bbox.x + bbox.width,
    y2: y,
    stroke: '#7fd0ff',
    opacity: 0.95,
    dominantLabel: '',
    dominantValue: 0,
  }
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

/* ════════════════════════════════════════════════════════════════════
 * Thickness heatmap (PR / ONL), 2026-06-23.
 *
 * <p>Architectural directive: stop deriving projection_*.png artifacts;
 * render the en-face heatmap directly from the raw segmentation envelope
 * the cluster ships. For surface_y tasks (PR + ONL) the envelope packs
 * two surfaces (upper + lower) shaped {@code (n_surfaces, n_bscans, cols)}
 * float32. Per-A-scan layer thickness = {@code (lower - upper) * pixel_axial_mm * 1000}
 * µm, clipped to a sensible clinical range, then mapped through a viridis
 * approximation onto an off-DOM canvas that we draw into the SVG via
 * {@code <foreignObject>} positioned at scan_bbox.
 * ════════════════════════════════════════════════════════════════════ */

const jobIdRef = computed<number | null>(() => props.jobId ?? null)
const { envelope: segEnvelope } = useSegmentationEnvelope(jobIdRef)

const isLayerTask = computed<boolean>(
  () => props.task === 'pr' || props.task === 'onl',
)

const isGaTask = computed<boolean>(() => props.task === 'ga')

const thicknessCanvasEl = ref<HTMLCanvasElement | null>(null)
const gaCanvasEl = ref<HTMLCanvasElement | null>(null)

/** Color stops for the µm → RGBA ramp (viridis-flavoured). */
const THICKNESS_STOPS: ReadonlyArray<readonly [number, [number, number, number]]> = [
  [0.00, [68, 1, 84]],
  [0.33, [59, 82, 139]],
  [0.66, [33, 145, 140]],
  [1.00, [253, 231, 37]],
] as const

/** Clinical range for retinal-layer thickness; values outside clamp. */
const THICKNESS_MIN_UM = 0
const THICKNESS_MAX_UM = 150

function rampColor(t: number): [number, number, number] {
  const x = Math.max(0, Math.min(1, t))
  for (let i = 0; i < THICKNESS_STOPS.length - 1; i++) {
    const lo = THICKNESS_STOPS[i]
    const hi = THICKNESS_STOPS[i + 1]
    if (x <= hi[0]) {
      const span = hi[0] - lo[0]
      const f = span > 0 ? (x - lo[0]) / span : 0
      return [
        Math.round(lo[1][0] + f * (hi[1][0] - lo[1][0])),
        Math.round(lo[1][1] + f * (hi[1][1] - lo[1][1])),
        Math.round(lo[1][2] + f * (hi[1][2] - lo[1][2])),
      ]
    }
  }
  return THICKNESS_STOPS[THICKNESS_STOPS.length - 1][1] as [number, number, number]
}

function paintThicknessHeatmap(): void {
  const canvas = thicknessCanvasEl.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  // 2026-06-23 — keep per-A-scan blocks crisp when CSS-scaled up onto
  // the fundus. putImageData() doesn't honour this flag, but defensive
  // for any future composite paint paths into the same context.
  ctx.imageSmoothingEnabled = false

  const env = segEnvelope.value
  if (!isLayerTask.value || !env || env.kind !== 'surface_y' || env.shape.length !== 3) {
    canvas.width = 1
    canvas.height = 1
    ctx.clearRect(0, 0, 1, 1)
    return
  }
  const nSurfaces = env.shape[0] ?? 0
  const nBscans = env.shape[1] ?? 0
  const cols = env.shape[2] ?? 0
  if (nSurfaces < 2 || nBscans === 0 || cols === 0) {
    canvas.width = 1
    canvas.height = 1
    ctx.clearRect(0, 0, 1, 1)
    return
  }
  // Pixel-to-µm conversion via the per-A-scan axial spacing from the
  // geometry. Falls back to a Heidelberg-typical 3.87 µm when the
  // geometry doesn't carry the spacing (shouldn't happen in practice).
  const axialMmPerPx = props.geometry.bscan?.pixel_axial_mm ?? 0.00387
  const data = env.data as Float32Array
  const sliceStride = nBscans * cols
  const upperBase = 0
  const lowerBase = sliceStride

  canvas.width = cols
  canvas.height = nBscans
  const img = ctx.createImageData(cols, nBscans)
  for (let z = 0; z < nBscans; z++) {
    const rowOffset = z * cols
    for (let x = 0; x < cols; x++) {
      const upper = data[upperBase + rowOffset + x] ?? 0
      const lower = data[lowerBase + rowOffset + x] ?? 0
      // Sentinel value 0 in either surface means "no detection at this
      // A-scan" — render transparent so the underlying fundus reads
      // through.
      const px = (z * cols + x) * 4
      if (upper <= 0 || lower <= 0 || lower < upper) {
        img.data[px + 3] = 0
        continue
      }
      const thicknessUm = (lower - upper) * axialMmPerPx * 1000
      const clamped = Math.max(THICKNESS_MIN_UM, Math.min(THICKNESS_MAX_UM, thicknessUm))
      const fraction = (clamped - THICKNESS_MIN_UM) / (THICKNESS_MAX_UM - THICKNESS_MIN_UM)
      const [r, g, b] = rampColor(fraction)
      img.data[px] = r
      img.data[px + 1] = g
      img.data[px + 2] = b
      img.data[px + 3] = 220 // ~86% opacity
    }
  }
  ctx.putImageData(img, 0, 0)
}

watch(
  [segEnvelope, isLayerTask],
  () => { paintThicknessHeatmap() },
  { flush: 'post' },
)
onMounted(paintThicknessHeatmap)

/**
 * 2026-06-23 — En-face GA presence heatmap, derived directly from the
 * binary_2d segmentation envelope the cluster ships (no PNG artifact).
 * For each (z, x) A-scan with envelope[z][x] != 0, paint a single
 * fuchsia-tinted pixel into a (cols × n_bscans) canvas; transparent
 * elsewhere. The canvas is positioned at scan_bbox via the
 * <foreignObject> below so the per-A-scan projection collapses onto
 * the fundus plane the same way the projection_ga.png did, minus the
 * intermediate PNG artifact.
 */
const GA_FILL: readonly [number, number, number] = [217, 70, 239] as const // fuchsia-500

function paintGaHeatmap(): void {
  const canvas = gaCanvasEl.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.imageSmoothingEnabled = false

  const env = segEnvelope.value
  if (!isGaTask.value || !env || env.kind !== 'binary_2d' || env.shape.length !== 2) {
    canvas.width = 1
    canvas.height = 1
    ctx.clearRect(0, 0, 1, 1)
    return
  }
  const nBscans = env.shape[0] ?? 0
  const cols = env.shape[1] ?? 0
  if (nBscans === 0 || cols === 0) {
    canvas.width = 1
    canvas.height = 1
    ctx.clearRect(0, 0, 1, 1)
    return
  }
  const data = env.data as Uint8Array
  canvas.width = cols
  canvas.height = nBscans
  const img = ctx.createImageData(cols, nBscans)
  for (let z = 0; z < nBscans; z++) {
    const rowBase = z * cols
    for (let x = 0; x < cols; x++) {
      if ((data[rowBase + x] ?? 0) === 0) continue
      const px = (z * cols + x) * 4
      img.data[px] = GA_FILL[0]
      img.data[px + 1] = GA_FILL[1]
      img.data[px + 2] = GA_FILL[2]
      img.data[px + 3] = 220
    }
  }
  ctx.putImageData(img, 0, 0)
}

watch(
  [segEnvelope, isGaTask],
  () => { paintGaHeatmap() },
  { flush: 'post' },
)
onMounted(paintGaHeatmap)

/* ════════════════════════════════════════════════════════════════════
 * Interactive ETDRS region selection (2026-06-22).
 * ════════════════════════════════════════════════════════════════════ */

/** Lookup table from `mm` → fundus-pixel radius. */
const r1Px = computed(() => mmToPx(0.5))
const r3Px = computed(() => mmToPx(1.5))
const r6Px = computed(() => mmToPx(3.0))

/** Unique ids for the SVG `<clipPath>` / `<mask>` defs — namespaced
 *  by jobId so two FundusOverlays on the same DOM can't collide. */
const defsId = computed(() => `fundus-${props.jobId ?? 'novj'}`)

/**
 * Annulus SVG path between two concentric circles centred at (cx, cy).
 * Drawn with two full circles in the SAME winding direction; the
 * caller renders this with {@code fill-rule="evenodd"} so the inner
 * disc is punched out.
 */
function annulusPath(cx: number, cy: number, rOuter: number, rInner: number): string {
  return [
    `M ${cx},${cy - rOuter}`,
    `A ${rOuter},${rOuter} 0 1,0 ${cx},${cy + rOuter}`,
    `A ${rOuter},${rOuter} 0 1,0 ${cx},${cy - rOuter} Z`,
    `M ${cx},${cy - rInner}`,
    `A ${rInner},${rInner} 0 1,0 ${cx},${cy + rInner}`,
    `A ${rInner},${rInner} 0 1,0 ${cx},${cy - rInner} Z`,
  ].join(' ')
}

const ring13Path = computed(() => annulusPath(fovea.value.x, fovea.value.y, r3Px.value, r1Px.value))
const ring36Path = computed(() => annulusPath(fovea.value.x, fovea.value.y, r6Px.value, r3Px.value))

/* ── Selection + hover state ─────────────────────────────────────── */

const hoveredRegion = ref<EtdrsRegion | null>(null)
const selectedSet = computed<Set<EtdrsRegion>>(() => new Set(props.selectedRegions))

function isSelected(id: EtdrsRegion): boolean {
  return selectedSet.value.has(id)
}

function toggleRegion(id: EtdrsRegion): void {
  const next = new Set(selectedSet.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  emit('update:selectedRegions', Array.from(next))
}

/**
 * Region fill driven by selection + hover. Selected regions get a
 * persistent translucent MUW-sky fill; hovered (but not yet selected)
 * regions get a fainter preview tint. Unselected + un-hovered regions
 * stay near-transparent — the underlying fundus + projection PNG
 * read through cleanly.
 */
function regionFill(id: EtdrsRegion): string {
  if (isSelected(id)) return 'rgba(95, 180, 229, 0.32)' // muw-sky-500 @ 32%
  if (hoveredRegion.value === id) return 'rgba(95, 180, 229, 0.15)' // hover preview
  return 'rgba(255, 255, 255, 0.001)' // near-zero but non-zero so hit-tests fire
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
           2026-06-23 — image-rendering: pixelated. Was: 'auto', which
           let the browser interpolate between A-scans + B-scans into a
           visibly blurry halo at the lesion edge (per-A-scan PED voxels
           dissolved into a fade). The clinical reading expects sharp
           per-A-scan edges since each pixel IS one A-scan's projection.

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
          style="image-rendering: pixelated;"
        />
      </template>
      <image
        v-else-if="projectionUrl && !isLayerTask && !isGaTask"
        data-testid="enface-projection"
        :href="projectionUrl"
        :x="scanBbox.x"
        :y="scanBbox.y"
        :width="scanBbox.width"
        :height="scanBbox.height"
        preserveAspectRatio="none"
        opacity="0.85"
        style="image-rendering: pixelated;"
      />

      <!-- 2026-06-23 — In-canvas thickness heatmap for PR / ONL.
           No projection_*.png artifact is produced any more; we
           pull the surface_y segmentation envelope directly + paint
           a viridis-flavoured colour ramp into a canvas inside a
           <foreignObject> aligned to scan_bbox. The canvas pixel
           buffer is (cols, n_bscans) and CSS-stretches uniformly
           inside the foreignObject so it covers the same scan area
           the fluid projection used to. -->
      <foreignObject
        v-if="isLayerTask"
        data-testid="enface-thickness-heatmap"
        :x="scanBbox.x"
        :y="scanBbox.y"
        :width="scanBbox.width"
        :height="scanBbox.height"
      >
        <canvas
          ref="thicknessCanvasEl"
          style="width:100%;height:100%;display:block;image-rendering:pixelated;"
        />
      </foreignObject>

      <!-- 2026-06-23 — En-face GA heatmap. Sibling foreignObject to
           the thickness one above; only one renders at a time since
           isLayerTask + isGaTask are mutually exclusive. Same
           positioning + sizing convention so the per-A-scan binary
           mask collapses onto the scan_bbox identically to how the
           projection_ga.png used to. -->
      <foreignObject
        v-if="isGaTask"
        data-testid="enface-ga-heatmap"
        :x="scanBbox.x"
        :y="scanBbox.y"
        :width="scanBbox.width"
        :height="scanBbox.height"
      >
        <canvas
          ref="gaCanvasEl"
          style="width:100%;height:100%;display:block;image-rendering:pixelated;"
        />
      </foreignObject>

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

      <!-- 3. Interactive ETDRS regions (2026-06-22).
           Four mutually-disjoint regions cover the entire scan bbox.
           Each one is clickable (toggle) + hover-previews. The
           parent (RetinalMetricsView) holds the selection set, sums
           biomarker contributions per region, and renders the total
           in the ETDRS card. -->
      <defs>
        <!-- Clip the 3-6 mm ring to the scan bbox: area outside the bbox is
             not quantified by the model, so the operator can't select it
             through this region. -->
        <clipPath :id="`${defsId}-bboxClip`">
          <rect
            :x="scanBbox.x"
            :y="scanBbox.y"
            :width="scanBbox.width"
            :height="scanBbox.height"
          />
        </clipPath>
        <!-- Mask for the "corners" region: white inside the bbox, black
             inside the 6 mm disc → the disc punches a hole out of the
             bbox rectangle and only the four corners remain visible /
             hittable. -->
        <mask :id="`${defsId}-cornersMask`">
          <rect
            :x="scanBbox.x"
            :y="scanBbox.y"
            :width="scanBbox.width"
            :height="scanBbox.height"
            fill="white"
          />
          <circle :cx="fovea.x" :cy="fovea.y" :r="r6Px" fill="black" />
        </mask>
      </defs>

      <g data-testid="etdrs-rings">
        <!-- Region: corners (rendered FIRST so dashed circles draw on top) -->
        <rect
          data-testid="etdrs-region-corners"
          :x="scanBbox.x"
          :y="scanBbox.y"
          :width="scanBbox.width"
          :height="scanBbox.height"
          :mask="`url(#${defsId}-cornersMask)`"
          :fill="regionFill('corners')"
          stroke="none"
          style="cursor: pointer"
          @mouseenter="hoveredRegion = 'corners'"
          @mouseleave="hoveredRegion = null"
          @click="toggleRegion('corners')"
        >
          <title>{{ t('retinal.fundusOverlay.region.corners') }}</title>
        </rect>

        <!-- Region: ring 3-6 mm (clipped to bbox) -->
        <g :clip-path="`url(#${defsId}-bboxClip)`">
          <path
            data-testid="etdrs-region-ring-3-6"
            :d="ring36Path"
            fill-rule="evenodd"
            :fill="regionFill('ring_3_6')"
            stroke="none"
            style="cursor: pointer"
            @mouseenter="hoveredRegion = 'ring_3_6'"
            @mouseleave="hoveredRegion = null"
            @click="toggleRegion('ring_3_6')"
          >
            <title>{{ t('retinal.fundusOverlay.region.ring_3_6') }}</title>
          </path>
        </g>

        <!-- Region: ring 1-3 mm -->
        <path
          data-testid="etdrs-region-ring-1-3"
          :d="ring13Path"
          fill-rule="evenodd"
          :fill="regionFill('ring_1_3')"
          stroke="none"
          style="cursor: pointer"
          @mouseenter="hoveredRegion = 'ring_1_3'"
          @mouseleave="hoveredRegion = null"
          @click="toggleRegion('ring_1_3')"
        >
          <title>{{ t('retinal.fundusOverlay.region.ring_1_3') }}</title>
        </path>

        <!-- Region: center 1 mm disc -->
        <circle
          data-testid="etdrs-region-center"
          :cx="fovea.x"
          :cy="fovea.y"
          :r="r1Px"
          :fill="regionFill('center')"
          stroke="none"
          style="cursor: pointer"
          @mouseenter="hoveredRegion = 'center'"
          @mouseleave="hoveredRegion = null"
          @click="toggleRegion('center')"
        >
          <title>{{ t('retinal.fundusOverlay.region.center') }}</title>
        </circle>

        <!-- Dashed reference circles, painted on top of the hit-test
             layer so they read as clear visual anchors. Stroke opacity
             dims for non-active regions to reduce clutter. -->
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
            pointer-events="none"
            opacity="0.85"
          />
          <text
            :x="fovea.x + ring.px + 4"
            :y="fovea.y"
            :fill="ETDRS_STROKE"
            font-size="11"
            dominant-baseline="middle"
            pointer-events="none"
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
      <!-- 2026-06-22 — current-B-scan position line. Drawn from the
           RAW geometry positions (not the biomarker-filtered list), so
           it appears for every slice the BscanViewer can show — not
           only the slices where the segmenter found IRF/SRF/PED. -->
      <g v-if="currentBscanLine" data-testid="bscan-lines">
        <line
          :x1="currentBscanLine.x1"
          :y1="currentBscanLine.y1"
          :x2="currentBscanLine.x2"
          :y2="currentBscanLine.y2"
          stroke="#7fd0ff"
          stroke-width="2"
          stroke-linecap="round"
          vector-effect="non-scaling-stroke"
          :data-testid="`bscan-line-${currentBscanLine.z}`"
          pointer-events="none"
          opacity="0.95"
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
