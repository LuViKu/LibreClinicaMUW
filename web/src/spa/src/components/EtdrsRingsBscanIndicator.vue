<script setup lang="ts">
/**
 * 2026-06-29 — ETDRS-ring eccentricity indicator for the B-scan viewer.
 *
 * For each visible ring (central 1 mm, 3 mm, 6 mm), draws two vertical
 * markers on the B-scan at the A-scan columns where the ring's
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
 * <p>2026-06-29 redesign — vertical lines + ticks stay as SVG (with
 * vector-effect="non-scaling-stroke" to survive the per-axis stretch),
 * but labels render as absolutely-positioned HTML so the type isn't
 * distorted by the SVG viewBox's xy-independent scale.
 *
 * <p>Used by both the correction fullscreen and the nAMD inline OCT
 * viewer — same overlay slot contract on BscanViewer.
 */
import { computed } from 'vue'

interface PixelSpacing { axialMm: number; lateralMm: number }
interface ImageDims { rows: number; cols: number }
type SliceCenter = { bscan_z?: number; ascan_x?: number } | null

interface Props {
  nBscans: number
  currentZ: number
  imageDims: ImageDims | null
  pixelSpacing: PixelSpacing | null
  /** mm per B-scan in the slice axis. Falls back to lateralMm × 3 (typical Heidelberg). */
  pixelSliceMm?: number
  etdrsCenter?: SliceCenter
  bboxStyle: Record<string, string>
  visible: boolean
}

const props = withDefaults(defineProps<Props>(), {
  pixelSliceMm: 0,
  etdrsCenter: null,
})

const RINGS_MM = [
  { r: 0.5, label: '1 mm' },   // central 1 mm disc — radius 0.5 mm
  { r: 1.5, label: '3 mm' },   // central 3 mm ring — radius 1.5 mm
  { r: 3.0, label: '6 mm' },   // central 6 mm ring — radius 3.0 mm
] as const

interface Marker {
  /** A-scan column in image-pixel space (used for SVG x). */
  xImage: number
  /** Fraction across the canvas (0..1). HTML labels use width × this. */
  xFrac: number
  label: string
}

const markers = computed<Marker[]>(() => {
  if (!props.visible) return []
  if (!props.imageDims || !props.pixelSpacing) return []
  const cols = props.imageDims.cols
  const lateralMm = props.pixelSpacing.lateralMm
  if (lateralMm <= 0 || cols <= 0) return []

  const foveaZ = props.etdrsCenter?.bscan_z ?? Math.floor(props.nBscans / 2)
  const foveaX = props.etdrsCenter?.ascan_x ?? Math.floor(cols / 2)

  // 2026-06-29 — slice spacing default. The DICOM stack doesn't carry
  // it (PixelSpacing is the in-plane axial × lateral pair), so when no
  // explicit pixelSliceMm prop arrives we estimate from the canonical
  // Heidelberg cube geometry: physical volume width ≈ physical volume
  // depth (≈ 6 mm × 6 mm at the standard "dense" preset). That gives
  //   sliceMm ≈ (cols × lateralMm) / nBscans
  // → for a 49-slice × 1024-col cube at 0.0058 mm/px lateral, sliceMm
  // ≈ 0.122 mm. The earlier `lateralMm × 3` heuristic was 3× too small
  // and made the 1 mm ring intersect ~28 slices instead of the
  // expected ~8 (±4 slices around the fovea-centred B-scan).
  const sliceMm = props.pixelSliceMm > 0
    ? props.pixelSliceMm
    : (cols * lateralMm) / Math.max(1, props.nBscans)
  const dzMm = (props.currentZ - foveaZ) * sliceMm

  const out: Marker[] = []
  for (const ring of RINGS_MM) {
    const inside = ring.r * ring.r - dzMm * dzMm
    if (inside <= 0) continue
    const lateralDist = Math.sqrt(inside)
    const leftX = foveaX - lateralDist / lateralMm
    const rightX = foveaX + lateralDist / lateralMm
    if (leftX >= 0 && leftX <= cols) {
      out.push({ xImage: leftX, xFrac: leftX / cols, label: ring.label })
    }
    if (rightX >= 0 && rightX <= cols && Math.abs(rightX - leftX) > 0.5) {
      out.push({ xImage: rightX, xFrac: rightX / cols, label: ring.label })
    }
  }
  return out
})

const cols = computed(() => props.imageDims?.cols ?? 0)
const rows = computed(() => props.imageDims?.rows ?? 0)
</script>

<template>
  <!-- Wrapper sits at the same bbox as the seg-overlay; pointer-events
       are off so the editing layer above still receives clicks. -->
  <div
    v-if="markers.length > 0"
    data-testid="etdrs-rings-bscan-indicator"
    :style="{ ...bboxStyle, pointerEvents: 'none' }"
    class="absolute"
  >
    <!-- SVG layer: dotted vertical lines + ticks. -->
    <svg
      :viewBox="`0 0 ${cols} ${rows}`"
      preserveAspectRatio="none"
      class="absolute inset-0 w-full h-full"
    >
      <g v-for="(m, i) in markers" :key="`etdrs-line-${i}-${m.xImage.toFixed(0)}`">
        <line
          :x1="m.xImage"
          :x2="m.xImage"
          :y1="0"
          :y2="rows"
          stroke="#fbbf24"
          stroke-width="1.2"
          stroke-dasharray="3 5"
          stroke-opacity="0.6"
          vector-effect="non-scaling-stroke"
        />
        <line
          :x1="m.xImage"
          :x2="m.xImage"
          :y1="0"
          :y2="rows * 0.02"
          stroke="#fbbf24"
          stroke-width="2"
          vector-effect="non-scaling-stroke"
        />
      </g>
    </svg>
    <!-- HTML labels layer: untouched by the SVG's per-axis stretch, so
         text remains legible at any image aspect ratio. -->
    <div class="absolute inset-0 w-full h-full">
      <span
        v-for="(m, i) in markers"
        :key="`etdrs-label-${i}-${m.xImage.toFixed(0)}`"
        :style="{
          left: `${m.xFrac * 100}%`,
          top: '4px',
        }"
        class="absolute -translate-x-1/2 px-1.5 py-0.5 rounded-md bg-slate-900/70 text-amber-300 text-[10px] font-semibold tracking-tight"
      >{{ m.label }}</span>
    </div>
  </div>
</template>
