/**
 * DR-025 — the camera-worklist strip on the subject page.
 *
 * The camera's worklist is the visit schedule filtered to today. The strip
 * must (1) stay silent where no camera serves the study, (2) say "on the list"
 * with the visit and its accession, and (3) when not on the list, offer the
 * one fix that cannot collide — schedule the study's only visit definition
 * for today, or move an existing open visit to today — and hand anything
 * ambiguous to the ordinary schedule dialog.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
    constructor(public status = 0, msg = '', public body: unknown = null) {
      super(msg)
    }
  }
  class ApiNetworkError extends Error {}
  return {
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
    ApiError,
    ApiNetworkError,
  }
})

vi.mock('@/api/events', () => ({
  getCameraWorklist: vi.fn(),
}))

// eslint-disable-next-line import/first
import { apiGet, apiPost, apiPut } from '@/api/client'
// eslint-disable-next-line import/first
import { getCameraWorklist, type CameraWorklistStatus } from '@/api/events'
// eslint-disable-next-line import/first
import CameraWorklistStatusStrip from '@/components/CameraWorklistStatus.vue'
// eslint-disable-next-line import/first
import deMessages from '@/locales/de.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>
const apiPutMock = apiPut as unknown as ReturnType<typeof vi.fn>
const worklistMock = getCameraWorklist as unknown as ReturnType<typeof vi.fn>

const i18n = createI18n({
  legacy: false,
  locale: 'de',
  fallbackLocale: 'de',
  messages: { de: deMessages },
  missingWarn: false,
  fallbackWarn: false,
})

const TODAY = '2026-09-20'

function definition(oid: string, name: string, repeating = false) {
  return {
    sedId: 1,
    oid,
    name,
    description: '',
    category: '',
    type: 'scheduled',
    repeating,
    ordinal: 1,
    status: 'available',
  }
}

function statusOf(overrides: Partial<CameraWorklistStatus> = {}): CameraWorklistStatus {
  return { date: TODAY, offered: true, today: [], otherOpen: [], ...overrides }
}

async function mountWith(
  status: CameraWorklistStatus,
  definitions: ReturnType<typeof definition>[] = [definition('SE_BASELINE', 'Baseline')],
  canSchedule = true,
) {
  setActivePinia(createPinia())
  worklistMock.mockReset()
  worklistMock.mockResolvedValue(status)
  apiGetMock.mockReset()
  apiGetMock.mockResolvedValue(definitions)
  apiPostMock.mockReset()
  apiPutMock.mockReset()
  const wrapper = mount(CameraWorklistStatusStrip, {
    props: { subjectId: 'HAE-007', studyOid: 'S_HAE', canSchedule },
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('CameraWorklistStatus', () => {
  beforeEach(() => {
    worklistMock.mockReset()
  })

  it('renders nothing where no camera serves the study', async () => {
    const w = await mountWith(statusOf({ offered: false }))
    expect(w.find('[data-testid="camera-worklist"]').exists()).toBe(false)
  })

  it('renders nothing when the status cannot be loaded', async () => {
    setActivePinia(createPinia())
    worklistMock.mockRejectedValue(new Error('boom'))
    apiGetMock.mockResolvedValue([])
    const w = mount(CameraWorklistStatusStrip, {
      props: { subjectId: 'HAE-007', studyOid: 'S_HAE', canSchedule: true },
      global: { plugins: [i18n] },
    })
    await flushPromises()
    expect(w.find('[data-testid="camera-worklist"]').exists()).toBe(false)
  })

  it('says the patient is on the list, with the visit and its accession', async () => {
    const w = await mountWith(statusOf({
      today: [{ studyEventId: 42, eventLabel: 'Baseline', date: TODAY, time: '09:30', status: 'scheduled', accession: 'LC42' }],
    }))
    const strip = w.find('[data-testid="camera-worklist"]')
    expect(strip.exists()).toBe(true)
    expect(w.find('[data-testid="camera-worklist-on"]').text()).toBe('Heute auf der Kamera-Worklist')
    expect(strip.text()).toContain('Baseline')
    expect(strip.text()).toContain('09:30')
    expect(strip.text()).toContain('LC42')
    expect(w.find('[data-testid="camera-worklist-schedule"]').exists()).toBe(false)
    expect(w.find('[data-testid="camera-worklist-move"]').exists()).toBe(false)
  })

  it('offers to schedule the only visit definition for today, on the server\'s date', async () => {
    const w = await mountWith(statusOf())
    expect(w.find('[data-testid="camera-worklist-off"]').text()).toBe('Nicht auf der Kamera-Worklist')
    const button = w.find('[data-testid="camera-worklist-schedule"]')
    expect(button.text()).toBe('Baseline für heute planen')

    apiPostMock.mockResolvedValue({ id: '77', eventDefinitionOid: 'SE_BASELINE', status: 'scheduled' })
    worklistMock.mockResolvedValue(statusOf({
      today: [{ studyEventId: 77, eventLabel: 'Baseline', date: TODAY, time: null, status: 'scheduled', accession: 'LC77' }],
    }))
    await button.trigger('click')
    await flushPromises()

    expect(apiPostMock).toHaveBeenCalledWith('/pages/api/v1/events', {
      subjectId: 'HAE-007',
      eventDefinitionOid: 'SE_BASELINE',
      dateStarted: TODAY,
    })
    expect(w.emitted('changed')).toHaveLength(1)
    expect(w.find('[data-testid="camera-worklist-on"]').exists()).toBe(true)
  })

  it('moves an open visit on another day to today instead of scheduling a second one', async () => {
    const w = await mountWith(statusOf({
      otherOpen: [{ studyEventId: 42, eventLabel: 'Baseline', date: '2026-09-27', time: null, status: 'scheduled', accession: 'LC42' }],
    }))
    const strip = w.find('[data-testid="camera-worklist"]')
    expect(strip.text()).toContain('Baseline ist für 27.09.2026 geplant.')
    // A non-repeating definition with an open visit elsewhere: scheduling a
    // second Baseline would be refused, so it is not offered.
    expect(w.find('[data-testid="camera-worklist-schedule"]').exists()).toBe(false)

    apiPutMock.mockResolvedValue({ id: '42', status: 'scheduled', dateStarted: TODAY })
    await w.find('[data-testid="camera-worklist-move"]').trigger('click')
    await flushPromises()

    expect(apiPutMock).toHaveBeenCalledWith('/pages/api/v1/events/42', { dateStarted: TODAY })
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('still offers a new visit today when the only definition repeats', async () => {
    const w = await mountWith(
      statusOf({
        otherOpen: [{ studyEventId: 42, eventLabel: 'Visite', date: '2026-10-18', time: null, status: 'scheduled', accession: 'LC42' }],
      }),
      [definition('SE_VISIT', 'Visite', true)],
    )
    expect(w.find('[data-testid="camera-worklist-move"]').exists()).toBe(true)
    expect(w.find('[data-testid="camera-worklist-schedule"]').text()).toBe('Visite für heute planen')
  })

  it('hands several definitions to the schedule dialog', async () => {
    const w = await mountWith(statusOf(), [
      definition('SE_BASELINE', 'Baseline'),
      definition('SE_FOLLOWUP', 'Follow-up'),
    ])
    expect(w.find('[data-testid="camera-worklist-schedule"]').exists()).toBe(false)
    await w.find('[data-testid="camera-worklist-pick"]').trigger('click')
    expect(w.emitted('open-schedule')).toHaveLength(1)
    expect(apiPostMock).not.toHaveBeenCalled()
  })

  it('shows the status without actions to a role that cannot schedule', async () => {
    const w = await mountWith(
      statusOf({
        otherOpen: [{ studyEventId: 42, eventLabel: 'Baseline', date: '2026-09-27', time: null, status: 'scheduled', accession: 'LC42' }],
      }),
      [definition('SE_BASELINE', 'Baseline')],
      false,
    )
    expect(w.find('[data-testid="camera-worklist-off"]').exists()).toBe(true)
    expect(w.find('[data-testid="camera-worklist-schedule"]').exists()).toBe(false)
    expect(w.find('[data-testid="camera-worklist-move"]').exists()).toBe(false)
    expect(w.find('[data-testid="camera-worklist-pick"]').exists()).toBe(false)
  })

  it('surfaces the server\'s refusal instead of pretending', async () => {
    const w = await mountWith(statusOf())
    const { ApiError } = await import('@/api/client')
    apiPostMock.mockRejectedValue(new ApiError(409, 'conflict', {
      message: 'That visit is already scheduled for this subject on that date',
    }))
    await w.find('[data-testid="camera-worklist-schedule"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="camera-worklist-error"]').text())
      .toContain('already scheduled')
    expect(w.emitted('changed')).toBeUndefined()
  })

  it('reloads when the visits on the page change', async () => {
    const w = await mountWith(statusOf())
    expect(worklistMock).toHaveBeenCalledTimes(1)
    await w.setProps({ eventsSignature: '42:2026-09-20:scheduled' })
    await flushPromises()
    expect(worklistMock).toHaveBeenCalledTimes(2)
  })
})
