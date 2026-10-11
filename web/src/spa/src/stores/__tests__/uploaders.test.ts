/**
 * uploaders store — the uploaders of the locations a session may manage,
 * and their tokens.
 *
 * Pins the wire contract (paths, verbs, bodies) and what the store makes of
 * the answers: the list is patched from each response rather than reloaded,
 * a 400 becomes per-field errors, any other refusal keeps the server's own
 * message (the 404 and 409 of this API are read by a person), a late list
 * answer is dropped, and the store never keeps a token: it is handed to
 * the caller, who shows it once.
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

import { apiGet, apiPost, apiPut, ApiError, ApiNetworkError } from '@/api/client'
import { i18n } from '@/i18n'
import { useUploadersStore } from '../uploaders'
import type { ManagedUploader } from '@/types/uploader'

const BASE = '/pages/api/v1/uploaders'
const TOKEN = 'mwu_' + 'A'.repeat(43)
const TOKEN_2 = 'mwu_' + 'B'.repeat(43)

function up(over: Partial<ManagedUploader> & Pick<ManagedUploader, 'id' | 'displayName'>): ManagedUploader {
  return {
    locationId: 1,
    locationCode: 'MUW-AUGEN',
    locationName: 'MUW Augenklinik',
    tokenHint: 'AbCdEfGh',
    status: 'active',
    createdBy: 'root',
    createdAt: '2026-10-12T08:00:00Z',
    lastUsedAt: null,
    revokedAt: null,
    instanceUid: null,
    lastHeartbeatAt: null,
    ...over,
  }
}

/** In the server's order: by location name, then display name. */
const CLARUS = up({ id: 11, displayName: 'Clarus-PC', lastUsedAt: '2026-10-12T09:30:00Z',
  instanceUid: 'inst-1', lastHeartbeatAt: '2026-10-12T09:31:00Z' })
const SPECTRALIS = up({ id: 12, displayName: 'Spectralis-PC' })
const GRAZ_OCT = up({ id: 21, displayName: 'OCT Graz', locationId: 2, locationCode: 'GRAZ', locationName: 'Klinik Graz' })

describe('useUploadersStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'en'
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPut).mockReset()
  })
  afterEach(() => { i18n.global.locale.value = 'de-AT' })

  it('starts empty and not loading', () => {
    const s = useUploadersStore()
    expect(s.uploaders).toEqual([])
    expect(s.isLoading).toBe(false)
    expect(s.error).toBeNull()
    expect(s.loaded).toBe(false)
  })

  describe('load', () => {
    it('GETs the list in the server order and marks it loaded', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ_OCT, CLARUS, SPECTRALIS])
      const s = useUploadersStore()
      await s.load()
      expect(apiGet).toHaveBeenCalledTimes(1)
      expect(apiGet).toHaveBeenCalledWith(BASE)
      expect(s.uploaders.map((u) => u.id)).toEqual([21, 11, 12])
      expect(s.loaded).toBe(true)
      expect(s.error).toBeNull()
      expect(s.isLoading).toBe(false)
    })

    it('keeps the server’s message when the list is refused', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(
        new ApiError(403, 'Forbidden', { message: 'You may not manage the uploaders of any location' }),
      )
      const s = useUploadersStore()
      await s.load()
      expect(s.uploaders).toEqual([])
      expect(s.error).toBe('You may not manage the uploaders of any location')
      expect(s.loaded).toBe(false)
      expect(s.isLoading).toBe(false)
    })

    it('names the status when the server sent no message', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(500, 'Server Error', null))
      const s = useUploadersStore()
      await s.load()
      expect(s.error).toBe('The uploaders could not be loaded (HTTP 500).')
    })

    it('says the server is unreachable when no answer came', async () => {
      vi.mocked(apiGet).mockRejectedValueOnce(new ApiNetworkError('offline', null))
      const s = useUploadersStore()
      await s.load()
      expect(s.error).toBe('The server could not be reached.')
    })

    it('drops a list answer that a later load has overtaken', async () => {
      let answerFirst!: (v: ManagedUploader[]) => void
      vi.mocked(apiGet)
        .mockImplementationOnce(() => new Promise<ManagedUploader[]>((r) => { answerFirst = r }))
        .mockResolvedValueOnce([CLARUS])
      const s = useUploadersStore()
      const first = s.load()
      await s.load()
      answerFirst([GRAZ_OCT, SPECTRALIS])
      await first
      expect(s.uploaders.map((u) => u.id)).toEqual([11])
      expect(s.isLoading).toBe(false)
    })

    it('a refresh keeps the rows on screen while it runs', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useUploadersStore()
      await s.load()
      let answer!: (v: ManagedUploader[]) => void
      vi.mocked(apiGet).mockImplementationOnce(() => new Promise<ManagedUploader[]>((r) => { answer = r }))
      const pending = s.load()
      expect(s.uploaders.map((u) => u.id)).toEqual([11])
      answer([{ ...CLARUS, lastHeartbeatAt: '2026-10-12T10:00:00Z' }])
      await pending
      expect(s.uploaders[0].lastHeartbeatAt).toBe('2026-10-12T10:00:00Z')
    })
  })

  describe('forLocation', () => {
    it('lists the uploaders of one location', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ_OCT, CLARUS, SPECTRALIS])
      const s = useUploadersStore()
      await s.load()
      expect(s.forLocation(1).map((u) => u.id)).toEqual([11, 12])
      expect(s.forLocation(2).map((u) => u.id)).toEqual([21])
      expect(s.forLocation(9)).toEqual([])
    })
  })

  describe('create', () => {
    it('POSTs the name to the location and hands back the uploader and its token', async () => {
      const issued = up({ id: 13, displayName: 'Optomed-PC', tokenHint: 'Zz9yXx8w' })
      vi.mocked(apiPost).mockResolvedValueOnce({ uploader: issued, token: TOKEN })
      const s = useUploadersStore()
      const result = await s.create(1, 'Optomed-PC')

      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/locations/1/uploaders', { displayName: 'Optomed-PC' })
      expect(result).toEqual({ ok: true, uploader: issued, token: TOKEN })
      expect(s.uploaders.map((u) => u.id)).toEqual([13])
    })

    it('puts the new uploader in order: by location name, then display name', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([GRAZ_OCT, CLARUS, SPECTRALIS])
      const s = useUploadersStore()
      await s.load()
      const issued = up({ id: 14, displayName: 'Baustelle' })
      vi.mocked(apiPost).mockResolvedValueOnce({ uploader: issued, token: TOKEN })
      await s.create(1, 'Baustelle')
      expect(s.uploaders.map((u) => u.id)).toEqual([21, 14, 11, 12])
    })

    it('never keeps the token: it is the caller’s to show once', async () => {
      vi.mocked(apiPost).mockResolvedValueOnce({ uploader: up({ id: 13, displayName: 'Optomed-PC' }), token: TOKEN })
      const s = useUploadersStore()
      await s.create(1, 'Optomed-PC')
      expect(JSON.stringify(s.$state)).not.toContain(TOKEN)
      expect(JSON.stringify(s.uploaders)).not.toContain('mwu_')
    })

    it('still hands back the token when the answer’s uploader lacks a location name', async () => {
      // The one chance to see a token must not depend on how complete the row is.
      const incomplete = { ...up({ id: 13, displayName: 'Optomed-PC' }), locationName: undefined } as unknown as ManagedUploader
      // A second row in the list, so that putting the new one in order really compares them.
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      vi.mocked(apiPost).mockResolvedValueOnce({ uploader: incomplete, token: TOKEN })
      const s = useUploadersStore()
      await s.load()
      const result = await s.create(1, 'Optomed-PC')
      expect(result).toMatchObject({ ok: true, token: TOKEN })
      expect(s.uploaders.map((u) => u.id)).toEqual([13, 11])
    })

    it('turns a 400 into one message per field', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [{ field: 'displayName', message: 'An uploader with this name already exists at this location' }],
      }))
      const s = useUploadersStore()
      const result = await s.create(1, 'Clarus-PC')
      expect(result).toMatchObject({
        ok: false,
        status: 400,
        fieldErrors: { displayName: 'An uploader with this name already exists at this location' },
      })
      expect(s.uploaders).toEqual([])
    })

    it('keeps the server’s text for a location that is no longer available (409)', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: 'Location is disabled' }))
      const s = useUploadersStore()
      const result = await s.create(1, 'Clarus-PC')
      expect(result).toMatchObject({ ok: false, status: 409, fieldErrors: {}, message: 'Location is disabled' })
    })

    it('keeps the server’s text for another location’s id (404)', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'No location with id 7' }))
      const s = useUploadersStore()
      const result = await s.create(7, 'Clarus-PC')
      expect(result).toMatchObject({ ok: false, status: 404, message: 'No location with id 7' })
    })

    it('says so when nothing came back, and does not change the list', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiNetworkError('offline', null))
      const s = useUploadersStore()
      const result = await s.create(1, 'Clarus-PC')
      expect(result).toMatchObject({ ok: false, status: null, message: 'The server could not be reached.' })
      expect(s.uploaders).toEqual([])
    })

    it('names the status when the refusal carries no text', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(500, 'Server Error', null))
      const s = useUploadersStore()
      const result = await s.create(1, 'Clarus-PC')
      expect(result).toMatchObject({ ok: false, status: 500, message: 'The change could not be saved (HTTP 500).' })
    })
  })

  describe('rename', () => {
    it('PUTs the name and patches the row, keeping the order', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS, SPECTRALIS])
      const s = useUploadersStore()
      await s.load()
      const renamed = { ...CLARUS, displayName: 'Zeiss Clarus' }
      vi.mocked(apiPut).mockResolvedValueOnce(renamed)
      const result = await s.rename(11, 'Zeiss Clarus')

      expect(apiPut).toHaveBeenCalledWith(`${BASE}/11`, { displayName: 'Zeiss Clarus' })
      expect(result).toEqual({ ok: true, uploader: renamed })
      // "Spectralis-PC" now sorts before "Zeiss Clarus".
      expect(s.uploaders.map((u) => u.displayName)).toEqual(['Spectralis-PC', 'Zeiss Clarus'])
    })

    it('turns a 400 into a field error and leaves the row alone', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useUploadersStore()
      await s.load()
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(400, 'Bad Request', {
        message: 'Validation failed',
        errors: [{ field: 'displayName', message: 'Must not be longer than 64 characters' }],
      }))
      const result = await s.rename(11, 'x'.repeat(65))
      expect(result).toMatchObject({ ok: false, fieldErrors: { displayName: 'Must not be longer than 64 characters' } })
      expect(s.uploaders[0].displayName).toBe('Clarus-PC')
    })

    it('keeps the server’s text for an uploader of another location (404)', async () => {
      vi.mocked(apiPut).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'No uploader with id 99' }))
      const s = useUploadersStore()
      const result = await s.rename(99, 'Nope')
      expect(result).toMatchObject({ ok: false, status: 404, message: 'No uploader with id 99' })
    })
  })

  describe('replaceToken', () => {
    it('POSTs to replace, hands back the new token and patches the row', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useUploadersStore()
      await s.load()
      const replaced = { ...CLARUS, tokenHint: 'NeWhInT1', lastUsedAt: null, lastHeartbeatAt: null }
      vi.mocked(apiPost).mockResolvedValueOnce({ uploader: replaced, token: TOKEN_2 })
      const result = await s.replaceToken(11)

      expect(apiPost).toHaveBeenCalledWith(`${BASE}/11/replace`, {})
      expect(result).toEqual({ ok: true, uploader: replaced, token: TOKEN_2 })
      expect(s.uploaders[0].tokenHint).toBe('NeWhInT1')
      expect(JSON.stringify(s.$state)).not.toContain(TOKEN_2)
    })

    it('re-activates a revoked uploader, as the server does', async () => {
      const revoked = up({ id: 12, displayName: 'Spectralis-PC', status: 'revoked', revokedAt: '2026-10-12T07:00:00Z' })
      vi.mocked(apiGet).mockResolvedValueOnce([revoked])
      const s = useUploadersStore()
      await s.load()
      vi.mocked(apiPost).mockResolvedValueOnce({
        uploader: { ...revoked, status: 'active', revokedAt: null, tokenHint: 'FreSh123' },
        token: TOKEN_2,
      })
      await s.replaceToken(12)
      expect(s.uploaders[0].status).toBe('active')
      expect(s.uploaders[0].revokedAt).toBeNull()
    })

    it('keeps the server’s text when the uploader is not there (404) and changes nothing', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useUploadersStore()
      await s.load()
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'No uploader with id 11' }))
      const result = await s.replaceToken(11)
      expect(result).toMatchObject({ ok: false, status: 404, message: 'No uploader with id 11' })
      expect(s.uploaders[0].tokenHint).toBe('AbCdEfGh')
    })

    it('keeps the server’s text when the location is gone (409)', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(409, 'Conflict', { message: 'The location was removed' }))
      const s = useUploadersStore()
      const result = await s.replaceToken(11)
      expect(result).toMatchObject({ ok: false, status: 409, message: 'The location was removed' })
    })
  })

  describe('revoke', () => {
    it('POSTs to revoke and patches the row', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS, SPECTRALIS])
      const s = useUploadersStore()
      await s.load()
      const revoked = { ...CLARUS, status: 'revoked' as const, revokedAt: '2026-10-12T10:00:00Z' }
      vi.mocked(apiPost).mockResolvedValueOnce(revoked)
      const result = await s.revoke(11)

      expect(apiPost).toHaveBeenCalledWith(`${BASE}/11/revoke`, {})
      expect(result).toEqual({ ok: true, uploader: revoked })
      expect(s.uploaders.find((u) => u.id === 11)?.status).toBe('revoked')
      expect(s.uploaders.find((u) => u.id === 12)?.status).toBe('active')
    })

    it('keeps the server’s text for a refusal and changes nothing', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
      const s = useUploadersStore()
      await s.load()
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(404, 'Not Found', { message: 'No uploader with id 11' }))
      const result = await s.revoke(11)
      expect(result).toMatchObject({ ok: false, status: 404, message: 'No uploader with id 11' })
      expect(s.uploaders[0].status).toBe('active')
    })
  })

  it('reset forgets everything', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce([CLARUS])
    const s = useUploadersStore()
    await s.load()
    s.reset()
    expect(s.uploaders).toEqual([])
    expect(s.loaded).toBe(false)
    expect(s.error).toBeNull()
  })
})
