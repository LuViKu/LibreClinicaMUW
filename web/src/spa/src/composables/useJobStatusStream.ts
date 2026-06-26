/**
 * Wave 2A — Real-time job-status SSE subscriber.
 *
 * Wraps the {@code EventSource('/LibreClinica/pages/api/v1/retinal-jobs/
 * {id}/status/stream')} channel into a reactive composable so views can
 * subscribe declaratively (mount → subscribe / unmount → close) without
 * managing the EventSource lifecycle by hand.
 *
 * <p>Lifecycle:
 *   1. On {@code jobId} non-null AND {@code enabled !== false}, open
 *      the EventSource and start listening for {@code status} +
 *      {@code heartbeat} events.
 *   2. On {@code status} event, update {@code status.value} with the
 *      payload and invoke the {@code onStatus} callback.
 *   3. On {@code heartbeat}, flip {@code connected} on but don't touch
 *      {@code status} — the heartbeat exists so proxies don't close
 *      the connection on idle, NOT to push a transition.
 *   4. On {@code onerror}, set {@code connected=false} and schedule a
 *      reconnect with exponential backoff (1s, 2s, 4s, 8s, capped at
 *      30s). The retry counter resets on a successful re-open so a
 *      flaky connection that re-establishes fully gets the first quick
 *      retry on its next failure.
 *   5. On {@code jobId} change, {@code enabled} flipping false, or
 *      component unmount, close the EventSource + cancel any pending
 *      reconnect.
 *
 * <p>The backend SSE controller —
 * {@link RetinalJobStatusSseController} — sends one
 * {@code event: status\ndata: <newStatus>\n\n} block per DB flip and a
 * comment line every 15s as a heartbeat. The composable's
 * {@code heartbeat} listener attaches to the addEventListener form so
 * named events are routed correctly; the default {@code onmessage}
 * handler covers the comment / unnamed case which we treat as a
 * heartbeat too.
 */
import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

/** Shape of a status push from the backend. */
export interface JobStatusEvent {
  jobId: number
  /** 'remote_pending' | 'queued' | 'segmenting' | 'done' | … */
  status: string
  /** ISO timestamp when present; the backend doesn't send it today. */
  timestamp?: string
}

export interface UseJobStatusStreamOptions {
  /** Invoked once per {@code status} event. Heartbeats are NOT routed here. */
  onStatus?: (e: JobStatusEvent) => void
  /**
   * Reactive flag — when false, the stream stays closed even if
   * {@code jobId} is non-null. Default: implicit true (no flag → always
   * subscribe).
   */
  enabled?: Ref<boolean>
}

export interface UseJobStatusStreamReturn {
  /** Latest status payload, or null when no status push has arrived yet. */
  status: Ref<string | null>
  /** True between {@code onopen} (or {@code heartbeat}) and {@code onerror}. */
  connected: Ref<boolean>
  /** Last connection error message; null while the connection is up. */
  error: Ref<string | null>
  /** Idempotent close + cancel-any-pending-reconnect. */
  close: () => void
}

/** Backoff schedule in ms — 1s, 2s, 4s, 8s, capped at 30s after step 5. */
function backoffMsForAttempt(attempt: number): number {
  if (attempt <= 0) return 1_000
  const base = 1_000 * Math.pow(2, attempt)
  return Math.min(base, 30_000)
}

export function useJobStatusStream(
  jobId: Ref<number | null>,
  opts: UseJobStatusStreamOptions = {},
): UseJobStatusStreamReturn {
  const status = ref<string | null>(null)
  const connected = ref(false)
  const error = ref<string | null>(null)

  let source: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempt = 0
  let closed = false

  function clearReconnect() {
    if (reconnectTimer != null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function teardown() {
    clearReconnect()
    if (source) {
      try {
        source.close()
      } catch {
        /* EventSource.close() is documented to never throw, but guard anyway. */
      }
      source = null
    }
    connected.value = false
  }

  function scheduleReconnect(currentJobId: number) {
    if (closed) return
    if (opts.enabled && opts.enabled.value === false) return
    clearReconnect()
    const delay = backoffMsForAttempt(reconnectAttempt)
    reconnectAttempt += 1
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (closed) return
      if (jobId.value !== currentJobId) return
      open(currentJobId)
    }, delay)
  }

  function open(currentJobId: number) {
    teardown()
    if (closed) return
    if (opts.enabled && opts.enabled.value === false) return

    const url = `/LibreClinica/pages/api/v1/retinal-jobs/${currentJobId}/status/stream`
    let next: EventSource
    try {
      next = new EventSource(url, { withCredentials: true })
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      connected.value = false
      scheduleReconnect(currentJobId)
      return
    }
    source = next

    next.onopen = () => {
      connected.value = true
      error.value = null
      // Successful open resets the backoff so the next failure starts
      // at 1s rather than continuing the previous exponential walk.
      reconnectAttempt = 0
    }

    // Default `onmessage` covers data-only frames + comment heartbeats
    // (some EventSource implementations surface the latter as an empty
    // `message` event). We treat anything arriving on this channel as a
    // liveness signal — keeps `connected` true through long idle gaps.
    next.onmessage = () => {
      connected.value = true
    }

    // Named `status` event — the backend's primary push. Payload is a
    // bare status string (`done`, `segmenting`, …); we wrap it into the
    // {@link JobStatusEvent} shape so callers get a stable contract.
    next.addEventListener('status', (event: MessageEvent) => {
      const raw = typeof event.data === 'string' ? event.data : String(event.data)
      const normalized = raw.trim()
      if (normalized.length === 0) return
      status.value = normalized
      if (opts.onStatus) {
        opts.onStatus({ jobId: currentJobId, status: normalized })
      }
    })

    // Named `heartbeat` event — keeps `connected` true through long
    // idle gaps; does NOT update `status`.
    next.addEventListener('heartbeat', () => {
      connected.value = true
    })

    next.onerror = (event: Event) => {
      // EventSource fires onerror on both transient socket loss + the
      // server explicitly closing the stream (terminal status). We
      // can't tell them apart from the event payload alone, so we
      // always schedule a reconnect; the parent view's `enabled` flag
      // is the authoritative kill switch when the status hits a
      // terminal state.
      connected.value = false
      error.value = 'SSE connection error'
      // Don't `void event` — keep the param so the public shape stays
      // documented; lint suppression is the only side effect.
      void event
      scheduleReconnect(currentJobId)
    }
  }

  function maybeOpen() {
    closed = false
    const id = jobId.value
    if (id == null) {
      teardown()
      return
    }
    if (opts.enabled && opts.enabled.value === false) {
      teardown()
      return
    }
    open(id)
  }

  // Initial open + react to jobId / enabled changes. flush:'post' so
  // both refs settle before we (re)open — avoids the open-then-close
  // dance when the parent toggles enabled in the same tick as jobId.
  watch(
    () => [jobId.value, opts.enabled?.value ?? true] as const,
    () => {
      reconnectAttempt = 0
      maybeOpen()
    },
    { immediate: true, flush: 'post' },
  )

  function close() {
    closed = true
    teardown()
  }

  onBeforeUnmount(() => {
    close()
  })

  return {
    status,
    connected,
    error,
    close,
  }
}
