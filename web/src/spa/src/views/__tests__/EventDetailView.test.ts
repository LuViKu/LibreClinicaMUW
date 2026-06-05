/**
 * Phase E.6 — EventDetailView render spec.
 *
 * Pins what the view shows in the four shapes the store can land in:
 *  - loaded with rows (Open + Start-legacy actions)
 *  - empty rows
 *  - 404 not-found
 *  - 403 forbidden
 *
 * Action-link targets matter — they're the whole point of this view
 * (Vue routes vs new-tab legacy URL).
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
      if (status === 403) this.isForbidden = true
      if (status === 401) this.isUnauthorized = true
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
import { apiGet, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import EventDetailView from '@/views/EventDetailView.vue'
// eslint-disable-next-line import/first
import type { EventDetailDto } from '@/types/event'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

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
      { path: '/subjects', name: 'subject-matrix', component: { template: '<div />' } },
      { path: '/subjects/:subjectId', name: 'subject-detail', component: { template: '<div />' } },
      { path: '/events/:eventId', name: 'event-detail', component: { template: '<div />' } },
      { path: '/event-crfs/:eventCrfOid', name: 'crf-entry', component: { template: '<div />' } },
    ],
  })
}

const TWO_ROWS: EventDetailDto = {
  eventId: 42,
  eventDefinitionOid: 'SE_V1',
  eventDefinitionName: 'Visit 1',
  subjectLabel: 'M-001',
  subjectOid: 'SS_M001',
  studyOid: 'S_DEFAULTS1',
  studyName: 'Default Study',
  dateStart: '2026-06-01',
  status: 'scheduled',
  ordinal: 1,
  repeating: false,
  crfs: [
    {
      eventCrfId: 7,
      eventCrfOid: '7',
      crfName: 'Demographics',
      crfVersionName: 'v1.0',
      crfVersionOid: 'F_DEMOGRAPHICS_V1',
      eventDefinitionCrfId: 100,
      status: 'data-entry-started',
      required: true,
      passwordRequired: false,
    },
    {
      eventCrfId: null,
      eventCrfOid: null,
      crfName: 'Vitals',
      crfVersionName: 'v1.0',
      crfVersionOid: 'F_VITALS_V1',
      eventDefinitionCrfId: 101,
      status: 'not-started',
      required: false,
      passwordRequired: false,
    },
  ],
}

async function mountAt(eventId: number, options: { rows?: EventDetailDto | 'empty' } = {}) {
  setActivePinia(createPinia())
  const router = makeRouter()
  router.push(`/events/${eventId}`)
  await router.isReady()

  if (options.rows === 'empty') {
    apiGetMock.mockResolvedValueOnce({ ...TWO_ROWS, crfs: [] })
  } else if (options.rows) {
    apiGetMock.mockResolvedValueOnce(options.rows)
  }
  const wrapper = mount(EventDetailView, {
    global: { plugins: [router, i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('EventDetailView', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
  })

  it('renders one row per CRF and points the started one at CrfEntryView', async () => {
    const w = await mountAt(42, { rows: TWO_ROWS })
    const rows = w.findAll('[data-test="event-detail-crf-row"]')
    expect(rows.length).toBe(2)

    const openLinks = w.findAll('[data-test="event-detail-open-crf"]')
    expect(openLinks.length).toBe(1)
    expect(openLinks[0]!.attributes('href')).toContain('/event-crfs/7')

    const legacyLinks = w.findAll('[data-test="event-detail-start-legacy"]')
    expect(legacyLinks.length).toBe(1)
    expect(legacyLinks[0]!.attributes('href')).toBe(
      '/LibreClinica/pages/EnterDataForStudyEvent?eventId=42',
    )
    expect(legacyLinks[0]!.attributes('target')).toBe('_blank')
  })

  it('renders the empty placeholder when the event has no CRFs', async () => {
    const w = await mountAt(42, { rows: 'empty' })
    expect(w.find('[data-test="event-detail-no-crfs"]').exists()).toBe(true)
    expect(w.findAll('[data-test="event-detail-crf-row"]').length).toBe(0)
  })

  it('renders the not-found banner on HTTP 404', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(404, 'not found', { message: 'No study_event' }))
    const w = await mountAt(99)
    expect(w.find('[data-test="event-detail-not-found"]').exists()).toBe(true)
  })

  it('renders the forbidden banner on HTTP 403', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(403, 'forbidden', { message: 'different study' }))
    const w = await mountAt(99)
    expect(w.find('[data-test="event-detail-forbidden"]').exists()).toBe(true)
  })

  it('back link returns to the subject detail view', async () => {
    const w = await mountAt(42, { rows: TWO_ROWS })
    const back = w.find('[data-test="event-detail-back"]')
    expect(back.exists()).toBe(true)
    expect(back.attributes('href')).toContain('/subjects/M-001')
  })
})
