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

  /**
   * Phase E.6 build-study tracker — operator-discretion task ack.
   *
   * POSTs to {@code /pages/api/v1/studies/{oid}/build-status/acknowledge}
   * with a {@code taskId} ∈ {'groups', 'rules', 'sites'} to flip a
   * zero-count optional task from "optional" to "complete". The
   * backend returns the refreshed {@link StudyBuildStatus} so the
   * store overwrites {@code status} verbatim without a follow-up
   * GET round-trip.
   *
   * <p>Idempotent on the backend (ON CONFLICT DO NOTHING) — clicking
   * twice is safe.
   */
  async function acknowledgeTask(
    studyOid: string,
    taskId: 'groups' | 'rules' | 'sites',
  ): Promise<{ ok: true } | { ok: false; message: string }> {
    if (!studyOid || studyOid.trim() === '') {
      return { ok: false, message: 'Active study OID is required.' }
    }
    try {
      status.value = await apiPost<StudyBuildStatus>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/build-status/acknowledge`,
        { taskId },
      )
      return { ok: true }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiNetworkError) {
        return {
          ok: false,
          message:
            'Backend nicht erreichbar — Schritt konnte nicht als abgeschlossen markiert werden.',
        }
      }
      if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        return {
          ok: false,
          message: body?.message ?? `Markierung fehlgeschlagen (HTTP ${e.status}).`,
        }
      }
      return {
        ok: false,
        message:
          e instanceof Error
            ? e.message
            : 'Unbekannter Fehler beim Markieren des Schritts.',
      }
    }
  }

  /**
   * Phase E.6 — cached study-identity snapshot for the
   * SubjectMatrixView footer (PI, planned start, status). Separate
   * from {@link status} (the build-status tracker) — the identity
   * payload is small + immutable per session, so we keep a single
   * cached copy keyed by the last fetched OID.
   */
  const identity = ref<StudyIdentity | null>(null)
  const identityOid = ref<string | null>(null)
  const isLoadingIdentity = ref(false)
  const identityError = ref<string | null>(null)

  async function loadIdentity(studyOid?: string): Promise<void> {
    if (!studyOid || studyOid.trim() === '') {
      identity.value = null
      identityOid.value = null
      return
    }
    // Cache hit — no re-fetch.
    if (identityOid.value === studyOid && identity.value !== null) return
    isLoadingIdentity.value = true
    identityError.value = null
    try {
      identity.value = await apiGet<StudyIdentity>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}`,
      )
      identityOid.value = studyOid
    } catch (e) {
      identity.value = null
      identityOid.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      identityError.value = e instanceof Error ? e.message : 'Failed to load study identity.'
    } finally {
      isLoadingIdentity.value = false
    }
  }

  /**
   * Phase E.6 — clear the cached build-status snapshot so the SPA
   * doesn't show study-A progress when navigating into study B's
   * build view. Called by {@link useAuthStore.pickStudy} before
   * re-bootstrapping.
   */
  function reset() {
    status.value = null
    isLoading.value = false
    error.value = null
    identity.value = null
    identityOid.value = null
    isLoadingIdentity.value = false
    identityError.value = null
  }

  return {
    status,
    isLoading,
    error,
    completedTasks,
    totalTasks,
    percentComplete,
    identity,
    isLoadingIdentity,
    identityError,
    load,
    loadIdentity,
    create,
    update,
    disable,
    restore,
    setStatus,
    acknowledgeTask,
    reset,
  }
})
