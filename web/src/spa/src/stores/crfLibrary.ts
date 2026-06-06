import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiDelete, apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  Crf,
  CreateCrfInput,
  CrfVersion,
  EventCrfAssignment,
  EventCrfAssignmentInput,
  MigrateVersionRequest,
  MigrateVersionResult,
  VersionUsageReport,
} from '@/types/crfLibrary'

/**
 * Phase E A8.3 — CRF library + event-CRF assignment store.
 *
 * Two surfaces in one store:
 * 1. CRF library list + create + version upload + disable
 * 2. Per-(study, event-def) CRF assignment list + attach + update +
 *    remove
 *
 * The library is study-independent; assignments are scoped to a
 * specific event definition under an active study.
 */
export const useCrfLibraryStore = defineStore('crfLibrary', () => {
  const crfs = ref<Crf[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function loadCrfs(includeRemoved: boolean = false): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      const params = includeRemoved ? '?includeRemoved=true' : ''
      crfs.value = await apiGet<Crf[]>(`/pages/api/v1/crfs${params}`)
    } catch (e) {
      crfs.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      error.value = humanError(e, 'load')
    } finally {
      isLoading.value = false
    }
  }

  type CrfMutation =
    | { ok: true; crf: Crf }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }

  async function createCrf(input: CreateCrfInput): Promise<CrfMutation> {
    try {
      const crf = await apiPost<Crf>('/pages/api/v1/crfs', input)
      crfs.value = [crf, ...crfs.value]
      return { ok: true, crf }
    } catch (e) {
      return mapMutationError(e, 'create')
    }
  }

  async function disableCrf(crfOid: string): Promise<boolean> {
    try {
      const updated = await apiPost<Crf>(`/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/disable`, {})
      const idx = crfs.value.findIndex((c) => c.oid === crfOid)
      if (idx >= 0) crfs.value[idx] = updated
      return true
    } catch (e) {
      error.value = humanError(e, 'disable')
      return false
    }
  }

  /**
   * Multipart upload — uses FormData directly since the api client
   * doesn't have a typed multipart helper.
   *
   * On parser failure the backend returns a {@code ValidationErrorBody}
   * with every parse-time message tagged as {@code field: "file"}.
   * Because multiple errors share the same field, we expose them as a
   * separate {@code parseErrors: string[]} alongside the standard
   * {@code fieldErrors} map so the view can render the list.
   */
  async function uploadVersion(
    crfOid: string,
    payload: { file: File; versionName: string; versionDescription?: string; revisionNotes?: string },
  ): Promise<
    | { ok: true; version: CrfVersion }
    | { ok: false; fieldErrors: Record<string, string>; parseErrors: string[]; message?: string }
  > {
    const form = new FormData()
    form.append('file', payload.file)
    form.append('versionName', payload.versionName)
    if (payload.versionDescription) form.append('versionDescription', payload.versionDescription)
    if (payload.revisionNotes) form.append('revisionNotes', payload.revisionNotes)

    try {
      const res = await fetch(`/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions`, {
        method: 'POST',
        body: form,
        credentials: 'include',
      })
      if (!res.ok) {
        let body: { message?: string; errors?: Array<{ field: string; message: string }> } = {}
        try { body = await res.json() } catch { /* not JSON */ }
        if (res.status === 401 || res.status === 403) {
          error.value = body.message ?? `Upload nicht erlaubt (HTTP ${res.status}).`
          throw new ApiError(res.status, body.message ?? res.statusText, body)
        }
        const fieldErrors: Record<string, string> = {}
        const parseErrors: string[] = []
        if (body.errors) {
          for (const fe of body.errors) {
            // Parser errors all share field="file"; collect them as a
            // separate list so the view can render them all instead of
            // showing only the last one.
            if (fe.field === 'file') parseErrors.push(fe.message)
            else fieldErrors[fe.field] = fe.message
          }
        }
        return {
          ok: false,
          fieldErrors,
          parseErrors,
          message: body.message ?? `Upload fehlgeschlagen (HTTP ${res.status}).`,
        }
      }
      const version: CrfVersion = await res.json()
      // Mutate the in-memory crf row to include the new version.
      const idx = crfs.value.findIndex((c) => c.oid === crfOid)
      if (idx >= 0) {
        crfs.value[idx] = { ...crfs.value[idx], versions: [...crfs.value[idx].versions, version] }
      }
      return { ok: true, version }
    } catch (e) {
      if (e instanceof ApiError) throw e
      return {
        ok: false,
        fieldErrors: {},
        parseErrors: [],
        message: e instanceof Error ? e.message : 'Unknown error',
      }
    }
  }

  /* ------------------------- Version lifecycle ----------------------- */
  // Phase E.6 crf-library — lock/unlock/restore are atomic status flips
  // that return the updated CrfVersion row. We mutate the in-memory CRF
  // row inline so the view re-renders without an extra round-trip; on
  // error the store's `error` ref is populated for the toast surface.

  async function patchVersionInPlace(crfOid: string, updated: CrfVersion): Promise<void> {
    const idx = crfs.value.findIndex((c) => c.oid === crfOid)
    if (idx < 0) return
    const versions = crfs.value[idx]!.versions.map((v) => (v.oid === updated.oid ? updated : v))
    crfs.value[idx] = { ...crfs.value[idx]!, versions }
  }

  async function lockVersion(crfOid: string, versionOid: string): Promise<boolean> {
    try {
      const updated = await apiPost<CrfVersion>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(versionOid)}/lock`,
        {},
      )
      await patchVersionInPlace(crfOid, updated)
      return true
    } catch (e) {
      error.value = humanError(e, 'lock-version')
      return false
    }
  }

  async function unlockVersion(crfOid: string, versionOid: string): Promise<boolean> {
    try {
      const updated = await apiPost<CrfVersion>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(versionOid)}/unlock`,
        {},
      )
      await patchVersionInPlace(crfOid, updated)
      return true
    } catch (e) {
      error.value = humanError(e, 'unlock-version')
      return false
    }
  }

  async function restoreVersion(crfOid: string, versionOid: string): Promise<boolean> {
    try {
      const updated = await apiPost<CrfVersion>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(versionOid)}/restore`,
        {},
      )
      await patchVersionInPlace(crfOid, updated)
      return true
    } catch (e) {
      error.value = humanError(e, 'restore-version')
      return false
    }
  }

  /**
   * Phase E.6 crf-library — sysadmin-only hard remove. Returns one of:
   * - `{ ok: true }` — the row was deleted (204 no content). The local
   *   CRF row's versions list is patched to drop the removed entry.
   * - `{ ok: false, blocker }` — the row is referenced; the blocker
   *   carries the VersionUsageReport the SPA renders in the dialog.
   * - `{ ok: false, message }` — any other failure (auth, network, 500).
   *
   * Importantly: 409 + the structured report is NOT a thrown error path
   * — the SPA treats it as a normal "no action taken" outcome and shows
   * the blocker list. Only 401/403/5xx escalate through ApiError /
   * humanError.
   */
  type HardRemoveResult =
    | { ok: true }
    | { ok: false; blocker: VersionUsageReport }
    | { ok: false; message: string }

  async function hardRemoveVersion(crfOid: string, versionOid: string): Promise<HardRemoveResult> {
    try {
      await apiDelete(`/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(versionOid)}`)
      // Drop from local state on success.
      const idx = crfs.value.findIndex((c) => c.oid === crfOid)
      if (idx >= 0) {
        const versions = crfs.value[idx]!.versions.filter((v) => v.oid !== versionOid)
        crfs.value[idx] = { ...crfs.value[idx]!, versions }
      }
      return { ok: true }
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const blocker = e.body as VersionUsageReport
        return { ok: false, blocker }
      }
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        error.value = humanError(e, 'hard-remove-version')
        throw e
      }
      return { ok: false, message: humanError(e, 'hard-remove-version') }
    }
  }

  /**
   * Phase E.6 crf-library — XLS download. Returns a `Blob` + the
   * filename the SPA should suggest (falls back to a synthesized name
   * when the Content-Disposition header is absent — older nginx
   * configurations sometimes strip it).
   *
   * The caller is responsible for triggering the browser download
   * (we don't auto-anchor here because the store stays UI-agnostic).
   */
  async function downloadVersionXls(
    crfOid: string,
    versionOid: string,
  ): Promise<{ ok: true; blob: Blob; filename: string } | { ok: false; message: string }> {
    try {
      const res = await fetch(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(versionOid)}/xls`,
        { credentials: 'include' },
      )
      if (!res.ok) {
        // 404 carries a structured body with a fallbackFilename hint;
        // surface the message verbatim so the operator knows whether it
        // was authored-in-app or scrubbed off disk.
        let body: { message?: string } = {}
        try { body = await res.json() } catch { /* not JSON */ }
        return { ok: false, message: body.message ?? `Download fehlgeschlagen (HTTP ${res.status}).` }
      }
      const blob = await res.blob()
      const cd = res.headers.get('content-disposition') ?? ''
      const match = /filename\s*=\s*"?([^"]+)"?/i.exec(cd)
      const filename = match?.[1] ?? `${crfOid}-${versionOid}.xls`
      return { ok: true, blob, filename }
    } catch (e) {
      return { ok: false, message: e instanceof Error ? e.message : 'Download fehlgeschlagen.' }
    }
  }

  /**
   * Phase E.6 crf-library — batch v.A → v.B migration. Same call shape
   * for dry-run + commit; the caller flips `body.dryRun` to switch
   * modes. Returns the structured result for the SPA preview pane (or
   * the audit-noted commit confirmation).
   */
  async function migrateVersion(
    crfOid: string,
    fromOid: string,
    toOid: string,
    body: MigrateVersionRequest,
  ): Promise<{ ok: true; result: MigrateVersionResult } | { ok: false; message: string }> {
    try {
      const result = await apiPost<MigrateVersionResult>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(fromOid)}/migrate-to/${encodeURIComponent(toOid)}`,
        body,
      )
      return { ok: true, result }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        error.value = humanError(e, 'migrate-version')
        throw e
      }
      return { ok: false, message: humanError(e, 'migrate-version') }
    }
  }

  async function disableVersion(crfOid: string, versionOid: string): Promise<boolean> {
    try {
      await apiPost<CrfVersion>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions/${encodeURIComponent(versionOid)}/disable`,
        {},
      )
      // Refresh just this CRF's versions in-place
      const idx = crfs.value.findIndex((c) => c.oid === crfOid)
      if (idx >= 0) {
        const refreshed = await apiGet<CrfVersion[]>(
          `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions`,
        )
        crfs.value[idx] = { ...crfs.value[idx], versions: refreshed }
      }
      return true
    } catch (e) {
      error.value = humanError(e, 'disable-version')
      return false
    }
  }

  /* --------------------------- Assignments --------------------------- */

  async function listAssignments(studyOid: string, sedOid: string): Promise<EventCrfAssignment[]> {
    return apiGet<EventCrfAssignment[]>(
      `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions/${encodeURIComponent(sedOid)}/crfs`,
    )
  }

  type AssignmentMutation =
    | { ok: true; assignment: EventCrfAssignment }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }

  async function attachCrf(studyOid: string, sedOid: string, body: EventCrfAssignmentInput): Promise<AssignmentMutation> {
    try {
      const assignment = await apiPost<EventCrfAssignment>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions/${encodeURIComponent(sedOid)}/crfs`,
        body,
      )
      return { ok: true, assignment }
    } catch (e) {
      return mapMutationErrorAssignment(e, 'attach')
    }
  }

  async function updateAssignment(
    studyOid: string,
    sedOid: string,
    crfOid: string,
    body: EventCrfAssignmentInput,
  ): Promise<AssignmentMutation> {
    try {
      const assignment = await apiPut<EventCrfAssignment>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions/${encodeURIComponent(sedOid)}/crfs/${encodeURIComponent(crfOid)}`,
        body,
      )
      return { ok: true, assignment }
    } catch (e) {
      return mapMutationErrorAssignment(e, 'update')
    }
  }

  async function removeAssignment(studyOid: string, sedOid: string, crfOid: string): Promise<boolean> {
    try {
      await apiDelete(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/event-definitions/${encodeURIComponent(sedOid)}/crfs/${encodeURIComponent(crfOid)}`,
      )
      return true
    } catch (e) {
      error.value = humanError(e, 'remove-assignment')
      return false
    }
  }

  /* ----------------------------- Helpers ---------------------------- */

  function mapMutationError(e: unknown, op: string): CrfMutation {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      error.value = (e.body as { message?: string } | null)?.message
              ?? `CRF ${op} nicht erlaubt (HTTP ${e.status}).`
      throw e
    }
    if (e instanceof ApiError) {
      const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
      const fieldErrors: Record<string, string> = {}
      if (body?.errors) for (const fe of body.errors) fieldErrors[fe.field] = fe.message
      return { ok: false, fieldErrors, message: body?.message ?? `CRF ${op} fehlgeschlagen (HTTP ${e.status}).` }
    }
    if (e instanceof ApiNetworkError) {
      return { ok: false, fieldErrors: {}, message: `Backend nicht erreichbar — CRF ${op} fehlgeschlagen.` }
    }
    return { ok: false, fieldErrors: {}, message: e instanceof Error ? e.message : `Unbekannter Fehler beim CRF-${op}.` }
  }

  function mapMutationErrorAssignment(e: unknown, op: string): AssignmentMutation {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      error.value = (e.body as { message?: string } | null)?.message
              ?? `Zuordnung ${op} nicht erlaubt (HTTP ${e.status}).`
      throw e
    }
    if (e instanceof ApiError) {
      const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
      const fieldErrors: Record<string, string> = {}
      if (body?.errors) for (const fe of body.errors) fieldErrors[fe.field] = fe.message
      return { ok: false, fieldErrors, message: body?.message ?? `Zuordnung ${op} fehlgeschlagen (HTTP ${e.status}).` }
    }
    if (e instanceof ApiNetworkError) {
      return { ok: false, fieldErrors: {}, message: `Backend nicht erreichbar — Zuordnung ${op} fehlgeschlagen.` }
    }
    return { ok: false, fieldErrors: {}, message: e instanceof Error ? e.message : `Unbekannter Fehler beim Zuordnung-${op}.` }
  }

  function humanError(e: unknown, op: string): string {
    if (e instanceof ApiNetworkError) return `Backend nicht erreichbar — ${op} fehlgeschlagen.`
    if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      return body?.message ?? `${op} fehlgeschlagen (HTTP ${e.status}).`
    }
    return e instanceof Error ? e.message : `Unbekannter Fehler beim ${op}.`
  }

  /**
   * Phase E.6 — clear every piece of study-scoped state. The CRF
   * library list itself is study-independent in principle (CRFs are
   * platform-wide assets) — but the per-(event-def) assignments
   * surface is study-scoped, and any in-flight error / loading flags
   * may reference a study-A request mid-switch. Wholesale clearing
   * keeps the post-switch view honest. Called by {@link
   * useAuthStore.pickStudy} before re-bootstrapping.
   */
  function reset() {
    crfs.value = []
    isLoading.value = false
    error.value = null
  }

  return {
    crfs,
    isLoading,
    error,
    loadCrfs,
    createCrf,
    disableCrf,
    uploadVersion,
    disableVersion,
    lockVersion,
    unlockVersion,
    restoreVersion,
    hardRemoveVersion,
    downloadVersionXls,
    migrateVersion,
    listAssignments,
    attachCrf,
    updateAssignment,
    removeAssignment,
    reset,
  }
})
