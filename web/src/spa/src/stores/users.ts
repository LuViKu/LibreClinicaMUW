import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { StudyUser, UserAuth, UserRole } from '@/types/user'

/**
 * Phase E.7 — Study-users store.
 *
 * Mock-hydrated; the planned adapter sits at
 * `GET /pages/api/v1/users?siteOid=…&role=…` per api-surface.md row 12.
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
      rows.value = await loadMock()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error loading users'
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

async function loadMock(): Promise<StudyUser[]> {
  await new Promise((resolve) => setTimeout(resolve, 30))
  return MOCK
}

const MOCK: StudyUser[] = [
  { id: 'u-1', username: 'm.mueller',    displayName: 'Dr. Maria Müller',     email: 'm.mueller@meduniwien.ac.at', role: 'Investigator',  siteLabel: 'München', auth: 'sso',     lastLoginAt: '2026-05-30T08:14:00Z', active: true },
  { id: 'u-2', username: 'k.huber',      displayName: 'Dr. Karl Huber',       email: 'k.huber@meduniwien.ac.at',   role: 'Investigator',  siteLabel: 'Wien',    auth: 'sso',     lastLoginAt: '2026-05-29T16:05:00Z', active: true },
  { id: 'u-3', username: 'monitor_demo', displayName: 'Mona Demo',            email: 'mona@example.org',           role: 'Monitor',       siteLabel: null,      auth: 'local',   lastLoginAt: '2026-05-30T09:08:00Z', active: true },
  { id: 'u-4', username: 'dm_demo',      displayName: 'Dora Manager',         email: 'dora@meduniwien.ac.at',      role: 'Data Manager',  siteLabel: null,      auth: 'sso',     lastLoginAt: '2026-05-30T11:00:00Z', active: true },
  { id: 'u-5', username: 'crc_muenchen', displayName: 'Lisa Koordinator',     email: 'l.koordinator@example.org',  role: 'CRC',           siteLabel: 'München', auth: 'local',   lastLoginAt: '2026-05-25T13:45:00Z', active: true },
  { id: 'u-6', username: 's.legacy',     displayName: 'Sieglinde Legacy',     email: 's.legacy@meduniwien.ac.at',  role: 'Investigator',  siteLabel: 'Wien',    auth: 'ldap',    lastLoginAt: '2025-12-12T08:30:00Z', active: false },
  { id: 'u-7', username: 'pending.fritz', displayName: 'Fritz Berger (pending)', email: 'f.berger@meduniwien.ac.at', role: 'Investigator',  siteLabel: 'München', auth: 'pending-invite', lastLoginAt: null, active: true },
]
