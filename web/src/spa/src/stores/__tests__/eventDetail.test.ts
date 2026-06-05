import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

/**
 * Phase E.6 — load() spec for {@link useEventDetailStore}. Pins the
 * URL it calls + the three error flavours the view branches on
 * (404 / 403 / network).
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
  }
})

// eslint-disable-next-line import/first
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
// eslint-disable-next-line import/first
import { useEventDetailStore } from '../eventDetail'
// eslint-disable-next-line import/first
import type { EventDetailDto } from '@/types/event'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

const FIXTURE: EventDetailDto = {
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

describe('useEventDetailStore.load', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
  })

  it('starts empty', () => {
    const store = useEventDetailStore()
    expect(store.event).toBeNull()
    expect(store.isLoading).toBe(false)
    expect(store.notFound).toBe(false)
    expect(store.forbidden).toBe(false)
  })

  it('calls the right URL and populates the event on success', async () => {
    apiGetMock.mockResolvedValueOnce(FIXTURE)
    const store = useEventDetailStore()
    await store.load(42)
    expect(apiGetMock).toHaveBeenCalledWith('/pages/api/v1/events/42')
    expect(store.event?.eventId).toBe(42)
    expect(store.event?.crfs.length).toBe(2)
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('url-encodes the eventId path segment', async () => {
    apiGetMock.mockResolvedValueOnce(FIXTURE)
    const store = useEventDetailStore()
    await store.load('42/inject')
    expect(apiGetMock).toHaveBeenCalledWith('/pages/api/v1/events/42%2Finject')
  })

  it('sets notFound on HTTP 404', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(404, 'not found', { message: 'No study_event' }))
    const store = useEventDetailStore()
    await store.load(99)
    expect(store.notFound).toBe(true)
    expect(store.event).toBeNull()
    expect(store.error).toBeNull()
  })

  it('sets forbidden on HTTP 403', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiError(403, 'forbidden', { message: 'belongs to a different study' }))
    const store = useEventDetailStore()
    await store.load(99)
    expect(store.forbidden).toBe(true)
    expect(store.event).toBeNull()
  })

  it('sets network on ApiNetworkError', async () => {
    apiGetMock.mockRejectedValueOnce(new ApiNetworkError('connection refused', new Error('refused')))
    const store = useEventDetailStore()
    await store.load(99)
    expect(store.network).toBe(true)
    expect(store.event).toBeNull()
  })

  it('reset() clears every flag', async () => {
    apiGetMock.mockResolvedValueOnce(FIXTURE)
    const store = useEventDetailStore()
    await store.load(42)
    store.reset()
    expect(store.event).toBeNull()
    expect(store.notFound).toBe(false)
    expect(store.forbidden).toBe(false)
    expect(store.network).toBe(false)
  })
})
