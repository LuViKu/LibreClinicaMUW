<script setup lang="ts">
/**
 * 2026-06-26 — IOWA layer-segmentation correction overlay.
 *
 * Port of the design's `oct-layer-edit.js` HEYEX-style editor, adapted to
 * Vue 3 + the SPA's image-pixel coordinate system. Mounts ON TOP of the
 * BscanViewer's cornerstone canvas using the SAME viewBox + bbox as the
 * existing surface_y overlay, so the operator's polyline lands exactly
 * on the cornerstone-rendered retinal bands at any container size.
 *
 * Three correction modes per active layer:
 *   - `shift`  drag the whole row up/down
 *   - `free`   freehand draw to overwrite an x-stretch
 *   - `points` HEYEX-style control points (Catmull-Rom curve through them);
 *              drag, shift-click multi-select, rubber-band selection, Delete
 *              to remove, double-click to add.
 *
 * State sits in IMAGE-PIXEL coords (x = A-scan column 0..cols-1, y = row
 * 0..rows-1) so the per-A-scan dense rows shipped to the backend
 * (`perSliceRows: { z: [y0, y1, …] }`) drop in unchanged.
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { IOWA_LAYER_COLORS, IOWA_LAYER_LABELS } from '@/components/retinalPalette'

/** Operator-edited control point — (x, y) in image-pixel coords. */
interface ControlPoint { x: number; y: number }

type EditMode = 'off' | 'shift' | 'free' | 'points'

interface PendingPerLayer {
  /** Operator's control points (sorted by x). Endpoints are x-locked. */
  points: ControlPoint[]
}

interface Props {
  /** Total B-scan count. */
  nBscans: number
  /** Image columns (A-scans). */
  cols: number
  /** Image rows. */
  rows: number
  /** Current slice index — v-model. */
  modelValue: number
  /**
   * Raw surface_y envelope — Float32Array of length
   * {@code n_surfaces * n_bscans * cols}, surface-major + slice-major.
   */
  envelopeData: Float32Array | null
  /** {@code env.shape[0]} — number of surfaces in the envelope. */
  nSurfaces: number
  /**
   * Per-surface labels (matches the {@code X-MUW-Seg-Labels} header).
   * The layer bar uses these in the chip text.
   */
  labels?: readonly string[]
  /**
   * Surfaces the operator is allowed to edit (zero-based indices into
   * the envelope). Defaults to all surfaces; nAMD viewer passes
   * {@code [0, 10]} so only ILM + BM are exposed.
   */
  correctableLayerIndices?: readonly number[]
  /** Whether the operator has the role to edit. False renders read-only. */
  canEdit: boolean
  /**
   * Inline style (left/top/width/height in px) positioning this overlay
   * over the BscanViewer's seg-overlay bbox. Pass-through from the
   * viewer's {@code overlayBboxStyle}.
   */
  bboxStyle: Record<string, string>
}

const props = withDefaults(defineProps<Props>(), {
  labels: () => IOWA_LAYER_LABELS,
  correctableLayerIndices: () => [],
})

const emit = defineEmits<{
  'update:modelValue': [z: number]
  /**
   * Operator clicked Save. Payload is the unsaved diff keyed by surface
   * index → { perSliceRows: { z: [y0, y1, …] } } in image-pixel coords.
   * The parent POSTs one request per surface index.
   */
  save: [payload: Map<number, Map<number, number[]>>]
  /** Operator clicked Discard (or closed fullscreen with confirmation). */
  discard: []
  /** {@code pendingEdits} count changed — parent uses it for the Save badge. */
  'pending-edit-count': [count: number]
}>()

const { t } = useI18n()

/* ── state ── */

const mode = ref<EditMode>('off')
/** Active surface index. Defaults to the first correctable surface. */
const activeLayer = ref<number>(
  props.correctableLayerIndices.length > 0
    ? props.correctableLayerIndices[0]!
    : 0,
)
/**
 * Pending edits, keyed by (sliceZ → surfaceIdx → control points). Empty
 * map = no unsaved edits. Pointwise edits stay until the operator Saves
 * or Discards.
 */
const pendingEdits = ref(new Map<number, Map<number, PendingPerLayer>>())
const selected = ref(new Set<ControlPoint>())

interface DragShift { type: 'shift'; startY: number; baseYs: number[] }
interface DragFree { type: 'free'; stroke: ControlPoint[] }
interface DragMove { type: 'move'; start: ControlPoint; base: { p: ControlPoint; x: number; y: number }[] }
interface DragBand { type: 'band'; rect: { x0: number; y0: number; x1: number; y1: number }; baseSel: Set<ControlPoint> }
type DragState = DragShift | DragFree | DragMove | DragBand | null
const drag = ref<DragState>(null)

const NUM_CONTROL_POINTS = 17
const HIT_RADIUS_PX = 18

/* ── derived ── */

const correctableSet = computed<ReadonlySet<number>>(() => {
  if (props.correctableLayerIndices.length === 0) {
    return new Set(Array.from({ length: props.nSurfaces }, (_, i) => i))
  }
  return new Set(props.correctableLayerIndices)
})

/** Per-A-scan y values for a (slice, surface) read out of the envelope. */
function readEnvelopeRow(z: number, s: number): Float32Array | null {
  const data = props.envelopeData
  if (!data) return null
  const surfaceStride = props.nBscans * props.cols
  const sliceOffset = s * surfaceStride + z * props.cols
  if (sliceOffset < 0 || sliceOffset + props.cols > data.length) return null
  return data.subarray(sliceOffset, sliceOffset + props.cols)
}

/**
 * Sample 17 control points from the envelope's dense row when the
 * operator first touches a (slice, surface). Endpoints are forced
 * to the first/last A-scan so the curve's x-extent never shrinks.
 */
function sampleControlPoints(z: number, s: number): ControlPoint[] {
  const row = readEnvelopeRow(z, s)
  if (!row) {
    // Best-effort: spread evenly across the canvas at mid-height so
    // the operator at least sees a curve they can drag.
    const pts: ControlPoint[] = []
    for (let i = 0; i < NUM_CONTROL_POINTS; i++) {
      pts.push({
        x: (i * (props.cols - 1)) / (NUM_CONTROL_POINTS - 1),
        y: props.rows / 2,
      })
    }
    return pts
  }
  const pts: ControlPoint[] = []
  for (let i = 0; i < NUM_CONTROL_POINTS; i++) {
    const xf = i / (NUM_CONTROL_POINTS - 1)
    const xIdx = Math.round(xf * (props.cols - 1))
    pts.push({ x: xIdx, y: row[xIdx] ?? 0 })
  }
  return pts
}

/**
 * Return the operator's points for (slice, surface), creating them
 * from the envelope on first access. Stored on {@link pendingEdits}.
 */
function getOrCreatePending(z: number, s: number): ControlPoint[] {
  let perSlice = pendingEdits.value.get(z)
  if (!perSlice) {
    perSlice = new Map()
    pendingEdits.value.set(z, perSlice)
  }
  let entry = perSlice.get(s)
  if (!entry) {
    entry = { points: sampleControlPoints(z, s) }
    perSlice.set(s, entry)
  }
  return entry.points
}

/** Read-only: peek at the operator's points without creating. */
function peekPending(z: number, s: number): ControlPoint[] | null {
  return pendingEdits.value.get(z)?.get(s)?.points ?? null
}

const z = computed(() => props.modelValue)
const activePoints = computed<ControlPoint[]>(() => {
  return peekPending(z.value, activeLayer.value) ?? []
})

const sortedActive = computed<ControlPoint[]>(() =>
  activePoints.value.slice().sort((a, b) => a.x - b.x),
)
const isEndpoint = (p: ControlPoint): boolean => {
  const s = sortedActive.value
  return p === s[0] || p === s[s.length - 1]
}

watch(
  pendingEdits,
  () => {
    let count = 0
    for (const perSlice of pendingEdits.value.values()) count += perSlice.size
    emit('pending-edit-count', count)
  },
  { deep: true },
)

/* ── geometry helpers (image-pixel coords throughout) ── */

const clampY = (y: number) => Math.max(1, Math.min(props.rows - 1, y))
const clampX = (x: number) => Math.max(0, Math.min(props.cols - 1, x))

/** Catmull-Rom → cubic-bezier path through sorted control points. */
function curvePath(pts: ControlPoint[]): string {
  if (pts.length < 2) return ''
  let d = `M ${pts[0]!.x.toFixed(1)} ${pts[0]!.y.toFixed(1)}`
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i]!
    const p1 = pts[i]!
    const p2 = pts[i + 1]!
    const p3 = pts[i + 2] || p2
    const c1x = p1.x + (p2.x - p0.x) / 6
    const c1y = p1.y + (p2.y - p0.y) / 6
    const c2x = p2.x - (p3.x - p1.x) / 6
    const c2y = p2.y - (p3.y - p1.y) / 6
    d += ` C ${c1x.toFixed(1)} ${c1y.toFixed(1)}, ${c2x.toFixed(1)} ${c2y.toFixed(1)}, ${p2.x.toFixed(1)} ${p2.y.toFixed(1)}`
  }
  return d
}

/** Sample the Catmull-Rom curve at the given x in image-pixel coords. */
function evalCurveAtX(pts: ControlPoint[], xTarget: number): number {
  if (pts.length < 2) return 0
  const sorted = pts.slice().sort((a, b) => a.x - b.x)
  if (xTarget <= sorted[0]!.x) return sorted[0]!.y
  if (xTarget >= sorted[sorted.length - 1]!.x) return sorted[sorted.length - 1]!.y
  for (let i = 0; i < sorted.length - 1; i++) {
    const p1 = sorted[i]!
    const p2 = sorted[i + 1]!
    if (xTarget < p1.x || xTarget > p2.x) continue
    const p0 = sorted[i - 1] || p1
    const p3 = sorted[i + 2] || p2
    // Find t such that the cubic-bezier x-value equals xTarget. The
    // bezier we emit has x control points c1x/c2x and endpoints p1.x
    // / p2.x — Catmull-Rom's x-spacing tends to be near-monotone, so
    // a binary search over t converges fast.
    const c1x = p1.x + (p2.x - p0.x) / 6
    const c2x = p2.x - (p3.x - p1.x) / 6
    const c1y = p1.y + (p2.y - p0.y) / 6
    const c2y = p2.y - (p3.y - p1.y) / 6
    let lo = 0
    let hi = 1
    let t = 0.5
    for (let iter = 0; iter < 24; iter++) {
      t = (lo + hi) / 2
      const oneT = 1 - t
      const x = oneT * oneT * oneT * p1.x
        + 3 * oneT * oneT * t * c1x
        + 3 * oneT * t * t * c2x
        + t * t * t * p2.x
      if (x < xTarget) lo = t
      else hi = t
    }
    const oneT = 1 - t
    return oneT * oneT * oneT * p1.y
      + 3 * oneT * oneT * t * c1y
      + 3 * oneT * t * t * c2y
      + t * t * t * p2.y
  }
  return sorted[sorted.length - 1]!.y
}

/**
 * Evaluate the operator's curve at every A-scan index to produce the
 * dense per-row array the backend's POST endpoint expects.
 */
function denseRowFromPoints(pts: ControlPoint[]): number[] {
  const out = new Array<number>(props.cols)
  const sorted = pts.slice().sort((a, b) => a.x - b.x)
  for (let x = 0; x < props.cols; x++) {
    out[x] = clampY(evalCurveAtX(sorted, x))
  }
  return out
}

/* ── SVG → image-pixel coord transform ── */

const svgEl = ref<SVGSVGElement | null>(null)
function toImageCoords(e: PointerEvent | MouseEvent): ControlPoint | null {
  const svg = svgEl.value
  if (!svg) return null
  const ctm = svg.getScreenCTM()
  if (!ctm) return null
  const pt = svg.createSVGPoint()
  pt.x = e.clientX
  pt.y = e.clientY
  const inv = ctm.inverse()
  const u = pt.matrixTransform(inv)
  return { x: u.x, y: u.y }
}

function hitPoint(n: ControlPoint): ControlPoint | null {
  // Hit-test radius is in SVG userspace pixels; the SVG uses
  // viewBox=cols×rows so the radius scales with the image's pixel
  // density. Use a generous radius (≈18 image px) so dense canvases
  // (cols=1024) still let the operator grab a control point without
  // pixel-perfect aiming.
  const r2 = HIT_RADIUS_PX * HIT_RADIUS_PX
  let best: ControlPoint | null = null
  let bd = r2
  for (const p of activePoints.value) {
    const dx = p.x - n.x
    const dy = p.y - n.y
    const d = dx * dx + dy * dy
    if (d < bd) { bd = d; best = p }
  }
  return best
}

/* ── interactions ── */

function onDown(e: PointerEvent): void {
  if (!props.canEdit) return
  if (mode.value === 'off') return
  const n = toImageCoords(e)
  if (!n) return
  e.preventDefault()
  if (svgEl.value) svgEl.value.setPointerCapture(e.pointerId)

  // Ensure the active layer has points to operate on.
  const pts = getOrCreatePending(z.value, activeLayer.value)

  if (mode.value === 'shift') {
    drag.value = {
      type: 'shift',
      startY: n.y,
      baseYs: pts.map((p) => p.y),
    }
  } else if (mode.value === 'free') {
    drag.value = { type: 'free', stroke: [n] }
  } else if (mode.value === 'points') {
    const hit = hitPoint(n)
    if (hit) {
      const sel = new Set(selected.value)
      if (e.shiftKey) {
        if (sel.has(hit)) sel.delete(hit)
        else sel.add(hit)
      } else if (!sel.has(hit)) {
        sel.clear()
        sel.add(hit)
      }
      selected.value = sel
      drag.value = {
        type: 'move',
        start: n,
        base: Array.from(selected.value).map((p) => ({ p, x: p.x, y: p.y })),
      }
    } else {
      if (!e.shiftKey) selected.value = new Set()
      drag.value = {
        type: 'band',
        rect: { x0: n.x, y0: n.y, x1: n.x, y1: n.y },
        baseSel: new Set(selected.value),
      }
    }
  }
}

function onMove(e: PointerEvent): void {
  if (!drag.value) {
    if (mode.value === 'points') {
      const n0 = toImageCoords(e)
      if (svgEl.value) {
        svgEl.value.style.cursor = (n0 && hitPoint(n0)) ? 'move' : 'crosshair'
      }
    }
    return
  }
  const n = toImageCoords(e)
  if (!n) return
  const d = drag.value
  if (d.type === 'shift') {
    const dy = n.y - d.startY
    const pts = peekPending(z.value, activeLayer.value)
    if (!pts) return
    pts.forEach((p, i) => { p.y = clampY((d.baseYs[i] ?? p.y) + dy) })
  } else if (d.type === 'free') {
    d.stroke.push(n)
  } else if (d.type === 'move') {
    const dx = n.x - d.start.x
    const dy = n.y - d.start.y
    const multi = d.base.length > 1
    for (const b of d.base) {
      const end = isEndpoint(b.p)
      b.p.y = clampY(b.y + dy)
      if (!multi && !end) {
        // x is constrained between neighbours so the row stays monotonic.
        const others = activePoints.value
          .filter((q) => q !== b.p)
          .sort((a, c) => a.x - c.x)
        let lx = 0
        let rx = props.cols - 1
        for (const q of others) {
          if (q.x <= b.x) lx = q.x
          else { rx = q.x; break }
        }
        b.p.x = Math.max(lx + 1, Math.min(rx - 1, b.x + dx))
      }
    }
  } else if (d.type === 'band') {
    d.rect.x1 = n.x
    d.rect.y1 = n.y
    const x0 = Math.min(d.rect.x0, d.rect.x1)
    const x1 = Math.max(d.rect.x0, d.rect.x1)
    const y0 = Math.min(d.rect.y0, d.rect.y1)
    const y1 = Math.max(d.rect.y0, d.rect.y1)
    const sel = new Set(d.baseSel)
    for (const p of activePoints.value) {
      if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1) sel.add(p)
    }
    selected.value = sel
  }
}

function onUp(): void {
  const d = drag.value
  drag.value = null
  if (d?.type === 'free') mergeStroke(d.stroke)
}

function onDblClick(e: MouseEvent): void {
  if (!props.canEdit || mode.value !== 'points') return
  const n = toImageCoords(e)
  if (!n) return
  const pts = getOrCreatePending(z.value, activeLayer.value)
  const p: ControlPoint = { x: clampX(n.x), y: clampY(n.y) }
  pts.push(p)
  pts.sort((a, b) => a.x - b.x)
  selected.value = new Set([p])
}

function onKey(e: KeyboardEvent): void {
  if (!props.canEdit) return
  if (mode.value !== 'points') return
  if (e.key !== 'Delete' && e.key !== 'Backspace') return
  if (selected.value.size === 0) return
  e.preventDefault()
  const pts = peekPending(z.value, activeLayer.value)
  if (!pts) return
  const sorted = pts.slice().sort((a, b) => a.x - b.x)
  const ends = new Set([sorted[0]!, sorted[sorted.length - 1]!])
  const keep = pts.filter((p) => !selected.value.has(p) || ends.has(p))
  if (keep.length < 2) return
  // Mutate in place to preserve the pendingEdits map entry reference.
  pts.length = 0
  pts.push(...keep)
  selected.value = new Set()
}

/**
 * Freehand: replace the x-stretch the operator drew with re-sampled
 * points + keep the unaffected endpoints. Reused verbatim from the
 * design's mergeStroke logic.
 */
function mergeStroke(stroke: ControlPoint[]): void {
  if (stroke.length < 2) return
  const pts = peekPending(z.value, activeLayer.value)
  if (!pts) return
  const stSorted = stroke.slice().sort((a, b) => a.x - b.x)
  const xMin = stSorted[0]!.x
  const xMax = stSorted[stSorted.length - 1]!.x
  const ends = new Set([
    pts.slice().sort((a, b) => a.x - b.x)[0]!,
    pts.slice().sort((a, b) => a.x - b.x).slice(-1)[0]!,
  ])
  const keep = pts.filter(
    (p) => ends.has(p) || p.x < xMin - 1 || p.x > xMax + 1,
  )
  const sampled: ControlPoint[] = []
  // Stride of ~3% of cols matches the design's 0.035 normalised step.
  const step = Math.max(1, Math.round(props.cols * 0.035))
  for (let x = xMin; x <= xMax + 1e-6; x += step) {
    let by = stSorted[0]!.y
    let bd = Infinity
    for (const p of stSorted) {
      const d = Math.abs(p.x - x)
      if (d < bd) { bd = d; by = p.y }
    }
    sampled.push({ x: clampX(x), y: clampY(by) })
  }
  const merged = keep.concat(sampled).sort((a, b) => a.x - b.x)
  // de-dupe near-equal x
  const out: ControlPoint[] = []
  for (const p of merged) {
    if (!out.length || p.x - out[out.length - 1]!.x > 2) out.push(p)
    else out[out.length - 1] = p
  }
  pts.length = 0
  pts.push(...out)
  selected.value = new Set()
}

/* ── render derived ── */

const overlayPaths = computed(() => {
  // Render every CORRECTABLE surface for the current slice. The active
  // layer is full-opacity; others fade to a dashed dim line. Surfaces
  // that aren't correctable (e.g. NFL when only ILM + BM are edited)
  // are deliberately NOT painted here — the underlying canvas overlay
  // still draws them.
  const paths: { d: string; stroke: string; opacity: number; dashed: boolean; layerIdx: number }[] = []
  for (const idx of correctableSet.value) {
    let pts = peekPending(z.value, idx)
    if (!pts) {
      // No pending edit → render the envelope's curve as a thin guide
      // so the operator sees where the AI's surface sits before they
      // start editing.
      const row = readEnvelopeRow(z.value, idx)
      if (!row) continue
      pts = []
      for (let i = 0; i < NUM_CONTROL_POINTS; i++) {
        const xIdx = Math.round((i * (props.cols - 1)) / (NUM_CONTROL_POINTS - 1))
        pts.push({ x: xIdx, y: row[xIdx] ?? 0 })
      }
    }
    const active = idx === activeLayer.value
    paths.push({
      d: curvePath(pts.slice().sort((a, b) => a.x - b.x)),
      stroke: IOWA_LAYER_COLORS[idx % IOWA_LAYER_COLORS.length]!,
      opacity: active ? 1 : 0.45,
      dashed: !active,
      layerIdx: idx,
    })
  }
  return paths
})

const ghostStrokeD = computed<string>(() => {
  if (!drag.value || drag.value.type !== 'free') return ''
  const stroke = drag.value.stroke
  if (stroke.length < 2) return ''
  return stroke.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
})

const bandRectAttrs = computed<{ x: number; y: number; w: number; h: number } | null>(() => {
  if (!drag.value || drag.value.type !== 'band') return null
  const r = drag.value.rect
  const x = Math.min(r.x0, r.x1)
  const y = Math.min(r.y0, r.y1)
  return { x, y, w: Math.abs(r.x1 - r.x0), h: Math.abs(r.y1 - r.y0) }
})

/* ── tool palette + actions ── */

function setMode(m: EditMode): void {
  mode.value = m
  if (m !== 'points') selected.value = new Set()
  if (svgEl.value) {
    svgEl.value.style.cursor = m === 'shift' ? 'ns-resize'
      : m === 'free' ? 'crosshair'
      : m === 'points' ? 'crosshair'
      : 'default'
  }
}

function setLayer(idx: number): void {
  activeLayer.value = idx
  selected.value = new Set()
}

function resetActiveLayer(): void {
  const perSlice = pendingEdits.value.get(z.value)
  if (!perSlice) return
  perSlice.delete(activeLayer.value)
  if (perSlice.size === 0) pendingEdits.value.delete(z.value)
  selected.value = new Set()
}

function bumpSlice(d: number): void {
  emit('update:modelValue', Math.max(0, Math.min(props.nBscans - 1, props.modelValue + d)))
}

/**
 * Public — parent calls this when the operator clicks Save in the
 * masthead. We emit one map entry per surface index containing the
 * dense per-A-scan arrays for every edited slice.
 */
function emitSave(): void {
  if (pendingEdits.value.size === 0) return
  const payload = new Map<number, Map<number, number[]>>()
  for (const [sliceZ, perSlice] of pendingEdits.value) {
    for (const [surfaceIdx, entry] of perSlice) {
      let bySurface = payload.get(surfaceIdx)
      if (!bySurface) {
        bySurface = new Map()
        payload.set(surfaceIdx, bySurface)
      }
      bySurface.set(sliceZ, denseRowFromPoints(entry.points))
    }
  }
  emit('save', payload)
}

function clearPending(): void {
  pendingEdits.value = new Map()
  selected.value = new Set()
}

/** Discard intent — parent confirms then calls this via {@link discard}. */
function emitDiscard(): void {
  clearPending()
  emit('discard')
}

defineExpose({ emitSave, clearPending, emitDiscard, pendingEdits })

/* ── lifecycle ── */

onMounted(() => {
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
})

const hintText = computed<string>(() => {
  switch (mode.value) {
    case 'off':    return t('retinal.correction.hint.off')
    case 'shift':  return t('retinal.correction.hint.shift')
    case 'free':   return t('retinal.correction.hint.free')
    case 'points': return t('retinal.correction.hint.points')
  }
  return ''
})

const selectedCount = computed(() => selected.value.size)
</script>

<template>
  <div
    data-testid="bscan-layer-edit-overlay"
    class="absolute inset-0 pointer-events-none z-10"
  >
    <!-- The SVG itself is positioned at the BscanViewer's seg-overlay
         bbox (CSS px). viewBox is the image's pixel dimensions so points
         and curves use the same coord system as the seg-overlay canvas. -->
    <svg
      ref="svgEl"
      :viewBox="`0 0 ${cols} ${rows}`"
      :style="bboxStyle"
      preserveAspectRatio="xMidYMid slice"
      :class="canEdit && mode !== 'off' ? 'pointer-events-auto' : 'pointer-events-none'"
      @pointerdown="onDown"
      @pointermove="onMove"
      @pointerup="onUp"
      @pointercancel="onUp"
      @dblclick.prevent="onDblClick"
    >
      <!-- Non-active layer guides -->
      <path
        v-for="path in overlayPaths.filter((p) => p.layerIdx !== activeLayer)"
        :key="`guide-${path.layerIdx}`"
        :d="path.d"
        fill="none"
        :stroke="path.stroke"
        stroke-width="1.6"
        :stroke-opacity="path.opacity"
        stroke-dasharray="3 6"
      />
      <!-- Active layer halo (dark) + colored line -->
      <template
        v-for="path in overlayPaths.filter((p) => p.layerIdx === activeLayer)"
        :key="`active-${path.layerIdx}`"
      >
        <path :d="path.d" fill="none" stroke="#0b1220" stroke-width="4.5" stroke-opacity="0.5" />
        <path :d="path.d" fill="none" :stroke="path.stroke" stroke-width="2.5" stroke-opacity="1" />
      </template>
      <!-- Control points (only in points mode) -->
      <template v-if="mode === 'points'">
        <template v-for="(p, i) in sortedActive" :key="`pt-${i}-${p.x}-${p.y}`">
          <circle
            v-if="selected.has(p)"
            :cx="p.x"
            :cy="p.y"
            r="6"
            fill="#ffffff"
            :stroke="IOWA_LAYER_COLORS[activeLayer % IOWA_LAYER_COLORS.length]"
            stroke-width="3"
          />
          <circle
            v-else
            :cx="p.x"
            :cy="p.y"
            r="5"
            :fill="IOWA_LAYER_COLORS[activeLayer % IOWA_LAYER_COLORS.length]"
            stroke="#0b1220"
            stroke-width="1.5"
          />
        </template>
      </template>
      <!-- Live freehand stroke -->
      <path
        v-if="ghostStrokeD"
        :d="ghostStrokeD"
        fill="none"
        :stroke="IOWA_LAYER_COLORS[activeLayer % IOWA_LAYER_COLORS.length]"
        stroke-width="2.5"
        stroke-dasharray="6 6"
        stroke-opacity="0.95"
      />
      <!-- Rubber-band selection rect -->
      <rect
        v-if="bandRectAttrs"
        :x="bandRectAttrs.x"
        :y="bandRectAttrs.y"
        :width="bandRectAttrs.w"
        :height="bandRectAttrs.h"
        fill="rgba(96,165,250,0.12)"
        stroke="#60a5fa"
        stroke-width="1.4"
        stroke-dasharray="5 4"
      />
    </svg>

    <!-- Tool palette (left edge, vertical). pointer-events: auto so
         buttons receive clicks even though the wrapper is pointer-
         events: none. -->
    <div
      v-if="canEdit"
      data-testid="bscan-layer-tools"
      class="absolute left-3 top-1/2 -translate-y-1/2 z-20 pointer-events-auto flex flex-col gap-1 rounded-2xl bg-slate-900/85 backdrop-blur-sm ring-1 ring-white/15 p-1.5"
    >
      <button
        v-for="m in (['off', 'shift', 'free', 'points'] as EditMode[])"
        :key="m"
        type="button"
        :data-testid="`bscan-layer-tool-${m}`"
        :title="t(`retinal.correction.tools.${m}`)"
        class="w-9 h-9 rounded-lg inline-flex items-center justify-center transition"
        :class="mode === m ? 'bg-white text-muw-blue' : 'text-white/70 hover:bg-white/15'"
        @click="setMode(m)"
      >
        <!-- Off (eye) -->
        <svg v-if="m === 'off'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="M2 12 C5 6 10 4 12 4 C14 4 19 6 22 12 C19 18 14 20 12 20 C10 20 5 18 2 12 Z" />
          <circle cx="12" cy="12" r="3" />
        </svg>
        <!-- Shift (up-down arrows) -->
        <svg v-else-if="m === 'shift'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="M12 3 V21 M8 7 L12 3 L16 7 M8 17 L12 21 L16 17" />
        </svg>
        <!-- Free (pen) -->
        <svg v-else-if="m === 'free'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="M12 19 L19 12 a2.8 2.8 0 0 0 -4 -4 L8 15 L7 20 Z" /><path d="M14 6 L18 10" />
        </svg>
        <!-- Points (curve with anchors) -->
        <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="M3 17 C 7 17 8 7 12 7 S 17 13 21 13" />
          <circle cx="3" cy="17" r="2" fill="currentColor" stroke="none" />
          <circle cx="12" cy="7" r="2" fill="currentColor" stroke="none" />
          <circle cx="21" cy="13" r="2" fill="currentColor" stroke="none" />
        </svg>
      </button>
      <div class="h-px bg-white/15 mx-1 my-0.5" />
      <button
        type="button"
        data-testid="bscan-layer-reset"
        :title="t('retinal.correction.resetLayer')"
        class="w-9 h-9 rounded-lg inline-flex items-center justify-center text-white/70 hover:bg-white/15"
        @click="resetActiveLayer"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
          <path d="M3 7 V13 H9" />
          <path d="M3 13 a9 9 0 1 0 3 -7.7 L3 8" />
        </svg>
      </button>
    </div>

    <!-- Layer bar (top center) — select active layer + contextual hint -->
    <div
      v-if="canEdit"
      data-testid="bscan-layer-bar"
      class="absolute top-4 left-1/2 -translate-x-1/2 z-20 pointer-events-auto flex items-center gap-3 rounded-xl bg-slate-900/85 backdrop-blur-sm ring-1 ring-white/15 px-3 py-1.5"
    >
      <span class="text-[10px] font-semibold uppercase tracking-[0.1em] text-white/45">Layer</span>
      <div class="flex items-center gap-1">
        <button
          v-for="idx in Array.from(correctableSet)"
          :key="`layer-${idx}`"
          type="button"
          :data-testid="`bscan-layer-pick-${idx}`"
          class="px-2.5 py-1 rounded-md text-[11px] font-semibold inline-flex items-center gap-1.5 transition"
          :class="activeLayer === idx
            ? 'bg-white/15 text-white ring-1 ring-white/25'
            : 'text-white/55 hover:text-white/80'"
          @click="setLayer(idx)"
        >
          <span
            class="w-2 h-2 rounded-[2px]"
            :style="{ background: IOWA_LAYER_COLORS[idx % IOWA_LAYER_COLORS.length] }"
          />
          <span>{{ labels[idx] ?? IOWA_LAYER_LABELS[idx] ?? `L${idx}` }}</span>
        </button>
      </div>
      <!-- Prev / next slice nav so the operator can scrub via the bar
           without leaving the editing context. -->
      <span class="w-px h-4 bg-white/15" />
      <button
        type="button"
        data-testid="bscan-layer-prev-slice"
        :title="t('retinal.correction.prevSlice')"
        class="px-1.5 py-0.5 rounded text-[11px] text-white/60 hover:text-white"
        @click="bumpSlice(-1)"
      >‹</button>
      <span class="tabular-nums text-[11px] text-white/70">{{ modelValue + 1 }} / {{ nBscans }}</span>
      <button
        type="button"
        data-testid="bscan-layer-next-slice"
        :title="t('retinal.correction.nextSlice')"
        class="px-1.5 py-0.5 rounded text-[11px] text-white/60 hover:text-white"
        @click="bumpSlice(1)"
      >›</button>
      <!-- Hint / selection-count -->
      <span class="w-px h-4 bg-white/15" />
      <span
        v-if="mode === 'points' && selectedCount > 0"
        class="text-[11px] text-white/85 font-medium"
      >{{ t('retinal.correction.selectedPoints', { n: selectedCount }) }}</span>
      <span v-else class="text-[11px] text-white/45 min-w-[260px]">{{ hintText }}</span>
    </div>
  </div>
</template>
