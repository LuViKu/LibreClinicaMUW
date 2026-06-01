import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import type { StudyUser, UserAuth, UserRole } from '@/types/user'

/**
 * Phase E.7 + E.4 M12 — Study-users store.
 *
 * Hydrates from `GET /pages/api/v1/users` (the M12 adapter). Filter
 * state mirrors the legacy "Manage Users" controls; the client-side
 * filters layer on top of whatever the server returns so dropdown
 * changes don't trigger a round-trip per keypress.
 *
 * Mock removal — per the polished-jumping-swan plan's hard-removal
 * policy: the previous `loadMock()` helper + 7-row MOCK fixture
 * are deleted in this PR. If the backend is unreachable the store
 * sets `error` so the view can render an explicit message rather
 * than silently displaying stale demo data.
 */
export const useUsersStore = defineStore('users', () => {
  const rows = ref<StudyUser[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const query = ref('')
  const roleFilter = ref<'all' | UserRole>('all')
  const authFilter = ref<'all' | UserAuth>('all')
  const onlyActive = ref(true)

  const filtered = computed<StudyUser[]>(() => {
    const q = query.value.trim().toLowerCase()
    return rows.value.filter((u) => {
      if (q) {
        const blob = `${u.username} ${u.displayName} ${u.email ?? ''}`.toLowerCase()
        if (!blob.includes(q)) return false
      }
      if (roleFilter.value !== 'all' && u.role !== roleFilter.value) return false
      if (authFilter.value !== 'all' && u.auth !== authFilter.value) return false
      if (onlyActive.value && !u.active) return false
      return true
    })
  })

  const totalCount = computed(() => rows.value.length)
  const visibleCount = computed(() => filtered.value.length)
  const pendingInviteCount = computed(() => rows.value.filter((u) => u.auth === 'pending-invite').length)

  function clearFilters() {
    query.value = ''
    roleFilter.value = 'all'
    authFilter.value = 'all'
    onlyActive.value = true
  }

  async function load(_siteOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      rows.value = await apiGet<StudyUser[]>('/pages/api/v1/users')
    } catch (e) {
      rows.value = []
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
        throw e
      }
      if (e instanceof ApiNetworkError) {
        error.value =
          'Backend nicht erreichbar — Nutzer können nicht geladen werden. Bitte später erneut versuchen.'
      } else if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        error.value = body?.message ?? `Fehler beim Laden der Nutzer (HTTP ${e.status}).`
      } else {
        error.value = e instanceof Error ? e.message : 'Unbekannter Fehler beim Laden der Nutzer.'
      }
    } finally {
      isLoading.value = false
    }
  }

  return {
    rows,
    isLoading,
    error,
    query,
    roleFilter,
    authFilter,
    onlyActive,
    filtered,
    totalCount,
    visibleCount,
    pendingInviteCount,
    clearFilters,
    load,
  }
})
