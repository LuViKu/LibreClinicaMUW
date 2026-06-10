import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError, ApiNetworkError } from '@/api/client'

/**
 * Phase E hardening — A5 (SPA global error boundary).
 *
 * Centralised, in-memory sink for uncaught errors raised anywhere in
 * the SPA — Vue render/setup throws (via `app.config.errorHandler`),
 * router resolution failures (`router.onError`), and (post-A4)
 * controller-level `ApiError` instances pushed by store actions.
 *
 * The store is deliberately a thin ring buffer rather than a structured
 * event log: it carries the last 20 errors so `GlobalErrorToast` can
 * surface the most recent one, plus a `dismiss(id)` so the user can
 * acknowledge the toast without losing earlier entries. There is no
 * remote sink — per the 2026-06-10 hardening-plan decision, the audit
 * trail + browser console + request-correlation ID are the single
 * error pathway (no `/client-errors` endpoint).
 *
 * A4 interaction: when the request-id filter lands, `ApiError` /
 * `ApiNetworkError` will gain a `reqId: string` field; this store
 * normalises it via `extractReqId()` so the toast renders the
 * "Fehler-ID: …" pill as soon as the field appears.
 */

const RING_BUFFER_CAP = 20

/** Internal id generator — monotonic counter, sufficient for in-tab uniqueness. */
let nextId = 1

export interface TrackedError {
  /** Monotonic in-tab id used as the v-for key + `dismiss(id)` target. */
  id: number
  /** Human-readable message — `err.message` or the wrapper's fallback. */
  message: string
  /** Request-correlation id from A4. May be empty until A4 lands. */
  reqId: string
  /** Capture time — drives the "8s auto-dismiss" countdown in the toast. */
  when: Date
  /** Optional Vue/router context string (e.g. `'router.onError'`, render hook). */
  info?: string
  /** Tag for downstream classification (toast styling, future telemetry). */
  kind: 'api' | 'network' | 'render' | 'router' | 'unknown'
}

/** Read the `reqId` field defensively — A4 adds it; until then it's undefined. */
function extractReqId(err: unknown): string {
  if (err && typeof err === 'object' && 'reqId' in err) {
    const r = (err as { reqId: unknown }).reqId
    if (typeof r === 'string') return r
  }
  return ''
}

function classify(err: unknown): TrackedError['kind'] {
  if (err instanceof ApiNetworkError) return 'network'
  if (err instanceof ApiError) return 'api'
  if (err instanceof Error) return 'render'
  return 'unknown'
}

function extractMessage(err: unknown): string {
  if (err instanceof Error) return err.message || err.name || 'Unbekannter Fehler'
  if (typeof err === 'string' && err.trim() !== '') return err
  return 'Unbekannter Fehler'
}

export const useErrorsStore = defineStore('errors', () => {
  const recent = ref<TrackedError[]>([])

  /** Latest entry — the toast component displays this one. */
  const latest = computed<TrackedError | null>(() =>
    recent.value.length > 0 ? recent.value[recent.value.length - 1] : null,
  )

  /**
   * Normalise an arbitrary thrown value into a `TrackedError` and
   * push it onto the ring buffer. The buffer is capped at
   * {@link RING_BUFFER_CAP}; oldest entries are discarded silently.
   *
   * If the caller passes a Vue `info` string (e.g. `"render"`,
   * `"setup function"`), it's stored on the tracked entry — useful
   * for the console debug line but not surfaced in the toast.
   */
  function push(err: unknown, info?: string): TrackedError {
    const entry: TrackedError = {
      id: nextId++,
      message: extractMessage(err),
      reqId: extractReqId(err),
      when: new Date(),
      info,
      kind: classify(err),
    }
    recent.value = [...recent.value, entry]
    if (recent.value.length > RING_BUFFER_CAP) {
      recent.value = recent.value.slice(recent.value.length - RING_BUFFER_CAP)
    }
    return entry
  }

  function dismiss(id: number): void {
    recent.value = recent.value.filter((e) => e.id !== id)
  }

  function clear(): void {
    recent.value = []
  }

  return { recent, latest, push, dismiss, clear }
})
