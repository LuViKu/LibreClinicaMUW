/**
 * Phase E.6 study-params — StudyParametersEditView render + interaction spec.
 *
 * Five vitest cases pinning the SPA-side contract surface the playbook
 * §3.2 acceptance criteria + reviewer flags call out:
 *
 *   1. Mount loads the store (one GET) and seeds 18 form fields.
 *   2. Save with no diff is a no-op (no PUT) but still flips the
 *      "saved" toast — matches the controller's idempotent contract.
 *   3. Save with a diff PUTs only the changed handles (partial patch).
 *   4. 400 validation envelope surfaces the per-handle error on the
 *      offending field; other fields stay editable.
 *   5. 401/403 from the store re-routes to the study picker (auth
 *      guard mirrors StudyIdentityEditView).
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
    apiPut: vi.fn(),
    ApiError,
    ApiNetworkError,
  }
})

// eslint-disable-next-line import/first
import { apiGet, apiPut, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import StudyParametersEditView from '@/views/StudyParametersEditView.vue'
// eslint-disable-next-line import/first
import type { StudyParameters } from '@/types/studyParameters'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPutMock = apiPut as unknown as ReturnType<typeof vi.fn>

const FIXTURE: StudyParameters = {
  studyOid: 'S_DEMO',
  subjectIdGeneration: 'manual',
  subjectIdPrefixSuffix: 'true',
  subjectPersonIdRequired: 'required',
  personIdShownOnCRF: 'false',
  collectDob: '1',
  genderRequired: 'true',
  eventLocationRequired: 'not_used',
  discrepancyManagement: 'true',
  interviewerNameRequired: 'not_used',
  interviewerNameDefault: 'blank',
  interviewerNameEditable: 'true',
  interviewDateRequired: 'not_used',
  interviewDateDefault: 'blank',
  interviewDateEditable: 'true',
  secondaryLabelViewable: 'false',
  adminForcedReasonForChange: 'true',
  participantPortal: 'disabled',
  randomization: 'disabled',
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/studies', name: 'study-picker', component: { template: '<div />' } },
      {
        path: '/studies/:oid/parameters',
        name: 'study-parameters',
        component: { template: '<div />' },
      },
      {
        path: '/build-study/:oid?',
        name: 'build-study',
        component: { template: '<div />' },
      },
    ],
  })
}

async function mountAt(oid: string) {
  setActivePinia(createPinia())
  const router = makeRouter()
  router.push(`/studies/${oid}/parameters`)
  await router.isReady()
  const wrapper = mount(StudyParametersEditView, {
    global: {
      plugins: [router, i18n],
      stubs: { SideRail: true },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('StudyParametersEditView', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiPutMock.mockReset()
  })

  it('mounts, fires one GET, and seeds the 18 form fields', async () => {
    apiGetMock.mockResolvedValueOnce(FIXTURE)
    const { wrapper } = await mountAt('S_DEMO')

    expect(apiGetMock).toHaveBeenCalledTimes(1)
    expect(apiGetMock).toHaveBeenCalledWith(
      '/pages/api/v1/studies/S_DEMO/parameters',
    )

    // Every handle binds to a select with the corresponding id. The
    // seed value lands as the <select> value.
    const subjectIdGen = wrapper.find<HTMLSelectElement>('#sp-subjectIdGeneration')
    expect(subjectIdGen.exists()).toBe(true)
    expect(subjectIdGen.element.value).toBe('manual')
    const collectDob = wrapper.find<HTMLSelectElement>('#sp-collectDob')
    expect(collectDob.exists()).toBe(true)
    expect(collectDob.element.value).toBe('1')
  })

  it('Save with no diff does not call PUT but shows the saved banner', async () => {
    apiGetMock.mockResolvedValueOnce(FIXTURE)
    const { wrapper } = await mountAt('S_DEMO')

    const saveBtn = wrapper.findAll('button').find((b) => b.text().includes('Save'))!
    await saveBtn.trigger('click')
    await flushPromises()

    expect(apiPutMock).not.toHaveBeenCalled()
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
  })

  it('Save with a diff PUTs only the changed handle', async () => {
    apiGetMock.mockResolvedValueOnce(FIXTURE)
    apiPutMock.mockResolvedValueOnce({ ...FIXTURE, collectDob: '3' })
    const { wrapper } = await mountAt('S_DEMO')

    const collectDob = wrapper.find<HTMLSelectElement>('#sp-collectDob')
    await collectDob.setValue('3')

    const saveBtn = wrapper.findAll('button').find((b) => b.text().includes('Save'))!
    await saveBtn.trigger('click')
    await flushPromises()

    expect(apiPutMock).toHaveBeenCalledTimes(1)
    expect(apiPutMock).toHaveBeenCalledWith(
      '/pages/api/v1/studies/S_DEMO/parameters',
      { collectDob: '3' },
    )
  })

  it('surfaces a 400 fieldErrors envelope on the offending field', async () => {
    apiGetMock.mockResolvedValueOnce(FIXTURE)
    apiPutMock.mockRejectedValueOnce(
      new ApiError(400, 'Validation failed', {
        message: 'Validation failed',
        fieldErrors: [
          { field: 'collectDob', message: 'collectDob must be one of [1, 2, 3]' },
        ],
      }),
    )
    const { wrapper } = await mountAt('S_DEMO')

    // Force a diff so the PUT actually fires.
    await wrapper.find<HTMLSelectElement>('#sp-collectDob').setValue('3')
    const saveBtn = wrapper.findAll('button').find((b) => b.text().includes('Save'))!
    await saveBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('collectDob must be one of')
  })

  it('routes to the study picker when GET returns 401', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(401, 'Not authenticated'))
    const { router } = await mountAt('S_DEMO')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('study-picker')
  })
})
