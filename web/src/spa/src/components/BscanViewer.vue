<script setup lang="ts">
/**
 * nAMD treat-and-extend Slice 5 (2026-06-20) — in-SPA B-scan
 * navigator.
 *
 * Mounts Cornerstone.js 3 against a multi-frame `bscan.dcm`
 * artifact and surfaces a stack scrubber so the physician can
 * scroll individual OCT slices without leaving the browser.
 * Critical for the Arm B path of the nAMD study, where the AI
 * en-face panel is gated off and the raw OCT IS the entire
 * dataset the physician sees.
 *
 * Lazy-loaded via {@code defineAsyncComponent} from
 * {@code RetinalMetricsView} so the ~150 KB Cornerstone bundle
 * only ships to operators who actually open a retinal view.
 *
 * Bidirectional hover sync: emits {@code update:bscan-z} when the
 * user scrubs; accepts {@code modelValue} (the current B-scan
 * index) as input so the {@link FundusOverlay}'s per-B-scan hover
 * can jump the viewer to the matching slice.
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

interface Props {
  /** Absolute URL of the multi-frame {@code bscan.dcm} artifact. */
  bscanDcmUrl: string
  /** Total B-scan count (n_bscans) — used to drive the slider range. */
  nBscans: number
  /** Current B-scan index (0-based) — bidirectional via v-model. */
  modelValue: number
  /**
   * 2026-06-22 — list of per-B-scan segmentation overlay artifact
   * names (e.g. {@code seg_bscan_0042.png}). The viewer overlays
   * the matching slice's PNG on top of the cornerstone canvas so
   * the operator sees the IRF / SRF / PED segmentation on the
   * exact B-scan it applies to. Pass {@code []} or omit to hide
   * the overlay (legacy jobs without per-slice PNGs).
   */
  segOverlayUrlBase?: string
  segOverlayArtifactNames?: string[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [z: number]
}>()

const containerEl = ref<HTMLDivElement | null>(null)
const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const errorMessage = ref<string | null>(null)

// Hold the cornerstone viewport ref non-reactively (the cornerstone
// objects mutate internally; we don't want Vue's proxy attached).
const viewport = shallowRef<unknown | null>(null)
const renderingEngineRef = shallowRef<{ destroy: () => void } | null>(null)

const sliderMax = computed(() => Math.max(0, props.nBscans - 1))

/**
 * 2026-06-22 — URL of the segmentation overlay PNG for the current
 * slice, or null when the runner didn't emit a PNG for this z
 * (slices with no biomarker voxels are skipped server-side). The
 * SPA absolutely-positions an <img> at the same size as the
 * cornerstone canvas so it overlays in pixel-space (the per-slice
 * PNG is emitted at the DICOM frame's native (rows, cols)).
 */
const segOverlayUrl = computed<string | null>(() => {
  const base = props.segOverlayUrlBase
  const names = props.segOverlayArtifactNames
  if (!base || !names || names.length === 0) return null
  // Filenames are zero-padded to four digits — `seg_bscan_0042.png`.
  const padded = String(props.modelValue).padStart(4, '0')
  const expected = `seg_bscan_${padded}.png`
  if (!names.includes(expected)) return null
  // Compose the artifact URL the same way RetinalMetricsView builds
  // the rest of the per-job artifact links. The base ends with the
  // job's artifact directory; just append the filename.
  return `${base}${expected}`
})

async function initViewer(): Promise<void> {
  if (!containerEl.value) return
  status.value = 'loading'
  errorMessage.value = null
  try {
    // 2026-06-22 — @cornerstonejs/dicom-image-loader 1.86 ships two
    // bundles in dist/: the default web-worker build (needs an
    // explicit worker URL via Vite worker-bundling) and a
    // no-web-workers build that decodes on the main thread. The
    // latter avoids the worker-path bootstrapping entirely; we're
    // viewing one DICOM at a time so the main-thread decode is fine
    // performance-wise. Hitting the file path directly bypasses
    // package-exports inversions that older bundler resolutions
    // sometimes apply to the default UMD entry.
    const [
      cornerstoneCoreModule,
      cornerstoneLoaderModule,
      dicomParserModule,
    ] = await Promise.all([
      import('@cornerstonejs/core'),
      // @ts-expect-error — sub-path import with no .d.ts;
      // the runtime shape matches the with-workers bundle minus
      // webWorkerManager.
      import('@cornerstonejs/dicom-image-loader/dist/cornerstoneDICOMImageLoaderNoWebWorkers.bundle.min.js'),
      import('dicom-parser'),
    ])
    // Dynamic import may wrap the namespace under .default for UMD
    // bundles; unwrap defensively so a future ESM-native release
    // doesn't double-wrap.
    const cornerstoneCore = (cornerstoneCoreModule as { default?: unknown }).default ?? cornerstoneCoreModule
    const cornerstoneLoader = (cornerstoneLoaderModule as { default?: unknown }).default ?? cornerstoneLoaderModule
    const dicomParser = (dicomParserModule as { default?: unknown }).default ?? dicomParserModule

    // Wire the DICOM image loader against the cornerstone core + the
    // parser. Subsequent component mounts in the same session no-op
    // via the loader's internal singleton. The NoWebWorkers build
    // does NOT expose webWorkerManager / init at the top level —
    // decoding kicks in transparently on first wadouri fetch.
    type CornerstoneLoader = {
      external: { cornerstone: unknown; dicomParser: unknown }
    }
    const loader = cornerstoneLoader as CornerstoneLoader
    loader.external.cornerstone = cornerstoneCore
    loader.external.dicomParser = dicomParser
    await (cornerstoneCore as { init: () => Promise<void> }).init()

    const engineId = `bscan-engine-${Math.floor(performance.now())}`
    const viewportId = `bscan-viewport-${Math.floor(performance.now())}`
    type RE = {
      destroy: () => void
      enableElement: (input: unknown) => void
      getViewport: (id: string) => unknown
    }
    const RenderingEngine = (cornerstoneCore as unknown as {
      RenderingEngine: new (id: string) => RE
    }).RenderingEngine
    const re = new RenderingEngine(engineId)
    renderingEngineRef.value = re

    // Build per-frame wado image IDs — one entry per B-scan.
    const imageIds = Array.from({ length: props.nBscans }, (_, frame) =>
      `wadouri:${props.bscanDcmUrl}?frame=${frame}`,
    )

    // Enable a stack viewport into the container element.
    const Enums = (cornerstoneCore as unknown as { Enums: { ViewportType: { STACK: string } } }).Enums
    re.enableElement({
      viewportId,
      type: Enums.ViewportType.STACK,
      element: containerEl.value,
    })
    const vp = re.getViewport(viewportId) as {
      setStack: (ids: string[]) => Promise<void>
      setImageIdIndex: (idx: number) => Promise<void>
      render: () => void
    }
    viewport.value = vp
    await vp.setStack(imageIds)
    await vp.setImageIdIndex(clampZ(props.modelValue))
    vp.render()
    status.value = 'ready'

    // 2026-06-22 — pre-warm caches so scroll-through is smooth.
    //  1. Cornerstone image cache: kick off loadAndCacheImages for
    //     every wadouri frame. The wheel handler then hits an
    //     in-memory cache instead of re-decoding the multi-frame
    //     DICOM on every notch.
    //  2. Seg overlay PNG cache: prefetch the artifact PNGs via
    //     `new Image()` so swapping the `<img src>` on slice change
    //     resolves from the browser's HTTP cache.
    // Both run in the background; failures are non-fatal — the
    // foreground scroll path still works even if prefetch breaks.
    try {
      const il = (cornerstoneCore as unknown as {
        imageLoader: { loadAndCacheImages?: (ids: string[], opts?: unknown) => unknown }
      }).imageLoader
      il?.loadAndCacheImages?.(imageIds, { priority: 0, requestType: 'prefetch' })
    } catch {
      /* prefetch is best-effort; never blocks the scroll path */
    }
    prefetchSegOverlays()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Cornerstone init failed'
    status.value = 'error'
  }
}

function clampZ(z: number): number {
  return Math.max(0, Math.min(z, sliderMax.value))
}

/**
 * 2026-06-22 round 9 follow-up — mouse-wheel scrolls through B-scans
 * when the cursor is over the viewer. Matches Heidelberg HRA + most
 * clinical OCT viewers; operators muscle-memory the same gesture
 * from the device they came from. The @wheel.prevent on the
 * container stops the gesture from also scrolling the page.
 *
 * Accumulator: trackpad gestures fire many small deltaY values per
 * physical scroll; reducing every event to +1/-1 with a tiny
 * dead-zone keeps the slice change predictable + matches mouse-wheel
 * "one notch = one slice" feel without resorting to a real
 * time-based throttle.
 */
let wheelAccum = 0
const hasInteracted = ref(false)

/**
 * 2026-06-22 — coalesce wheel events: track the latest pending
 * slice index and only fire setImageIdIndex once per animation
 * frame. Trackpads fire ~60 wheel events / sec; without this the
 * SPA queued one cornerstone render per event and lagged behind
 * the user's gesture. Now the visible slice always reflects the
 * latest accumulated delta, even if intermediate events were
 * dropped.
 */
let pendingZ: number | null = null
let rafId: number | null = null
function schedulePendingZ(): void {
  if (rafId != null) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    if (pendingZ == null) return
    const target = pendingZ
    pendingZ = null
    void setZ(target)
  })
}

function onWheel(ev: WheelEvent): void {
  if (status.value !== 'ready') return
  wheelAccum += ev.deltaY
  const threshold = 24 // px — calibrated for both notched mice (~100 per notch) and trackpads (~5-20 per tick)
  let next: number | null = null
  if (wheelAccum >= threshold) {
    wheelAccum = 0
    next = (pendingZ ?? props.modelValue) + 1
  } else if (wheelAccum <= -threshold) {
    wheelAccum = 0
    next = (pendingZ ?? props.modelValue) - 1
  }
  if (next == null) return
  hasInteracted.value = true
  pendingZ = clampZ(next)
  schedulePendingZ()
}

/**
 * Prefetch every per-slice seg overlay PNG so the `<img src>` swap
 * on scroll lands an already-cached resource. Uses `new Image()`
 * because the browser's image cache key matches what `<img src>`
 * resolves against; no need to roundtrip through fetch().
 */
function prefetchSegOverlays(): void {
  const base = props.segOverlayUrlBase
  const names = props.segOverlayArtifactNames
  if (!base || !names || names.length === 0) return
  for (const name of names) {
    if (!name.startsWith('seg_bscan_') || !name.endsWith('.png')) continue
    const img = new Image()
    img.decoding = 'async'
    img.src = `${base}${name}`
  }
}

async function setZ(z: number) {
  const clamped = clampZ(z)
  if (clamped !== props.modelValue) emit('update:modelValue', clamped)
  if (viewport.value) {
    const vp = viewport.value as {
      setImageIdIndex: (idx: number) => Promise<void>
      render: () => void
    }
    await vp.setImageIdIndex(clamped)
    vp.render()
  }
}

watch(
  () => props.modelValue,
  async (z) => {
    if (status.value !== 'ready') return
    if (viewport.value) {
      const vp = viewport.value as {
        setImageIdIndex: (idx: number) => Promise<void>
        render: () => void
      }
      await vp.setImageIdIndex(clampZ(z))
      vp.render()
    }
  },
)

onMounted(initViewer)

onBeforeUnmount(() => {
  try {
    renderingEngineRef.value?.destroy()
  } catch {
    /* never throw on unmount */
  }
})
</script>

<template>
  <section
    data-testid="bscan-viewer"
    class="bg-slate-900 rounded-muw overflow-clip border border-slate-200"
  >
    <header class="px-3 py-1.5 flex items-baseline justify-between bg-slate-800 text-slate-200">
      <h3 class="text-xs font-semibold uppercase tracking-wider">
        {{ t('retinal.bscanViewer.header') }}
      </h3>
      <span class="text-[11px] text-slate-400 tabular-nums">
        {{ t('retinal.bscanViewer.position', { current: modelValue + 1, total: nBscans }) }}
      </span>
    </header>

    <!-- 2026-06-22 — wrap the canvas + the per-slice seg overlay so
         the overlay can be absolutely positioned over the canvas
         without competing with the cornerstone DOM. The overlay
         <img> covers the same box as the canvas (object-fit:
         contain matches the way cornerstone draws inside the
         aspect-[4/3] container) so the per-A-scan biomarker mask
         lands in-pixel. pointer-events:none so the canvas still
         receives the keydown / wheel events. -->
    <div class="relative aspect-[4/3] w-full bg-black">
      <div
        ref="containerEl"
        data-testid="bscan-viewer-canvas"
        class="absolute inset-0"
        tabindex="0"
        @keydown.left.prevent="setZ(modelValue - 1)"
        @keydown.right.prevent="setZ(modelValue + 1)"
        @wheel.prevent="onWheel"
      />
      <img
        v-if="segOverlayUrl && status === 'ready'"
        :src="segOverlayUrl"
        :alt="t('retinal.bscanViewer.segOverlayAlt', { current: modelValue + 1, total: nBscans })"
        class="absolute inset-0 w-full h-full pointer-events-none mix-blend-screen"
        style="object-fit: contain;"
        data-testid="bscan-viewer-seg-overlay"
      />
      <!-- Discoverability hint — auto-fades after the operator has
           used the scroll/scrub once (the hint is only useful for the
           first interaction; after that it's noise). -->
      <div
        v-if="status === 'ready' && !hasInteracted"
        class="absolute bottom-2 right-2 px-2 py-0.5 rounded bg-slate-900/70 text-slate-200 text-[10px] uppercase tracking-wider pointer-events-none"
      >
        {{ t('retinal.bscanViewer.scrollHint') }}
      </div>
    </div>

    <div
      v-if="status === 'loading'"
      data-testid="bscan-viewer-loading"
      class="px-3 py-2 text-xs text-slate-300"
    >
      {{ t('retinal.bscanViewer.loading') }}
    </div>
    <div
      v-else-if="status === 'error'"
      data-testid="bscan-viewer-error"
      class="px-3 py-2 text-xs text-rose-300"
    >
      {{ t('retinal.bscanViewer.errorPrefix') }} {{ errorMessage }}
    </div>

    <div class="px-3 py-2 flex items-center gap-2 bg-slate-800">
      <button
        type="button"
        class="text-slate-300 hover:text-white px-2 py-0.5 text-xs"
        :disabled="modelValue <= 0"
        @click="setZ(modelValue - 1)"
      >
        ←
      </button>
      <input
        type="range"
        min="0"
        :max="sliderMax"
        :value="modelValue"
        class="flex-1"
        data-testid="bscan-viewer-slider"
        @input="(e) => setZ(Number((e.target as HTMLInputElement).value))"
      />
      <button
        type="button"
        class="text-slate-300 hover:text-white px-2 py-0.5 text-xs"
        :disabled="modelValue >= sliderMax"
        @click="setZ(modelValue + 1)"
      >
        →
      </button>
    </div>
  </section>
</template>
