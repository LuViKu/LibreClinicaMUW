import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn().mockResolvedValue({}),
  apiPut: vi.fn().mockResolvedValue({}),
  apiPatch: vi.fn().mockResolvedValue({}),
  apiDelete: vi.fn().mockResolvedValue({}),
  ApiError: class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
  },
  ApiNetworkError: class ApiNetworkError extends Error {},
}))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => () => Promise.resolve(true) }))

import { apiGet } from '@/api/client'
import SitesView from '@/views/SitesView.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'
import type { AuthenticatedUser } from '@/types/auth'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

/**
 * The backend lets a director or coordinator create and edit sites
 * (userMayEditStudy) but disable and restore only a system administrator
 * (roleMayLifecycleStudy), so only the latter sees those two buttons.
 */
async function mountAs(role: AuthenticatedUser['role']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    username: 'u', displayName: 'U', email: null, role, siteLabel: null, source: 'local',
    mfaSatisfied: true, profileComplete: true, locale: null, timezone: null,
    activeStudy: { id: 1, oid: 'S_1', name: 'Study', isSite: false, role, roles: [role] },
  } as unknown as AuthenticatedUser
  ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
    { oid: 'SITE_1', name: 'Site One', status: 'available', briefSummary: '', principalInvestigator: 'Dr X' },
  ])
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  const w = mount(SitesView, { global: { plugins: [pinia, router, i18n] } })
  await flushPromises()
  return w
}

describe('SitesView write controls by role', () => {
  beforeEach(() => vi.clearAllMocks())

  it.each(['CRC', 'Data Manager', 'Administrator'] as const)('%s sees the create button', async (role) => {
    const w = await mountAs(role)
    expect(w.text()).toContain(enMessages.sites.createAction)
  })

  it('shows disable only to the administrator', async () => {
    for (const role of ['CRC', 'Data Manager'] as const) {
      const w = await mountAs(role)
      expect(w.text(), role).toContain('Site One')
      expect(w.text(), role).not.toContain(enMessages.sites.disable)
    }
    const admin = await mountAs('Administrator')
    expect(admin.text()).toContain(enMessages.sites.disable)
  })
})
