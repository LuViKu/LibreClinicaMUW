<script setup lang="ts">
/**
 * nAMD workspace — OCT scan frame (design-parity wrapper for
 * {@link BscanViewer}).
 *
 * Mounts a Cornerstone B-scan viewer inside a black, ring-bordered
 * frame with the design's corner overlays (eye/visit/date pill,
 * slice counter, scale bar, KI-Maske toggle). Below the frame:
 * a controls row carrying a play/pause button, the design's
 * "activity heatmap" bar strip (one bar per B-scan, height encodes
 * the per-slice fluid presence read from the segmentation envelope),
 * a hidden range input that the heatmap drives, and an optional
 * en-face locator thumbnail.
 *
 * <p>Props/v-model contract — the slice + mask values are driven
 * by the parent so the Compare tab can share a single ref across
 * two frames. When the parent omits v-model bindings the frame
 * falls back to local state.
 *
 * <p>BscanViewer runs in {@code staticFrame=true} mode so its own
 * slider + header are suppressed; the frame's own controls drive
 * slice changes via the model-value prop.
 */
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { artifactUrl, saveLayerCorrection } from '@/api/retinal'
import {
  clearSegmentationEnvelopeCache,
  useSegmentationEnvelope,
} from '@/composables/useSegmentationEnvelope'
import { useRetinalJobStore } from '@/stores/retinalJob'
import { formatDate } from '@/lib/dateFormat'
import { IOWA_LAYER_COLORS, IOWA_LAYER_LABELS } from '@/components/retinalPalette'
import BscanLayerEditOverlay from '@/components/BscanLayerEditOverlay.vue'
import EtdrsRingsBscanIndicator from '@/components/EtdrsRingsBscanIndicator.vue'
import NamdEnFaceLocator from './NamdEnFaceLocator.vue'
import { useSiblingLayersJob } from '../composables/useSiblingLayersJob'
import { I } from '../icons'
import type { NamdVisit, Laterality } from '../types'

interface Props {
  visit: NamdVisit
  /** Eye under workspace — OD or OS. Surfaces in the corner pill. */
  eye: Laterality
  /** Total B-scan count (n_bscans) for this visit. */
  nSlices: number
  /** Current slice index — v-model:slice. */
  slice: number
  /** KI-Maske toggle — v-model:mask. */
  mask: boolean
  /**
   * Hide the en-face thumbnail; the Compare tab uses compact
   * frames without it.
   */
  showThumbs?: boolean
  /** Optional id suffix for testids when two frames coexist. */
  idBase?: string
  /**
   * Opt-in fullscreen toggle (default true). Toggles CSS only (no Teleport)
   * so the Cornerstone WebGL context survives; Compare-tab frames pass false.
   */
  enableFullscreen?: boolean
  /** Slider accent colour; fullscreen always uses sky (navy is swallowed by the dark backdrop). */
  sliderTone?: 'navy' | 'sky'
  /**
   * 2026-06-26 user-feedback round (round 2) — opt-in
   * fill-the-parent layout WITHOUT spawning the frame's own
   * dark-backdrop fullscreen. Used by the Compare-tab stacked
   * fullscreen, which provides its own backdrop + masthead and
   * just wants the scan box to grow to fill the pane it's been
   * placed in. When true:
   *   * Root becomes {@code h-full flex flex-col gap-3} — controls
   *     row sits below the scan box.
   *   * The scan box becomes {@code flex-1 min-h-0} so it expands
   *     to the available vertical space, and the inner aspect-
   *     [16/9] constraint is released to {@code w-full h-full}.
   *   * The BscanViewer's {@code fillContainer} flag is set so its
   *     internal 4:3 wrapper releases too.
   *   * Slider tone forced to sky regardless of {@code sliderTone}
   *     (dark backdrop above forbids navy contrast).
   *   * Per-frame maximize button suppressed — the parent owns
   *     the fullscreen affordance.
   */
  fillContainer?: boolean
  /**
   * 2026-06-26 user-feedback round (round 3) — comparison anchor
   * for the activity heatmap's diverging colormap. When supplied,
   * each B-scan bar's COLOR encodes
   *   {@code current.per_bscan_mm2[i] − prev.per_bscan_mm2[i]}
   * on a red-green diverging scale — red where this visit has
   * MORE fluid at this slice than the reference (clinically bad),
   * green where it has LESS (clinically good), grey near zero.
   * Bar HEIGHT continues to encode the current visit's per-slice
   * activity so taller bars still call attention to the
   * fluid-heavy slices.
   *
   * <p>Wiring:
   *   * OCT-Viewer tab passes {@code props.data.prev} so the
   *     viewer shows change vs the previous chronological visit.
   *   * Compare-tab panes pass each OTHER side's visit so the
   *     two panes' bars read symmetrically opposite (red on one
   *     = green on the other at the same slice).
   *
   * <p>Null / undefined falls back to the legacy coral / grey
   * behaviour (active = coral, inactive = light grey).
   */
  prevVisit?: NamdVisit | null
  /**
   * 2026-06-26 — study subject id (numeric), threaded so the frame
   * can resolve the SIBLING `layers` / `bm` job for the same
   * (event, eye). When present:
   *   * Always-on: paints ILM + BM polylines on top of the fluid
   *     mask whenever a `done` sibling job exists.
   *   * Fullscreen: mounts the {@link BscanLayerEditOverlay} so
   *     operators with the role gate can correct ILM + BM.
   * Compare-tab panes pass null because the comparison ergonomics
   * are read-only by design.
   */
  studySubjectId?: number | null
  /**
   * Role gate for the layer-correction UI. False renders the
   * fullscreen overlay read-only (no tools, no save). Defaults to
   * false so the editing UI never accidentally surfaces to a
   * Monitor / CRC role.
   */
  canCorrectLayers?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  showThumbs: true,
  idBase: 'frame',
  enableFullscreen: true,
  sliderTone: 'navy',
  fillContainer: false,
  prevVisit: null,
  studySubjectId: null,
  canCorrectLayers: false,
})

const emit = defineEmits<{
  'update:slice': [z: number]
  'update:mask': [m: boolean]
  /** Bubbled after a save POST succeeds — parent renders the toast. */
  'correction-saved': [info: { layers: number; slices: number }]
  /** Bubbled when a save POST throws — parent renders the toast. */
  'correction-error': [message: string]
}>()

const { t } = useI18n()

const BscanViewer = defineAsyncComponent(() => import('@/components/BscanViewer.vue'))

const jobIdRef = computed(() => props.visit.retinalJobId)
const bscanDcmUrl = computed(() =>
  props.visit.retinalJobId != null ? artifactUrl(props.visit.retinalJobId, 'bscan.dcm') : null,
)

// Lift the envelope into the frame so we can derive a per-slice
// activity bar strip without poking into BscanViewer's internals.
// The composable's module-level Map cache means BscanViewer's own
// call resolves from the same promise — only one HTTP fetch per
// job total.
const { envelope: segEnvelope } = useSegmentationEnvelope(jobIdRef)

/**
 * 2026-06-26 — Sibling `layers` / `bm` job for the same (subject, event,
 * eye). Resolves to null when no done sibling exists; otherwise the
 * frame paints ILM + BM polylines on top of the fluid mask AND mounts
 * the editing overlay in fullscreen.
 */
const studySubjectIdRef = computed<number | null>(() => props.studySubjectId)
const { siblingJobId, envelope: siblingEnvelope } = useSiblingLayersJob({
  studySubjectId: studySubjectIdRef,
  currentJobId: jobIdRef,
})

/**
 * Layer-correction state. The frame owns the unsaved-changes count
 * (the overlay emits it via {@code pending-edit-count}) so the fs
 * masthead's Save button can show the diff badge.
 */
const correctionPendingCount = ref(0)
const correctionSaving = ref(false)
/**
 * 2026-06-27 — Inline success pill for the layer-correction Save. The
 * SPA has no toast store; routing a success message through the errors
 * store makes it render as a red "Ein Fehler ist aufgetreten" toast.
 * The pill clears after 3.5 s.
 */
const correctionSavedToast = ref<string>('')
let correctionSavedTimer: ReturnType<typeof setTimeout> | null = null
function showCorrectionSavedToast(text: string): void {
  correctionSavedToast.value = text
  if (correctionSavedTimer) clearTimeout(correctionSavedTimer)
  correctionSavedTimer = setTimeout(() => {
    correctionSavedToast.value = ''
    correctionSavedTimer = null
  }, 3500)
}
const correctionDiscardOpen = ref(false)
const correctionOverlayRef = ref<InstanceType<typeof BscanLayerEditOverlay> | null>(null)

/**
 * 2026-06-29 — ETDRS-ring eccentricity indicator toggle. Defaults OFF;
 * persisted in localStorage so the operator's choice survives across
 * sessions. Same key as the correction-fullscreen indicator so the
 * preference is shared.
 */
const showEtdrsRings = ref<boolean>(
  typeof localStorage !== 'undefined'
    && localStorage.getItem('retinal.correction.etdrsRings') === '1',
)
function toggleEtdrsRings(): void {
  showEtdrsRings.value = !showEtdrsRings.value
  try {
    localStorage.setItem('retinal.correction.etdrsRings', showEtdrsRings.value ? '1' : '0')
  } catch {
    /* sandboxed / private mode — preference doesn't persist */
  }
}
function slotImageDims(slotProps: unknown): { rows: number; cols: number } | null {
  const d = (slotProps as { imageDims?: { rows: number; cols: number } | null })?.imageDims
  return d ?? null
}
function slotPixelSpacing(slotProps: unknown): { axialMm: number; lateralMm: number } | null {
  const sp = (slotProps as { pixelSpacing?: { axialMm: number; lateralMm: number } | null })?.pixelSpacing
  return sp ?? null
}

const FLUID_LABELS = new Set([1, 2, 3]) // IRF=1, SRF=2, PED=3

/**
 * Per-slice activity in [0,1] — sum of non-zero (fluid-labelled)
 * voxels normalised against the max across all slices. Surface_y
 * envelopes (onl/pr) fall back to a flat 0 array since "thickness
 * activity" isn't a meaningful proxy here.
 */
const activity = computed<number[]>(() => {
  const env = segEnvelope.value
  const n = props.nSlices
  if (!env || env.kind !== 'volume') return new Array(n).fill(0)
  const shape = env.shape
  if (shape.length < 3) return new Array(n).fill(0)
  const [, rows, cols] = shape as [number, number, number]
  const sliceStride = rows * cols
  const data = env.data as Uint8Array
  const arr: number[] = new Array(n).fill(0)
  let max = 0
  for (let z = 0; z < n; z++) {
    let count = 0
    const sliceOffset = z * sliceStride
    // Sample every 4th voxel — full scan is rows*cols = 200k+ per
    // slice and we're computing 49 of them just to drive 49 bar
    // heights. A 4x stride still preserves the bell-curve shape.
    for (let i = 0; i < sliceStride; i += 4) {
      if (FLUID_LABELS.has(data[sliceOffset + i] ?? 0)) count++
    }
    arr[z] = count
    if (count > max) max = count
  }
  if (max === 0) return arr
  for (let z = 0; z < n; z++) arr[z] = arr[z]! / max
  return arr
})

const atFovea = computed(() =>
  Math.abs(props.slice - Math.floor(props.nSlices / 2)) <= 1,
)

function setSlice(z: number): void {
  const clamped = Math.max(0, Math.min(props.nSlices - 1, z))
  emit('update:slice', clamped)
}

/**
 * 2026-06-23 user-feedback round — wheel scroll on the scan box
 * scrubs through B-scans. BscanViewer's own wheel handler is
 * gated by {@code staticFrame=true}, so we duplicate the accumulator
 * pattern here and drive slice changes via the parent's emit.
 * Threshold matches BscanViewer (24px) so trackpad + mouse-wheel
 * feel identical between the two callers.
 */
let wheelAccum = 0
function onWheel(ev: WheelEvent): void {
  ev.preventDefault()
  wheelAccum += ev.deltaY
  const threshold = 24
  if (wheelAccum >= threshold) {
    wheelAccum = 0
    setSlice(props.slice + 1)
  } else if (wheelAccum <= -threshold) {
    wheelAccum = 0
    setSlice(props.slice - 1)
  }
}

function toggleMask(): void {
  emit('update:mask', !props.mask)
}

// Play / pause auto-scrub — bounce inside the volume at ~14fps.
const playing = ref(false)
const dir = ref(1)
let rafId: number | null = null

function tick(): void {
  if (!playing.value) return
  // Every other frame to keep the design's ~14fps cadence.
  const next = props.slice + dir.value
  if (next >= props.nSlices - 1) {
    dir.value = -1
    setSlice(props.nSlices - 1)
  } else if (next <= 0) {
    dir.value = 1
    setSlice(0)
  } else {
    setSlice(next)
  }
  // Schedule the next tick with a small delay so the cornerstone
  // viewport has time to render.
  rafId = window.setTimeout(() => {
    rafId = requestAnimationFrame(tick)
  }, 70) as unknown as number
}

function togglePlay(): void {
  playing.value = !playing.value
  if (playing.value) tick()
  else if (rafId != null) {
    cancelAnimationFrame(rafId)
    clearTimeout(rafId)
    rafId = null
  }
}

watch(() => props.nSlices, () => {
  playing.value = false
  if (rafId != null) {
    cancelAnimationFrame(rafId)
    clearTimeout(rafId)
    rafId = null
  }
})

onBeforeUnmount(() => {
  playing.value = false
  if (rafId != null) {
    cancelAnimationFrame(rafId)
    clearTimeout(rafId)
    rafId = null
  }
})

const eyeLabel = computed(() => (props.eye === 'OD' ? 'OD' : 'OS'))

/* ── 2026-06-26 user-feedback round — per-slice Δ colormap ────── */

const jobStore = useRetinalJobStore()

// Ensure the prev-visit's job detail is loaded so we can read its
// per_bscan_mm2 array. Cached by the store so a return-trip from
// another tab is free; null prevVisit / null retinalJobId no-ops.
watch(
  () => props.prevVisit?.retinalJobId ?? null,
  async (id) => {
    if (id == null) return
    if (jobStore.jobs[id] == null) await jobStore.loadJob(id)
  },
  { immediate: true },
)
watch(
  () => props.visit.retinalJobId,
  async (id) => {
    if (id == null) return
    if (jobStore.jobs[id] == null) await jobStore.loadJob(id)
  },
  { immediate: true },
)

/**
 * Sum the three fluid biomarkers (irf + srf + ped) per B-scan
 * from an output_payload's {@code per_bscan_mm2} array. Returns
 * null when the payload isn't fluid-shaped or the arrays are
 * missing — the colormap falls back to the legacy coral / grey
 * scheme in that case.
 */
function perBscanFluidMm2(payload: unknown): number[] | null {
  if (!payload || typeof payload !== 'object') return null
  const per = (payload as { per_bscan_mm2?: unknown }).per_bscan_mm2
  if (!per || typeof per !== 'object') return null
  const { irf, srf, ped } = per as { irf?: unknown; srf?: unknown; ped?: unknown }
  if (!Array.isArray(irf) || !Array.isArray(srf) || !Array.isArray(ped)) return null
  const n = Math.min(irf.length, srf.length, ped.length)
  const out = new Array<number>(n)
  for (let i = 0; i < n; i++) {
    const a = typeof irf[i] === 'number' ? (irf[i] as number) : 0
    const b = typeof srf[i] === 'number' ? (srf[i] as number) : 0
    const c = typeof ped[i] === 'number' ? (ped[i] as number) : 0
    out[i] = a + b + c
  }
  return out
}

/**
 * Per-slice Δ = current.per_bscan − prev.per_bscan (mm²). Null
 * when either job's detail hasn't loaded yet OR the payloads
 * lack the per_bscan_mm2 trace. The colormap reads the array
 * and the maxAbsDelta normaliser; when null, every bar falls
 * back to the legacy coral / grey scheme.
 */
const perSliceDeltaMm2 = computed<number[] | null>(() => {
  if (props.prevVisit == null) return null
  const curId = props.visit.retinalJobId
  const prvId = props.prevVisit.retinalJobId
  if (curId == null || prvId == null) return null
  const curJob = jobStore.jobs[curId]
  const prvJob = jobStore.jobs[prvId]
  if (!curJob || !prvJob) return null
  const cur = perBscanFluidMm2(curJob.outputPayload)
  const prv = perBscanFluidMm2(prvJob.outputPayload)
  if (!cur || !prv) return null
  const n = Math.min(cur.length, prv.length)
  if (n === 0) return null
  const out = new Array<number>(n)
  for (let i = 0; i < n; i++) out[i] = cur[i]! - prv[i]!
  return out
})

/** Largest absolute Δ across all slices — colormap normaliser. */
const maxAbsDeltaMm2 = computed<number>(() => {
  const d = perSliceDeltaMm2.value
  if (!d) return 0
  let m = 0
  for (const v of d) {
    const a = Math.abs(v)
    if (a > m) m = a
  }
  return m
})

/**
 * Diverging red-green colormap for a per-slice Δ. Normalised
 * against the visit's max absolute delta so the scale adapts
 * to each comparison's range (a baseline-vs-active-week showdown
 * doesn't drown a baseline-vs-baseline near-zero comparison).
 *
 * <ul>
 *   <li>Δ > 0 (more fluid than reference) → red (clinically bad).</li>
 *   <li>Δ < 0 (less fluid than reference) → emerald (clinically good).</li>
 *   <li>|Δ| ≈ 0 → mid-saturation neutral grey.</li>
 * </ul>
 *
 * <p>Alpha grows with magnitude so a major change pops while a
 * tiny one stays muted, avoiding "everything is red / green"
 * noise on borderline visits.
 */
function deltaColor(d: number): string {
  const m = maxAbsDeltaMm2.value
  if (m <= 0) return '#a8b1bf'
  const t = Math.max(-1, Math.min(1, d / m))
  if (Math.abs(t) < 0.05) return '#a8b1bf'
  const alpha = 0.45 + 0.55 * Math.abs(t)
  if (t > 0) return `rgba(220, 38, 38, ${alpha.toFixed(2)})` // red-600
  return `rgba(16, 185, 129, ${alpha.toFixed(2)})`            // emerald-500
}

/**
 * Resolve the activity bar's fill colour for index i.
 * Precedence:
 *   1. selected slice → navy (or sky in fullscreen for contrast)
 *   2. per-slice Δ colormap when {@link prevVisit} supplied + payloads loaded
 *   3. neutral grey when there's no comparison data
 *
 * <p>2026-06-26 user-feedback round — the legacy coral / grey
 * scheme was misleading without a reference visit: a coral bar
 * means 'this slice has fluid', which the operator might read as
 * 'this slice got worse' even though we never compared anything.
 * Reverting to a single grey tone when no Δ is available makes
 * the absence of comparison data visually clear.
 */
function barColor(i: number, a: number): string {
  void a
  if (i === props.slice) return fillsParent.value ? '#5fb4e5' : '#111d4e'
  const deltas = perSliceDeltaMm2.value
  if (deltas && deltas[i] != null) return deltaColor(deltas[i]!)
  return '#d7dce6'
}

/**
 * 2026-06-26 user-feedback round — fullscreen state.
 *
 * The wrapper toggles between inline layout and {@code fixed inset-0}
 * full-viewport mode. While open we Esc-handle + body-scroll-lock.
 * Auto-play stays running across the transition so a play-pause-fs
 * session resumes where it left off; ditto the slice / mask state
 * which lives on the parent via v-model.
 */
const fsOpen = ref(false)

function openFullscreen(): void {
  if (!props.enableFullscreen) return
  fsOpen.value = true
}

function closeFullscreen(): void {
  if (correctionPendingCount.value > 0) {
    correctionDiscardOpen.value = true
    return
  }
  fsOpen.value = false
}

function confirmDiscardCorrection(): void {
  correctionOverlayRef.value?.clearPending()
  correctionPendingCount.value = 0
  correctionDiscardOpen.value = false
  fsOpen.value = false
}

/* ── Sibling-envelope derived values ── */

const siblingCols = computed<number>(() => {
  const env = siblingEnvelope.value
  if (!env || env.shape.length < 2) return 0
  return env.shape.length === 3 ? (env.shape[2] ?? 0) : (env.shape[1] ?? 0)
})
const siblingNSurfaces = computed<number>(() => {
  const env = siblingEnvelope.value
  if (!env) return 0
  return env.shape.length === 3 ? (env.shape[0] ?? 0) : 1
})
const siblingEnvelopeData = computed<Float32Array | null>(() => {
  const env = siblingEnvelope.value
  if (!env || env.dtype !== 'float32') return null
  return env.data as Float32Array
})
const siblingLabels = computed<readonly string[]>(() => {
  const env = siblingEnvelope.value
  if (env?.labels?.length) return env.labels
  return IOWA_LAYER_LABELS
})

/**
 * nAMD viewer restricts corrections to ILM (idx 0) + BM (last). Both
 * surfaces are always-on visible whenever the sibling envelope exists;
 * the rest of the IOWA stack stays hidden to keep the fluid + layers
 * read uncluttered.
 */
const namdCorrectableLayerIndices = computed<readonly number[]>(() => {
  const n = siblingNSurfaces.value
  if (n <= 0) return []
  if (n === 1) return [0]
  return [0, n - 1]
})

/**
 * Static ILM + BM polyline paths (per current slice), used in the
 * non-fullscreen (read-only) view. Same Catmull-Rom-free flat polyline
 * the BscanViewer's surface_y branch draws — cheaper than mounting the
 * full editing overlay for the inline path. Empty when no sibling
 * envelope or no `done` layers job.
 */
const namdStaticOverlayPaths = computed<{ d: string; stroke: string; idx: number }[]>(() => {
  const data = siblingEnvelopeData.value
  if (!data) return []
  const cols = siblingCols.value
  const n = siblingNSurfaces.value
  if (!cols || n <= 0) return []
  const surfaceStride = props.nSlices * cols
  const z = Math.max(0, Math.min(props.nSlices - 1, props.slice))
  const out: { d: string; stroke: string; idx: number }[] = []
  for (const idx of namdCorrectableLayerIndices.value) {
    const sliceOffset = idx * surfaceStride + z * cols
    const seg: string[] = []
    let drawing = false
    for (let x = 0; x < cols; x++) {
      const y = data[sliceOffset + x] ?? 0
      if (y <= 0) { drawing = false; continue }
      seg.push(`${drawing ? 'L' : 'M'} ${x} ${y.toFixed(1)}`)
      drawing = true
    }
    if (seg.length > 0) {
      out.push({
        d: seg.join(' '),
        stroke: IOWA_LAYER_COLORS[idx % IOWA_LAYER_COLORS.length]!,
        idx,
      })
    }
  }
  return out
})

/**
 * Slot scope unpacker — BscanViewer's overlay slot exposes
 * { bboxStyle, imageDims, envelope }. The template needs to type-narrow
 * these values; doing it via tiny helpers avoids inline `as` casts which
 * the SFC's TS-in-template parser rejects (`<{ … }>` reads as a tag).
 */
function slotRows(slotProps: unknown): number {
  const dims = (slotProps as { imageDims?: { rows?: number } | null })?.imageDims
  return dims?.rows ?? 496
}
function slotBbox(slotProps: unknown): Record<string, string> {
  const bbox = (slotProps as { bboxStyle?: Record<string, string> })?.bboxStyle
  return bbox ?? {}
}

/* ── Layer-correction save flow ── */

let correctionSaveResolver: ((p: Map<number, Map<number, number[]>>) => void) | null = null
function onCorrectionOverlaySave(payload: Map<number, Map<number, number[]>>): void {
  if (correctionSaveResolver) {
    correctionSaveResolver(payload)
    correctionSaveResolver = null
  }
}

async function onCorrectionSaveClick(): Promise<void> {
  const overlay = correctionOverlayRef.value
  const targetJobId = siblingJobId.value
  if (!overlay || targetJobId == null) return
  if (correctionSaving.value || correctionPendingCount.value === 0) return
  correctionSaving.value = true
  try {
    const payload = await new Promise<Map<number, Map<number, number[]>>>((resolve) => {
      correctionSaveResolver = resolve
      overlay.emitSave()
    })
    let savedLayers = 0
    let savedSlices = 0
    await Promise.all(Array.from(payload.entries()).map(async ([layerIdx, perSlice]) => {
      const layerLabel = siblingLabels.value[layerIdx]
        ?? IOWA_LAYER_LABELS[layerIdx]
        ?? `L${layerIdx}`
      const rowsByZ: Record<string, number[]> = {}
      for (const [z, row] of perSlice) {
        rowsByZ[String(z)] = row
        savedSlices++
      }
      await saveLayerCorrection(targetJobId, layerIdx, layerLabel, rowsByZ)
      savedLayers++
    }))
    overlay.clearPending()
    correctionPendingCount.value = 0
    clearSegmentationEnvelopeCache(targetJobId)
    showCorrectionSavedToast(
      t('retinal.correction.savedToast', { layers: savedLayers, slices: savedSlices }),
    )
    emit('correction-saved', { layers: savedLayers, slices: savedSlices })
  } catch (e) {
    emit('correction-error', e instanceof Error ? e.message : String(e))
  } finally {
    correctionSaving.value = false
  }
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' && fsOpen.value) {
    closeFullscreen()
  }
}

watch(fsOpen, (open) => {
  if (typeof document === 'undefined') return
  if (open) {
    document.addEventListener('keydown', onKey)
    // Body scroll-lock — preserve the previous overflow so the
    // restore on close doesn't stomp a value the page set itself.
    fsPrevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  } else {
    document.removeEventListener('keydown', onKey)
    document.body.style.overflow = fsPrevOverflow
    fsPrevOverflow = ''
  }
})

let fsPrevOverflow = ''

onBeforeUnmount(() => {
  // Defensive — if the consumer tears the component down while
  // we're still fullscreen, make sure we don't leak the scroll-lock
  // or the keydown listener.
  if (fsOpen.value && typeof document !== 'undefined') {
    document.removeEventListener('keydown', onKey)
    document.body.style.overflow = fsPrevOverflow
  }
  if (correctionSavedTimer) {
    clearTimeout(correctionSavedTimer)
    correctionSavedTimer = null
  }
})

/** Format the visit date + label for the fullscreen header subtitle. */
const fsTitle = computed(() => `${eyeLabel.value} · ${props.visit.label} · ${formatDate(props.visit.date)}`)

/**
 * Layout-mode flag — true when the frame should fill its parent
 * (own fullscreen overlay OR external fill-container request).
 * The internal aspect-[16/9] constraint is released and the
 * scan box becomes a flex-1 child so the controls row lives
 * below it instead of below a fixed-aspect canvas.
 */
const fillsParent = computed(() => fsOpen.value || props.fillContainer)
</script>

<template>
  <div
    :data-testid="`namd-scan-frame-${idBase}`"
    :class="
      fsOpen
        ? 'fixed inset-0 z-50 bg-black/95 backdrop-blur-sm px-5 py-4 flex flex-col gap-3 select-none'
        : fillContainer
          ? 'h-full flex flex-col gap-2 select-none'
          : 'select-none'
    "
  >
    <!-- Fullscreen header — eyebrow + title + close button. Mirrors
         the design's FsHeader; only rendered when fsOpen so the
         inline layout keeps its original height. -->
    <header
      v-if="fsOpen"
      data-testid="namd-scan-fs-header"
      class="flex items-center justify-between gap-4 shrink-0"
    >
      <div class="flex items-center gap-3 text-white min-w-0">
        <span class="text-[12px] font-semibold uppercase tracking-[0.12em] whitespace-nowrap">
          {{ t('studyModules.namd.scanFrame.fsEyebrow') }}
        </span>
        <span class="text-white/45 text-[12px] truncate">{{ fsTitle }}</span>
      </div>
      <div class="flex items-center gap-2.5">
        <!-- 2026-06-27 — Inline success pill (not routed through the
             errors store, which would render it red via
             GlobalErrorToast). -->
        <transition
          enter-active-class="transition duration-150"
          leave-active-class="transition duration-300"
          enter-from-class="opacity-0 translate-y-1"
          leave-to-class="opacity-0"
        >
          <span
            v-if="correctionSavedToast"
            data-testid="namd-scan-fs-saved-toast"
            class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold bg-emerald-500/90 text-white shadow"
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
              <path d="M5 12 L10 17 L20 7" />
            </svg>
            {{ correctionSavedToast }}
          </span>
        </transition>
        <!-- 2026-06-29 — ETDRS-rings toggle on the fullscreen masthead. -->
        <button
          type="button"
          data-testid="namd-scan-fs-etdrs"
          :title="t('retinal.correction.etdrsToggle')"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition"
          :class="showEtdrsRings ? 'bg-amber-400 text-slate-900' : 'bg-white/10 text-white/85 hover:bg-white/20'"
          @click="toggleEtdrsRings"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <circle cx="12" cy="12" r="3" />
            <circle cx="12" cy="12" r="7" />
            <circle cx="12" cy="12" r="11" />
          </svg>
          ETDRS
        </button>
        <!-- 2026-06-26 — Save button surfaces only when (sibling layers
             job exists, role gate open, edits pending). The badge
             shows the unsaved-edit count. -->
        <button
          v-if="siblingJobId != null && canCorrectLayers"
          type="button"
          data-testid="namd-scan-fs-save"
          :disabled="correctionPendingCount === 0 || correctionSaving"
          class="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[11px] font-semibold transition"
          :class="correctionPendingCount > 0 && !correctionSaving
            ? 'bg-muw-teal text-white hover:bg-muw-teal-700'
            : 'bg-white/10 text-white/40'"
          @click="onCorrectionSaveClick"
        >
          {{ correctionSaving ? '…' : t('retinal.correction.save') }}
          <span
            v-if="correctionPendingCount > 0"
            class="ml-1 px-1.5 py-0.5 rounded-full bg-white/25 text-[10px]"
          >{{ t('retinal.correction.saveBadge', { n: correctionPendingCount }) }}</span>
        </button>
        <button
          type="button"
          data-testid="namd-scan-fs-close"
          class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold bg-white/10 text-white hover:bg-white/20 transition"
          @click="closeFullscreen"
        >
          <span class="inline-block w-3.5 h-3.5" v-html="I.close" />
          {{ t('studyModules.namd.scanFrame.fsClose') }}
        </button>
      </div>
    </header>

    <!-- 2026-06-26 — Discard-correction confirm. Mounted at the root
         so it overlays the fullscreen masthead + scan box. -->
    <div
      v-if="correctionDiscardOpen"
      data-testid="namd-scan-fs-discard-confirm"
      class="fixed inset-0 z-[60] bg-black/70 backdrop-blur-sm flex items-center justify-center"
      @click.self="correctionDiscardOpen = false"
    >
      <div class="bg-white rounded-2xl shadow-xl max-w-md mx-auto p-6">
        <p class="text-[14px] font-semibold text-slate-900 mb-4">
          {{ t('retinal.correction.discardConfirm') }}
        </p>
        <div class="flex items-center justify-end gap-2">
          <button
            type="button"
            class="px-3.5 py-1.5 rounded-lg border border-slate-200 text-[12px] font-medium text-slate-700 hover:bg-slate-50"
            @click="correctionDiscardOpen = false"
          >{{ t('retinal.correction.discardCancel') }}</button>
          <button
            type="button"
            class="px-3.5 py-1.5 rounded-lg bg-rose-600 text-white text-[12px] font-semibold hover:bg-rose-700"
            @click="confirmDiscardCorrection"
          >{{ t('retinal.correction.discardYes') }}</button>
        </div>
      </div>
    </div>

    <!-- Scan frame: black background, rounded corners, the
         BscanViewer fills the entire aspect-[16/9] box (or h-full
         in fullscreen) and the corner overlays sit absolutely on
         top. Hover + scroll scrubs through the B-scans via the
         wheel handler. -->
    <div
      :class="[
        'relative rounded-xl overflow-hidden bg-black ring-1 ring-black/40',
        fillsParent ? 'flex-1 min-h-0' : '',
      ]"
      style="box-shadow: 0 8px 20px -8px rgba(0,0,0,0.45)"
      @wheel.prevent="onWheel"
    >
      <div :class="fillsParent ? 'w-full h-full' : 'aspect-[16/9]'">
        <div v-if="bscanDcmUrl" class="w-full h-full">
          <!-- 2026-06-24 user-feedback round — `:key` forces a fresh
               BscanViewer mount whenever the bound DICOM URL changes
               (e.g. the Compare tab swaps the left or right visit).
               Without it, BscanViewer's cornerstone initViewer() only
               runs onMounted; the segmentation overlay was updating
               correctly (the envelope composable watches jobId) but
               the cornerstone stack stayed on the FIRST visit's
               bscan.dcm. -->
          <BscanViewer
            :key="bscanDcmUrl"
            :bscan-dcm-url="bscanDcmUrl"
            :n-bscans="nSlices"
            :model-value="slice"
            :job-id="jobIdRef"
            :show-segmentation="mask"
            :static-frame="true"
            :fill-container="fillsParent"
          >
            <!-- 2026-06-26 — Sibling ILM + BM overlay. Inline path uses
                 a tiny SVG (read-only polylines) so we don't ship the
                 editing component on every nAMD viewer mount. In fs
                 mode, the BscanLayerEditOverlay replaces it when
                 (a) a sibling layers/bm job exists and (b) the role
                 gate is open. The slot is conditional so non-editing
                 BscanViewer mounts keep the cheap no-op default. -->
            <template #overlay="slotProps">
              <!-- Sibling-layers overlay (existing) -->
              <template v-if="mask && siblingEnvelopeData && namdStaticOverlayPaths.length > 0">
                <BscanLayerEditOverlay
                  v-if="fsOpen && siblingJobId != null"
                  ref="correctionOverlayRef"
                  :job-id="siblingJobId"
                  :n-bscans="nSlices"
                  :cols="siblingCols"
                  :rows="slotRows(slotProps)"
                  :model-value="slice"
                  :envelope-data="siblingEnvelopeData"
                  :n-surfaces="siblingNSurfaces"
                  :labels="siblingLabels"
                  :correctable-layer-indices="namdCorrectableLayerIndices"
                  :can-edit="canCorrectLayers"
                  :bbox-style="slotBbox(slotProps)"
                  @update:model-value="(z) => emit('update:slice', z)"
                  @save="onCorrectionOverlaySave"
                  @pending-edit-count="(n) => correctionPendingCount = n"
                />
                <svg
                  v-else
                  data-testid="namd-static-layers-overlay"
                  :viewBox="`0 0 ${siblingCols} ${slotRows(slotProps)}`"
                  :style="slotBbox(slotProps)"
                  preserveAspectRatio="none"
                  class="pointer-events-none"
                >
                  <template
                    v-for="path in namdStaticOverlayPaths"
                    :key="`namd-layer-${path.idx}`"
                  >
                    <path
                      :d="path.d" fill="none" stroke="#0b1220"
                      stroke-width="3" stroke-opacity="0.45"
                      vector-effect="non-scaling-stroke"
                    />
                    <path
                      :d="path.d" fill="none" :stroke="path.stroke"
                      stroke-width="1.6" stroke-opacity="0.95"
                      vector-effect="non-scaling-stroke"
                    />
                  </template>
                </svg>
              </template>
              <!-- ETDRS rings (independent toggle, always evaluated) -->
              <EtdrsRingsBscanIndicator
                :n-bscans="nSlices"
                :current-z="slice"
                :image-dims="slotImageDims(slotProps)"
                :pixel-spacing="slotPixelSpacing(slotProps)"
                :bbox-style="slotBbox(slotProps)"
                :visible="showEtdrsRings"
              />
            </template>
          </BscanViewer>
        </div>
        <div
          v-else
          data-testid="namd-scan-frame-empty"
          class="flex items-center justify-center w-full h-full text-white/40 text-sm"
        >
          {{ t('studyModules.namd.viewer.empty') }}
        </div>
      </div>

      <!-- Top-left meta pill -->
      <div class="absolute top-3 left-3 flex items-center gap-2 pointer-events-none">
        <span class="inline-flex items-center gap-1.5 rounded-md bg-black/55 backdrop-blur-sm text-white/90 text-[11px] font-medium px-2 py-1">
          <span class="text-muw-sky-300 inline-block w-3 h-3">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M2 12 C5 6 10 4 12 4 C14 4 19 6 22 12 C19 18 14 20 12 20 C10 20 5 18 2 12 Z" stroke-linecap="round" stroke-linejoin="round" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          </span>
          {{ eyeLabel }} · {{ visit.label }}
        </span>
        <span class="rounded-md bg-black/55 backdrop-blur-sm text-white/70 text-[11px] px-2 py-1 font-mono">
          {{ formatDate(visit.date) }}
        </span>
      </div>

      <!-- Top-right slice counter + maximize/minimize button. The
           buttons cluster MUST be pointer-events-auto so the click
           on the maximize/minimize affordance lands. -->
      <div class="absolute top-3 right-3 flex items-center gap-2">
        <span
          class="rounded-md bg-black/55 backdrop-blur-sm text-white/85 text-[11px] px-2 py-1 font-mono tabular-nums pointer-events-none"
          :data-testid="`namd-scan-counter-${idBase}`"
        >
          {{ t('studyModules.namd.scanFrame.bscanCounter', { z: slice + 1, n: nSlices }) }}<template v-if="atFovea"> · {{ t('studyModules.namd.scanFrame.fovea') }}</template>
        </span>
        <button
          v-if="enableFullscreen"
          type="button"
          :data-testid="`namd-scan-fs-toggle-${idBase}`"
          :aria-label="fsOpen ? t('studyModules.namd.scanFrame.fsMinimize') : t('studyModules.namd.scanFrame.fsMaximize')"
          :title="fsOpen ? t('studyModules.namd.scanFrame.fsMinimize') : t('studyModules.namd.scanFrame.fsMaximize')"
          class="inline-flex items-center justify-center w-7 h-7 rounded-md bg-black/55 backdrop-blur-sm text-white/85 hover:bg-white hover:text-muw-blue transition"
          @click="fsOpen ? closeFullscreen() : openFullscreen()"
        >
          <span class="inline-block w-3.5 h-3.5" v-html="fsOpen ? I.minimize : I.maximize" />
        </button>
      </div>

      <!-- Bottom-left scale bar — 1 mm of design real estate -->
      <div class="absolute bottom-3 left-3 flex items-center gap-2 text-white/55 text-[10px] pointer-events-none">
        <span class="block w-12 h-[3px] bg-white/55 rounded" />
        <span>1 mm</span>
      </div>

      <!-- Bottom-right KI-Maske toggle. 2026-06-26 — suppressed
           in fillContainer mode: the compare-fs stack provides a
           single masthead KI-Maske button which already drives
           both panes' mask state via the shared v-model, so two
           more buttons in the lower-right of each pane are
           redundant + their state can desync visually when the
           operator clicks the wrong one. -->
      <button
        v-if="!fillContainer"
        type="button"
        :data-testid="`namd-scan-mask-toggle-${idBase}`"
        class="absolute bottom-3 right-3 inline-flex items-center gap-1.5 whitespace-nowrap rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition"
        :class="mask ? 'bg-white text-muw-blue' : 'bg-black/55 text-white/85 hover:bg-black/70'"
        @click="toggleMask"
      >
        <span class="w-2 h-2 rounded-full" :class="mask ? 'bg-muw-teal' : 'bg-white/50'" />
        {{ mask ? t('studyModules.namd.scanFrame.maskOn') : t('studyModules.namd.scanFrame.maskOff') }}
      </button>
      <!-- 2026-06-29 — ETDRS-rings toggle (1 / 3 / 6 mm). Sits next to
           the mask toggle in inline mode. Hidden in compare-tab
           fill-container mode for the same anti-duplicate reasoning
           as the mask toggle. -->
      <button
        v-if="!fillContainer"
        type="button"
        :data-testid="`namd-scan-etdrs-toggle-${idBase}`"
        :title="t('retinal.correction.etdrsToggle')"
        class="absolute bottom-3 right-32 inline-flex items-center gap-1.5 whitespace-nowrap rounded-lg px-2.5 py-1.5 text-[11px] font-semibold transition"
        :class="showEtdrsRings ? 'bg-amber-400 text-slate-900' : 'bg-black/55 text-white/85 hover:bg-black/70'"
        @click="toggleEtdrsRings"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
          <circle cx="12" cy="12" r="3" />
          <circle cx="12" cy="12" r="7" />
          <circle cx="12" cy="12" r="11" />
        </svg>
        ETDRS
      </button>
    </div>

    <!-- Controls row: play/pause + activity heatmap + slider + en-face.
         Fullscreen mode (own backdrop OR external fill-container)
         drops the en-face thumb (the dark backdrop leaves room for
         a wider slider strip and the locator chrome doesn't read
         against black) and re-tints the labels white. -->
    <div :class="['flex items-center gap-3', fillsParent ? 'mt-0 shrink-0' : 'mt-3']">
      <!-- 2026-06-26 — play/pause hidden in fillContainer mode
           (compare-fs stack). Each pane has its own local
           `playing` ref so two play buttons would visually
           desync the moment one was clicked (the other would
           keep showing 'play' even though its slice was
           advancing via the shared v-model). Heatmap clicks +
           slider drag are sufficient navigation in the stacked
           view; auto-play can be re-introduced as a single
           masthead control if operators ask for it. -->
      <button
        v-if="!fillContainer"
        type="button"
        :data-testid="`namd-scan-play-${idBase}`"
        class="shrink-0 w-9 h-9 rounded-lg bg-muw-blue text-white inline-flex items-center justify-center hover:bg-muw-blue-700 transition"
        :aria-label="playing ? t('studyModules.namd.scanFrame.pause') : t('studyModules.namd.scanFrame.play')"
        @click="togglePlay"
      >
        <svg v-if="!playing" viewBox="0 0 24 24" fill="currentColor" class="w-3.5 h-3.5">
          <path d="M6 4 L20 12 L6 20 Z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="currentColor" class="w-3.5 h-3.5">
          <rect x="5" y="4" width="5" height="16" rx="1" />
          <rect x="14" y="4" width="5" height="16" rx="1" />
        </svg>
      </button>

      <div class="flex-1 min-w-0">
        <!-- Activity heatmap: one clickable bar per slice -->
        <div
          :data-testid="`namd-scan-activity-${idBase}`"
          class="flex items-end gap-px h-3 mb-1.5 px-0.5"
        >
          <button
            v-for="(a, i) in activity"
            :key="i"
            type="button"
            class="flex-1 rounded-[1px] cursor-pointer p-0 border-0 outline-none focus:ring-1 focus:ring-muw-sky"
            :style="{
              height: `${20 + a * 80}%`,
              background: barColor(i, a),
            }"
            :aria-label="`B-Scan ${i + 1}`"
            @click="setSlice(i)"
          />
        </div>
        <input
          type="range"
          min="0"
          :max="Math.max(0, nSlices - 1)"
          :value="slice"
          :class="[
            'w-full',
            fillsParent || sliderTone === 'sky' ? 'accent-muw-sky' : 'accent-muw-blue',
          ]"
          :data-testid="`namd-scan-slider-${idBase}`"
          @input="(e) => setSlice(Number((e.target as HTMLInputElement).value))"
        />
        <!-- 2026-06-26 — superior/inferior + count label hidden in
             the compare-stacked fillContainer mode to reclaim ~16px
             of vertical space per pane (two panes → 32px). The
             single-fullscreen variant keeps the labels since it
             has the budget. -->
        <div
          v-if="!props.fillContainer"
          :class="['flex justify-between text-[10px] mt-1 px-0.5', fillsParent ? 'text-white/40' : 'text-slate-400']"
        >
          <span>{{ t('studyModules.namd.scanFrame.superior') }}</span>
          <span :class="['font-medium', fillsParent ? 'text-white/60' : 'text-slate-500']">
            {{ t('studyModules.namd.scanFrame.volumeScroll', { n: nSlices }) }}
          </span>
          <span>{{ t('studyModules.namd.scanFrame.inferior') }}</span>
        </div>
      </div>

      <NamdEnFaceLocator
        v-if="showThumbs && !fillsParent"
        :job-id="jobIdRef"
        :slice="slice"
        :n-slices="nSlices"
      />
    </div>
  </div>
</template>
