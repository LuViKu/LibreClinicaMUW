/**
 * 2026-06-22 — fetch + decode the per-job segmentation envelope served
 * by {@code GET /api/v1/retinal-jobs/{jobId}/segmentation}.
 *
 * <p>The envelope carries the raw segmentation bytes for whichever
 * task the job belongs to:
 *
 * <ul>
 *   <li>{@code kind="volume"} — fluid: per-A-scan labelled volume
 *       {@code (z, rows, cols) uint8}. Labels: IRF=1, SRF=2, PED=3.</li>
 *   <li>{@code kind="binary_2d"} — ga: per-A-scan boolean mask
 *       {@code (z, cols) uint8}.</li>
 *   <li>{@code kind="surface_y"} — onl/pr: per-A-scan surface row
 *       index {@code (z, cols) float32}.</li>
 *   <li>{@code kind="lesion_packed"} — sdretinanet {@code ?part=lesions}:
 *       per-pixel packed lesion byte {@code (z, rows, cols) uint8}, same
 *       layout as {@code volume}. Decoded by {@link lesionPackedLayout} +
 *       {@link decodeLesionPackedValue}.</li>
 * </ul>
 *
 * <p>The composable caches the result by jobId (and part) so the
 * BscanViewer can scrub through slices without re-decoding. One fetch
 * per job and part.
 */
import { isRef, ref, watch, type Ref } from 'vue'

/**
 * Optional second envelope a task serves next to its default one. Only
 * sdretinanet has one today: {@code lesions} (the default part is its
 * 12 boundary surfaces). The backend ignores {@code part} for every
 * other task.
 */
export type SegmentationPart = 'lesions'

export interface SegmentationEnvelope {
  task: string
  kind: 'volume' | 'binary_2d' | 'surface_y' | 'lesion_packed'
  dtype: 'uint8' | 'float32'
  shape: number[]
  labels: string[]
  /** Owned by the composable — the BscanViewer reads slices via subarray views. */
  data: Uint8Array | Float32Array
  /**
   * 2026-06-26 — surface indices (0-based, matching the envelope's
   * surface-major order) the BACKEND served from
   * {@code bscan_masks_dir/corrections/} instead of the original AI
   * output. Sourced from the {@code X-MUW-Seg-Corrected} response
   * header (CSV of indices). Empty when no operator corrections
   * apply. The SPA uses this to render the "korrigiert" badge per
   * layer chip.
   */
  correctedSurfaceIndices: number[]
}

/**
 * How to read a {@code lesion_packed} byte, derived from the envelope's
 * labels. The header lists the main lesions first, then the overlay
 * lesions with a {@code +} prefix (e.g.
 * {@code Cyst,SRF,PED,SHRM,Pseudodrusen,ORT,+HRF}). Main lesions are
 * mutually exclusive and share the low bits as an id; each overlay owns
 * one high bit, so a pixel can carry a main lesion AND an overlay (HRF
 * inside a cyst).
 */
export interface LesionPackedLayout {
  /** Main lesion labels as the backend names them; id i+1 ↔ mainLabels[i]. */
  mainLabels: string[]
  /** Overlay labels with the {@code +} stripped; overlay k ↔ bit (7 - k). */
  overlayLabels: string[]
  /** Selects the main id from a byte: {@code (1 << (8 - K)) - 1}, K = overlay count. */
  mainMask: number
}

export function lesionPackedLayout(labels: readonly string[]): LesionPackedLayout {
  const mainLabels: string[] = []
  const overlayLabels: string[] = []
  for (const label of labels) {
    if (label.startsWith('+')) overlayLabels.push(label.slice(1))
    else mainLabels.push(label)
  }
  // A byte has 8 bits; more than 8 overlays cannot be encoded, and the
  // shift below would go negative. Clamp so a malformed header yields
  // "no main lesion" instead of garbage.
  const k = Math.min(overlayLabels.length, 8)
  return { mainLabels, overlayLabels, mainMask: (1 << (8 - k)) - 1 }
}

/**
 * Decode one packed byte. {@code mainIndex} is an index into
 * {@code layout.mainLabels} (null for "no main lesion", and for an id the
 * header has no label for); {@code overlayIndices} index into
 * {@code layout.overlayLabels}.
 */
export function decodeLesionPackedValue(
  value: number,
  layout: LesionPackedLayout,
): { mainIndex: number | null; overlayIndices: number[] } {
  const mainId = value & layout.mainMask
  const mainIndex = mainId > 0 && mainId <= layout.mainLabels.length ? mainId - 1 : null
  const overlayIndices: number[] = []
  const k = Math.min(layout.overlayLabels.length, 8)
  for (let i = 0; i < k; i++) {
    if (value & (1 << (7 - i))) overlayIndices.push(i)
  }
  return { mainIndex, overlayIndices }
}

/**
 * Cache key: the job id alone for the default part (unchanged from
 * before parts existed), {@code <jobId>:<part>} for a named part.
 */
function cacheKey(jobId: number, part: SegmentationPart | null): string {
  return part ? `${jobId}:${part}` : String(jobId)
}

const cache = new Map<string, Promise<SegmentationEnvelope | null>>()

/**
 * 2026-06-26 user-feedback round — bust the module-level cache for
 * one job + signal all live consumers to re-fetch.
 *
 * <p>Symptom that motivated this: when a job transitioned from
 * {@code segmenting} to {@code done} while the metrics view was
 * already mounted, the initial 'no seg dir yet' fetch returned 404
 * → null, the Promise got cached as null, and the BscanViewer +
 * FundusOverlay kept resolving from that null even after the SSE
 * stream pushed 'done'. The visual symptom: the layers task's
 * surface overlay never appeared until the operator manually
 * refreshed the page; the downloads list (driven by the job DTO)
 * updated fine because that took the {@code load()} path.
 *
 * <p>Consumers re-watch the {@link refreshTick} ref so the same
 * watcher that drove the initial fetch re-fires when this gets
 * bumped.
 */
const refreshTick = ref(0)

export function clearSegmentationEnvelopeCache(jobId: number | null | undefined): void {
  if (jobId == null) return
  // Every part of the job — a re-run or correction can change any of them.
  const prefix = `${jobId}:`
  for (const key of Array.from(cache.keys())) {
    if (key === String(jobId) || key.startsWith(prefix)) cache.delete(key)
  }
  refreshTick.value += 1
}

async function fetchEnvelope(
  jobId: number,
  part: SegmentationPart | null = null,
): Promise<SegmentationEnvelope | null> {
  // /LibreClinica is the WAR context path the Vite dev proxy
  // forwards to Tomcat — `apiGet` in @/api/client prepends it
  // implicitly, but this composable uses raw fetch (we need the
  // ArrayBuffer body + custom response headers, neither of which
  // apiGet exposes), so prepend explicitly.
  // 2026-06-23 — bust the browser's HTTP cache + the module-level
  // Map cache (which we already invalidate via cache.delete() on
  // error). When a backend rebuild changes the envelope's binary
  // shape mid-session (e.g. the GA loader switch), reusing an
  // HTTP-cached body would paint stale data even after a hard
  // refresh. `cache: 'no-store'` forces the browser to fetch fresh
  // and skip writing to the HTTP cache.
  const base = `/LibreClinica/pages/api/v1/retinal-jobs/${encodeURIComponent(String(jobId))}/segmentation`
  const url = part ? `${base}?part=${encodeURIComponent(part)}` : base
  const resp = await fetch(url, {
    headers: { Accept: 'application/octet-stream' },
    credentials: 'same-origin',
    cache: 'no-store',
  })
  if (resp.status === 501 || resp.status === 404) {
    // Task not yet wired or job has no seg dir — no error, just no overlay.
    return null
  }
  if (!resp.ok) {
    throw new Error(`segmentation envelope HTTP ${resp.status}`)
  }
  const task = resp.headers.get('X-MUW-Seg-Task') ?? ''
  const kind = (resp.headers.get('X-MUW-Seg-Kind') ?? '') as SegmentationEnvelope['kind']
  const dtype = (resp.headers.get('X-MUW-Seg-Dtype') ?? '') as SegmentationEnvelope['dtype']
  const shape = (resp.headers.get('X-MUW-Seg-Shape') ?? '')
    .split(',')
    .map((s) => Number.parseInt(s.trim(), 10))
    .filter((n) => Number.isFinite(n) && n > 0)
  const labels = (resp.headers.get('X-MUW-Seg-Labels') ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
  // 2026-06-26 — X-MUW-Seg-Corrected (CSV of indices). Older backends
  // omit the header → treat as no corrections; the controller emits
  // an empty string when the file is present but the list is empty,
  // which also lands as []. The header is read for layers-task
  // envelopes; volume / binary_2d responses don't carry corrections
  // yet but the field is null-safe across kinds.
  const correctedSurfaceIndices = (resp.headers.get('X-MUW-Seg-Corrected') ?? '')
    .split(',')
    .map((s) => Number.parseInt(s.trim(), 10))
    .filter((n) => Number.isFinite(n) && n >= 0)
  const bytes = new Uint8Array(await resp.arrayBuffer())
  let data: Uint8Array | Float32Array
  if (dtype === 'float32') {
    // The server emits little-endian C-order; ArrayBuffer view is
    // host-endian, but x86 + ARM are both LE so direct cast is fine.
    data = new Float32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 4)
  } else {
    data = bytes
  }
  return { task, kind, dtype, shape, labels, data, correctedSurfaceIndices }
}

/**
 * Reactive wrapper. Resolves with the envelope (or {@code null} when
 * the task isn't wired); errors are surfaced via the {@code error}
 * ref so the viewer can degrade gracefully without throwing into the
 * caller's render cycle.
 *
 * <p>{@code part} selects a task's second envelope (sdretinanet's
 * {@code lesions}); omit it for the default envelope.
 */
export function useSegmentationEnvelope(
  jobId: Ref<number | null>,
  part: Ref<SegmentationPart | null> | SegmentationPart | null = null,
): {
  envelope: Ref<SegmentationEnvelope | null>
  loading: Ref<boolean>
  error: Ref<string | null>
} {
  const envelope = ref<SegmentationEnvelope | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const partRef: Ref<SegmentationPart | null> = isRef(part) ? part : ref(part)

  async function refresh(): Promise<void> {
    const id = jobId.value
    const p = partRef.value
    // 2026-07-10 — also reject NaN, not just null. RetinalMetricsView resolves
    // the per-subject deep link (/subjects/{label}/jobs/{n}) asynchronously and
    // uses NaN as its "unresolved jobId" sentinel; NaN passes a bare `== null`
    // check, so we were firing GET /retinal-jobs/NaN/segmentation on every
    // deep-link mount (400 + a spurious audit row + a console error toast).
    if (id == null || !Number.isFinite(id)) {
      envelope.value = null
      return
    }
    loading.value = true
    error.value = null
    try {
      const key = cacheKey(id, p)
      let pending = cache.get(key)
      if (!pending) {
        pending = fetchEnvelope(id, p)
        cache.set(key, pending)
      }
      envelope.value = await pending
    } catch (e) {
      cache.delete(cacheKey(id, p))
      envelope.value = null
      error.value = e instanceof Error ? e.message : 'Failed to fetch segmentation envelope'
    } finally {
      loading.value = false
    }
  }

  // Re-fire whenever the jobId source changes — props bind on the
  // parent's first render so the initial setup-time call usually
  // sees null. Without a watcher the canvas would stay empty until
  // a manual re-mount. Immediate so we still kick off on the
  // initial value.
  // 2026-06-26 — also re-fire on refreshTick bumps, which
  // clearSegmentationEnvelopeCache() drives when an external
  // signal (SSE done push, manual reset) wants every live
  // consumer to drop its cached null and re-poll the endpoint.
  watch([jobId, partRef, refreshTick], () => { void refresh() }, { immediate: true })
  return { envelope, loading, error }
}
