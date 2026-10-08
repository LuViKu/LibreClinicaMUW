/**
 * Add Subject collects the identifiers the study's parameters ask for, as
 * the legacy registration form does: the Person ID (required, optional or
 * not used), and the full date of birth, the year of birth, or neither
 * (collectDob). Before, the form collected neither, so a study that
 * required them could not register a subject through the SPA.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
    constructor(public status = 0, msg = '', public body: unknown = null) {
      super(msg)
      if (status === 401) this.isUnauthorized = true
      if (status === 403) this.isForbidden = true
    }
  }
  class ApiNetworkError extends Error {
    constructor(message: string, public cause: unknown = null) {
      super(message)
    }
  }
  return {
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
    ApiError,
    ApiNetworkError,
  }
})

// eslint-disable-next-line import/first
import { apiGet, apiPost } from '@/api/client'
// eslint-disable-next-line import/first
import AddSubjectView from '@/views/AddSubjectView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
  missingWarn: false,
  fallbackWarn: false,
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/subjects', name: 'subject-matrix', component: { template: '<div />' } },
      { path: '/subjects/new', name: 'subject-new', component: { template: '<div />' } },
      { path: '/subjects/:subjectId', name: 'subject-detail', component: { template: '<div />' } },
    ],
  })
}

async function mountWith(params: { collectDob: string; subjectPersonIdRequired: string }) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: 'Investigator',
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    mustChangePassword: false,
    passwordChangeReason: null,
    locale: null,
    timezone: null,
    activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'Default Study', isSite: false },
  } as unknown as ReturnType<typeof useAuthStore>['user']['value']

  apiGetMock.mockReset()
  apiGetMock.mockImplementation(async (path: string) => {
    if (path.includes('/parameters')) {
      return { studyOid: 'S_DEFAULTS1', genderRequired: 'true', randomization: 'disabled', ...params }
    }
    if (path.includes('/check-label')) return { available: true }
    return []
  })
  apiPostMock.mockReset()
  apiPostMock.mockResolvedValue({
    id: 'N-001', secondaryId: null, siteOid: 'S_DEFAULTS1', siteLabel: 'Default Study',
    gender: 'F', yearOfBirth: null, groupLabel: null, enrolledOn: '2026-01-01',
    signed: false, openQueries: 0, studyEye: null, status: 'available', groupAssignments: [], events: [],
  })

  const router = makeRouter()
  router.push('/subjects/new')
  await router.isReady()
  const wrapper = mount(AddSubjectView, { global: { plugins: [router, i18n] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function fillCommon(w: ReturnType<typeof mount>) {
  await w.find('#subject-id').setValue('N-001')
  await w.find('input[name="gender"][value="F"]').setValue(true)
}

describe('AddSubjectView — identifiers the study requires', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiPostMock.mockReset()
  })

  it('collects the Person ID and the full date of birth where the study requires them', async () => {
    const w = await mountWith({ collectDob: '1', subjectPersonIdRequired: 'required' })

    expect(w.find('#person-id').exists()).toBe(true)
    expect(w.find('#date-of-birth').exists()).toBe(true)
    expect(w.find('#year-of-birth').exists()).toBe(false)

    await fillCommon(w)
    await w.find('#person-id').setValue('P-4711')
    await w.find('#date-of-birth').setValue('17/04/1962')
    await w.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(apiPostMock).toHaveBeenCalledTimes(1)
    const [, body] = apiPostMock.mock.calls[0]
    expect(body).toMatchObject({ personId: 'P-4711', dateOfBirth: '1962-04-17', yearOfBirth: null })
    w.unmount()
  })

  it('does not send the form while a required identifier is missing', async () => {
    const w = await mountWith({ collectDob: '1', subjectPersonIdRequired: 'required' })

    await fillCommon(w)
    await w.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(apiPostMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('asks for the year only, and no Person ID, where the study says so', async () => {
    const w = await mountWith({ collectDob: '2', subjectPersonIdRequired: 'not_used' })

    expect(w.find('#person-id').exists()).toBe(false)
    expect(w.find('#date-of-birth').exists()).toBe(false)

    await fillCommon(w)
    await w.find('#year-of-birth').setValue('1980')
    await w.find('form').trigger('submit.prevent')
    await flushPromises()

    const [, body] = apiPostMock.mock.calls[0]
    expect(body).toMatchObject({ personId: null, dateOfBirth: null, yearOfBirth: 1980 })
    w.unmount()
  })
})
