import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { StudyBuildStatus } from '@/types/study'

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

  return {
    status,
    isLoading,
    error,
    completedTasks,
    totalTasks,
    percentComplete,
    load,
  }
})
