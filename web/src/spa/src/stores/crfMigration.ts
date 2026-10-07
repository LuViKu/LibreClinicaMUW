import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  EventCrfMigrationOptions,
  EventCrfMigrationPreview,
  EventCrfMigrationRequest,
  EventCrfMigrationResult,
} from '@/types/crfMigration'
import { csvCell } from '@/lib/csv'

/**
 * Moving existing event CRFs to another version of their CRF: options, then
 * a preview that writes nothing, then the run. The run sends the count and
 * the selection digest its preview showed, and the server refuses (409,
 * nothing changed) when the selection no longer matches them.
 *
 * <p>The server runs the move in one transaction and returns the log, so the
 * result and the log appear here (and in the audit trail); nothing is
 * e-mailed, unlike the legacy batch migration.
 */
export const useCrfMigrationStore = defineStore('crfMigration', () => {
  const options = ref<EventCrfMigrationOptions | null>(null)
  const preview = ref<EventCrfMigrationPreview | null>(null)
  const result = ref<EventCrfMigrationResult | null>(null)
  const isLoading = ref(false)
  const isPreviewing = ref(false)
  const isRunning = ref(false)
  /** The server's message for a refusal or failure; null when none. */
  const error = ref<string | null>(null)
  /** Per-field validation errors of a 400 (studyOid, sourceVersionOid, siteOids, …). */
  const fieldErrors = ref<Record<string, string>>({})

  function url(crfOid: string, suffix = ''): string {
    return `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/event-crf-migration${suffix}`
  }

  async function loadOptions(crfOid: string, studyOid: string): Promise<boolean> {
    isLoading.value = true
    clearErrors()
    try {
      options.value = await apiGet<EventCrfMigrationOptions>(
        `${url(crfOid, '/options')}?studyOid=${encodeURIComponent(studyOid)}`,
      )
      return true
    } catch (e) {
      options.value = null
      absorb(e)
      return false
    } finally {
      isLoading.value = false
    }
  }

  async function runPreview(crfOid: string, request: EventCrfMigrationRequest): Promise<boolean> {
    isPreviewing.value = true
    clearErrors()
    result.value = null
    try {
      // A preview never carries what it expects; that belongs to the run.
      const body: EventCrfMigrationRequest = { ...request }
      delete body.expectedEventCrfCount
      delete body.expectedSelectionDigest
      preview.value = await apiPost<EventCrfMigrationPreview>(url(crfOid, '/preview'), body)
      return true
    } catch (e) {
      preview.value = null
      absorb(e)
      return false
    } finally {
      isPreviewing.value = false
    }
  }

  /** Runs the move the current preview showed; refused client-side without one. */
  async function run(crfOid: string, request: EventCrfMigrationRequest): Promise<boolean> {
    if (!preview.value) return false
    isRunning.value = true
    clearErrors()
    try {
      result.value = await apiPost<EventCrfMigrationResult>(url(crfOid), {
        ...request,
        expectedEventCrfCount: preview.value.eventCrfCount,
        expectedSelectionDigest: preview.value.selectionDigest,
      })
      preview.value = null
      return true
    } catch (e) {
      absorb(e)
      return false
    } finally {
      isRunning.value = false
    }
  }

  function clearPreview(): void {
    preview.value = null
    clearErrors()
  }

  function reset(): void {
    options.value = null
    preview.value = null
    result.value = null
    isLoading.value = false
    isPreviewing.value = false
    isRunning.value = false
    clearErrors()
  }

  function clearErrors(): void {
    error.value = null
    fieldErrors.value = {}
  }

  /** A lost session (401) is rethrown for the app to handle; everything else is shown. */
  function absorb(e: unknown): void {
    if (e instanceof ApiError && e.isUnauthorized) throw e
    if (e instanceof ApiError) {
      const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
      if (body?.errors && body.errors.length > 0) {
        const out: Record<string, string> = {}
        for (const fe of body.errors) out[fe.field] = out[fe.field] ? `${out[fe.field]} ${fe.message}` : fe.message
        fieldErrors.value = out
        return
      }
      error.value = body?.message ?? `HTTP ${e.status}`
      return
    }
    if (e instanceof ApiNetworkError) {
      error.value = 'Backend nicht erreichbar.'
      return
    }
    error.value = e instanceof Error ? e.message : String(e)
  }

  return {
    options,
    preview,
    result,
    isLoading,
    isPreviewing,
    isRunning,
    error,
    fieldErrors,
    loadOptions,
    runPreview,
    run,
    clearPreview,
    reset,
  }
})

const CSV_HEADER = [
  'CRF_Name', 'Origin_Version', 'Target_Version', 'Subject_ID', 'Site', 'Event', 'Event_Ordinal',
  'Event_CRF_ID', 'SDV_Cleared', 'Subject_Signature_Removed', 'Event_Signature_Removed', 'CRF_Signature_Removed',
]

/**
 * The run's log as CSV: the legacy report's columns (CRF name, origin and
 * target version, subject, site, event, event ordinal), then the event CRF
 * id and what the move cleared.
 */
export function migrationLogCsv(result: EventCrfMigrationResult): string {
  const lines = [CSV_HEADER.join(',')]
  for (const r of result.log) {
    lines.push([
      result.crfName, result.sourceVersion.name, result.targetVersion.name, r.studySubjectLabel, r.siteName,
      r.eventName, r.eventOrdinal, r.eventCrfId, r.sdvVerified, r.subjectSigned, r.eventSigned, r.eventCrfSigned,
    ].map(csvCell).join(','))
  }
  return lines.join('\r\n') + '\r\n'
}
