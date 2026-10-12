/**
 * locationOptions store — the locations the caller may place a study or a
 * site at (`GET /pages/api/v1/locations`, DR-040 phase 4).
 *
 * Pins the path, that concurrent loads share one request, and that a
 * failure leaves an empty list with a readable message instead of throwing.
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})

import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import { i18n } from '@/i18n'
import { useLocationOptionsStore } from '../locationOptions'
import type { LocationOption } from '@/types/location'

const MUW: LocationOption = {
  id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik', street: 'Währinger Gürtel 18-20',
  city: 'Wien', zip: '1090', country: 'AT',
}

describe('useLocationOptionsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'en'
    vi.mocked(apiGet).mockReset()
  })
  afterEach(() => { i18n.global.locale.value = 'de-AT' })

  it('loads the options from /pages/api/v1/locations', async () => {
    vi.mocked(apiGet).mockResolvedValue([MUW])
    const s = useLocationOptionsStore()
    await s.load()
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/locations')
    expect(s.options).toEqual([MUW])
    expect(s.loaded).toBe(true)
    expect(s.error).toBeNull()
  })

  it('shares one request between two loads that overlap', async () => {
    let resolve!: (v: LocationOption[]) => void
    vi.mocked(apiGet).mockReturnValue(new Promise((r) => { resolve = r }))
    const s = useLocationOptionsStore()
    const a = s.load()
    const b = s.load()
    expect(s.isLoading).toBe(true)
    resolve([MUW])
    await Promise.all([a, b])
    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(s.isLoading).toBe(false)
  })

  it('keeps an empty list and a message when the request fails', async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError(500, 'boom', null))
    const s = useLocationOptionsStore()
    await s.load()
    expect(s.options).toEqual([])
    expect(s.loaded).toBe(false)
    expect(s.error).toBe('The locations could not be loaded (HTTP 500).')
  })

  it('names a missing connection', async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiNetworkError('down', null))
    const s = useLocationOptionsStore()
    await s.load()
    expect(s.error).toBe('Server not reachable — the locations could not be loaded.')
  })
})
