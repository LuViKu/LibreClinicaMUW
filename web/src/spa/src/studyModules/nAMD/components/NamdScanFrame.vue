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
import { artifactUrl } from '@/api/retinal'
import { useSegmentationEnvelope } from '@/composables/useSegmentationEnvelope'
import { useRetinalJobStore } from '@/stores/retinalJob'
import { formatDate } from '@/lib/dateFormat'
import NamdEnFaceLocator from './NamdEnFaceLocator.vue'
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
}

const props = withDefaults(defineProps<Props>(), {
  showThumbs: true,
  idBase: 'frame',
  enableFullscreen: true,
  sliderTone: 'navy',
  fillContainer: false,
  prevVisit: null,
})

const emit = defineEmits<{
  'update:slice': [z: number]
  'update:mask': [m: boolean]
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
  fsOpen.value = false
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
      <button
        type="button"
        data-testid="namd-scan-fs-close"
        class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11px] font-semibold bg-white/10 text-white hover:bg-white/20 transition"
        @click="closeFullscreen"
      >
        <span class="inline-block w-3.5 h-3.5" v-html="I.close" />
        {{ t('studyModules.namd.scanFrame.fsClose') }}
      </button>
    </header>

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
          />
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
