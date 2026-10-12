/**
 * StudyPickerView -- a Standort-Admin who has no study to pick.
 *
 * A site's IT contact is the typical Standort-Admin and holds no study role,
 * so after login the picker has nothing to offer them and, until now, was a
 * dead end. The Uploaders page is the one place they have work, so the picker
 * sends them there instead, but only when it knows it has nothing to offer:
 * the list was read and is empty, and no study is bound. A failed read, a
 * list with studies, a bound study, or a session without the right keeps
 * the picker as it was.
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

import { apiGet, ApiError } from '@/api/client'
import StudyPickerView from '@/views/StudyPickerView.vue'
import { useAuthStore } from '@/stores/auth'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const MUW = { id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik' }

const OWN_BINDING = {
  oid: 'S_OWN', name: 'Own Study', uniqueIdentifier: 'own', parentOid: null, parentName: null,
  role: 'Investigator', isSite: false, isActive: false,
}

const ADMIN_STUDIES = [
  { oid: 'S_A', name: 'Alpha', uniqueIdentifier: 'a', principalInvestigator: 'PI', createdDate: null,
    status: 'AVAILABLE', parentOid: null, sites: [] },
]

interface Who {
  userType?: 'USER' | 'SYSADMIN'
  /** The locations /me lists as manageable; none for most people. */
  manageable?: Array<{ id: number; code: string; name: string }>
  /** A study the session is bound to; none right after a login of someone with no stored study. */
  activeStudy?: { oid: string; name: string } | null
}

async function mountAs(who: Who = {}) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    username: 'it.contact',
    displayName: 'IT Contact',
    // The sentinel role /me projects for a user who holds no study role at all.
    role: who.userType === 'SYSADMIN' ? 'Administrator' : 'Investigator',
    userType: who.userType ?? 'USER',
    manageableLocations: who.manageable ?? [],
    activeStudy: who.activeStudy ?? null,
  } as unknown as ReturnType<typeof useAuthStore>['user']
  auth.state = 'authenticated'
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/uploaders', name: 'uploaders', component: { template: '<div />' } },
      { path: '/pick-study', name: 'pick-study', component: StudyPickerView },
    ],
  })
  const replace = vi.spyOn(router, 'replace')
  const push = vi.spyOn(router, 'push')
  await router.push('/pick-study')
  await router.isReady()
  push.mockClear()
  const w = mount(StudyPickerView, { global: { plugins: [router, i18n] } })
  await flushPromises()
  return { w, router, replace, push }
}

function answer(over: { studies?: unknown[] | Error; adminStudies?: unknown[] | Error } = {}) {
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === '/pages/api/v1/studies') {
      if (over.studies instanceof Error) throw over.studies
      return over.studies ?? []
    }
    if (path === '/pages/api/v1/admin/studies') {
      if (over.adminStudies instanceof Error) throw over.adminStudies
      return over.adminStudies ?? ADMIN_STUDIES
    }
    throw new Error(`unexpected GET ${path}`)
  })
}

describe('StudyPickerView with nothing to pick', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
    answer()
  })

  it('sends a Standort-Admin who holds no study role to the Uploaders page', async () => {
    const { router, replace, push } = await mountAs({ manageable: [MUW] })
    expect(router.currentRoute.value.name).toBe('uploaders')
    // Replaced, not pushed: "back" must not return to a picker that has nothing in it.
    expect(replace).toHaveBeenCalledWith({ name: 'uploaders' })
    expect(push).not.toHaveBeenCalled()
  })

  it('does not flash the empty-picker message before it leaves', async () => {
    const { w } = await mountAs({ manageable: [MUW] })
    expect(w.text()).not.toContain(enMessages.studyPicker.empty)
  })

  it('leaves a user without the right on the picker, with its message, as before', async () => {
    const { w, router, replace } = await mountAs({ manageable: [] })
    expect(router.currentRoute.value.name).toBe('pick-study')
    expect(replace).not.toHaveBeenCalled()
    expect(w.text()).toContain(enMessages.studyPicker.empty)
  })

  it('leaves a Standort-Admin who has a study to pick on the picker, with the study listed', async () => {
    answer({ studies: [OWN_BINDING] })
    const { w, router, replace } = await mountAs({ manageable: [MUW] })
    expect(router.currentRoute.value.name).toBe('pick-study')
    expect(replace).not.toHaveBeenCalled()
    expect(w.findAll('li span.font-mono').map((s) => s.text())).toEqual(['S_OWN'])
  })

  it('does not leave when the list could not be read: an unknown list is not an empty one', async () => {
    answer({ studies: new ApiError(500, 'Database unavailable', null) })
    const { w, router, replace } = await mountAs({ manageable: [MUW] })
    expect(router.currentRoute.value.name).toBe('pick-study')
    expect(replace).not.toHaveBeenCalled()
    expect(w.text()).toContain('Database unavailable')
  })

  it('does not leave a session that already has a study bound', async () => {
    const { router, replace } = await mountAs({ manageable: [MUW], activeStudy: { oid: 'S_X', name: 'Study X' } })
    expect(router.currentRoute.value.name).toBe('pick-study')
    expect(replace).not.toHaveBeenCalled()
  })

  describe('for a system administrator, who can open any study', () => {
    it('stays when there are studies to open', async () => {
      const { w, router, replace } = await mountAs({ userType: 'SYSADMIN', manageable: [MUW] })
      expect(router.currentRoute.value.name).toBe('pick-study')
      expect(replace).not.toHaveBeenCalled()
      expect(w.findAll('li span.font-mono').map((s) => s.text())).toEqual(['S_A'])
    })

    it('leaves when there is no study on the platform at all', async () => {
      answer({ adminStudies: [] })
      const { router } = await mountAs({ userType: 'SYSADMIN', manageable: [MUW] })
      expect(router.currentRoute.value.name).toBe('uploaders')
    })

    it('stays when the list of studies could not be read', async () => {
      answer({ adminStudies: new ApiError(500, 'Server Error', null) })
      const { router, replace } = await mountAs({ userType: 'SYSADMIN', manageable: [MUW] })
      expect(router.currentRoute.value.name).toBe('pick-study')
      expect(replace).not.toHaveBeenCalled()
    })
  })
})
