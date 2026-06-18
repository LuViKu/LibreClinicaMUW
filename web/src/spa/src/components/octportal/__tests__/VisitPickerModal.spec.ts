/**
 * Wave 2B — VisitPickerModal spec.
 *
 * Pins the load-bearing contract:
 *  - On open, the modal fetches /pages/api/v1/events?subjectId={label}.
 *  - Lists events with label + date + status pill.
 *  - Clicking a row second-hops to /events/{id} and emits
 *    {@code event-picked} with the first non-removed CRF's id.
 *  - "Abbrechen" closes the modal.
 *  - Empty + error states render.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import deMessages from '@/locales/de.json'

// Stub the API client at the import boundary — the modal calls apiGet
// directly to keep the call site close to the wire protocol.
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
  }
})

import VisitPickerModal from '../VisitPickerModal.vue'
import { apiGet, ApiError } from '@/api/client'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

const EVT_LIST = [
  {
    id: '1234',
    subjectId: 'GA-014',
    eventDefinitionOid: 'V1',
    eventLabel: 'V1 · Follow-up',
    ordinal: 1,
    dateStarted: '2026-06-17',
    dateEnded: null,
    location: null,
    status: 'data-entry-started',
    repeating: false,
  },
  {
    id: '1235',
    subjectId: 'GA-014',
    eventDefinitionOid: 'V2',
    eventLabel: 'V2 · Check',
    ordinal: 1,
    dateStarted: '2026-07-01',
    dateEnded: null,
    location: null,
    status: 'scheduled',
    repeating: false,
  },
]

const DETAIL_1234 = {
  eventId: 1234,
  eventDefinitionOid: 'V1',
  eventDefinitionName: 'V1 · Follow-up',
  subjectLabel: 'GA-014',
  subjectOid: 'SS_GA_014',
  studyOid: 'S_GA',
  studyName: 'GA-Studie',
  dateStart: '2026-06-17',
  status: 'data-entry-started',
  ordinal: 1,
  repeating: false,
  crfs: [
    {
      eventCrfId: 8888,
      eventCrfOid: '8888',
      crfName: 'OCT-CRF',
      crfVersionName: 'v1',
      crfVersionOid: 'OCT_V1',
      eventDefinitionCrfId: 99,
      status: 'data-entry-started',
      required: true,
      passwordRequired: false,
    },
  ],
}

function mountModal(props: {
  open: boolean
  studySubjectId?: number
  subjectLabel?: string
} = { open: true }) {
  return mount(VisitPickerModal, {
    props: {
      studySubjectId: 42,
      subjectLabel: 'GA-014',
      ...props,
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('VisitPickerModal', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('fetches events for the subject label on open', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(EVT_LIST)
    mountModal({ open: true })
    await flushPromises()
    expect(apiGet).toHaveBeenCalledTimes(1)
    const calledWith = vi.mocked(apiGet).mock.calls[0][0] as string
    expect(calledWith).toContain('/pages/api/v1/events?')
    expect(calledWith).toContain('subjectId=GA-014')
  })

  it('renders each event row with the label + date + status pill', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(EVT_LIST)
    mountModal({ open: true })
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('V1 · Follow-up')
    expect(text).toContain('V2 · Check')
    expect(text).toContain('2026-06-17')
    expect(text).toContain('data-entry-started')
  })

  it('clicking a row second-hops to /events/{id} and emits event-picked with the first eventCrfId', async () => {
    vi.mocked(apiGet)
      .mockResolvedValueOnce(EVT_LIST)
      .mockResolvedValueOnce(DETAIL_1234)
    const w = mountModal({ open: true })
    await flushPromises()

    const row = document.querySelector<HTMLButtonElement>(
      '[data-testid="visit-picker-result-1234"]',
    )
    expect(row).not.toBeNull()
    row!.click()
    await flushPromises()

    expect(apiGet).toHaveBeenCalledTimes(2)
    expect(vi.mocked(apiGet).mock.calls[1][0]).toBe('/pages/api/v1/events/1234')
    const emitted = w.emitted('event-picked')
    expect(emitted).toBeTruthy()
    expect(emitted![0]).toEqual([{
      eventCrfId: 8888,
      definitionLabel: 'V1 · Follow-up',
      dateStart: '2026-06-17',
    }])
  })

  it('renders the empty state when zero events come back', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([])
    mountModal({ open: true })
    await flushPromises()
    expect(document.querySelector('[data-testid="visit-picker-empty"]')).not.toBeNull()
    expect(document.body.textContent ?? '').toContain('Keine Visiten')
  })

  it('Abbrechen emits close', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([])
    const w = mountModal({ open: true })
    await flushPromises()
    const cancel = document.querySelector<HTMLButtonElement>('[data-testid="visit-picker-cancel"]')
    expect(cancel).not.toBeNull()
    cancel!.click()
    await flushPromises()
    expect(w.emitted('close')).toBeTruthy()
  })

  it('surfaces backend errors via the error banner', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, 'boom', null, 'req-1'))
    mountModal({ open: true })
    await flushPromises()
    const errBanner = document.querySelector('[data-testid="visit-picker-error"]')
    expect(errBanner).not.toBeNull()
    expect(errBanner!.textContent ?? '').toContain('boom')
  })
})
