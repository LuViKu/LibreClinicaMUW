/**
 * The event detail store removes a CRF with the reason given and hands the
 * dialog the server's refusal as a message. It leaves reloading the visit
 * to the page, which closes the dialog first.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn() }
})

// eslint-disable-next-line import/first
import { apiGet, apiPost, ApiError } from '@/api/client'
// eslint-disable-next-line import/first
import { useEventDetailStore } from '../eventDetail'
// eslint-disable-next-line import/first
import type { EventDetailDto } from '@/types/event'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>
const apiPostMock = apiPost as unknown as ReturnType<typeof vi.fn>

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
  crfs: [],
}

describe('useEventDetailStore CRF removal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiGetMock.mockReset()
    apiPostMock.mockReset()
  })

  it('posts the reason and keeps the loaded visit until the page reloads it', async () => {
    apiGetMock.mockResolvedValueOnce(EVENT)
    const store = useEventDetailStore()
    await store.load(42)
    apiPostMock.mockResolvedValueOnce(undefined)

    expect(await store.removeCrf(7, 'Entered on the wrong subject')).toBe(true)
    expect(apiPostMock).toHaveBeenCalledWith('/pages/api/v1/eventCrfs/7/remove',
      { reason: 'Entered on the wrong subject' })
    expect(apiGetMock).toHaveBeenCalledTimes(1)
    expect(store.event).toEqual(EVENT)
    expect(store.removeCrfError).toBeNull()
    expect(store.isRemovingCrf).toBe(false)
  })

  it('keeps the server message when the removal is refused', async () => {
    apiPostMock.mockRejectedValueOnce(new ApiError(409, 'conflict', { message: 'event_crf 7 is already removed' }))
    const store = useEventDetailStore()

    expect(await store.removeCrf(7, 'Twice')).toBe(false)
    expect(store.removeCrfError).toBe('event_crf 7 is already removed')
  })

  it('reads the removal impact, or the refusal as a message', async () => {
    const store = useEventDetailStore()
    apiGetMock.mockResolvedValueOnce({ values: 4, openNoteThreads: 1 })
    expect(await store.removalImpact(7)).toEqual({ impact: { values: 4, openNoteThreads: 1 } })
    expect(apiGetMock).toHaveBeenLastCalledWith('/pages/api/v1/eventCrfs/7/removal-impact')

    apiGetMock.mockRejectedValueOnce(new ApiError(403, 'forbidden',
      { message: 'Your role does not permit removing a CRF' }))
    expect(await store.removalImpact(7)).toEqual({ refused: 'Your role does not permit removing a CRF' })
  })

  it('re-throws on 401 so the router-level auth guard can pick it up', async () => {
    apiPostMock.mockRejectedValueOnce(new ApiError(401, 'unauthorized', { message: 'login required' }))
    const store = useEventDetailStore()
    await expect(store.removeCrf(7, 'x')).rejects.toBeInstanceOf(ApiError)
  })
})
