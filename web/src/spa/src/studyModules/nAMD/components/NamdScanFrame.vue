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
import NamdEnFaceLocator from './NamdEnFaceLocator.vue'
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
}

const props = withDefaults(defineProps<Props>(), {
  showThumbs: true,
  idBase: 'frame',
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

const dateFormatter = computed(() => {
  if (typeof Intl === 'undefined') return null
  try {
    return new Intl.DateTimeFormat('de-AT', { day: '2-digit', month: 'short', year: 'numeric' })
  } catch {
    return null
  }
})

function fmtDate(iso: string): string {
  if (!iso) return ''
  if (!dateFormatter.value) return iso
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return dateFormatter.value.format(d)
}

function setSlice(z: number): void {
  const clamped = Math.max(0, Math.min(props.nSlices - 1, z))
  emit('update:slice', clamped)
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
</script>

<template>
  <div
    :data-testid="`namd-scan-frame-${idBase}`"
    class="select-none"
  >
    <!-- Scan frame: black background, rounded corners, the
         BscanViewer fills the entire aspect-[16/9] box and the
         corner overlays sit absolutely on top. -->
    <div
      class="relative rounded-xl overflow-hidden bg-black ring-1 ring-black/40"
      style="box-shadow: 0 8px 20px -8px rgba(0,0,0,0.45)"
    >
      <div class="aspect-[16/9]">
        <div v-if="bscanDcmUrl" class="w-full h-full">
          <BscanViewer
            :bscan-dcm-url="bscanDcmUrl"
            :n-bscans="nSlices"
            :model-value="slice"
            :job-id="jobIdRef"
            :show-segmentation="mask"
            :static-frame="true"
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
          {{ fmtDate(visit.date) }}
        </span>
      </div>

      <!-- Top-right slice counter -->
      <div class="absolute top-3 right-3 flex items-center gap-2 pointer-events-none">
        <span
          class="rounded-md bg-black/55 backdrop-blur-sm text-white/85 text-[11px] px-2 py-1 font-mono tabular-nums"
          :data-testid="`namd-scan-counter-${idBase}`"
        >
          {{ t('studyModules.namd.scanFrame.bscanCounter', { z: slice + 1, n: nSlices }) }}<template v-if="atFovea"> · {{ t('studyModules.namd.scanFrame.fovea') }}</template>
        </span>
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

    <!-- Controls row: play/pause + activity heatmap + slider + en-face -->
    <div class="mt-3 flex items-center gap-3">
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
          class="w-full accent-muw-blue"
          :data-testid="`namd-scan-slider-${idBase}`"
          @input="(e) => setSlice(Number((e.target as HTMLInputElement).value))"
        />
        <div class="flex justify-between text-[10px] text-slate-400 mt-1 px-0.5">
          <span>{{ t('studyModules.namd.scanFrame.superior') }}</span>
          <span class="font-medium text-slate-500">
            {{ t('studyModules.namd.scanFrame.volumeScroll', { n: nSlices }) }}
          </span>
          <span>{{ t('studyModules.namd.scanFrame.inferior') }}</span>
        </div>
      </div>

      <NamdEnFaceLocator
        v-if="showThumbs"
        :job-id="jobIdRef"
        :slice="slice"
        :n-slices="nSlices"
      />
    </div>
  </div>
</template>
