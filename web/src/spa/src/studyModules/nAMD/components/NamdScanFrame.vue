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
   * 2026-06-26 user-feedback round — opt-in fullscreen toggle.
   * When true (default), the frame's top-right shows a maximize
   * button; clicking it switches the wrapper to {@code fixed inset-0}
   * positioning over a dark backdrop, the BscanViewer fills the
   * whole viewport, and Esc / the close button restores inline mode.
   * Cornerstone canvas stays mounted across the transition (we
   * toggle CSS only, no Teleport) so the WebGL context survives.
   * Compare-tab side-by-side frames opt out by passing false — that
   * tab's stacked-fullscreen mode is owned at the tab level.
   */
  enableFullscreen?: boolean
  /**
   * 2026-06-26 user-feedback round — slider accent colour. The
   * inline OCT-Viewer-tab variant (paired with FundusOverlay)
   * uses the inference-job viewer's sky-blue thumb so the visual
   * language between the two viewers matches. Other consumers
   * (Overview-tab inline OCT, Compare-tab frames) keep the
   * legacy navy thumb. Fullscreen mode always uses sky-blue
   * regardless — the dark backdrop swallows navy.
   */
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
}

const props = withDefaults(defineProps<Props>(), {
  showThumbs: true,
  idBase: 'frame',
  enableFullscreen: true,
  sliderTone: 'navy',
  fillContainer: false,
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
          ? 'h-full flex flex-col gap-3 select-none'
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

      <!-- Bottom-right KI-Maske toggle -->
      <button
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
      <button
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
              background:
                i === slice
                  ? '#111d4e'
                  : a > 0.25
                    ? 'rgba(217,97,74,0.55)'
                    : '#d7dce6',
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
        <div :class="['flex justify-between text-[10px] mt-1 px-0.5', fillsParent ? 'text-white/40' : 'text-slate-400']">
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
