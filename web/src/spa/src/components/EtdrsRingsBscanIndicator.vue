<script setup lang="ts">
/**
 * 2026-06-29 — ETDRS-ring eccentricity indicator for the B-scan viewer.
 *
 * For each visible ring (central 1 mm, 3 mm, 6 mm), draws two vertical
 * markers on the B-scan SVG at the A-scan columns where the ring's
 * circumference intersects this slice.
 *
 * <p>The B-scan covers one horizontal cross-section through the volume.
 * Assuming the slice plane sits at (z = currentZ) and the fovea is at
 * (z = foveaZ, x = foveaX), then for a ring of radius {@code r} mm
 * the intersection with the slice plane is at lateral mm-distance
 *
 *   ±√(r² − (Δz · pixelSliceMm)²)
 *
 * from the fovea's lateral position, where Δz = currentZ − foveaZ.
 * When |Δz · sliceMm| ≥ r the ring lies entirely outside the slice and
 * we don't render its markers.
 *
 * <p>Fovea position: the segmentation envelope's payload may carry an
 * {@code etdrs_center} object (FluidPayload.etdrs_center.{bscan_z, ascan_x}).
 * For tasks without etdrs_center we fall back to (n_bscans/2, cols/2)
 * which assumes the volume was acquired with the fovea at the centre —
 * true for the vast majority of nAMD scans, but the operator can
 * eye-check via the labels.
 *
 * <p>Visual: tick marks at the SVG bottom + thin dotted vertical lines
 * spanning the canvas height. Labels (1mm / 3mm / 6mm) flank the
 * outermost-visible ring on each side.
 *
 * <p>Mounted at the same viewBox/bbox as the seg-overlay so the marker
 * x-coordinate maps to A-scan column directly.
 */
import { computed } from 'vue'

interface PixelSpacing { axialMm: number; lateralMm: number }
interface ImageDims { rows: number; cols: number }
type SliceCenter = { bscan_z?: number; ascan_x?: number } | null

interface Props {
  /** Total B-scan count. */
  nBscans: number
  /** Current slice index (0-based). */
  currentZ: number
  /** DICOM image dims for the viewBox sizing. */
  imageDims: ImageDims | null
  /** Pixel spacing in mm — only the lateral component drives the rings. */
  pixelSpacing: PixelSpacing | null
  /** mm per B-scan in the slice axis (from geometry.json). Not yet wired through. */
  pixelSliceMm?: number
  /** Optional ETDRS center pulled from the fluid task payload. */
  etdrsCenter?: SliceCenter
  /** Inline style positioning the SVG at the B-scan bbox. */
  bboxStyle: Record<string, string>
  /** Off / on toggle from the parent. */
  visible: boolean
}

const props = withDefaults(defineProps<Props>(), {
  pixelSliceMm: 0,
  etdrsCenter: null,
})

const RINGS_MM = [
  { r: 0.5, label: '1 mm', stroke: '#fbbf24', strong: false },  // central 1 mm disc → radius 0.5 mm
  { r: 1.5, label: '3 mm', stroke: '#fbbf24', strong: true },   // central 3 mm ring → radius 1.5 mm
  { r: 3.0, label: '6 mm', stroke: '#fbbf24', strong: false },  // central 6 mm ring → radius 3.0 mm
] as const

interface Marker {
  /** A-scan column (image-pixel x). */
  x: number
  /** Distance-from-fovea label (e.g. "1 mm"). */
  label: string
  /** Stroke colour. */
  stroke: string
  /** Heavier stroke for the most-clinically-used 3 mm ring. */
  strong: boolean
}

const markers = computed<Marker[]>(() => {
  if (!props.visible) return []
  if (!props.imageDims || !props.pixelSpacing) return []
  const cols = props.imageDims.cols
  const lateralMm = props.pixelSpacing.lateralMm
  if (lateralMm <= 0 || cols <= 0) return []

  // Fovea (z, x) — payload or centre fallback.
  const foveaZ = props.etdrsCenter?.bscan_z ?? Math.floor(props.nBscans / 2)
  const foveaX = props.etdrsCenter?.ascan_x ?? Math.floor(cols / 2)

  // mm per B-scan slice — without geometry.json access we assume isotropic
  // mm-per-px in z to mm-per-px lateral / 3 (typical 1024×N×512 dense cubes
  // have slice spacing ~3× lateral spacing). Operators get the labelled
  // chips regardless; the per-slice fall-off accuracy improves once
  // pixelSliceMm is wired through.
  const sliceMm = props.pixelSliceMm > 0 ? props.pixelSliceMm : lateralMm * 3
  const dzMm = (props.currentZ - foveaZ) * sliceMm

  const out: Marker[] = []
  for (const ring of RINGS_MM) {
    const inside = ring.r * ring.r - dzMm * dzMm
    if (inside <= 0) continue
    const lateralDist = Math.sqrt(inside)
    const leftMm = -lateralDist
    const rightMm = lateralDist
    const leftX = foveaX + leftMm / lateralMm
    const rightX = foveaX + rightMm / lateralMm
    if (leftX >= 0 && leftX <= cols) {
      out.push({ x: leftX, label: ring.label, stroke: ring.stroke, strong: ring.strong })
    }
    if (rightX >= 0 && rightX <= cols && Math.abs(rightX - leftX) > 0.5) {
      out.push({ x: rightX, label: ring.label, stroke: ring.stroke, strong: ring.strong })
    }
  }
  return out
})

const cols = computed(() => props.imageDims?.cols ?? 0)
const rows = computed(() => props.imageDims?.rows ?? 0)
</script>

<template>
  <svg
    v-if="markers.length > 0"
    data-testid="etdrs-rings-bscan-indicator"
    :viewBox="`0 0 ${cols} ${rows}`"
    :style="{ ...bboxStyle, pointerEvents: 'none' }"
    preserveAspectRatio="none"
    class="absolute"
  >
    <g v-for="(m, i) in markers" :key="`etdrs-${i}-${m.x.toFixed(0)}`">
      <!-- Dotted vertical span -->
      <line
        :x1="m.x"
        :x2="m.x"
        :y1="0"
        :y2="rows"
        :stroke="m.stroke"
        :stroke-width="m.strong ? 1.5 : 1"
        stroke-dasharray="3 5"
        stroke-opacity="0.65"
        vector-effect="non-scaling-stroke"
      />
      <!-- Tick at top -->
      <line
        :x1="m.x"
        :x2="m.x"
        :y1="0"
        :y2="rows * 0.025"
        :stroke="m.stroke"
        :stroke-width="m.strong ? 2.5 : 1.8"
        vector-effect="non-scaling-stroke"
      />
      <!-- Label near top -->
      <text
        :x="m.x"
        :y="rows * 0.06"
        :fill="m.stroke"
        font-size="14"
        text-anchor="middle"
        style="font-family: ui-sans-serif, system-ui, sans-serif; font-weight: 600;"
        :opacity="m.strong ? 1 : 0.8"
      >{{ m.label }}</text>
    </g>
  </svg>
</template>
