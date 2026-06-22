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
import { useSegmentationEnvelope } from '@/composables/useSegmentationEnvelope'

const { t } = useI18n()

interface Props {
  /** Absolute URL of the multi-frame {@code bscan.dcm} artifact. */
  bscanDcmUrl: string
  /** Total B-scan count (n_bscans) — used to drive the slider range. */
  nBscans: number
  /** Current B-scan index (0-based) — bidirectional via v-model. */
  modelValue: number
  /**
   * 2026-06-22 — retinal job id used to fetch the segmentation
   * envelope ({@code GET /retinal-jobs/{id}/segmentation}). The
   * viewer decodes the envelope on a 2D canvas overlay so the
   * operator sees per-pixel IRF / SRF / PED labels (fluid),
   * binary GA presence (ga), or layer surface positions (onl/pr)
   * on the matching B-scan. Pass {@code null} or omit to hide
   * the overlay.
   */
  jobId?: number | null
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
 * 2026-06-22 — canvas-based segmentation overlay.
 *
 * <p>The composable fetches the per-job segmentation envelope once
 * via {@code GET /api/v1/retinal-jobs/{id}/segmentation} and
 * caches it; the canvas re-paints on slice change without any
 * additional network traffic. Three kinds are supported, all
 * rendered into the same overlay canvas:
 *
 * <ul>
 *   <li>{@code volume}: per-pixel labelled mask
 *       {@code (z, rows, cols) uint8}. Colours follow the
 *       FundusOverlay palette — IRF sky-400, SRF orange-400,
 *       PED fuchsia-500.</li>
 *   <li>{@code binary_2d}: per-A-scan boolean mask
 *       {@code (z, cols)}. Renders a tinted vertical column
 *       (full-height) at every A-scan where the mask is set.</li>
 *   <li>{@code surface_y}: per-A-scan layer-surface row index
 *       {@code (z, cols)} as float32. Renders a 2-pixel polyline
 *       through (x, surface[z][x]) — the layer boundary as the
 *       segmenter located it.</li>
 * </ul>
 *
 * <p>The canvas is sized to the segmentation's native (rows × cols)
 * for volume/surface kinds, then stretched via CSS to match the
 * cornerstone viewport. Stretching is sub-pixel — the DOM scales
 * uniformly so the mask still lines up with the DICOM frame
 * underneath.
 */
const jobIdRef = computed<number | null>(() => props.jobId ?? null)
const { envelope: segEnvelope } = useSegmentationEnvelope(jobIdRef)
const overlayCanvasEl = ref<HTMLCanvasElement | null>(null)

/**
 * 2026-06-22 round 3 — size the inner wrapper at the bscan's
 * *physical* aspect (mm-correct), not its pixel aspect. Cornerstone3D
 * honours DICOM ``PixelSpacing`` and renders the image at physical
 * proportions (Heidelberg axial 0.00387 mm/px vs lateral 0.00566
 * mm/px → physical aspect ~3.0, vs pixel aspect ~2.07). If the inner
 * div is at pixel aspect, cornerstone letterboxes the bscan inside
 * the div while the overlay canvas — which is CSS-stretched to the
 * full div — fills it uniformly. Result: the overlay drifts off the
 * bscan in the cornerstone view even when the seg data is correctly
 * placed in the underlying volume (the OS-scan misalignment we
 * observed visually was entirely this aspect mismatch).
 *
 * We therefore prefer the physical aspect derived from cornerstone's
 * imagePlaneModule (``pixelSpacing``) + imagePixelModule (rows/cols)
 * metadata. The pixel-derived fallback is kept for the brief window
 * between cornerstone init and the first metadata read, and for
 * inputs without ``PixelSpacing``.
 */
const bscanImageDims = ref<{ rows: number; cols: number } | null>(null)
const bscanPhysicalAspect = ref<string | null>(null)
const bscanAspect = computed<string>(() => {
  if (bscanPhysicalAspect.value) return bscanPhysicalAspect.value
  const dims = bscanImageDims.value
  if (dims && dims.rows > 0 && dims.cols > 0) {
    return `${dims.cols} / ${dims.rows}`
  }
  const env = segEnvelope.value
  if (env?.kind === 'volume' && env.shape.length >= 3) {
    const rows = env.shape[1] ?? 0
    const cols = env.shape[2] ?? 0
    if (rows > 0 && cols > 0) return `${cols} / ${rows}`
  }
  // Default Heidelberg Spectralis pixel ratio (1024 wide × 496 tall) —
  // used only during cornerstone init before the first metadata read.
  return '1024 / 496'
})

/** Label-colour ramp — matches FundusOverlay's BIOMARKER_COLORS. */
const FLUID_LABEL_COLOURS: Record<number, [number, number, number]> = {
  1: [56, 189, 248],   // sky-400 — IRF
  2: [251, 146, 60],   // orange-400 — SRF
  3: [217, 70, 239],   // fuchsia-500 — PED
}
const OVERLAY_ALPHA = 160 // ~63%

function paintOverlay(): void {
  const canvas = overlayCanvasEl.value
  const env = segEnvelope.value
  if (!canvas) return
  if (!env) {
    // Clear any stale paint so a job-id change doesn't leave the
    // previous job's overlay underneath.
    const ctx0 = canvas.getContext('2d')
    if (ctx0) ctx0.clearRect(0, 0, canvas.width, canvas.height)
    return
  }
  const z = clampZ(props.modelValue)
  if (env.kind === 'volume') {
    const [, rows, cols] = env.shape
    if (!rows || !cols) return
    canvas.width = cols
    canvas.height = rows
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const data = env.data as Uint8Array
    const sliceStride = rows * cols
    const sliceOffset = z * sliceStride
    const img = ctx.createImageData(cols, rows)
    for (let i = 0; i < sliceStride; i++) {
      const label = data[sliceOffset + i] ?? 0
      const px = i * 4
      const rgb = FLUID_LABEL_COLOURS[label]
      if (rgb) {
        img.data[px] = rgb[0]
        img.data[px + 1] = rgb[1]
        img.data[px + 2] = rgb[2]
        img.data[px + 3] = OVERLAY_ALPHA
      } else {
        img.data[px + 3] = 0
      }
    }
    ctx.putImageData(img, 0, 0)
    return
  }
  if (env.kind === 'binary_2d') {
    const [, cols] = env.shape
    if (!cols) return
    canvas.width = cols
    canvas.height = 32 // arbitrary thin strip — CSS stretches to viewport
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const data = env.data as Uint8Array
    const offset = z * cols
    const img = ctx.createImageData(cols, canvas.height)
    for (let x = 0; x < cols; x++) {
      if ((data[offset + x] ?? 0) === 0) continue
      for (let y = 0; y < canvas.height; y++) {
        const px = (y * cols + x) * 4
        img.data[px] = 217 // fuchsia-500
        img.data[px + 1] = 70
        img.data[px + 2] = 239
        img.data[px + 3] = OVERLAY_ALPHA
      }
    }
    ctx.putImageData(img, 0, 0)
    return
  }
  if (env.kind === 'surface_y') {
    // 2026-06-22 — surface_y now supports two shape ranks:
    //   shape=(z, cols)            — single surface (legacy)
    //   shape=(n_surfaces, z, cols) — multiple stacked surfaces (onl / pr)
    // Labels colour each surface — the layer thickness reads as the
    // gap between adjacent polylines.
    const data = env.data as Float32Array
    let nSurfaces = 1
    let zStride = 0
    let cols = 0
    let surfaceStride = 0
    if (env.shape.length === 3) {
      nSurfaces = env.shape[0] ?? 1
      zStride = env.shape[2] ?? 0
      cols = zStride
      surfaceStride = (env.shape[1] ?? 0) * cols
    } else {
      cols = env.shape[1] ?? 0
      zStride = cols
    }
    if (!cols) return
    // Discover the canvas height from the max Y across surfaces.
    let maxY = 0
    for (let s = 0; s < nSurfaces; s++) {
      const surfaceOffset = s * surfaceStride
      const sliceOffset = surfaceOffset + z * zStride
      for (let x = 0; x < cols; x++) {
        const yv = data[sliceOffset + x] ?? 0
        if (yv > maxY) maxY = yv
      }
    }
    const h = Math.max(16, Math.ceil(maxY) + 2)
    canvas.width = cols
    canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, cols, h)
    const palette = SURFACE_PALETTE
    for (let s = 0; s < nSurfaces; s++) {
      ctx.strokeStyle = palette[s % palette.length] ?? palette[0]!
      ctx.lineWidth = 2
      ctx.beginPath()
      const surfaceOffset = s * surfaceStride
      const sliceOffset = surfaceOffset + z * zStride
      let drawing = false
      for (let x = 0; x < cols; x++) {
        const yv = data[sliceOffset + x] ?? 0
        if (yv <= 0) {
          drawing = false
          continue
        }
        if (!drawing) {
          ctx.moveTo(x, yv)
          drawing = true
        } else {
          ctx.lineTo(x, yv)
        }
      }
      ctx.stroke()
    }
    return
  }
}

/**
 * Surface-line palette — one per stacked surface. Two entries cover
 * the ONL (OPL-HFL + BMEIS) and PR (BMEIS + OB-OPR) cases; the
 * modulo wrap accommodates any future task with more surfaces.
 */
const SURFACE_PALETTE = [
  'rgba(56, 189, 248, 0.85)',  // sky-400
  'rgba(251, 146, 60, 0.85)',  // orange-400
  'rgba(217, 70, 239, 0.85)',  // fuchsia-500
  'rgba(34, 197, 94, 0.85)',   // emerald-500
] as const

watch([segEnvelope, () => props.modelValue], () => {
  paintOverlay()
}, { flush: 'post' })

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
    //
    // 2026-06-22 — cornerstone-dicom-image-loader's wadouri scheme
    // uses 1-based frame numbers per the DICOM multi-frame standard
    // (PS3.3 §C.7.6.2 — FrameNumber starts at 1). Stack-index k still
    // corresponds to the (k+1)-th DICOM frame; the seg runner reads
    // the same DICOM frames in storage order (slice 0 → first frame),
    // so this aligns the cornerstone-displayed image with the seg
    // envelope's slice index. Without the +1 every displayed B-scan
    // was one slice behind the seg overlay.
    const imageIds = Array.from({ length: props.nBscans }, (_, frame) =>
      `wadouri:${props.bscanDcmUrl}?frame=${frame + 1}`,
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

    // 2026-06-22 — read the displayed image's true (rows, cols) +
    // PixelSpacing so the aspect-preserving wrapper sizes to the
    // bscan's PHYSICAL extent (mm-correct). The imagePixelModule +
    // imagePlaneModule metadata is populated by the DICOM image
    // loader as part of stack init.
    try {
      type MetaDataAccess = {
        get: (m: string, id: string) =>
          | { rows?: number; columns?: number; pixelSpacing?: [number, number] | number[] }
          | undefined
      }
      const md = (cornerstoneCore as unknown as { metaData: MetaDataAccess }).metaData
      const firstImageId = imageIds[0]
      const pix = firstImageId ? md?.get('imagePixelModule', firstImageId) : undefined
      const plane = firstImageId ? md?.get('imagePlaneModule', firstImageId) : undefined
      const rows = Number(pix?.rows ?? 0)
      const cols = Number(pix?.columns ?? 0)
      if (rows > 0 && cols > 0) {
        bscanImageDims.value = { rows, cols }
      }
      // PixelSpacing is [row spacing (axial mm/px), col spacing (lateral mm/px)] per
      // DICOM PS3.3 §C.7.6.2 — same convention our preprocess sidecar writes in
      // e2e_parser.write_bscan_dcm (PixelSpacing = [axial, lateral]).
      const ps = plane?.pixelSpacing
      const axialMm = Array.isArray(ps) ? Number(ps[0] ?? 0) : 0
      const lateralMm = Array.isArray(ps) ? Number(ps[1] ?? 0) : 0
      if (rows > 0 && cols > 0 && axialMm > 0 && lateralMm > 0) {
        const physWidth = cols * lateralMm
        const physHeight = rows * axialMm
        bscanPhysicalAspect.value = `${physWidth} / ${physHeight}`
      }
    } catch {
      /* aspect falls back to pixel dims, then seg dims, then Heidelberg default */
    }

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
    // Seg overlay envelope kicks itself off via useSegmentationEnvelope
    // — the composable cache means we don't need to explicitly prefetch.
    // Paint the first slice once cornerstone + envelope are both ready.
    paintOverlay()
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

    <!-- 2026-06-22 — outer 4:3 box keeps the viewer's footprint stable
         in the SPA layout; the inner aspect-preserving box matches
         the B-scan's actual proportions so the overlay canvas and
         the cornerstone canvas letterbox identically. Both share the
         same parent so any per-A-scan pixel in the overlay lands on
         the same screen pixel as the underlying B-scan pixel. -->
    <div class="relative aspect-[4/3] w-full bg-black flex items-center justify-center overflow-hidden">
      <div
        class="relative w-full"
        :style="{ aspectRatio: bscanAspect, maxHeight: '100%' }"
      >
        <div
          ref="containerEl"
          data-testid="bscan-viewer-canvas"
          class="absolute inset-0"
          tabindex="0"
          @keydown.left.prevent="setZ(modelValue - 1)"
          @keydown.right.prevent="setZ(modelValue + 1)"
          @wheel.prevent="onWheel"
        />
        <canvas
          v-show="segEnvelope && status === 'ready'"
          ref="overlayCanvasEl"
          class="absolute inset-0 w-full h-full pointer-events-none"
          data-testid="bscan-viewer-seg-overlay"
          :aria-label="t('retinal.bscanViewer.segOverlayAlt', { current: modelValue + 1, total: nBscans })"
        />
      </div>
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
