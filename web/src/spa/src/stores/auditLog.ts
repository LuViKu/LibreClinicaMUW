import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiError, ApiNetworkError } from '@/api/client'
import { apiDownload } from '@/api/download'
import { useAuditLogPaging } from './auditLogPaging'

/**
 * Phase E.6 + E.4 M10 — Audit-log store.
 *
 * Backs the StudyAuditLogView. Pages through
 * `GET /pages/api/v1/audit?actor=…&variant=…&subjectId=…&item=…&from=…&to=…&page=…`
 * (the M10 adapter), which filters, counts and pages the study's whole
 * trail in the database; see {@link useAuditLogPaging}. Until 2026-09-30
 * the adapter returned the newest 500 rows and this store filtered them,
 * so an older row of a subject, user or item could not be reached.
 *
 * If the backend is unreachable the store sets `error` so the view can
 * render an explicit message rather than silently displaying stale data.
 */
export const useAuditLogStore = defineStore('auditLog', () => {
  const log = useAuditLogPaging('/pages/api/v1/audit', '/pages/api/v1/audit/facets', {
    unreachable:
      'Backend nicht erreichbar — Audit-Log kann nicht geladen werden. Bitte später erneut versuchen.',
    failed: (status) => `Fehler beim Laden des Audit-Logs (HTTP ${status}).`,
    unknown: 'Unbekannter Fehler beim Laden des Audit-Logs.',
  })
  const isExporting = ref(false)
  const exportError = ref<string | null>(null)

  /**
   * Phase E.6 — sponsor / inspector XLSX hand-off of the audit log.
   *
   * Forwards the filters the view shows as query params; the workbook holds
   * every row that matches them, not only the page on screen. The backend
   * additionally emits an audit_log_event row (type 55) recording who pulled
   * the export and which filters were active.
   *
   * Failures are surfaced via the local `exportError` ref so the view
   * can render an inline toast without clobbering the main `error`
   * state used for the list-load failure mode.
   */
  async function exportXlsx(): Promise<void> {
    isExporting.value = true
    exportError.value = null
    try {
      const qs = log.filterParams().toString()
      const path = qs
        ? `/pages/api/v1/audit/export.xlsx?${qs}`
        : '/pages/api/v1/audit/export.xlsx'
      // Fallback filename when the server doesn't set Content-Disposition
      // (defensive — production backend always does).
      const today = new Date().toISOString().slice(0, 10).replaceAll('-', '')
      await apiDownload(path, `audit_${today}.xlsx`)
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        exportError.value =
          'Backend nicht erreichbar — Audit-Log-Export konnte nicht gestartet werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        exportError.value =
          body?.message ?? `Audit-Log-Export fehlgeschlagen (HTTP ${e.status}).`
      } else {
        exportError.value =
          e instanceof Error ? e.message : 'Unbekannter Fehler beim Audit-Log-Export.'
      }
    } finally {
      isExporting.value = false
    }
  }

  /**
   * Phase E.6 — clear every piece of study-scoped state so the audit
   * log doesn't blend study-A entries into study B's timeline. Called
   * by {@link useAuthStore.pickStudy} before re-bootstrapping.
   */
  function reset() {
    log.resetState()
    isExporting.value = false
    exportError.value = null
  }

  return {
    ...log,
    isExporting,
    exportError,
    exportXlsx,
    reset,
  }
})
