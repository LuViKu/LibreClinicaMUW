import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CreateStudyInput,
  StudyBuildStatus,
  StudyIdentity,
  UpdateStudyInput,
} from '@/types/study'

/**
 * Phase E.7 + E.4 M12 — Study-build store.
 *
 * Hydrates from `GET /pages/api/v1/studies/{oid}/build-status` (the
 * M12 adapter). Caller passes the active study OID; the response is
 * the 7-task setup tracker (create-study → CRF → events → groups
 * → rules → sites → users) with each task's current count + status.
 *
 * Mock removal — per the polished-jumping-swan plan's hard-removal
 * policy: the previous `loadMock()` helper + the LCDemo MOCK
 * fixture are deleted in this PR.
 */
export const useStudyStore = defineStore('study', () => {
  const status = ref<StudyBuildStatus | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const completedTasks = computed(() => status.value?.tasks.filter((t) => t.status === 'complete').length ?? 0)
  const totalTasks = computed(() => status.value?.tasks.length ?? 0)
  const percentComplete = computed(() => {
    if (totalTasks.value === 0) return 0
    return Math.round((completedTasks.value / totalTasks.value) * 100)
  })

  async function load(studyOid?: string): Promise<void> {
    if (!studyOid || studyOid.trim() === '') {
      status.value = null
      error.value = 'Active study OID is required to load the build tracker.'
      return
    }
    isLoading.value = true
    error.value = null
    try {
      status.value = await apiGet<StudyBuildStatus>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/build-status`,
      )
    } catch (e) {
      status.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Build-Status kann nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden des Build-Status (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden des Build-Status.'
      }
    } finally {
      isLoading.value = false
    }
  }

  /* ----------------------------------------------------------------- */
  /* Phase E A8.1 — study identity CRUD                                */
  /* ----------------------------------------------------------------- */

  type CreateResult =
    | { ok: true; study: StudyIdentity }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }

  async function create(input: CreateStudyInput): Promise<CreateResult> {
    return submitStudyMutation(
      () => apiPost<StudyIdentity>('/pages/api/v1/studies', input),
      'create',
    )
  }

  async function update(oid: string, patch: UpdateStudyInput): Promise<CreateResult> {
    return submitStudyMutation(
      () => apiPut<StudyIdentity>(`/pages/api/v1/studies/${encodeURIComponent(oid)}`, patch),
      'update',
    )
  }

  async function disable(oid: string): Promise<boolean> {
    return lifecycle(oid, 'disable')
  }

  async function restore(oid: string): Promise<boolean> {
    return lifecycle(oid, 'restore')
  }

  async function submitStudyMutation(
    op: () => Promise<StudyIdentity>,
    label: 'create' | 'update',
  ): Promise<CreateResult> {
    try {
      const study = await op()
      return { ok: true, study }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Studie ${label} nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiError) {
        const errBody = e.body as
          | { message?: string; errors?: Array<{ field: string; message: string }> }
          | null
        const fieldErrors: Record<string, string> = {}
        if (errBody?.errors) for (const fe of errBody.errors) fieldErrors[fe.field] = fe.message
        return {
          ok: false,
          fieldErrors,
          message: errBody?.message ?? `Studie ${label} fehlgeschlagen (HTTP ${e.status}).`,
        }
      }
      if (e instanceof ApiNetworkError) {
        return {
          ok: false,
          fieldErrors: {},
          message: `Backend nicht erreichbar — Studie ${label} fehlgeschlagen. Bitte später erneut versuchen.`,
        }
      }
      return {
        ok: false,
        fieldErrors: {},
        message: e instanceof Error ? e.message : `Unbekannter Fehler beim Studie-${label}.`,
      }
    }
  }

  async function lifecycle(oid: string, op: 'disable' | 'restore'): Promise<boolean> {
    try {
      await apiPost<StudyIdentity>(
        `/pages/api/v1/studies/${encodeURIComponent(oid)}/${op}`,
        {},
      )
      return true
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Studie ${op} nicht erlaubt (HTTP ${e.status}).`
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value = `Backend nicht erreichbar — Studie ${op} fehlgeschlagen.`
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Studie ${op} fehlgeschlagen (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : `Unbekannter Fehler beim Studie-${op}.`
      }
      return false
    }
  }

  /**
   * Phase E A8.5 — operational status transition.
   * Sysadmin-only on the backend; the SPA hides the select for other
   * roles. {@code reason} is required for AVAILABLE→LOCKED /
   * AVAILABLE→FROZEN (the backend 400s otherwise).
   */
  async function setStatus(
    oid: string,
    targetStatus: 'AVAILABLE' | 'PENDING' | 'LOCKED' | 'FROZEN',
    reason: string,
  ): Promise<CreateResult> {
    return submitStudyMutation(
      () => apiPost<StudyIdentity>(
        `/pages/api/v1/studies/${encodeURIComponent(oid)}/status`,
        { targetStatus, reason },
      ),
      'update',
    )
  }

  return {
    status,
    isLoading,
    error,
    completedTasks,
    totalTasks,
    percentComplete,
    load,
    create,
    update,
    disable,
    restore,
    setStatus,
  }
})
