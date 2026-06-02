import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiDelete, apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  Crf,
  CreateCrfInput,
  CrfVersion,
  EventCrfAssignment,
  EventCrfAssignmentInput,
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

  return {
    crfs,
    isLoading,
    error,
    loadCrfs,
    createCrf,
    disableCrf,
    uploadVersion,
    disableVersion,
    listAssignments,
    attachCrf,
    updateAssignment,
    removeAssignment,
  }
})
