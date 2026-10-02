import { defineStore } from 'pinia'
import { useAuditLogPaging } from './auditLogPaging'

/**
 * Phase E hardening B — System-wide audit-log store.
 *
 * Backs the {@link SystemAuditLogView}. Pages through
 * `GET /pages/api/v1/audit/system` (the sysadmin-only adapter that
 * bypasses the per-study endpoint's {@code is_user_visible=true}
 * filter so OPERATION_FAILED(61) + JOB_FAILED(62) §11.10(e) rows are
 * surfaced for compliance review), which filters, counts and pages the
 * whole trail in the database; see {@link useAuditLogPaging}.
 *
 * Mirrors {@link useAuditLogStore} except for:
 *   - the URLs (`/audit/system` vs `/audit`),
 *   - no `exportXlsx` action (XLSX export is deferred per the
 *     plan — the per-study audit-log already carries one and the
 *     sysadmin surface ships read-only first).
 *
 * Forbidden / unauthorized errors are re-thrown so the global error
 * handler + the router-guard can react (an Investigator who
 * accidentally lands here gets bounced home rather than seeing a
 * forever-loading state).
 */
export const useSystemAuditLogStore = defineStore('systemAuditLog', () => {
  const log = useAuditLogPaging('/pages/api/v1/audit/system', '/pages/api/v1/audit/system/facets', {
    unreachable:
      'Backend nicht erreichbar — System-Audit-Protokoll kann nicht geladen werden. Bitte später erneut versuchen.',
    failed: (status) => `Fehler beim Laden des System-Audit-Protokolls (HTTP ${status}).`,
    unknown: 'Unbekannter Fehler beim Laden des System-Audit-Protokolls.',
  })

  function reset() {
    log.resetState()
  }

  return { ...log, reset }
})
