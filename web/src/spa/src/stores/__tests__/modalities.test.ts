/**
 * Phase E.6 — modality admin store spec.
 *
 * Pins the wire contract: GET / POST / PUT / DELETE all under
 * `/pages/api/v1/modalities`. Each write action MUST re-run `load()`
 * on success so the local list reflects backend-side ordinal
 * recomputation.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/client'
import type { Modality } from '@/types/modality'

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

import { apiGet, apiPost, apiPut, apiDelete } from '@/api/client'
import { useModalitiesStore } from '../modalities'

const FIXTURE: Modality[] = [
  {
    modalityId: 1,
    code: 'VA',
    labelEn: 'Visual acuity',
    labelDe: 'Visus',
    ordinal: 10,
    itemOidOd: 'I_VA_OD',
    itemOidOs: 'I_VA_OS',
    dataType: 'numeric',
    unit: 'logMAR',
  },
  {
    modalityId: 2,
    code: 'IOP',
    labelEn: 'Intraocular pressure',
    labelDe: 'Augeninnendruck',
    ordinal: 20,
    itemOidOd: 'I_IOP_OD',
    itemOidOs: 'I_IOP_OS',
    dataType: 'numeric',
    unit: 'mmHg',
  },
]

describe('useModalitiesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPut).mockReset()
    vi.mocked(apiDelete).mockReset()
  })

  it('starts empty + not loading', () => {
    const store = useModalitiesStore()
    expect(store.list).toEqual([])
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('load() GETs /pages/api/v1/modalities + populates list', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE)
    const store = useModalitiesStore()

    await store.load()

    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/modalities')
    expect(store.list).toEqual(FIXTURE)
    expect(store.error).toBeNull()
  })

  it('load() surfaces ApiError messages in store.error', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(
      new ApiError(500, 'boom', { message: 'database down' }),
    )
    const store = useModalitiesStore()

    await store.load()

    expect(store.list).toEqual([])
    expect(store.error).toBe('database down')
  })

  it('load() rethrows 401/403 so the router guard can react', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(
      new ApiError(401, 'unauth', { message: 'not authenticated' }),
    )
    const store = useModalitiesStore()

    await expect(store.load()).rejects.toBeInstanceOf(ApiError)
  })

  it('create() POSTs body + re-runs load on success', async () => {
    const created: Modality = {
      modalityId: 3,
      code: 'OCT',
      labelEn: 'OCT thickness',
      labelDe: 'OCT-Dicke',
      ordinal: 30,
      itemOidOd: 'I_OCT_OD',
      itemOidOs: null,
      dataType: 'numeric',
      unit: 'μm',
    }
    vi.mocked(apiPost).mockResolvedValueOnce(created)
    vi.mocked(apiGet).mockResolvedValueOnce([...FIXTURE, created])
    const store = useModalitiesStore()

    const result = await store.create({
      code: 'OCT',
      labelEn: 'OCT thickness',
      labelDe: 'OCT-Dicke',
      ordinal: 30,
      itemOidOd: 'I_OCT_OD',
      dataType: 'numeric',
      unit: 'μm',
    })

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/modalities', {
      code: 'OCT',
      labelEn: 'OCT thickness',
      labelDe: 'OCT-Dicke',
      ordinal: 30,
      itemOidOd: 'I_OCT_OD',
      dataType: 'numeric',
      unit: 'μm',
    })
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/modalities')
    expect(result).toEqual(created)
    expect(store.list.length).toBe(3)
  })

  it('create() propagates 409 (duplicate code)', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(409, 'conflict', { message: 'Code already exists' }),
    )
    const store = useModalitiesStore()

    await expect(
      store.create({
        code: 'VA',
        labelEn: 'Visual acuity',
        labelDe: 'Visus',
        ordinal: 1,
        itemOidOd: 'I_VA_OD',
        dataType: 'numeric',
      }),
    ).rejects.toMatchObject({ status: 409 })

    // No re-load should be fired on failure.
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('create() propagates 400 (unknown OID / missing both OIDs)', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(
      new ApiError(400, 'bad', { message: 'unknown OID I_NOPE' }),
    )
    const store = useModalitiesStore()

    await expect(
      store.create({
        code: 'NEW',
        labelEn: 'New',
        labelDe: 'Neu',
        ordinal: 99,
        itemOidOd: 'I_NOPE',
        dataType: 'numeric',
      }),
    ).rejects.toMatchObject({ status: 400 })
  })

  it('update(id, body) PUTs /pages/api/v1/modalities/{id} + re-runs load', async () => {
    const updated: Modality = { ...FIXTURE[0], labelEn: 'BCVA' }
    vi.mocked(apiPut).mockResolvedValueOnce(updated)
    vi.mocked(apiGet).mockResolvedValueOnce([updated, FIXTURE[1]])
    const store = useModalitiesStore()

    const result = await store.update(1, {
      labelEn: 'BCVA',
      labelDe: 'Visus',
      ordinal: 10,
      itemOidOd: 'I_VA_OD',
      itemOidOs: 'I_VA_OS',
      dataType: 'numeric',
      unit: 'logMAR',
    })

    expect(apiPut).toHaveBeenCalledWith('/pages/api/v1/modalities/1', {
      labelEn: 'BCVA',
      labelDe: 'Visus',
      ordinal: 10,
      itemOidOd: 'I_VA_OD',
      itemOidOs: 'I_VA_OS',
      dataType: 'numeric',
      unit: 'logMAR',
    })
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/modalities')
    expect(result).toEqual(updated)
    expect(store.list[0].labelEn).toBe('BCVA')
  })

  it('remove(id) DELETEs /pages/api/v1/modalities/{id} + re-runs load', async () => {
    vi.mocked(apiDelete).mockResolvedValueOnce(undefined as never)
    vi.mocked(apiGet).mockResolvedValueOnce([FIXTURE[1]])
    const store = useModalitiesStore()

    await store.remove(1)

    expect(apiDelete).toHaveBeenCalledWith('/pages/api/v1/modalities/1')
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/modalities')
    expect(store.list.length).toBe(1)
    expect(store.list[0].modalityId).toBe(2)
  })

  it('reset() clears local state', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(FIXTURE)
    const store = useModalitiesStore()
    await store.load()
    expect(store.list.length).toBe(2)

    store.reset()

    expect(store.list).toEqual([])
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })
})
