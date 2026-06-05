import { defineStore } from 'pinia'
import { ref } from 'vue'

import { apiGet, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  StudyParameters,
  UpdateStudyParametersInput,
} from '@/types/studyParameters'

/**
 * Phase E.6 study-params — Pinia store wrapping
 * {@code /pages/api/v1/studies/{oid}/parameters}.
 *
 * Pattern matches {@code stores/study.ts} (load → mutation → reset)
 * — the underlying endpoints surface the same partial-patch + field-
 * error envelope as the study-identity PUT, so the SPA store collapses
 * onto the same {@code fieldErrors[handle]} shape that
 * {@code StudyIdentityEditView} consumes.
 *
 * <h3>Actions</h3>
 *
 * <ul>
 *   <li>{@code load(oid)} — pulls the current 19-field DTO into
 *       {@code current}.</li>
 *   <li>{@code update(oid, patch)} — sends the partial patch;
 *       returns {@code {ok:true, data}} on 200, {@code {ok:false,
 *       fieldErrors, message}} on a 400 validation envelope. Throws
 *       {@code ApiError} for 401/403/404/409 so views handle them
 *       via the same auth-redirect / blocked-state guards they
 *       already use for {@code build-study-edit}.</li>
 *   <li>{@code reset()} — drops in-memory state. Called by the
 *       view's {@code onUnmounted} so re-mounts re-fetch fresh.</li>
 * </ul>
 *
 * <h3>Field-error shape</h3>
 *
 * Backend returns {@code SubjectsApiController.ValidationErrorBody}
 * — {@code { message, fieldErrors: [{field, message}] }}. We flatten
 * onto a {@code Record<handle, message>} so the view can bind via
 * {@code v-bind:errors="store.fieldErrors[handle]"} without an
 * O(n²) lookup per render.
 */
export const useStudyParametersStore = defineStore('studyParameters', () => {
  const current = ref<StudyParameters | null>(null)
  const isLoading = ref(false)
  const isSaving = ref(false)
  const error = ref<string | null>(null)
  const fieldErrors = ref<Record<string, string>>({})

  async function load(studyOid: string): Promise<void> {
    if (!studyOid || studyOid.trim() === '') {
      current.value = null
      error.value = 'Study OID is required to load parameters.'
      return
    }
    isLoading.value = true
    error.value = null
    fieldErrors.value = {}
    try {
      current.value = await apiGet<StudyParameters>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/parameters`,
      )
    } catch (e) {
      current.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Studienparameter konnten nicht geladen werden.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value =
          body?.message ?? `Fehler beim Laden der Studienparameter (HTTP ${e.status}).`
      } else {
        error.value =
          e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden.'
      }
    } finally {
      isLoading.value = false
    }
  }

  type UpdateResult =
    | { ok: true; data: StudyParameters }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }

  async function update(
    studyOid: string,
    patch: UpdateStudyParametersInput,
  ): Promise<UpdateResult> {
    if (!studyOid || studyOid.trim() === '') {
      return { ok: false, fieldErrors: {}, message: 'Study OID is required' }
    }
    isSaving.value = true
    error.value = null
    fieldErrors.value = {}
    try {
      const data = await apiPut<StudyParameters>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/parameters`,
        patch,
      )
      current.value = data
      return { ok: true, data }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiError && e.status === 400) {
        const body = e.body as
          | { message?: string; fieldErrors?: Array<{ field: string; message: string }> }
          | null
        const flat: Record<string, string> = {}
        for (const fe of body?.fieldErrors ?? []) {
          flat[fe.field] = fe.message
        }
        fieldErrors.value = flat
        return { ok: false, fieldErrors: flat, message: body?.message }
      }
      if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value =
          body?.message ?? `Fehler beim Speichern (HTTP ${e.status}).`
        return { ok: false, fieldErrors: {}, message: error.value ?? undefined }
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Änderungen wurden nicht gespeichert.'
        return { ok: false, fieldErrors: {}, message: error.value ?? undefined }
      }
      const msg = e instanceof Error ? e.message : 'Unbekannter Fehler beim Speichern.'
      error.value = msg
      return { ok: false, fieldErrors: {}, message: msg }
    } finally {
      isSaving.value = false
    }
  }

  function reset(): void {
    current.value = null
    isLoading.value = false
    isSaving.value = false
    error.value = null
    fieldErrors.value = {}
  }

  return {
    current,
    isLoading,
    isSaving,
    error,
    fieldErrors,
    load,
    update,
    reset,
  }
})
