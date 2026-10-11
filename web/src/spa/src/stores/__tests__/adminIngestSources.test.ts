/**
 * adminIngestSources store — where device files come from, and the location
 * each source is assigned to (system administrators only).
 *
 * Pins the wire contract and what the store makes of the answers: the list
 * is patched from the assignment's answer, a `null` location is sent as
 * `null` (the source becomes unassigned again), and the server's refusals
 * (409 for a location that is no longer available) reach the person in the
 * server's words and leave the row as it was.
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() }
})

import { apiGet, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import { i18n } from '@/i18n'
import { useAdminIngestSourcesStore } from '../adminIngestSources'
import type { IngestSource } from '@/types/ingestSource'

const BASE = '/pages/api/v1/admin/ingest-sources'

function src(over: Partial<IngestSource> & Pick<IngestSource, 'id' | 'kind' | 'displayName'>): IngestSource {
  return {
    sourceKey: null,
    locationId: 1,
    locationName: 'MUW Augenklinik',
    pendingItemCount: 0,
    lastSeenAt: null,
    status: 'active',
    ...over,
  }
}

const CLARUS = src({ id: 1, kind: 'uploader', displayName: 'Clarus-PC', lastSeenAt: '2026-10-12T09:30:00Z' })
const LUMO = src({ id: 2, kind: 'dicom_ae', displayName: 'Optomed Lumo', sourceKey: 'LUMO01',
  locationId: null, locationName: null, pendingItemCount: 12 })
const REMIDIO = src({ id: 3, kind: 'remidio', displayName: 'Remidio-Konto', sourceKey: 'muw-augen',
  locationId: null, locationName: null, pendingItemCount: 0 })

describe('useAdminIngestSourcesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'en'
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPut).mockReset()
  })
  afterEach(() => { i18n.global.locale.value = 'de-AT' })

  it('starts empty and not loading', () => {
    const s = useAdminIngestSourcesStore()
    expect(s.sources).toEqual([])
    expect(s.isLoading).toBe(false)
    expect(s.error).toBeNull()
    expect(s.loaded).toBe(false)
  })

  describe('load', () => {
    it('GETs every source in the server order and marks the list loaded', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS, LUMO, REMIDIO])
      const s = useAdminIngestSourcesStore()
      await s.load()
      expect(apiGet).toHaveBeenCalledTimes(1)
      expect(apiGet).toHaveBeenCalledWith(BASE)
      expect(s.sources.map((x) => x.id)).toEqual([1, 2, 3])
      expect(s.loaded).toBe(true)
      expect(s.error).toBeNull()
    })

    it('keeps the server’s message when the list is refused', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(
        new ApiError(403, 'Forbidden', { message: 'Only a system administrator may list the sources' }),
      )
      const s = useAdminIngestSourcesStore()
      await s.load()
      expect(s.sources).toEqual([])
      expect(s.error).toBe('Only a system administrator may list the sources')
    })

    it('names the status when the server sent no message', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, 'Server Error', null))
      const s = useAdminIngestSourcesStore()
      await s.load()
      expect(s.error).toBe('The sources could not be loaded (HTTP 500).')
    })

    it('says the server is unreachable when no answer came', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiNetworkError('offline', null))
      const s = useAdminIngestSourcesStore()
      await s.load()
      expect(s.error).toBe('The server could not be reached.')
    })
  })

  describe('what waits', () => {
    it('counts the sources without a location and the files that wait for them', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([
        CLARUS,
        LUMO,
        REMIDIO,
        // Files that wait at an assigned source are the inbox's normal work, not a gap in the set-up.
        src({ id: 4, kind: 'uploader', displayName: 'Spectralis-PC', pendingItemCount: 30 }),
      ])
      const s = useAdminIngestSourcesStore()
      await s.load()
      expect(s.unassignedCount).toBe(2)
      expect(s.waitingCount).toBe(12)
    })

    it('is zero when every source has a location', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useAdminIngestSourcesStore()
      await s.load()
      expect(s.unassignedCount).toBe(0)
      expect(s.waitingCount).toBe(0)
    })
  })

  describe('assignLocation', () => {
    it('PUTs the location id and patches the row from the answer', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS, LUMO, REMIDIO])
      const s = useAdminIngestSourcesStore()
      await s.load()
      const assigned = { ...LUMO, locationId: 2, locationName: 'Klinik Graz' }
      vi.mocked(apiPut).mockResolvedValueOnce(assigned)
      const result = await s.assignLocation(2, 2)

      expect(apiPut).toHaveBeenCalledTimes(1)
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/2/location`, { locationId: 2 })
      expect(result).toEqual({ ok: true, source: assigned })
      expect(s.sources.find((x) => x.id === 2)).toEqual(assigned)
      // The others keep their place and their values.
      expect(s.sources.map((x) => x.id)).toEqual([1, 2, 3])
      expect(s.unassignedCount).toBe(1)
    })

    it('sends null to take the location away', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useAdminIngestSourcesStore()
      await s.load()
      const unassigned = { ...CLARUS, locationId: null, locationName: null }
      vi.mocked(apiPut).mockResolvedValueOnce(unassigned)
      const result = await s.assignLocation(1, null)

      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1/location`, { locationId: null })
      expect(result.ok).toBe(true)
      expect(s.sources[0].locationId).toBeNull()
      expect(s.unassignedCount).toBe(1)
    })

    it('shows the server’s text for a location that is no longer available (409) and keeps the row', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([LUMO])
      const s = useAdminIngestSourcesStore()
      await s.load()
      vi.mocked(apiPut).mockRejectedValueOnce(
        new ApiError(409, 'Conflict', { message: 'Location "Klinik Linz" is disabled' }),
      )
      const result = await s.assignLocation(2, 3)
      expect(result).toMatchObject({ ok: false, status: 409, message: 'Location "Klinik Linz" is disabled' })
      expect(s.sources[0].locationId).toBeNull()
    })

    it('shows the server’s text for a source or location that does not exist (404)', async () => {
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'No ingest source with id 9' }))
      const s = useAdminIngestSourcesStore()
      const result = await s.assignLocation(9, 1)
      expect(result).toMatchObject({ ok: false, status: 404, message: 'No ingest source with id 9' })
    })

    it('names the status when the refusal carries no text', async () => {
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(500, 'Server Error', null))
      const s = useAdminIngestSourcesStore()
      const result = await s.assignLocation(1, 1)
      expect(result).toMatchObject({ ok: false, status: 500, message: 'The change could not be saved (HTTP 500).' })
    })

    it('says so when nothing came back', async () => {
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiNetworkError('offline', null))
      const s = useAdminIngestSourcesStore()
      const result = await s.assignLocation(1, 1)
      expect(result).toMatchObject({ ok: false, status: null, message: 'The server could not be reached.' })
    })
  })

  describe('fetchAnonymousUploaders', () => {
    it('GETs the uploaders that never used a token, for the warning before the second location', async () => {
      const answer = {
        count: 2,
        instances: [
          { displayName: 'CLARUS-PC', kind: 'export-watcher', lastSeenAt: '2026-10-11T09:30:00Z' },
          { displayName: null, kind: 'optomed-bridge', lastSeenAt: '2026-10-10T08:00:00Z' },
        ],
      }
      vi.mocked(apiGet).mockResolvedValueOnce(answer)
      const s = useAdminIngestSourcesStore()
      const result = await s.fetchAnonymousUploaders()
      expect(apiGet).toHaveBeenCalledTimes(1)
      expect(apiGet).toHaveBeenCalledWith(`${BASE}/anonymous-uploaders`)
      expect(result).toEqual({ ok: true, anonymous: answer })
    })

    it('keeps the full count when the instances are cut off at 50', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce({ count: 71, instances: [{ displayName: 'A', kind: 'x', lastSeenAt: '2026-10-11T09:30:00Z' }] })
      const result = await useAdminIngestSourcesStore().fetchAnonymousUploaders()
      expect(result).toMatchObject({ ok: true, anonymous: { count: 71 } })
    })

    it('reads an answer that lacks a part as nothing in that part', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce({})
      const result = await useAdminIngestSourcesStore().fetchAnonymousUploaders()
      expect(result).toEqual({ ok: true, anonymous: { count: 0, instances: [] } })
    })

    it('does not touch the list of sources, which is its own read', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useAdminIngestSourcesStore()
      await s.load()
      vi.mocked(apiGet).mockResolvedValueOnce({ count: 1, instances: [] })
      await s.fetchAnonymousUploaders()
      expect(s.sources.map((x) => x.id)).toEqual([1])
      expect(s.error).toBeNull()
    })

    it('hands the server’s refusal to the caller in the server’s words, and leaves the store’s error alone', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(403, 'Forbidden', { message: 'Only a system administrator' }))
      const s = useAdminIngestSourcesStore()
      const result = await s.fetchAnonymousUploaders()
      expect(result).toMatchObject({ ok: false, status: 403, message: 'Only a system administrator' })
      expect(s.error).toBeNull()
    })

    it('names the status when the refusal carries no text, and says so when nothing came back', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, 'Server Error', null))
      const s = useAdminIngestSourcesStore()
      expect(await s.fetchAnonymousUploaders()).toMatchObject({
        ok: false, status: 500, message: 'The uploaders without a token could not be determined (HTTP 500).',
      })
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiNetworkError('offline', null))
      expect(await s.fetchAnonymousUploaders()).toMatchObject({
        ok: false, status: null, message: 'The server could not be reached.',
      })
    })
  })

  it('reset forgets everything', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
    const s = useAdminIngestSourcesStore()
    await s.load()
    s.reset()
    expect(s.sources).toEqual([])
    expect(s.loaded).toBe(false)
  })
})
