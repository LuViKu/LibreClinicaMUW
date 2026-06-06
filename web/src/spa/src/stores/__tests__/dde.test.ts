import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDdeStore } from '../dde'
import { ApiError, ApiNetworkError } from '@/api/client'
import type {
  DdeCommitResponse,
  DdeConflicts,
  DdePassResponse,
  DdeResolveResponse,
} from '@/types/dde'

/**
 * Phase E.6 dde — Vitest coverage for the dde store's load / commit /
 * resolve actions. Mocks the apiGet / apiPost calls so we can pin
 * URL shape + body shape + error mapping without spinning the SPA.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

import { apiGet, apiPost } from '@/api/client'

const PASS_RESP: DdePassResponse = {
  eventCrfOid: '17',
  pass: '2',
  idePass1ClerkId: 5,
  mismatchCount: 0,
}

const CONFLICT_RESP: DdeConflicts = {
  eventCrfOid: '17',
  subjectId: 'M-001',
  crfName: 'Demographics',
  items: [
    {
      itemOid: 'I_HEIGHT_CM',
      label: 'Height (cm)',
      ideValue: '175',
      ddeValue: '178',
      resolved: false,
      winner: null,
    },
    {
      itemOid: 'I_WEIGHT_KG',
      label: 'Weight (kg)',
      ideValue: '72',
      ddeValue: '72',
      resolved: false,
      winner: null,
    },
  ],
}

const COMMIT_RESP: DdeCommitResponse = {
  eventCrfOid: '17',
  mismatchCount: 1,
  status: 'dde-conflicts',
  lastSavedAt: '2026-06-08T10:00:00Z',
}

const RESOLVE_RESP: DdeResolveResponse = { nextItem: '/event-crfs/17/dde-reconcile' }

describe('useDdeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loadPass GETs /dde-pass and stores the response', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(PASS_RESP)
    const store = useDdeStore()
    const result = await store.loadPass('17')
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/eventCrfs/17/dde-pass')
    expect(result).toEqual(PASS_RESP)
    expect(store.pass).toEqual(PASS_RESP)
    expect(store.error).toBeNull()
  })

  it('loadPass surfaces a German error message on network failure', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiNetworkError('boom'))
    const store = useDdeStore()
    const result = await store.loadPass('17')
    expect(result).toBeNull()
    expect(store.error).toContain('Backend nicht erreichbar')
  })

  it('loadPass propagates 401 so the router can react', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiError(401, 'no auth'))
    const store = useDdeStore()
    await expect(store.loadPass('17')).rejects.toBeInstanceOf(ApiError)
  })

  it('commitPass2 POSTs the values map to /dde-commit', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(COMMIT_RESP)
    const store = useDdeStore()
    const values = { I_HEIGHT_CM: '178', I_WEIGHT_KG: '72' }
    const result = await store.commitPass2('17', values)
    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/eventCrfs/17/dde-commit',
      { values },
    )
    expect(result).toEqual(COMMIT_RESP)
  })

  it('commitPass2 reports backend ApiError message verbatim', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(409, 'conflict', { message: 'event_crf 17 is not DDE-enabled' }),
    )
    const store = useDdeStore()
    const result = await store.commitPass2('17', {})
    expect(result).toBeNull()
    expect(store.error).toContain('not DDE-enabled')
  })

  it('loadConflicts GETs /dde-conflicts and exposes hasOpenConflicts', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(structuredClone(CONFLICT_RESP))
    const store = useDdeStore()
    await store.loadConflicts('17')
    expect(store.conflicts?.items).toHaveLength(2)
    expect(store.hasOpenConflicts).toBe(true)
  })

  it('resolve POSTs winner+RFC body and flips the row to resolved locally', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(structuredClone(CONFLICT_RESP))
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(RESOLVE_RESP)
    const store = useDdeStore()
    await store.loadConflicts('17')

    const result = await store.resolve('17', 'I_HEIGHT_CM', {
      winner: 'dde',
      reasonForChange: 'Paper original matches DDE',
    })

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/eventCrfs/17/dde-conflicts/I_HEIGHT_CM/resolve',
      { winner: 'dde', reasonForChange: 'Paper original matches DDE' },
    )
    expect(result).toEqual(RESOLVE_RESP)

    const row = store.conflicts!.items.find((i) => i.itemOid === 'I_HEIGHT_CM')!
    expect(row.resolved).toBe(true)
    expect(row.winner).toBe('dde')
  })

  it('resolve does not flip rows when the request fails', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(structuredClone(CONFLICT_RESP))
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(400, 'bad', { message: 'winner must be one of: ide, dde, manual' }),
    )
    const store = useDdeStore()
    await store.loadConflicts('17')
    await store.resolve('17', 'I_HEIGHT_CM', {
      winner: 'dde',
      reasonForChange: 'r',
    })
    const row = store.conflicts!.items.find((i) => i.itemOid === 'I_HEIGHT_CM')!
    expect(row.resolved).toBe(false)
    expect(store.error).toContain('winner must be one of')
  })
})
