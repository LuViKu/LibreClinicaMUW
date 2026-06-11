/**
 * Phase E follow-up (2026-06-11) — in-browser client-log ring buffer.
 *
 * Captures recent `console.error` / `console.warn` calls plus Phase A5
 * uncaught Vue errors so the bug-report dialog can offer to attach them
 * to the email it ships to the institutional inbox. The store is a
 * thin ring buffer (100 entries cap) — older entries are discarded
 * silently when the buffer overflows.
 *
 * No remote sink. The store lives in tab-local memory only; on a
 * full-page reload the buffer resets. That matches the Phase E.6
 * decision to keep client-side error pathways audit-trail + console +
 * reqId only (see `stores/errors.ts` for the parallel sink that drives
 * the global toast).
 *
 * Message truncation lives in the call-site (`main.ts` console
 * interceptor) — the store stores whatever `push` receives so a
 * direct-from-test caller can inspect short messages without
 * round-tripping through the truncation cap.
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

/** Categorisation of the captured line — drives the email-body label. */
export type ClientLogLevel = 'error' | 'warn' | 'uncaught'

export interface ConsoleEntry {
  /** "error" | "warn" | "uncaught" — matches the backend `ConsoleEntry` record. */
  level: ClientLogLevel
  /** Already-truncated single-line message; the store does not re-truncate. */
  message: string
  /** ISO-8601 timestamp stamped at `push` time. */
  timestamp: string
}

/** Ring-buffer cap. 100 keeps the in-memory footprint trivially small. */
const RING_BUFFER_CAP = 100

export const useClientLogsStore = defineStore('clientLogs', () => {
  const entries = ref<ConsoleEntry[]>([])

  /**
   * Append one entry to the buffer, stamping it with the current ISO
   * timestamp. Trims the oldest entries when the buffer overflows.
   */
  function push(input: { level: ClientLogLevel; message: string }): ConsoleEntry {
    const entry: ConsoleEntry = {
      level: input.level,
      message: input.message,
      timestamp: new Date().toISOString(),
    }
    entries.value = [...entries.value, entry]
    if (entries.value.length > RING_BUFFER_CAP) {
      entries.value = entries.value.slice(entries.value.length - RING_BUFFER_CAP)
    }
    return entry
  }

  /**
   * Return a slice containing the most recent {@code n} entries (default
   * 50 — the dialog's attach default). The returned array is a fresh
   * copy so the caller can include it in an HTTP payload without
   * concern for downstream mutation.
   */
  function recent(n: number = 50): ConsoleEntry[] {
    if (n <= 0) return []
    const total = entries.value.length
    if (total <= n) return [...entries.value]
    return entries.value.slice(total - n)
  }

  function clear(): void {
    entries.value = []
  }

  return { entries, push, recent, clear }
})
