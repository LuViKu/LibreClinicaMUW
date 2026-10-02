/**
 * StudyPickerView — which studies it offers.
 *
 * A system administrator may open any study that is not removed, so the
 * picker lists every such study and site from the admin study list; an
 * administrator without study roles would otherwise find nothing to
 * pick. Everyone else picks among their own bindings.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { apiGet, apiPost } from '@/api/client'
import StudyPickerView from '@/views/StudyPickerView.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const OWN_BINDING = {
  oid: 'S_OWN', name: 'Own Study', uniqueIdentifier: 'own', parentOid: null, parentName: null,
  role: 'Investigator', isSite: false, isActive: false,
}

const ADMIN_STUDIES = [
  { oid: 'S_A', name: 'Alpha', uniqueIdentifier: 'a', principalInvestigator: 'PI', createdDate: null,
    status: 'AVAILABLE', parentOid: null, sites: [
      { oid: 'S_A_1', name: 'Alpha site', uniqueIdentifier: 'a1', principalInvestigator: 'PI', createdDate: null,
        status: 'AVAILABLE', parentOid: 'S_A', sites: [] },
    ] },
  { oid: 'S_GONE', name: 'Gone', uniqueIdentifier: 'g', principalInvestigator: 'PI', createdDate: null,
    status: 'REMOVED', parentOid: null, sites: [] },
]

async function mountAs(userType: 'USER' | 'SYSADMIN') {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = { username: 'u', displayName: 'U', role: 'Administrator', userType, activeStudy: null } as unknown as
    ReturnType<typeof useAuthStore>['user']
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/pick-study', name: 'pick-study', component: StudyPickerView },
    ],
  })
  await router.push('/pick-study')
  await router.isReady()
  const w = mount(StudyPickerView, { global: { plugins: [router, i18n] } })
  await flushPromises()
  return w
}

/** The OIDs the picker lists, read from each row. */
function offered(w: Awaited<ReturnType<typeof mountAs>>): string[] {
  return w.findAll('li span.font-mono').map((span) => span.text())
}

describe('StudyPickerView', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiGet).mockImplementation(async (path: string) => {
      if (path === '/pages/api/v1/studies') return [OWN_BINDING]
      if (path === '/pages/api/v1/admin/studies') return ADMIN_STUDIES
      throw new Error(`unexpected GET ${path}`)
    })
  })

  it('offers a system administrator every study and site that is not removed', async () => {
    const w = await mountAs('SYSADMIN')
    expect(offered(w)).toEqual(['S_A', 'S_A_1'])
    expect(w.text()).toContain('Alpha site')
    expect(w.text()).not.toContain('Gone')
  })

  it('opens the picked study through the active-study endpoint', async () => {
    vi.mocked(apiPost).mockResolvedValue({ username: 'u', role: 'Administrator', profileComplete: true,
      activeStudy: { id: 5, oid: 'S_A_1', name: 'Alpha site' } })
    const w = await mountAs('SYSADMIN')
    const row = w.findAll('li').find((li) => li.text().includes('S_A_1'))
    expect(row, 'the site is offered').toBeDefined()
    await row!.get('button').trigger('click')
    await flushPromises()
    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/me/activeStudy', { oid: 'S_A_1' })
  })

  it('offers anyone else their own bindings only', async () => {
    const w = await mountAs('USER')
    expect(offered(w)).toEqual(['S_OWN'])
    expect(apiGet).not.toHaveBeenCalledWith('/pages/api/v1/admin/studies')
  })
})
