import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { RuleSet } from '@/types/rule'

/**
 * Phase E RX.1 — rules store (read-only).
 *
 * Hydrates from `GET /api/v1/rule-sets`. The store keeps the full
 * rule_set list in memory; if the dataset grows past a few hundred
 * rows we'll need a paged surface — for now the typical study
 * carries dozens.
 */
export const useRulesStore = defineStore('rules', () => {
  const rows = ref<RuleSet[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const selected = ref<RuleSet | null>(null)
  const isLoadingSelected = ref(false)
  const selectedError = ref<string | null>(null)

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<RuleSet[]>('/pages/api/v1/rule-sets')
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      error.value = humanError(e, 'load')
    } finally {
      isLoading.value = false
    }
  }

  async function fetchOne(id: number): Promise<RuleSet | null> {
    isLoadingSelected.value = true
    selectedError.value = null
    try {
      const detail = await apiGet<RuleSet>(`/pages/api/v1/rule-sets/${id}`)
      selected.value = detail
      return detail
    } catch (e) {
      selected.value = null
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      selectedError.value = humanError(e, 'load')
      return null
    } finally {
      isLoadingSelected.value = false
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

  return {
    rows,
    isLoading,
    error,
    selected,
    isLoadingSelected,
    selectedError,
    load,
    fetchOne,
  }
})
