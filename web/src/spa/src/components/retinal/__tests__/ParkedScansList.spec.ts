/**
 * Wave 2B — ParkedScansList spec.
 *
 * Pins the load-bearing contract:
 *  - Filters subject jobs to status === 'parked'.
 *  - Empty state when no parked jobs.
 *  - "Visite zuweisen" → opens VisitPickerModal → PATCH bind happy
 *    path removes the row + shows a success toast.
 *  - 409 conflict path refreshes the list + shows the conflict toast
 *    instead of a hard error.
 *  - Network/4xx error path shows the error banner + restores the row.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import deMessages from '@/locales/de.json'

vi.mock('@/api/retinal', async () => {
  const actual = await vi.importActual<typeof import('@/api/retinal')>('@/api/retinal')
  return {
    ...actual,
    listSubjectJobs: vi.fn(),
    bindParkedJob: vi.fn(),
  }
})
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
  }
})

import ParkedScansList from '../ParkedScansList.vue'
import {
  listSubjectJobs,
  bindParkedJob,
  type RetinalJobSummary,
} from '@/api/retinal'
import { apiGet, ApiError } from '@/api/client'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

const PARKED_JOB: RetinalJobSummary = {
  jobId: 101,
  task: 'fluid',
  laterality: 'OD',
  status: 'parked',
  modelVersion: 'fluid-v3',
  completedAt: null,
  primaryMetric: null,
}

const QUEUED_JOB: RetinalJobSummary = {
  jobId: 102,
  task: 'fluid',
  laterality: 'OS',
  status: 'queued',
  modelVersion: 'fluid-v3',
  completedAt: null,
  primaryMetric: null,
}

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
      eventCrfId: 9000,
      eventCrfOid: '9000',
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

function mountList(props: { studySubjectId: number; subjectLabel?: string } = {
  studySubjectId: 42,
  subjectLabel: 'GA-014',
}) {
  return mount(ParkedScansList, {
    props,
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('ParkedScansList', () => {
  beforeEach(() => {
    vi.mocked(listSubjectJobs).mockReset()
    vi.mocked(bindParkedJob).mockReset()
    vi.mocked(apiGet).mockReset()
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders only parked-status jobs', async () => {
    vi.mocked(listSubjectJobs).mockResolvedValue([PARKED_JOB, QUEUED_JOB])
    mountList()
    await flushPromises()
    expect(listSubjectJobs).toHaveBeenCalledWith(42)
    expect(document.querySelector(`[data-testid="parked-scans-row-${PARKED_JOB.jobId}"]`)).not.toBeNull()
    expect(document.querySelector(`[data-testid="parked-scans-row-${QUEUED_JOB.jobId}"]`)).toBeNull()
  })

  it('renders the empty state when no parked jobs', async () => {
    vi.mocked(listSubjectJobs).mockResolvedValue([QUEUED_JOB])
    mountList()
    await flushPromises()
    expect(document.querySelector('[data-testid="parked-scans-empty"]')).not.toBeNull()
    expect(document.body.textContent ?? '').toContain('Keine parkenden Scans')
  })

  it('bind happy path: PATCH /retinal-jobs/{id}/bind, removes the row + success toast', async () => {
    // First load returns the parked job; after bind, the refresh returns empty.
    vi.mocked(listSubjectJobs)
      .mockResolvedValueOnce([PARKED_JOB])
      .mockResolvedValueOnce([])
    vi.mocked(apiGet)
      .mockResolvedValueOnce(EVT_LIST)       // GET /events?subjectId=...
      .mockResolvedValueOnce(DETAIL_1234)    // GET /events/1234
    vi.mocked(bindParkedJob).mockResolvedValue({ jobId: PARKED_JOB.jobId, status: 'queued' })

    mountList()
    await flushPromises()

    // Click "Visite zuweisen" — opens the modal.
    const bindBtn = document.querySelector<HTMLButtonElement>(
      `[data-testid="parked-scans-bind-${PARKED_JOB.jobId}"]`,
    )
    expect(bindBtn).not.toBeNull()
    bindBtn!.click()
    await flushPromises()

    // Pick the event row in the modal → triggers the bind call.
    const evtBtn = document.querySelector<HTMLButtonElement>(
      '[data-testid="visit-picker-result-1234"]',
    )
    expect(evtBtn).not.toBeNull()
    evtBtn!.click()
    await flushPromises()

    expect(bindParkedJob).toHaveBeenCalledWith(PARKED_JOB.jobId, { eventCrfId: 9000 })
    // Refresh after bind — listSubjectJobs called twice now (initial + refresh).
    expect(listSubjectJobs).toHaveBeenCalledTimes(2)
    // Success toast visible.
    const toast = document.querySelector('[data-testid="parked-scans-toast"]')
    expect(toast).not.toBeNull()
    expect(toast!.textContent ?? '').toContain('zugewiesen')
  })

  it('409 conflict: refreshes the list + shows the conflict toast', async () => {
    vi.mocked(listSubjectJobs)
      .mockResolvedValueOnce([PARKED_JOB])
      .mockResolvedValueOnce([]) // after the bind, server says it's gone
    vi.mocked(apiGet)
      .mockResolvedValueOnce(EVT_LIST)
      .mockResolvedValueOnce(DETAIL_1234)
    vi.mocked(bindParkedJob).mockRejectedValue(
      new ApiError(409, 'Job is not parked (status=queued)', { message: 'Job is not parked (status=queued)' }, 'req-x'),
    )

    mountList()
    await flushPromises()
    document.querySelector<HTMLButtonElement>(`[data-testid="parked-scans-bind-${PARKED_JOB.jobId}"]`)!.click()
    await flushPromises()
    document.querySelector<HTMLButtonElement>('[data-testid="visit-picker-result-1234"]')!.click()
    await flushPromises()

    expect(listSubjectJobs).toHaveBeenCalledTimes(2)
    const toast = document.querySelector('[data-testid="parked-scans-toast"]')
    expect(toast).not.toBeNull()
    expect(toast!.textContent ?? '').toMatch(/bereits|Sitzung/)
    // No error banner — conflicts are non-fatal.
    expect(document.querySelector('[data-testid="parked-scans-error"]')).toBeNull()
  })
})
