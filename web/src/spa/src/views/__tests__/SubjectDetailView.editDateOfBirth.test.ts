/**
 * The subject edit form corrects the date of birth as the study collects
 * it (collectDob): the full date where the study records it, the year
 * where it records the year only. Before, the form offered the year alone,
 * so a full date of birth could not be corrected at all.
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
    isNotFound = false
    constructor(public status = 0, msg = '', public body: unknown = null) {
      super(msg)
      if (status === 401) this.isUnauthorized = true
      if (status === 403) this.isForbidden = true
      if (status === 404) this.isNotFound = true
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
import { apiGet, apiPut } from '@/api/client'
// eslint-disable-next-line import/first
import SubjectDetailView from '@/views/SubjectDetailView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import type { SubjectDetail } from '@/types/subject'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPutMock = apiPut as unknown as ReturnType<typeof vi.fn>

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
      { path: '/subjects/:subjectId/sign', name: 'sign-subject', component: { template: '<div />' } },
      { path: '/events/:eventId', name: 'event-detail', component: { template: '<div />' } },
    ],
  })
}

function makeDetail(): SubjectDetail {
  return {
    id: 'M-001',
    secondaryId: null,
    siteOid: 'S_DEFAULTS1',
    siteLabel: 'Vienna',
    studyOid: 'S_DEFAULTS1',
    studyName: 'iAMD',
    gender: 'F',
    yearOfBirth: 1962,
    dateOfBirth: '1962-04-17',
    groupLabel: null,
    enrolledOn: '2026-05-01',
    signed: false,
    locked: false,
    openQueries: 0,
    events: [],
    studyEye: null,
    screeningDate: null,
    status: 'available',
    groupAssignments: [],
    eyeTransitions: [],
  } as SubjectDetail
}

async function mountWithCollectDob(collectDob: string) {
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
    activeStudy: { id: 1, oid: 'S_DEFAULTS1', name: 'iAMD', isSite: false },
  } as unknown as ReturnType<typeof useAuthStore>['user']['value']

  const router = makeRouter()
  router.push('/subjects/M-001')
  await router.isReady()

  apiGetMock.mockReset()
  apiGetMock.mockImplementation(async (path: string) => {
    if (path.includes('/parameters')) {
      return { studyOid: 'S_DEFAULTS1', collectDob, subjectPersonIdRequired: 'required', genderRequired: 'true' }
    }
    if (path.includes('/subjects/')) return makeDetail()
    return []
  })

  const wrapper = mount(SubjectDetailView, { global: { plugins: [router, i18n] } })
  await flushPromises()
  await wrapper.find('button.text-muw-blue.underline').trigger('click')
  await flushPromises()
  return wrapper
}

describe('SubjectDetailView — edit form date of birth', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiPutMock.mockReset()
  })

  it('corrects the full date of birth where the study collects it', async () => {
    const w = await mountWithCollectDob('1')

    const dob = w.find<HTMLInputElement>('#edit-dob')
    expect(dob.exists()).toBe(true)
    expect(dob.element.value).toBe('17/04/1962')
    expect(w.find('#edit-yob').exists()).toBe(false)

    await dob.setValue('18/04/1962')
    apiPutMock.mockResolvedValueOnce(makeDetail())
    await w.find('form').trigger('submit.prevent')
    await flushPromises()

    const [, body] = apiPutMock.mock.calls[0]
    expect(body).toMatchObject({ dateOfBirth: '1962-04-18', yearOfBirth: null })
  })

  it('edits the year where the study collects the year only', async () => {
    const w = await mountWithCollectDob('2')

    expect(w.find('#edit-dob').exists()).toBe(false)
    const yob = w.find<HTMLInputElement>('#edit-yob')
    expect(yob.exists()).toBe(true)

    await yob.setValue('1963')
    apiPutMock.mockResolvedValueOnce(makeDetail())
    await w.find('form').trigger('submit.prevent')
    await flushPromises()

    const [, body] = apiPutMock.mock.calls[0]
    expect(body).toMatchObject({ yearOfBirth: 1963, dateOfBirth: null })
  })
})
