/**
 * Phase E.6 — Per-eye modality baselines store.
 *
 * Backs the {@code ModalityBaselinesPanel} on the Subject Detail view.
 * Caches the response from
 * `GET /pages/api/v1/subjects/{label}/eyes/{eye}/modality-baselines`
 * keyed by `${label}|${eye}` so navigating between OD and OS or away
 * and back doesn't re-hit the backend.
 *
 * Hard-fails per the SPA's mock-removal policy: on error
 * `errorByKey.get(key)` carries a user-facing message and `byKey` is
 * left untouched (callers detect "no data yet" by `!byKey.has(key)`).
 * The panel surfaces a retry button that calls `load(label, eye, true)`
 * to bypass the cache.
 *
 * Reactivity note: Pinia's `ref<Map<...>>` doesn't auto-track set/delete
 * mutations against the original Map instance. Every write replaces the
 * underlying Map with a shallow copy so components see the change —
 * same convention as the datasets store's `filesByDataset` cache.
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { ModalityBaseline } from '@/types/baselines'

export type EyeScope = 'OD' | 'OS'

/** `${label}|${eye}` — keep this colocated with the store so callers don't open-code the format. */
export function baselinesKey(label: string, eye: EyeScope): string {
  return `${label}|${eye}`
}

export const useModalityBaselinesStore = defineStore('modalityBaselines', () => {
  const byKey = ref<Map<string, ModalityBaseline[]>>(new Map())
  const isLoadingByKey = ref<Map<string, boolean>>(new Map())
  const errorByKey = ref<Map<string, string | null>>(new Map())

  function setLoading(key: string, value: boolean) {
    const next = new Map(isLoadingByKey.value)
    if (value) next.set(key, true)
    else next.delete(key)
    isLoadingByKey.value = next
  }

  function setError(key: string, message: string | null) {
    const next = new Map(errorByKey.value)
    if (message === null) next.delete(key)
    else next.set(key, message)
    errorByKey.value = next
  }

  function setData(key: string, data: ModalityBaseline[]) {
    const next = new Map(byKey.value)
    next.set(key, data)
    byKey.value = next
  }

  /**
   * Fetch + cache the baselines for one (label, eye) pair. No-op when
   * the pair is already cached unless `force` is set (retry button).
   *
   * Auth errors (401/403) propagate so the router-level guard kicks in;
   * other failures are surfaced via `errorByKey` and the cache stays
   * empty.
   */
  async function load(label: string, eye: EyeScope, force = false): Promise<void> {
    const key = baselinesKey(label, eye)
    if (!force && byKey.value.has(key)) return
    setLoading(key, true)
    setError(key, null)
    try {
      const data = await apiGet<ModalityBaseline[]>(
        `/pages/api/v1/subjects/${encodeURIComponent(label)}/eyes/${eye}/modality-baselines`,
      )
      setData(key, data)
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        setError(key, 'Backend nicht erreichbar — bitte später erneut versuchen.')
        return
      }
      if (e instanceof ApiError) {
        setError(key, e.message)
        return
      }
      setError(key, e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Baselines.')
    } finally {
      setLoading(key, false)
    }
  }

  /**
   * Phase E.6 — clear every cached baseline. Called by
   * {@link useAuthStore.pickStudy} when the operator switches studies
   * so a stale per-study baseline doesn't bleed across the switch.
   */
  function reset() {
    byKey.value = new Map()
    isLoadingByKey.value = new Map()
    errorByKey.value = new Map()
  }

  return {
    // state
    byKey,
    isLoadingByKey,
    errorByKey,
    // actions
    load,
    reset,
  }
})
