import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiDelete, apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import type {
  CreateModalityRequest,
  Modality,
  UpdateModalityRequest,
} from '@/types/modality'

/**
 * Phase E.6 — modality admin store.
 *
 * Surface: list + create + update + remove. Each write action re-runs
 * `load()` on success so the in-memory list stays in lockstep with
 * backend state (ordinal recomputation on create is the main reason
 * — the backend may shift other rows around to keep ordinals dense).
 *
 * Errors:
 *   - 401 / 403 → re-thrown so the global router guard can react
 *   - 400 / 409 → surfaced as a structured ApiError so the view can
 *     show inline copy for "duplicate code" / "unknown OID" /
 *     "missing OID" without losing the raw HTTP signal
 *   - network failures → ApiNetworkError surfaced verbatim; view
 *     decides between a toast and an inline error
 */
export const useModalitiesStore = defineStore('modalities', () => {
  const list = ref<Modality[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      list.value = await apiGet<Modality[]>('/pages/api/v1/modalities')
    } catch (e) {
      list.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      error.value = humanError(e, 'load')
    } finally {
      isLoading.value = false
    }
  }

  async function create(body: CreateModalityRequest): Promise<Modality> {
    error.value = null
    try {
      const created = await apiPost<Modality>('/pages/api/v1/modalities', body)
      // Re-load so the local list reflects the backend's ordinal
      // recomputation (the backend may shift sibling rows around to
      // keep ordinals dense + the (code) uniqueness gates).
      await load()
      return created
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        error.value = humanError(e, 'create')
      }
      throw e
    }
  }

  async function update(id: number, body: UpdateModalityRequest): Promise<Modality> {
    error.value = null
    try {
      const updated = await apiPut<Modality>(
        `/pages/api/v1/modalities/${encodeURIComponent(String(id))}`,
        body,
      )
      await load()
      return updated
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        error.value = humanError(e, 'update')
      }
      throw e
    }
  }

  async function remove(id: number): Promise<void> {
    error.value = null
    try {
      await apiDelete<void>(`/pages/api/v1/modalities/${encodeURIComponent(String(id))}`)
      await load()
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        error.value = humanError(e, 'remove')
      }
      throw e
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
   * Phase E.6 — clear local state so re-mounting the view after a
   * logout / study switch doesn't show stale rows.
   */
  function reset() {
    list.value = []
    isLoading.value = false
    error.value = null
  }

  return { list, isLoading, error, load, create, update, remove, reset }
})
