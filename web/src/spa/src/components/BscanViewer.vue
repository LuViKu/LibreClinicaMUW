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
 * 2026-06-22 round 4 — position the overlay canvas at the bscan's
 * *actual rendered* bounding box via cornerstone's worldToCanvas
 * coordinate transform. Previous attempts (pixel-aspect wrapper,
 * physical-aspect wrapper) tried to make a CSS aspect-ratio match
 * how cornerstone internally scales the image — but cornerstone's
 * fit logic is its own + may apply additional pan/zoom/rotation
 * the SPA can't predict. The only authoritative source for "where
 * is image pixel (col, row) actually drawn" is the viewport itself.
 *
 * We therefore query ``viewport.worldToCanvas([col, row, 0])`` for
 * the two corners (0,0) and (cols, rows) after each render, and
 * absolutely-position the overlay canvas at that bbox in the same
 * parent as the cornerstone container. The overlay canvas's pixel
 * buffer stays at seg native resolution; CSS scales it uniformly
 * to the bbox, so each seg voxel lands on the same screen pixel
 * as the bscan voxel underneath.
 */
const bscanImageDims = ref<{ rows: number; cols: number } | null>(null)
const bscanPixelSpacing = ref<{ axialMm: number; lateralMm: number } | null>(null)
const overlayBbox = ref<{ left: number; top: number; width: number; height: number } | null>(null)
const overlayBboxStyle = computed<Record<string, string>>(() => {
  const b = overlayBbox.value
  if (!b || b.width <= 0 || b.height <= 0) {
    return { display: 'none', position: 'absolute', left: '0', top: '0', width: '0', height: '0', pointerEvents: 'none' }
  }
  return {
    display: 'block',
    position: 'absolute',
    left: `${b.left}px`,
    top: `${b.top}px`,
    width: `${b.width}px`,
    height: `${b.height}px`,
    pointerEvents: 'none',
  }
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
    // 2026-06-22 — GA RPEL classification per A-scan. Render as a
    // soft full-height column wash + a saturated band along the
    // bottom 12% so the operator can read which A-scans are
    // classified as GA without the column tint dominating the
    // retinal anatomy underneath. The previous full-strip-at-160-
    // alpha rendering swamped the RPE/choroid layers we still
    // want to see for visual sanity-checking.
    const [, cols] = env.shape
    if (!cols) return
    const H = 100 // logical buffer height; CSS stretches uniformly to bbox
    canvas.width = cols
    canvas.height = H
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const data = env.data as Uint8Array
    const offset = z * cols
    const img = ctx.createImageData(cols, H)
    const bandStart = Math.floor(H * 0.88) // bottom 12%
    const COLOUR = [217, 70, 239] // fuchsia-500
    const WASH_ALPHA = 40       // ~16% — full-column tint
    const BAND_ALPHA = 200      // ~78% — saturated band
    for (let x = 0; x < cols; x++) {
      if ((data[offset + x] ?? 0) === 0) continue
      for (let y = 0; y < H; y++) {
        const px = (y * cols + x) * 4
        img.data[px] = COLOUR[0]
        img.data[px + 1] = COLOUR[1]
        img.data[px + 2] = COLOUR[2]
        img.data[px + 3] = y >= bandStart ? BAND_ALPHA : WASH_ALPHA
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
    // 2026-06-23 — the surface_y values are row indices in the B-scan's
    // PIXEL frame. The canvas must therefore have the same axial extent
    // as the underlying B-scan (n_rows = 496 for Heidelberg cubes) — NOT
    // the max-Y of the surface data, which would compress the layers
    // toward the bottom of the bbox once CSS-stretches the canvas to the
    // worldToCanvas-derived overlay bbox. Prefer cornerstone's reported
    // dims; fall back to a Heidelberg default if the metadata isn't in
    // yet (which can happen on the very first paint).
    const h = bscanImageDims.value?.rows ?? 496
    canvas.width = cols
    canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, cols, h)
    // eslint-disable-next-line no-console
    console.debug('[BscanViewer] surface_y paint:',
      'shape=', env.shape, 'nSurfaces=', nSurfaces, 'cols=', cols,
      'z=', z, 'canvas=', canvas.width, 'x', canvas.height,
      'firstUpper5=', Array.from({ length: 5 }, (_, i) => data[(0 * surfaceStride) + z * zStride + i]),
      'lastUpper5=', Array.from({ length: 5 }, (_, i) => data[(0 * surfaceStride) + z * zStride + (cols - 5 + i)]),
    )
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

/**
 * 2026-06-22 — query cornerstone for where the bscan's pixel corners
 * (0, 0) and (cols, rows) ACTUALLY end up on the canvas, then size the
 * overlay div to span exactly that rectangle. Works regardless of how
 * cornerstone scales the image — fit-inside, physical-mm aspect, pan,
 * zoom — because we're asking the viewport itself.
 */
function recomputeOverlayBbox(): void {
  const vp = viewport.value as
    | {
        worldToCanvas?: (w: [number, number, number]) => [number, number] | null
        getCanvas?: () => HTMLCanvasElement | undefined
        getImageData?: () => { dimensions?: number[]; spacing?: number[]; origin?: number[] } | undefined
      }
    | null
  const dims = bscanImageDims.value
  if (!vp || !dims || dims.rows <= 0 || dims.cols <= 0) {
    // Defensive fallback: show the overlay across the entire container
    // so the user at least sees the seg even if the transform-based
    // path isn't available. Better-than-invisible.
    overlayBbox.value = fallbackContainerBbox()
    return
  }
  try {
    // Cornerstone3D's worldToCanvas expects WORLD coords in MILLIMETRES,
    // not image-pixel indices — the IJK→world transform is applied via
    // PixelSpacing + ImageOrientationPatient at stack load. Translate
    // pixel corners (-0.5, -0.5) and (cols-0.5, rows-0.5) to world mm.
    // Without PixelSpacing we degrade to unit spacing (1 mm/px) — the
    // bbox will still scale linearly, just at the wrong absolute size,
    // and the fallback container-fill will kick in.
    const sp = bscanPixelSpacing.value
    const lateralMm = sp?.lateralMm ?? 1
    const axialMm = sp?.axialMm ?? 1
    const tl = vp.worldToCanvas?.([
      -0.5 * lateralMm,
      -0.5 * axialMm,
      0,
    ])
    const br = vp.worldToCanvas?.([
      (dims.cols - 0.5) * lateralMm,
      (dims.rows - 0.5) * axialMm,
      0,
    ])
    const tlOk = tl && Number.isFinite(tl[0]) && Number.isFinite(tl[1])
    const brOk = br && Number.isFinite(br[0]) && Number.isFinite(br[1])

    if (tlOk && brOk && tl && br) {
      const left = Math.min(tl[0], br[0])
      const top = Math.min(tl[1], br[1])
      const width = Math.abs(br[0] - tl[0])
      const height = Math.abs(br[1] - tl[1])
      if (width > 0 && height > 0) {
        // The canvas returned by getCanvas() is positioned inside
        // containerEl (absolute inset-0). worldToCanvas() reports
        // pixel coords RELATIVE TO THE CANVAS ELEMENT in CSS px.
        // Our overlay sits as a sibling of containerEl, so its
        // (0,0) is the same as the canvas's (0,0).
        overlayBbox.value = { left, top, width, height }
        // eslint-disable-next-line no-console
        console.debug('[BscanViewer] overlay bbox via worldToCanvas:', overlayBbox.value)
        return
      }
    }

    // worldToCanvas didn't yield a usable bbox — log so we can see why,
    // and fall back to the container-fill behaviour.
    // eslint-disable-next-line no-console
    console.warn('[BscanViewer] worldToCanvas unusable (tl=%o br=%o), falling back to container bbox', tl, br)
    overlayBbox.value = fallbackContainerBbox()
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn('[BscanViewer] recomputeOverlayBbox threw, falling back:', e)
    overlayBbox.value = fallbackContainerBbox()
  }
}

/**
 * Cover the entire container element. Used when worldToCanvas isn't
 * usable yet (or at all). At least the seg is visible and roughly
 * aligned via the container's intrinsic aspect.
 */
function fallbackContainerBbox(): { left: number; top: number; width: number; height: number } | null {
  const el = containerEl.value
  if (!el) return null
  return { left: 0, top: 0, width: el.clientWidth, height: el.clientHeight }
}

// Recompute the bbox whenever cornerstone repaints (slice change) or
// the container resizes (window resize, layout reflow, drawer toggle).
let resizeObs: ResizeObserver | null = null
watch(() => props.modelValue, () => {
  if (status.value === 'ready') {
    // run after cornerstone's render commit
    requestAnimationFrame(recomputeOverlayBbox)
  }
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

    // 2026-06-22 — read the displayed image's true (rows, cols) so
    // recomputeOverlayBbox knows which world coords map to the image
    // corners. The imagePixelModule metadata is populated by the DICOM
    // image loader as part of stack init.
    try {
      type MetaDataAccess = {
        get: (m: string, id: string) =>
          | { rows?: number; columns?: number; pixelSpacing?: number[] | [number, number] }
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
      // PixelSpacing per DICOM PS3.3 §C.7.6.2 = [row_spacing (axial mm/px),
      // col_spacing (lateral mm/px)]. We need it to convert pixel-index
      // corners to the world-mm coords cornerstone3D's worldToCanvas expects.
      const ps = plane?.pixelSpacing
      const axialMm = Array.isArray(ps) ? Number(ps[0] ?? 0) : 0
      const lateralMm = Array.isArray(ps) ? Number(ps[1] ?? 0) : 0
      if (axialMm > 0 && lateralMm > 0) {
        bscanPixelSpacing.value = { axialMm, lateralMm }
      }
    } catch {
      /* falls back to seg dims + unit spacing for the bbox computation */
    }

    status.value = 'ready'
    recomputeOverlayBbox()

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

onMounted(async () => {
  await initViewer()
  // Recompute on resize so the overlay tracks the bscan as the
  // browser window or surrounding layout changes.
  if (containerEl.value && 'ResizeObserver' in window) {
    resizeObs = new ResizeObserver(() => recomputeOverlayBbox())
    resizeObs.observe(containerEl.value)
  }
})

onBeforeUnmount(() => {
  try {
    resizeObs?.disconnect()
    resizeObs = null
    renderingEngineRef.value?.destroy()
  } catch {
    /* never throw on unmount */
  }
})
</script>

<template>
  <section
    data-testid="bscan-viewer"
    class="bg-slate-900 rounded-2xl overflow-clip border border-slate-800"
  >
    <header class="px-4 py-2.5 flex items-center justify-between gap-3 bg-slate-900 border-b border-white/10 text-white/80">
      <h3 class="text-[11px] font-semibold uppercase tracking-[0.12em]">
        {{ t('retinal.bscanViewer.header') }}
      </h3>
      <div class="flex items-center gap-4">
        <!-- 2026-06-22 — biomarker legend. Shown only when the
             segmentation envelope is a volume kind (fluid), since
             that's the only task that paints multi-label per-A-scan
             colours on the overlay. -->
        <div
          v-if="segEnvelope?.kind === 'volume'"
          class="hidden sm:flex items-center gap-3 text-[11px]"
          data-testid="bscan-viewer-legend"
        >
          <span class="inline-flex items-center gap-1.5 text-white/70">
            <span class="w-2.5 h-2.5 rounded-[3px] bg-cyan-400" />IRF
          </span>
          <span class="inline-flex items-center gap-1.5 text-white/70">
            <span class="w-2.5 h-2.5 rounded-[3px] bg-amber-400" />SRF
          </span>
          <span class="inline-flex items-center gap-1.5 text-white/70">
            <span class="w-2.5 h-2.5 rounded-[3px] bg-fuchsia-500" />PED
          </span>
        </div>
        <span class="text-[11px] text-white/60 tabular-nums font-mono">
          {{ t('retinal.bscanViewer.position', { current: modelValue + 1, total: nBscans }) }}
        </span>
      </div>
    </header>

    <!-- 2026-06-22 round 4 — outer 4:3 box keeps the viewer's footprint
         stable; cornerstone fills it directly and decides where to
         render the bscan inside (fit-inside, physical-mm aspect, etc).
         The overlay canvas is absolutely positioned via :style at the
         bscan's actual rendered bbox (queried from worldToCanvas),
         so it ALWAYS aligns regardless of how cornerstone chooses to
         scale internally. -->
    <div class="relative aspect-[4/3] w-full bg-black flex items-center justify-center overflow-hidden">
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
        v-show="segEnvelope && status === 'ready' && overlayBbox"
        ref="overlayCanvasEl"
        :style="overlayBboxStyle"
        data-testid="bscan-viewer-seg-overlay"
        :aria-label="t('retinal.bscanViewer.segOverlayAlt', { current: modelValue + 1, total: nBscans })"
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

    <div class="px-4 py-3 flex items-center gap-3 bg-slate-900 border-t border-white/10">
      <button
        type="button"
        class="shrink-0 w-8 h-8 rounded-lg bg-white/10 hover:bg-white/20 disabled:opacity-30 disabled:hover:bg-white/10 text-white inline-flex items-center justify-center transition"
        :disabled="modelValue <= 0"
        :aria-label="t('retinal.bscanViewer.previous', 'Previous slice')"
        @click="setZ(modelValue - 1)"
      >
        ‹
      </button>
      <input
        type="range"
        min="0"
        :max="sliderMax"
        :value="modelValue"
        class="flex-1 accent-muw-sky"
        data-testid="bscan-viewer-slider"
        @input="(e) => setZ(Number((e.target as HTMLInputElement).value))"
      />
      <button
        type="button"
        class="shrink-0 w-8 h-8 rounded-lg bg-white/10 hover:bg-white/20 disabled:opacity-30 disabled:hover:bg-white/10 text-white inline-flex items-center justify-center transition"
        :disabled="modelValue >= sliderMax"
        :aria-label="t('retinal.bscanViewer.next', 'Next slice')"
        @click="setZ(modelValue + 1)"
      >
        ›
      </button>
    </div>
  </section>
</template>
