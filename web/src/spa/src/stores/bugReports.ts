/**
 * Phase E in-app bug-report store.
 *
 * Single action — {@code submit(payload)} POSTs to
 * {@code /pages/api/v1/bug-report}. The store keeps a thin slice of
 * UI state ({@code isSubmitting}, {@code error}, {@code lastTicketId})
 * so the dialog can render the in-flight spinner + the success toast
 * with the returned ticket id, and so the error block can surface the
 * server's recipient-not-configured copy when the institution forgot
 * to wire the recipient.
 *
 * Wire shape: see BugReportApiController.BugReportRequest /
 * BugReportApiController.BugReportResponse. The SPA's reqId-correlation
 * header is added by the {@link apiPost} wrapper.
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type { ConsoleEntry } from '@/stores/clientLogs'

export interface BugReportSubmitPayload {
  title: string
  description: string
  /** Optional — empty / undefined skips the field on the wire. */
  reproductionSteps?: string
  /**
   * When the operator unchecks the "attach page URL" toggle the dialog
   * passes {@code false}; the store then omits the {@code pageUrl}
   * field from the wire payload entirely instead of auto-deriving from
   * {@code window.location}. Defaults to {@code true} so existing
   * callers (none yet outside the dialog) preserve the original
   * always-include behaviour.
   */
  attachPageUrl?: boolean
  /**
   * Pre-collected console entries to ship with the report. Omitted /
   * {@code undefined} skips the field; an empty array is treated the
   * same as omitted (the backend already short-circuits on empty).
   */
  consoleEntries?: ConsoleEntry[]
}

export interface BugReportResponse {
  delivered: boolean
  ticketId: string
}

/** Distinguish recipient-not-configured (503) from any other error so the dialog can pick the right copy. */
export type BugReportErrorKind = 'recipient-not-configured' | 'network' | 'unknown'

export const useBugReportsStore = defineStore('bugReports', () => {
  const isSubmitting = ref(false)
  const lastTicketId = ref<string | null>(null)
  const errorKind = ref<BugReportErrorKind | null>(null)
  const errorMessage = ref<string | null>(null)

  /**
   * Submit the operator's bug report. On success the store sets
   * {@link lastTicketId} and returns the ticket id. On failure the
   * store sets {@link errorKind} + {@link errorMessage} and returns
   * {@code null}; the dialog renders the copy inline and stays open.
   *
   * The {@code pageUrl} + {@code userAgent} are auto-populated from
   * the running browser context here so the dialog component stays
   * free of side-effecting reads — keeps the dialog testable with a
   * deterministic emit-payload contract.
   */
  async function submit(payload: BugReportSubmitPayload): Promise<string | null> {
    isSubmitting.value = true
    errorKind.value = null
    errorMessage.value = null
    try {
      const attachPageUrl = payload.attachPageUrl !== false
      const pageUrl = attachPageUrl
        && typeof window !== 'undefined'
        && window.location
        ? window.location.pathname + (window.location.search ?? '')
        : null
      const userAgent = typeof navigator !== 'undefined' && navigator.userAgent
        ? navigator.userAgent
        : ''
      const reproduction = payload.reproductionSteps?.trim() ?? ''
      const consoleEntries = payload.consoleEntries && payload.consoleEntries.length > 0
        ? payload.consoleEntries
        : undefined
      const body: Record<string, unknown> = {
        title: payload.title.trim(),
        description: payload.description.trim(),
        reproductionSteps: reproduction === '' ? null : reproduction,
        userAgent,
      }
      if (pageUrl !== null) body.pageUrl = pageUrl
      if (consoleEntries !== undefined) body.consoleEntries = consoleEntries
      const resp = await apiPost<BugReportResponse>('/pages/api/v1/bug-report', body)
      lastTicketId.value = resp.ticketId
      return resp.ticketId
    } catch (e) {
      if (e instanceof ApiError && e.status === 503) {
        errorKind.value = 'recipient-not-configured'
      } else if (e instanceof ApiNetworkError) {
        errorKind.value = 'network'
      } else {
        errorKind.value = 'unknown'
      }
      const body = e instanceof ApiError ? (e.body as { message?: string } | null) : null
      errorMessage.value = body?.message
        ?? (e instanceof Error ? e.message : String(e))
      return null
    } finally {
      isSubmitting.value = false
    }
  }

  /** Reset the transient UI state so re-opening the dialog starts fresh. */
  function reset() {
    isSubmitting.value = false
    lastTicketId.value = null
    errorKind.value = null
    errorMessage.value = null
  }

  return { isSubmitting, lastTicketId, errorKind, errorMessage, submit, reset }
})
