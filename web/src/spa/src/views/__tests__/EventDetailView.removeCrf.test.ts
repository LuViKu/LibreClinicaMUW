/**
 * The visit page offers Remove on a started CRF to the roles legacy lets
 * remove one (Administrator, Data Manager, CRC), not on a signed or locked
 * visit, and opens the removal dialog for the row.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    isUnauthorized = false
    constructor(public status = 0, msg = '', public body: unknown = null) {
      super(msg)
      if (status === 401) this.isUnauthorized = true
    }
  }
  class ApiNetworkError extends Error {}
  return { apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn(), ApiError, ApiNetworkError }
})
vi.mock('@/api/ingest', () => ({
  listIngestByEvent: vi.fn().mockResolvedValue({ items: [], pendingForSubject: 0, plan: [] }),
  unbindIngestItem: vi.fn(),
}))

// eslint-disable-next-line import/first
import { apiGet } from '@/api/client'
// eslint-disable-next-line import/first
import EventDetailView from '@/views/EventDetailView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import type { EventDetailDto, StudyEventStatus } from '@/types/event'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const ROW = { crfVersionName: 'v1.0', required: false, passwordRequired: false }
const EVENT: EventDetailDto = {
  eventId: 42,
  eventDefinitionOid: 'SE_V1',
  eventDefinitionName: 'Visit 1',
  subjectLabel: 'M-001',
  subjectOid: 'SS_M001',
  studyOid: 'S_DEFAULTS1',
  studyName: 'Default Study',
  dateStart: '2026-06-01',
  status: 'data-entry-started',
  ordinal: 1,
  repeating: false,
  crfs: [
    { ...ROW, eventCrfId: 7, eventCrfOid: '7', crfName: 'Demographics', crfVersionOid: 'F_DEMO_V1',
      eventDefinitionCrfId: 100, status: 'data-entry-started' },
    { ...ROW, eventCrfId: null, eventCrfOid: null, crfName: 'Vitals', crfVersionOid: 'F_VITALS_V1',
      eventDefinitionCrfId: 101, status: 'not-started' },
    { ...ROW, eventCrfId: 8, eventCrfOid: '8', crfName: 'Labs', crfVersionOid: 'F_LABS_V1',
      eventDefinitionCrfId: 102, status: 'removed' },
  ],
}

async function mountAs(role: string, status: StudyEventStatus = 'data-entry-started') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: 1, username: 'u', name: 'U', role } as unknown as typeof auth.user
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/events/:eventId', name: 'event-detail', component: { template: '<div />' } },
      { path: '/:rest(.*)*', component: { template: '<div />' } },
    ],
  })
  router.push('/events/42')
  await router.isReady()
  apiGetMock.mockResolvedValueOnce({ ...EVENT, status })
  const wrapper = mount(EventDetailView, {
    global: {
      plugins: [pinia, router, i18n],
      stubs: { RemoveEventCrfDialog: true, RetinalResultsTab: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('EventDetailView — remove a CRF', () => {
  beforeEach(() => apiGetMock.mockReset())

  it.each(['Administrator', 'Data Manager', 'CRC'])('offers Remove on the started CRF to %s', async (role) => {
    const wrapper = await mountAs(role)
    const rows = wrapper.findAll('[data-test="event-detail-crf-row"]')
    expect(rows[0].find('[data-test="event-detail-remove-crf"]').exists()).toBe(true)
    expect(rows[1].find('[data-test="event-detail-remove-crf"]').exists()).toBe(false)
    expect(rows[2].find('[data-test="event-detail-remove-crf"]').exists()).toBe(false)
    expect(rows[2].find('[data-test="event-detail-restore-crf"]').exists()).toBe(true)
  })

  it.each(['Investigator', 'Monitor'])('does not offer Remove to %s', async (role) => {
    const wrapper = await mountAs(role)
    expect(wrapper.find('[data-test="event-detail-remove-crf"]').exists()).toBe(false)
  })

  it.each<StudyEventStatus>(['signed', 'locked'])('does not offer Remove on a %s visit', async (status) => {
    const wrapper = await mountAs('Data Manager', status)
    expect(wrapper.find('[data-test="event-detail-remove-crf"]').exists()).toBe(false)
  })

  it('opens the removal dialog for the row', async () => {
    const wrapper = await mountAs('Data Manager')
    await wrapper.find('[data-test="event-detail-remove-crf"]').trigger('click')

    const dialog = wrapper.findComponent({ name: 'RemoveEventCrfDialog' })
    expect(dialog.exists()).toBe(true)
    expect(dialog.props('crf')).toMatchObject({ eventCrfId: 7, crfName: 'Demographics' })
    expect(dialog.props('subjectLabel')).toBe('M-001')
    expect(dialog.props('eventLabel')).toBe('Visit 1')
    expect(dialog.props('open')).toBe(true)
  })

  it('closes the dialog and reloads the visit once the CRF is removed', async () => {
    const wrapper = await mountAs('Data Manager')
    await wrapper.find('[data-test="event-detail-remove-crf"]').trigger('click')
    apiGetMock.mockResolvedValueOnce({
      ...EVENT,
      crfs: EVENT.crfs.map((c) => (c.eventCrfId === 7 ? { ...c, status: 'removed' as const } : c)),
    })

    const callsBefore = apiGetMock.mock.calls.length
    wrapper.findComponent({ name: 'RemoveEventCrfDialog' }).vm.$emit('removed')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'RemoveEventCrfDialog' }).exists()).toBe(false)
    // The reload reads the visit first; the visit's field notes may follow.
    expect(apiGetMock.mock.calls[callsBefore]?.[0]).toBe('/pages/api/v1/events/42')
    const rows = wrapper.findAll('[data-test="event-detail-crf-row"]')
    expect(rows[0].find('[data-test="event-detail-restore-crf"]').exists()).toBe(true)
  })
})
