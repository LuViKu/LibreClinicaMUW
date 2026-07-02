import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CreateGroupClassInput,
  GroupClass,
  UpdateGroupClassInput,
} from '@/types/groupClass'

/**
 * Phase E A8.6 — subject group classes store.
 */
export const useGroupClassesStore = defineStore('groupClasses', () => {
  const rows = ref<GroupClass[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function load(studyOid: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<GroupClass[]>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/group-classes`,
      )
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      error.value = humanError(e, 'load')
    } finally {
      isLoading.value = false
    }
  }

  type MutationResult =
    | { ok: true; groupClass: GroupClass }
    | { ok: false; fieldErrors: Record<string, string>; message?: string }

  async function create(studyOid: string, body: CreateGroupClassInput): Promise<MutationResult> {
    return mutate(
      () => apiPost<GroupClass>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/group-classes`,
        body,
      ),
      'create',
      (gc) => { rows.value = [...rows.value, gc] },
    )
  }

  async function update(
    studyOid: string,
    groupClassId: number,
    patch: UpdateGroupClassInput,
  ): Promise<MutationResult> {
    return mutate(
      () => apiPut<GroupClass>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/group-classes/${groupClassId}`,
        patch,
      ),
      'update',
      (gc) => {
        const idx = rows.value.findIndex((r) => r.id === groupClassId)
        if (idx >= 0) rows.value[idx] = gc
      },
    )
  }

  async function disable(studyOid: string, groupClassId: number): Promise<boolean> {
    try {
      const updated = await apiPost<GroupClass>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/group-classes/${groupClassId}/disable`,
        {},
      )
      const idx = rows.value.findIndex((r) => r.id === groupClassId)
      if (idx >= 0) rows.value[idx] = updated
      return true
    } catch (e) {
      error.value = humanError(e, 'disable')
      return false
    }
  }

  async function restore(studyOid: string, groupClassId: number): Promise<boolean> {
    try {
      const updated = await apiPost<GroupClass>(
        `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/group-classes/${groupClassId}/restore`,
        {},
      )
      const idx = rows.value.findIndex((r) => r.id === groupClassId)
      if (idx >= 0) rows.value[idx] = updated
      return true
    } catch (e) {
      error.value = humanError(e, 'restore')
      return false
    }
  }

  async function mutate(
    op: () => Promise<GroupClass>,
    label: 'create' | 'update',
    onSuccess: (gc: GroupClass) => void,
  ): Promise<MutationResult> {
    try {
      const gc = await op()
      onSuccess(gc)
      return { ok: true, groupClass: gc }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        error.value = humanError(e, label)
        throw e
      }
      if (e instanceof ApiError) {
        const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
        const fieldErrors: Record<string, string> = {}
        if (body?.errors) for (const fe of body.errors) fieldErrors[fe.field] = fe.message
        return {
          ok: false,
          fieldErrors,
          message: body?.message ?? `Gruppenklasse ${label} fehlgeschlagen (HTTP ${e.status}).`,
        }
      }
      if (e instanceof ApiNetworkError) {
        return { ok: false, fieldErrors: {}, message: `Backend nicht erreichbar — Gruppenklasse ${label} fehlgeschlagen.` }
      }
      return { ok: false, fieldErrors: {}, message: e instanceof Error ? e.message : `Unbekannter Fehler beim Gruppenklasse-${label}.` }
    }
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
   * 2026-07-02 — v1 randomization picker call.
   *
   * <p>Wraps {@code POST /api/v1/studies/{studyOid}/group-classes/{id}/randomize}
   * (backend: SubjectRandomizationService). Returns the pick + the
   * hex seed so the AddSubjectView can round-trip the randomization
   * envelope into the enrollment POST. Stateless — the store rows
   * stay untouched.
   */
  async function randomize(studyOid: string, groupClassId: number): Promise<RandomizeResult> {
    return apiPost<RandomizeResult>(
      `/pages/api/v1/studies/${encodeURIComponent(studyOid)}/group-classes/${groupClassId}/randomize`,
      {},
    )
  }

  return { rows, isLoading, error, load, create, update, disable, restore, randomize }
})

/**
 * 2026-07-02 — response shape of the {@code /randomize} endpoint.
 * Matches {@code RandomizeGroupClassResponse} in Java.
 */
export interface RandomizeResult {
  groupId: number
  groupName: string
  seed: string
  source: string
  meta: string | null
  groupClassId: number
  groupClassName: string
}
