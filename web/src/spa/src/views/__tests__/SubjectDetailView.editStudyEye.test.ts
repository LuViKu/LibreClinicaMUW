/**
 * 2026-06-10 — Subject demographics edit form: study-eye field.
 *
 * Pins the bug-fix: previously the SubjectDetailView edit form omitted
 * the Studienauge select, so a subject created without one (NULL) had
 * no in-product correction path. The new behaviour:
 *
 *   1. Opening the edit form pre-fills the select from the current
 *      subject.studyEye; submitting passes the (possibly new) value to
 *      the subjects store's `updateSubject` action.
 *   2. Cancelling the edit reverts an in-progress change — the read-
 *      only display still reflects the pre-edit value.
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
import { useSubjectsStore } from '@/stores/subjects'
// eslint-disable-next-line import/first
import type { SubjectDetail, StudyEye } from '@/types/subject'
// eslint-disable-next-line import/first
import type { UserRole } from '@/types/auth'
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
      {
        path: '/subjects/:subjectId',
        name: 'subject-detail',
        component: { template: '<div />' },
      },
      {
        path: '/subjects/:subjectId/sign',
        name: 'sign-subject',
        component: { template: '<div />' },
      },
      { path: '/events/:eventId', name: 'event-detail', component: { template: '<div />' } },
    ],
  })
}

function makeDetail(overrides: Partial<SubjectDetail> = {}): SubjectDetail {
  return {
    id: 'M-001',
    secondaryId: null,
    siteOid: 'S_DEFAULTS1',
    siteLabel: 'Vienna',
    studyOid: 'S_DEFAULTS1',
    studyName: 'iAMD',
    gender: 'F',
    yearOfBirth: 1962,
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
    ...overrides,
  } as SubjectDetail
}

interface MountOptions {
  role?: UserRole | null
  detail?: SubjectDetail
}

async function mountAt(options: MountOptions = {}) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    username: 'demo',
    displayName: 'Demo',
    email: null,
    role: (options.role ?? 'Investigator') as UserRole,
    siteLabel: null,
    source: 'local',
    mfaSatisfied: true,
    profileComplete: true,
    mustChangePassword: false,
    passwordChangeReason: null,
    locale: null,
    timezone: null,
    activeStudy: {
      id: 1,
      oid: 'S_DEFAULTS1',
      name: 'iAMD',
      isSite: false,
    },
  } as unknown as ReturnType<typeof useAuthStore>['user']['value']
  auth.availableStudies = [
    { oid: 'S_DEFAULTS1', name: 'iAMD' },
    { oid: 'S_GA001', name: 'GA' },
  ] as unknown as typeof auth.availableStudies

  const router = makeRouter()
  router.push('/subjects/M-001')
  await router.isReady()

  const detail = options.detail ?? makeDetail()
  apiGetMock.mockReset()
  // Tail-fallback for the ModalityBaselinesPanel's own apiGet calls.
  apiGetMock.mockResolvedValue([])
  apiGetMock.mockResolvedValueOnce(detail)

  const wrapper = mount(SubjectDetailView, {
    global: { plugins: [router, i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('SubjectDetailView — edit form study-eye field', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiPutMock.mockReset()
  })

  it('pre-fills the study-eye select from the subject and forwards the new value to updateSubject on submit', async () => {
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({ studyEye: 'OD' as StudyEye }),
    })

    // Open the edit form.
    await w.find('button.text-muw-blue.underline').trigger('click')
    await flushPromises()

    const select = w.find<HTMLSelectElement>('[data-testid="edit-study-eye"]')
    expect(select.exists()).toBe(true)
    // Pre-fill assertion: select reflects the current subject value.
    expect(select.element.value).toBe('OD')

    // Operator switches to OU and submits.
    await select.setValue('OU')

    // updateSubject calls apiPut under the hood; resolve with a fresh
    // detail so the form closes cleanly.
    apiPutMock.mockResolvedValueOnce(makeDetail({ studyEye: 'OU' as StudyEye }))

    await w.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(apiPutMock).toHaveBeenCalledTimes(1)
    const [url, body] = apiPutMock.mock.calls[0]
    // The store hits PUT /pages/api/v1/subjects/{label} — updateSubject
    // passes the user-facing label through directly (unlike fetchOne,
    // which normalises to the SS_ OID convention).
    expect(String(url)).toContain('/pages/api/v1/subjects/M-001')
    expect(body).toMatchObject({ studyEye: 'OU' })

    // The store mirror also reflects the new value.
    const subjects = useSubjectsStore()
    expect(subjects.selected?.studyEye).toBe('OU')
  })

  it('cancelling the edit reverts an in-progress study-eye change', async () => {
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({ studyEye: 'OD' as StudyEye }),
    })

    await w.find('button.text-muw-blue.underline').trigger('click')
    await flushPromises()

    const select = w.find<HTMLSelectElement>('[data-testid="edit-study-eye"]')
    // Typed change to OU, then bail.
    await select.setValue('OU')
    expect(select.element.value).toBe('OU')

    // Click the cancel button. The form has a "Cancel" + a "Save"
    // button; the cancel is the first button (type="button") inside
    // the trailing action row. Locate via text content.
    const cancelBtn = w.findAll('button').find(
      (b) => b.text() === w.vm.$t('common.cancel'),
    )
    expect(cancelBtn).toBeDefined()
    await cancelBtn!.trigger('click')
    await flushPromises()

    // Form closed — select gone.
    expect(w.find('[data-testid="edit-study-eye"]').exists()).toBe(false)

    // No backend call made.
    expect(apiPutMock).not.toHaveBeenCalled()

    // Read-only mode shows the pre-edit OD value (the OD pill renders
    // for in-scope eyes). Re-opening the form should also re-pre-fill
    // to OD (NOT the abandoned OU).
    await w.find('button.text-muw-blue.underline').trigger('click')
    await flushPromises()
    const reopened = w.find<HTMLSelectElement>('[data-testid="edit-study-eye"]')
    expect(reopened.element.value).toBe('OD')
  })
})
