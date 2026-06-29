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
  /**
   * 2026-06-27 — surfaces this overlay is now painting itself (active
   * layer + any layer with a pending edit). The parent forwards this
   * list to BscanViewer's {@code suppressedSurfaceIndices} so the
   * native canvas SKIPS those surfaces — otherwise the original AI
   * polyline keeps showing through after the operator drags the
   * edited line elsewhere.
   */
  'painted-surfaces': [indices: number[]]
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
/**
 * 2026-06-27 — version counter bumped on every in-place point mutation
 * (shift drag, free draw, move). Vue's deep reactivity on the
 * pendingEdits Map fires for {@code set} / {@code delete} on the Map
 * itself, but in-place {@code p.y = …} on a control point nested in
 * an array doesn't reliably retrigger downstream computeds. The
 * render-side computeds read this counter to force re-evaluation on
 * every drag tick.
 */
const editVersion = ref(0)
const bumpEditVersion = () => { editVersion.value++ }

/**
 * 2026-06-29 — Undo stack. Each entry is a deep snapshot of
 * {@link pendingEdits} captured BEFORE an edit operation begins
 * (shift drag, points move, freehand draw, dblclick-add, delete,
 * arrow-key shift). Ctrl+Z (and the toolbar's undo button) pops the
 * top and restores it. We cap the stack at 50 entries — operators
 * rarely need deeper history and unbounded growth would leak
 * memory on heavy editing sessions.
 */
type EditSnapshot = Map<number, Map<number, ControlPoint[]>>
const undoStack = ref<EditSnapshot[]>([])
const UNDO_LIMIT = 50

function snapshotPendingEdits(): EditSnapshot {
  const out: EditSnapshot = new Map()
  for (const [z, perSlice] of pendingEdits.value) {
    const cloned = new Map<number, ControlPoint[]>()
    for (const [s, entry] of perSlice) {
      cloned.set(s, entry.points.map((p) => ({ x: p.x, y: p.y })))
    }
    out.set(z, cloned)
  }
  return out
}

function pushUndo(): void {
  const snap = snapshotPendingEdits()
  undoStack.value.push(snap)
  if (undoStack.value.length > UNDO_LIMIT) {
    undoStack.value.shift()
  }
}

function undo(): void {
  const snap = undoStack.value.pop()
  if (!snap) return
  const next = new Map<number, Map<number, PendingPerLayer>>()
  for (const [z, perSlice] of snap) {
    const restored = new Map<number, PendingPerLayer>()
    for (const [s, pts] of perSlice) {
      restored.set(s, { points: pts.map((p) => ({ x: p.x, y: p.y })) })
    }
    next.set(z, restored)
  }
  pendingEdits.value = next
  selected.value = new Set()
  bumpEditVersion()
}

const canUndo = computed(() => undoStack.value.length > 0)

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
  // Read editVersion so in-place mutations on individual control
  // points retrigger this computed.
  void editVersion.value
  return peekPending(z.value, activeLayer.value) ?? []
})

const sortedActive = computed<ControlPoint[]>(() =>
  activePoints.value.slice().sort((a, b) => a.x - b.x),
)
const isEndpoint = (p: ControlPoint): boolean => {
  const s = sortedActive.value
  return p === s[0] || p === s[s.length - 1]
}

/**
 * 2026-06-27 — Renderable control points for the ACTIVE layer.
 *
 * <p>If the operator has already touched this (slice, layer), shows the
 * pending control points (same ones {@link sortedActive} returns). If
 * not, samples them from the envelope on the fly so the operator sees
 * the dots immediately when they switch into points mode — without
 * materialising a pendingEdits entry (which would inflate the unsaved
 * Save badge to ≥1 just for previewing).
 *
 * <p>{@link onDown} still materialises pending on first hit, so dragging
 * mutates the real pendingEdits state and the displayed dots stay
 * aligned with what gets saved.
 */
const displayedSortedActive = computed<ControlPoint[]>(() => {
  void editVersion.value
  const peeked = peekPending(z.value, activeLayer.value)
  const pts = peeked ?? sampleControlPoints(z.value, activeLayer.value)
  return pts.slice().sort((a, b) => a.x - b.x)
})

watch(
  pendingEdits,
  () => {
    let count = 0
    for (const perSlice of pendingEdits.value.values()) count += perSlice.size
    emit('pending-edit-count', count)
  },
  { deep: true },
)

/**
 * 2026-06-27 — Emit the set of surfaces this overlay paints (active +
 * pending) whenever it changes. The parent forwards it to BscanViewer
 * so the cornerstone canvas suppresses those surfaces from its own
 * surface_y paint. Computed-derived to coalesce duplicates.
 */
const paintedSurfaces = computed<number[]>(() => {
  const set = new Set<number>()
  set.add(activeLayer.value)
  for (const perSlice of pendingEdits.value.values()) {
    for (const idx of perSlice.keys()) set.add(idx)
  }
  return Array.from(set).sort((a, b) => a - b)
})
watch(
  paintedSurfaces,
  (v) => emit('painted-surfaces', v),
  { immediate: true },
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
    pushUndo()
    drag.value = {
      type: 'shift',
      startY: n.y,
      baseYs: pts.map((p) => p.y),
    }
  } else if (mode.value === 'free') {
    pushUndo()
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
      // Snapshot before the move drag starts mutating point coords.
      pushUndo()
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
    bumpEditVersion()
  } else if (d.type === 'free') {
    d.stroke.push(n)
    // Live stroke render reads {@link ghostStrokeD} which depends on
    // {@code drag} (already reactive), so no version bump needed.
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
    bumpEditVersion()
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
  if (d?.type === 'free') {
    mergeStroke(d.stroke)
    bumpEditVersion()
  }
}

function onDblClick(e: MouseEvent): void {
  if (!props.canEdit || mode.value !== 'points') return
  const n = toImageCoords(e)
  if (!n) return
  pushUndo()
  const pts = getOrCreatePending(z.value, activeLayer.value)
  const p: ControlPoint = { x: clampX(n.x), y: clampY(n.y) }
  pts.push(p)
  pts.sort((a, b) => a.x - b.x)
  selected.value = new Set([p])
  bumpEditVersion()
}

function onKey(e: KeyboardEvent): void {
  if (!props.canEdit) return

  // 2026-06-29 — Ctrl+Z / ⌘+Z undo. Works in every mode + when no
  // selection / no active drag. The undo stack is shared across modes
  // so an operator who switched from shift to points can still roll
  // back the last shift drag.
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
    if (!canUndo.value) return
    e.preventDefault()
    undo()
    return
  }

  // 2026-06-29 — Arrow up/down in shift mode shifts the whole active
  // layer by 1 image-pixel per press (Shift+Arrow steps by 5 px for
  // coarser adjustments). Each press is one undo entry.
  if (mode.value === 'shift' && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
    e.preventDefault()
    const step = e.shiftKey ? 5 : 1
    const dy = e.key === 'ArrowUp' ? -step : step
    pushUndo()
    const pts = getOrCreatePending(z.value, activeLayer.value)
    pts.forEach((p) => { p.y = clampY(p.y + dy) })
    bumpEditVersion()
    return
  }

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
  pushUndo()
  // Mutate in place to preserve the pendingEdits map entry reference.
  pts.length = 0
  pts.push(...keep)
  selected.value = new Set()
  bumpEditVersion()
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
  // Read editVersion to retrigger on in-place point mutations.
  void editVersion.value
  // 2026-06-27 — render ONLY the active layer + any layer with a
  // pending edit. Dashed guides for the other correctable layers
  // duplicated the BscanViewer legend toggle (which already controls
  // visibility of those layers on the underlying canvas) and added
  // visual clutter the operator can't dismiss. The parent
  // simultaneously asks BscanViewer to SUPPRESS the active surface
  // from its native paint so the original AI line doesn't show
  // through after the operator drags the edited line elsewhere.
  const paths: { d: string; stroke: string; opacity: number; dashed: boolean; layerIdx: number }[] = []
  const surfacesToRender = new Set<number>()
  surfacesToRender.add(activeLayer.value)
  const perSlice = pendingEdits.value.get(z.value)
  if (perSlice) {
    for (const idx of perSlice.keys()) surfacesToRender.add(idx)
  }
  for (const idx of surfacesToRender) {
    if (!correctableSet.value.has(idx)) continue
    let pts = peekPending(z.value, idx)
    if (!pts) {
      // Active layer with no pending edit → sample 17 points from the
      // envelope and render as the operator's working curve. Once they
      // drag, pendingEdits is materialised + this branch flips to
      // peekPending above.
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
      opacity: 1,
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

/**
 * 2026-06-27 — The cornerstone-rendered B-scan is at the IMAGE'S
 * PHYSICAL aspect ratio (~4.5:1 for Heidelberg cubes), not the pixel
 * aspect (~1.55:1). Since the editing SVG uses
 * {@code preserveAspectRatio="none"} to match the canvas's per-axis
 * stretch, viewBox units are stretched UNEVENLY into screen space.
 * Anything sized in viewBox units (stroke width, circle radius) renders
 * as an ellipse / lopsided stroke unless we compensate. These computeds
 * pull the bbox CSS dimensions, derive the per-axis scale factors, and
 * expose viewBox-unit radii that result in a target screen-pixel radius
 * regardless of stretch. Strokes use {@code vector-effect="non-scaling-stroke"}
 * directly in the template; circles use the computed ellipse radii.
 */
function parsePx(v: string | undefined): number {
  if (!v) return 0
  const n = Number.parseFloat(v)
  return Number.isFinite(n) && n > 0 ? n : 0
}
const screenScale = computed<{ x: number; y: number }>(() => ({
  x: (parsePx(props.bboxStyle.width) || props.cols) / Math.max(1, props.cols),
  y: (parsePx(props.bboxStyle.height) || props.rows) / Math.max(1, props.rows),
}))
const POINT_R_SCREEN_PX = 5
const POINT_R_SELECTED_PX = 6
const pointRadii = computed<{ rx: number; ry: number; rxSel: number; rySel: number }>(() => {
  const sx = screenScale.value.x || 1
  const sy = screenScale.value.y || 1
  return {
    rx: POINT_R_SCREEN_PX / sx,
    ry: POINT_R_SCREEN_PX / sy,
    rxSel: POINT_R_SELECTED_PX / sx,
    rySel: POINT_R_SELECTED_PX / sy,
  }
})

/**
 * BscanViewer's {@code overlayBboxStyle} bakes
 * {@code pointerEvents: 'none'} into the inline style so the seg-overlay
 * canvas never intercepts wheel scrubs / cornerstone tool clicks. Inline
 * style beats Tailwind's {@code pointer-events-auto} class, so the
 * editing SVG would silently swallow nothing without this override.
 * Re-merge the bbox-style with a pointer-events value driven by mode +
 * canEdit so clicks reach the SVG only when an editing tool is active.
 */
const svgStyle = computed<Record<string, string>>(() => ({
  ...props.bboxStyle,
  pointerEvents: (props.canEdit && mode.value !== 'off') ? 'auto' : 'none',
  // Tiny ergonomic tweak: hide native touch scroll on the SVG so
  // pointer captures don't get hijacked by the page scroll on iPad/
  // trackpad pinch.
  touchAction: 'none',
}))
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
      :style="svgStyle"
      preserveAspectRatio="none"
      @pointerdown="onDown"
      @pointermove="onMove"
      @pointerup="onUp"
      @pointercancel="onUp"
      @dblclick.prevent="onDblClick"
    >
      <!-- Painted layers = active layer + any layer with a pending
           edit. Inactive correctable layers and non-correctable layers
           are NOT drawn here — the BscanViewer's native canvas handles
           the legend-toggle visibility, and the active layer is
           suppressed in the canvas while this overlay is mounted so
           there's no double-rendering. -->
      <template
        v-for="path in overlayPaths"
        :key="`layer-${path.layerIdx}`"
      >
        <path
          :d="path.d" fill="none" stroke="#0b1220"
          stroke-width="4.5" stroke-opacity="0.5"
          vector-effect="non-scaling-stroke"
        />
        <path
          :d="path.d" fill="none" :stroke="path.stroke"
          :stroke-width="path.layerIdx === activeLayer ? 2.5 : 1.8"
          stroke-opacity="1"
          vector-effect="non-scaling-stroke"
        />
      </template>
      <!-- Control points (only in points mode). Use <ellipse> with
           per-axis radii so the dots stay ROUND in screen space — a
           plain <circle r="5"> renders as a flat ellipse when the SVG
           is non-uniformly stretched. {@link displayedSortedActive}
           shows points immediately on mode switch (sampled from the
           envelope) without materialising pendingEdits until the first
           drag. -->
      <template v-if="mode === 'points'">
        <template v-for="(p, i) in displayedSortedActive" :key="`pt-${i}-${p.x}-${p.y}`">
          <!-- 2026-06-29 — selected vs unselected differ ONLY in fill
               colour; radius + stroke-width stay identical so the visual
               anchor doesn't move when the operator clicks. -->
          <ellipse
            :cx="p.x"
            :cy="p.y"
            :rx="pointRadii.rx"
            :ry="pointRadii.ry"
            :fill="selected.has(p) ? '#60a5fa' : IOWA_LAYER_COLORS[activeLayer % IOWA_LAYER_COLORS.length]"
            stroke="#0b1220"
            stroke-width="1.5"
            vector-effect="non-scaling-stroke"
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
        vector-effect="non-scaling-stroke"
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
        vector-effect="non-scaling-stroke"
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
      <!-- Undo (Ctrl/⌘+Z mirror) — disabled when stack is empty. -->
      <button
        type="button"
        data-testid="bscan-layer-undo"
        :title="t('retinal.correction.undo')"
        :disabled="!canUndo"
        class="w-9 h-9 rounded-lg inline-flex items-center justify-center transition"
        :class="canUndo ? 'text-white/70 hover:bg-white/15' : 'text-white/25 cursor-not-allowed'"
        @click="undo"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
          <path d="M9 14 L4 9 L9 4" />
          <path d="M4 9 H14 a6 6 0 0 1 6 6 v0 a6 6 0 0 1 -6 6 H9" />
        </svg>
      </button>
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
