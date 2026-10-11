/**
 * adminLocations store — the system administrator's global locations.
 *
 * Pins the wire contract (paths, verbs, bodies, the encoded username) and
 * what the store makes of the answers: the list is patched from each
 * response rather than reloaded, a 400 becomes per-field errors, any other
 * refusal keeps the server's own message (the 409 of a disable is read by
 * a person), and a late answer for a location that is no longer selected
 * is dropped.
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
  }
})

import { apiGet, apiPost, apiPut, apiDelete, ApiError, ApiNetworkError } from '@/api/client'
import { i18n } from '@/i18n'
import { useAdminLocationsStore } from '../adminLocations'
import type { Location, LocationDetail } from '@/types/location'

const BASE = '/pages/api/v1/admin/locations'

function loc(over: Partial<Location> & Pick<Location, 'id' | 'code' | 'name'>): Location {
  return {
    status: 'available', street: null, city: null, zip: null, country: null,
    siteCount: 0, studyCount: 0, ingestItemCount: 0, userCount: 0,
    ...over,
  }
}

const GRAZ = loc({ id: 2, code: 'GRAZ', name: 'Klinik Graz', city: 'Graz', country: 'AT', siteCount: 1 })
const MUW = loc({ id: 1, code: 'MUW-AUGEN', name: 'MUW Augenklinik', city: 'Wien', country: 'AT',
  siteCount: 2, studyCount: 1, ingestItemCount: 340, userCount: 1 })

function detailOf(l: Location, over: Partial<LocationDetail> = {}): LocationDetail {
  return { ...l, studies: [], users: [], ...over }
}

describe('useAdminLocationsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'en'
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPut).mockReset()
    vi.mocked(apiDelete).mockReset()
  })
  afterEach(() => { i18n.global.locale.value = 'de-AT' })

  it('starts empty, not loading, nothing selected', () => {
    const s = useAdminLocationsStore()
    expect(s.locations).toEqual([])
    expect(s.isLoading).toBe(false)
    expect(s.error).toBeNull()
    expect(s.detail).toBeNull()
    expect(s.selectedId).toBeNull()
  })

  describe('load', () => {
    it('GETs the list in the server order and marks it loaded', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW])
      const s = useAdminLocationsStore()
      await s.load()
      expect(apiGet).toHaveBeenCalledTimes(1)
      expect(apiGet).toHaveBeenCalledWith(BASE)
      expect(s.locations.map((l) => l.id)).toEqual([2, 1])
      expect(s.loaded).toBe(true)
      expect(s.error).toBeNull()
    })

    it('keeps the server’s message when the list is refused', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(
        new ApiError(403, 'Forbidden', { message: 'Only a system administrator may manage locations' }),
      )
      const s = useAdminLocationsStore()
      await s.load()
      expect(s.locations).toEqual([])
      expect(s.error).toBe('Only a system administrator may manage locations')
      expect(s.isLoading).toBe(false)
    })

    it('says the server is unreachable when no answer came', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiNetworkError('offline', null))
      const s = useAdminLocationsStore()
      await s.load()
      expect(s.error).toBe('The server could not be reached.')
    })
  })

  describe('select', () => {
    it('GETs the detail of one location', async () => {
      const detail = detailOf(MUW, {
        studies: [{ oid: 'S_ALPHA_1', name: 'Alpha Wien', isSite: true, parentOid: 'S_ALPHA', status: 'available' }],
        users: [{ username: 'anna', displayName: 'Anna Beispiel', canManageSources: false }],
      })
      vi.mocked(apiGet).mockResolvedValueOnce(detail)
      const s = useAdminLocationsStore()
      await s.select(1)
      expect(apiGet).toHaveBeenCalledTimes(1)
      expect(apiGet).toHaveBeenCalledWith(`${BASE}/1`)
      expect(s.selectedId).toBe(1)
      expect(s.detail).toEqual(detail)
      expect(s.detailLoading).toBe(false)
      expect(s.detailError).toBeNull()
    })

    it('shows the server’s message when the detail is not found', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'No location with id 9' }))
      const s = useAdminLocationsStore()
      await s.select(9)
      expect(s.detail).toBeNull()
      expect(s.detailError).toBe('No location with id 9')
    })

    it('drops the answer for a location that is no longer selected', async () => {
      let answerFirst!: (v: LocationDetail) => void
      vi.mocked(apiGet)
        .mockImplementationOnce(() => new Promise<LocationDetail>((r) => { answerFirst = r }))
        .mockResolvedValueOnce(detailOf(GRAZ))
      const s = useAdminLocationsStore()
      const first = s.select(1)
      await s.select(2)
      answerFirst(detailOf(MUW))
      await first
      expect(s.selectedId).toBe(2)
      expect(s.detail?.id).toBe(2)
    })

    it('clearSelection forgets the detail', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(detailOf(MUW))
      const s = useAdminLocationsStore()
      await s.select(1)
      s.clearSelection()
      expect(s.selectedId).toBeNull()
      expect(s.detail).toBeNull()
    })
  })

  describe('create', () => {
    it('POSTs the input and slots the new location into the list by name, without reloading', async () => {
      const created = loc({ id: 3, code: 'LINZ', name: 'Klinik Linz', city: 'Linz' })
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW])
      vi.mocked(apiPost).mockResolvedValueOnce(created)
      const s = useAdminLocationsStore()
      await s.load()
      const input = { code: 'LINZ', name: 'Klinik Linz', city: 'Linz' }
      const r = await s.create(input)
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenCalledWith(BASE, input)
      expect(r).toEqual({ ok: true, location: created })
      expect(s.locations.map((l) => l.name)).toEqual(['Klinik Graz', 'Klinik Linz', 'MUW Augenklinik'])
      expect(apiGet).toHaveBeenCalledTimes(1)
    })

    it('turns a 400 into one message per field and leaves the list alone', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [
          { field: 'code', message: 'A location with this code exists already' },
          { field: 'name', message: 'Name must not be longer than 100 characters' },
        ],
      }))
      const s = useAdminLocationsStore()
      const r = await s.create({ code: 'MUW-AUGEN', name: 'x'.repeat(101) })
      expect(r).toEqual({
        ok: false,
        status: 400,
        fieldErrors: {
          code: 'A location with this code exists already',
          name: 'Name must not be longer than 100 characters',
        },
        message: 'Validation failed',
      })
      expect(s.locations).toEqual([])
    })

    it('keeps the server’s message of any other refusal, with no field errors', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(403, 'Forbidden', { message: 'Sysadmin only' }))
      const s = useAdminLocationsStore()
      const r = await s.create({ code: 'LINZ', name: 'Klinik Linz' })
      expect(r).toMatchObject({ ok: false, status: 403, fieldErrors: {}, message: 'Sysadmin only' })
    })

    it('falls back to a message with the status when the body has none', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(500, 'Server Error', null))
      const s = useAdminLocationsStore()
      const r = await s.create({ code: 'LINZ', name: 'Klinik Linz' })
      expect(r).toMatchObject({ ok: false, status: 500, message: 'The change could not be saved (HTTP 500).' })
    })

    it('reports an unreachable server as such', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiNetworkError('offline', null))
      const s = useAdminLocationsStore()
      const r = await s.create({ code: 'LINZ', name: 'Klinik Linz' })
      expect(r).toMatchObject({ ok: false, status: null, message: 'The server could not be reached.' })
    })
  })

  describe('update', () => {
    it('PUTs the input, replaces the row and the open detail with the answer', async () => {
      const before = detailOf(MUW, { users: [{ username: 'anna', displayName: 'Anna Beispiel', canManageSources: false }] })
      const after = { ...before, name: 'MUW Augenklinik Wien', city: 'Wien' }
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW]).mockResolvedValueOnce(before)
      vi.mocked(apiPut).mockResolvedValueOnce(after)
      const s = useAdminLocationsStore()
      await s.load()
      await s.select(1)
      const input = { name: 'MUW Augenklinik Wien', street: '', city: 'Wien', zip: '', country: 'AT' }
      const r = await s.update(1, input)
      expect(apiPut).toHaveBeenCalledTimes(1)
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1`, input)
      expect(r).toEqual({ ok: true, location: after })
      expect(s.locations.find((l) => l.id === 1)?.name).toBe('MUW Augenklinik Wien')
      expect(s.detail?.name).toBe('MUW Augenklinik Wien')
      expect(s.detail?.users).toHaveLength(1)
    })

    it('turns a 400 into field errors and changes nothing', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW])
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [{ field: 'name', message: 'A location with this name exists already' }],
      }))
      const s = useAdminLocationsStore()
      await s.load()
      const r = await s.update(1, { name: 'Klinik Graz' })
      expect(r).toMatchObject({ ok: false, status: 400, fieldErrors: { name: 'A location with this name exists already' } })
      expect(s.locations.find((l) => l.id === 1)?.name).toBe('MUW Augenklinik')
    })
  })

  describe('disable and restore', () => {
    it('POSTs /disable and shows the location as removed in the list and the detail', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW]).mockResolvedValueOnce(detailOf(GRAZ))
      vi.mocked(apiPost).mockResolvedValueOnce({ ...GRAZ, status: 'removed' })
      const s = useAdminLocationsStore()
      await s.load()
      await s.select(2)
      const r = await s.disable(2)
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenCalledWith(`${BASE}/2/disable`, {})
      expect(r.ok).toBe(true)
      expect(s.locations.find((l) => l.id === 2)?.status).toBe('removed')
      expect(s.detail?.status).toBe('removed')
      expect(apiGet).toHaveBeenCalledTimes(2)
    })

    it('hands the 409 refusal to the caller verbatim and keeps the location available', async () => {
      const refusal = 'The location cannot be disabled: 3 studies or sites still use it'
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW])
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: refusal }))
      const s = useAdminLocationsStore()
      await s.load()
      const r = await s.disable(1)
      expect(r).toMatchObject({ ok: false, status: 409, message: refusal })
      expect(s.locations.find((l) => l.id === 1)?.status).toBe('available')
    })

    it('POSTs /restore and shows the location as available again', async () => {
      const removed = { ...GRAZ, status: 'removed' as const }
      vi.mocked(apiGet).mockResolvedValueOnce([removed, MUW])
      vi.mocked(apiPost).mockResolvedValueOnce({ ...GRAZ })
      const s = useAdminLocationsStore()
      await s.load()
      const r = await s.restore(2)
      expect(apiPost).toHaveBeenCalledWith(`${BASE}/2/restore`, {})
      expect(r.ok).toBe(true)
      expect(s.locations.find((l) => l.id === 2)?.status).toBe('available')
    })
  })

  describe('assigning people', () => {
    const ANNA = { username: 'anna', displayName: 'Anna Beispiel', canManageSources: false }

    it('PUTs the encoded username and takes the answer as the detail and the user count', async () => {
      const assigned = detailOf({ ...MUW, userCount: 2 }, { users: [ANNA, { ...ANNA, username: 'b.k@muw', displayName: 'B K' }] })
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW]).mockResolvedValueOnce(detailOf(MUW, { users: [ANNA] }))
      vi.mocked(apiPut).mockResolvedValueOnce(assigned)
      const s = useAdminLocationsStore()
      await s.load()
      await s.select(1)
      const r = await s.assignUser(1, 'b.k@muw')
      expect(apiPut).toHaveBeenCalledTimes(1)
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1/users/b.k%40muw`, {})
      expect(r).toEqual({ ok: true, location: assigned })
      expect(s.detail?.users.map((u) => u.username)).toEqual(['anna', 'b.k@muw'])
      expect(s.locations.find((l) => l.id === 1)?.userCount).toBe(2)
    })

    it('hands the 404 for an unknown user to the caller', async () => {
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'No user "nobody"' }))
      const s = useAdminLocationsStore()
      const r = await s.assignUser(1, 'nobody')
      expect(r).toMatchObject({ ok: false, status: 404, message: 'No user "nobody"' })
    })

    it('DELETEs the assignment and takes the answer as the detail and the user count', async () => {
      const left = detailOf({ ...MUW, userCount: 0 }, { users: [] })
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW]).mockResolvedValueOnce(detailOf(MUW, { users: [ANNA] }))
      vi.mocked(apiDelete).mockResolvedValueOnce(left)
      const s = useAdminLocationsStore()
      await s.load()
      await s.select(1)
      const r = await s.unassignUser(1, 'anna')
      expect(apiDelete).toHaveBeenCalledTimes(1)
      expect(apiDelete).toHaveBeenCalledWith(`${BASE}/1/users/anna`)
      expect(r).toEqual({ ok: true, location: left })
      expect(s.detail?.users).toEqual([])
      expect(s.locations.find((l) => l.id === 1)?.userCount).toBe(0)
    })

    it('does not show an answer for a location that is not the open one as its detail', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce(detailOf(MUW))
      vi.mocked(apiPut).mockResolvedValueOnce(detailOf({ ...GRAZ, userCount: 1 }, { users: [ANNA] }))
      const s = useAdminLocationsStore()
      await s.select(1)
      await s.assignUser(2, 'anna')
      expect(s.detail?.id).toBe(1)
    })
  })

  describe('the Standort-Admin right', () => {
    const ANNA = { username: 'anna', displayName: 'Anna Beispiel', canManageSources: false }

    it('PUTs the flag alone to the encoded username and takes the answer as the open detail', async () => {
      const granted = detailOf(MUW, { users: [{ ...ANNA, canManageSources: true }] })
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW]).mockResolvedValueOnce(detailOf(MUW, { users: [ANNA] }))
      vi.mocked(apiPut).mockResolvedValueOnce(granted)
      const s = useAdminLocationsStore()
      await s.load()
      await s.select(1)
      const r = await s.setCanManageSources(1, 'anna', true)
      expect(apiPut).toHaveBeenCalledTimes(1)
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1/users/anna`, { canManageSources: true })
      expect(r).toEqual({ ok: true, location: granted })
      expect(s.detail?.users[0].canManageSources).toBe(true)
    })

    it('withdraws the right with false, and encodes a username with URL characters', async () => {
      const withdrawn = detailOf(MUW, { users: [{ ...ANNA, username: 'b.k@muw', canManageSources: false }] })
      vi.mocked(apiPut).mockResolvedValueOnce(withdrawn)
      const s = useAdminLocationsStore()
      const r = await s.setCanManageSources(1, 'b.k@muw', false)
      expect(apiPut).toHaveBeenCalledWith(`${BASE}/1/users/b.k%40muw`, { canManageSources: false })
      expect(r.ok).toBe(true)
    })

    it('hands a refusal to the caller verbatim and keeps the right as it was', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW]).mockResolvedValueOnce(detailOf(MUW, { users: [ANNA] }))
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'anna is not assigned to this location' }))
      const s = useAdminLocationsStore()
      await s.load()
      await s.select(1)
      const r = await s.setCanManageSources(1, 'anna', true)
      expect(r).toMatchObject({ ok: false, status: 404, message: 'anna is not assigned to this location' })
      expect(s.detail?.users[0].canManageSources).toBe(false)
    })
  })

  describe('refresh', () => {
    it('GETs the list again without showing the loading state, and takes the new counts', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW])
      const s = useAdminLocationsStore()
      await s.load()
      let answer!: (v: Location[]) => void
      vi.mocked(apiGet).mockImplementationOnce(() => new Promise<Location[]>((r) => { answer = r }))
      const pending = s.refresh()
      expect(s.isLoading).toBe(false)
      answer([GRAZ, { ...MUW, ingestItemCount: 352 }])
      await pending
      expect(apiGet).toHaveBeenLastCalledWith(BASE)
      expect(s.locations.find((l) => l.id === 1)?.ingestItemCount).toBe(352)
      expect(s.error).toBeNull()
    })

    it('leaves the list and the error alone when the refresh fails', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW])
      const s = useAdminLocationsStore()
      await s.load()
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, 'Server Error', null))
      await s.refresh()
      expect(s.locations.map((l) => l.id)).toEqual([2, 1])
      expect(s.error).toBeNull()
    })
  })

  it('reset empties everything', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([GRAZ, MUW]).mockResolvedValueOnce(detailOf(MUW))
    const s = useAdminLocationsStore()
    await s.load()
    await s.select(1)
    s.reset()
    expect(s.locations).toEqual([])
    expect(s.detail).toBeNull()
    expect(s.selectedId).toBeNull()
    expect(s.loaded).toBe(false)
    expect(s.error).toBeNull()
  })
})
