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

/**
 * Best-effort PII redaction applied to every message BEFORE it lands
 * in the ring buffer. The bug-report dialog ships these entries
 * to an institutional inbox — anything PHI-shaped that leaks via a
 * stack trace, an Error message that interpolated patient data, or
 * a third-party library's debug log gets replaced with a
 * `[REDACTED:…]` marker.
 *
 * Pattern set tuned for the MUW Ophth deployment:
 *
 *  - Subject labels matching the seeded conventions ({@code M-001},
 *    {@code DF-001}, {@code GA-001}, {@code OPH-2024-001}). Pattern is
 *    1-4 uppercase letters, a dash, 2-4 digits, optional further
 *    dash + 1-4 alphanumerics (covers M-001-V1 sub-IDs). We DON'T
 *    redact arbitrary "FOO-123" tokens — too broad and would scrub
 *    library identifiers / git SHAs / Spring bean ids.
 *  - Dates of birth in ISO ({@code 1970-03-15}), German
 *    ({@code 15.03.1970}), and US ({@code 03/15/1970}) format.
 *    Restricted to 19xx / 20xx year ranges so things like CSS
 *    {@code rgba(0.0.0.1)} or version strings ({@code 3.4.10}) survive.
 *  - Email addresses (any RFC-ish shape).
 *
 * Operator preview still shows the (already-redacted) entries, so the
 * operator can scan one more time before submitting. Truly novel PII
 * patterns won't be caught — flagged for a Phase F redaction sweep.
 */
const REDACTION_PATTERNS: { re: RegExp; replacement: string }[] = [
  // Email — match first so the `@` boundary doesn't get eaten by the
  // subject-label rule.
  {
    re: /\b[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}\b/g,
    replacement: '[REDACTED:EMAIL]',
  },
  // Subject label — 1-4 uppercase letters + dash + 2-4 digits
  // + optional further dash-segment.
  {
    re: /\b[A-Z]{1,4}-\d{2,4}(?:-[A-Za-z0-9]{1,4})?\b/g,
    replacement: '[REDACTED:SUBJECT-ID]',
  },
  // DOB in ISO 8601 — restricted to plausible years.
  {
    re: /\b(?:19|20)\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])\b/g,
    replacement: '[REDACTED:DOB]',
  },
  // DOB in German DD.MM.YYYY.
  {
    re: /\b(?:0[1-9]|[12]\d|3[01])\.(?:0[1-9]|1[0-2])\.(?:19|20)\d{2}\b/g,
    replacement: '[REDACTED:DOB]',
  },
  // DOB in US MM/DD/YYYY (operators occasionally cross-paste).
  {
    re: /\b(?:0[1-9]|1[0-2])\/(?:0[1-9]|[12]\d|3[01])\/(?:19|20)\d{2}\b/g,
    replacement: '[REDACTED:DOB]',
  },
]

/**
 * Apply every {@link REDACTION_PATTERNS} replacement to a single
 * captured message. Exported for the unit-test layer.
 */
export function redactPii(message: string): string {
  let out = message
  for (const { re, replacement } of REDACTION_PATTERNS) {
    out = out.replace(re, replacement)
  }
  return out
}

export const useClientLogsStore = defineStore('clientLogs', () => {
  const entries = ref<ConsoleEntry[]>([])

  /**
   * Append one entry to the buffer, stamping it with the current ISO
   * timestamp. Trims the oldest entries when the buffer overflows.
   */
  function push(input: { level: ClientLogLevel; message: string }): ConsoleEntry {
    const entry: ConsoleEntry = {
      level: input.level,
      // PII guard: redact at capture time so the preview disclosure
      // shows the scrubbed string — what the operator sees is what
      // the email will carry.
      message: redactPii(input.message),
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
