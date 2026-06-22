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
 * </ul>
 *
 * <p>The composable caches the result by jobId so the BscanViewer can
 * scrub through slices without re-decoding. One fetch per job.
 */
import { ref, watch, type Ref } from 'vue'

export interface SegmentationEnvelope {
  task: string
  kind: 'volume' | 'binary_2d' | 'surface_y'
  dtype: 'uint8' | 'float32'
  shape: number[]
  labels: string[]
  /** Owned by the composable — the BscanViewer reads slices via subarray views. */
  data: Uint8Array | Float32Array
}

const cache = new Map<number, Promise<SegmentationEnvelope | null>>()

async function fetchEnvelope(jobId: number): Promise<SegmentationEnvelope | null> {
  const url = `/pages/api/v1/retinal-jobs/${encodeURIComponent(String(jobId))}/segmentation`
  const resp = await fetch(url, {
    headers: { Accept: 'application/octet-stream' },
    credentials: 'same-origin',
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
  const bytes = new Uint8Array(await resp.arrayBuffer())
  let data: Uint8Array | Float32Array
  if (dtype === 'float32') {
    // The server emits little-endian C-order; ArrayBuffer view is
    // host-endian, but x86 + ARM are both LE so direct cast is fine.
    data = new Float32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 4)
  } else {
    data = bytes
  }
  return { task, kind, dtype, shape, labels, data }
}

/**
 * Reactive wrapper. Resolves with the envelope (or {@code null} when
 * the task isn't wired); errors are surfaced via the {@code error}
 * ref so the viewer can degrade gracefully without throwing into the
 * caller's render cycle.
 */
export function useSegmentationEnvelope(jobId: Ref<number | null>): {
  envelope: Ref<SegmentationEnvelope | null>
  loading: Ref<boolean>
  error: Ref<string | null>
} {
  const envelope = ref<SegmentationEnvelope | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function refresh(): Promise<void> {
    const id = jobId.value
    if (id == null) {
      envelope.value = null
      return
    }
    loading.value = true
    error.value = null
    try {
      let pending = cache.get(id)
      if (!pending) {
        pending = fetchEnvelope(id)
        cache.set(id, pending)
      }
      envelope.value = await pending
    } catch (e) {
      cache.delete(id)
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
  watch(jobId, () => { void refresh() }, { immediate: true })
  return { envelope, loading, error }
}
