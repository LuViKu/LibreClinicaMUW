import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useStudyParametersStore } from '../studyParameters'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { StudyParameters } from '@/types/studyParameters'

/**
 * Phase E.6 study-params — Vitest coverage for the {@code useStudyParametersStore}
 * load/update actions.
 *
 * Focus on the wire-contract guarantees the controller's MockMvc IT
 * also pins:
 *   - load round-trips the 19-field DTO into {@code current};
 *   - update sends the partial patch verbatim;
 *   - 400 validation envelope flattens into {@code fieldErrors};
 *   - 401/403 re-throw so the view's auth-redirect guard runs;
 *   - 409 LOCKED surfaces as a non-throwing {@code {ok:false}} so
 *     the view can show an inline status banner without crashing.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPut: vi.fn(),
  }
})

import { apiGet, apiPut } from '@/api/client'

const FIXTURE: StudyParameters = {
  studyOid: 'S_DEMO',
  subjectIdGeneration: 'manual',
  subjectIdPrefixSuffix: 'true',
  subjectPersonIdRequired: 'required',
  personIdShownOnCRF: 'false',
  collectDob: '1',
  genderRequired: 'true',
  eventLocationRequired: 'not_used',
  discrepancyManagement: 'true',
  interviewerNameRequired: 'not_used',
  interviewerNameDefault: 'blank',
  interviewerNameEditable: 'true',
  interviewDateRequired: 'not_used',
  interviewDateDefault: 'blank',
  interviewDateEditable: 'true',
  secondaryLabelViewable: 'false',
  adminForcedReasonForChange: 'true',
  participantPortal: 'disabled',
  randomization: 'disabled',
}

describe('useStudyParametersStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('load', () => {
    it('GETs /pages/api/v1/studies/{oid}/parameters and stores the DTO', async () => {
      ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(FIXTURE)
      const store = useStudyParametersStore()
      await store.load('S_DEMO')

      expect(apiGet).toHaveBeenCalledWith(
        '/pages/api/v1/studies/S_DEMO/parameters',
      )
      expect(store.current).toEqual(FIXTURE)
      expect(store.error).toBeNull()
    })

    it('URL-encodes OIDs with reserved characters', async () => {
      ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(FIXTURE)
      const store = useStudyParametersStore()
      await store.load('S/DEMO 01')
      expect(apiGet).toHaveBeenCalledWith(
        '/pages/api/v1/studies/S%2FDEMO%2001/parameters',
      )
    })

    it('refuses an empty OID with an inline error and no network call', async () => {
      const store = useStudyParametersStore()
      await store.load('')
      expect(apiGet).not.toHaveBeenCalled()
      expect(store.current).toBeNull()
      expect(store.error).toMatch(/required/i)
    })

    it('re-throws 401 so the view auth-redirect guard runs', async () => {
      ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new ApiError(401, 'Not authenticated'),
      )
      const store = useStudyParametersStore()
      await expect(store.load('S_DEMO')).rejects.toBeInstanceOf(ApiError)
      expect(store.current).toBeNull()
    })

    it('captures a non-throwing error on 404 missing OID', async () => {
      ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new ApiError(404, 'No study', { message: 'No study with oid' }),
      )
      const store = useStudyParametersStore()
      await store.load('S_DEMO')
      expect(store.current).toBeNull()
      expect(store.error).toMatch(/No study/)
    })

    it('captures a network error without throwing', async () => {
      ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new ApiNetworkError('boom', new Error()),
      )
      const store = useStudyParametersStore()
      await store.load('S_DEMO')
      expect(store.current).toBeNull()
      expect(store.error).toMatch(/Backend nicht erreichbar/)
    })
  })

  describe('update', () => {
    it('PUTs the partial patch verbatim and stores the refreshed DTO', async () => {
      const refreshed = { ...FIXTURE, collectDob: '3' }
      ;(apiPut as ReturnType<typeof vi.fn>).mockResolvedValueOnce(refreshed)
      const store = useStudyParametersStore()
      const result = await store.update('S_DEMO', { collectDob: '3' })

      expect(apiPut).toHaveBeenCalledWith(
        '/pages/api/v1/studies/S_DEMO/parameters',
        { collectDob: '3' },
      )
      expect(result.ok).toBe(true)
      if (result.ok) expect(result.data).toEqual(refreshed)
      expect(store.current).toEqual(refreshed)
    })

    it('flattens a 400 fieldErrors envelope onto {@code fieldErrors[handle]}', async () => {
      ;(apiPut as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new ApiError(400, 'Validation failed', {
          message: 'Validation failed',
          fieldErrors: [
            { field: 'collectDob', message: 'collectDob must be one of [1, 2, 3]' },
            { field: 'subjectIdGeneration', message: 'subjectIdGeneration must be one of …' },
          ],
        }),
      )
      const store = useStudyParametersStore()
      const result = await store.update('S_DEMO', { collectDob: '9' })

      expect(result.ok).toBe(false)
      if (!result.ok) {
        expect(result.fieldErrors.collectDob).toMatch(/one of/)
        expect(result.fieldErrors.subjectIdGeneration).toMatch(/one of/)
      }
      expect(store.fieldErrors.collectDob).toMatch(/one of/)
    })

    it('re-throws 401 so the auth-redirect guard runs', async () => {
      ;(apiPut as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new ApiError(401, 'Not authenticated'),
      )
      const store = useStudyParametersStore()
      await expect(store.update('S_DEMO', { collectDob: '1' })).rejects.toBeInstanceOf(
        ApiError,
      )
    })

    it('re-throws 403 forbidden so the view can show "no permission" UI', async () => {
      ;(apiPut as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new ApiError(403, 'Forbidden'),
      )
      const store = useStudyParametersStore()
      await expect(store.update('S_DEMO', { collectDob: '1' })).rejects.toBeInstanceOf(
        ApiError,
      )
    })

    it('returns {ok:false} with an inline message on 409 LOCKED', async () => {
      ;(apiPut as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new ApiError(409, 'Conflict', {
          message: 'Study is locked — parameter writes are refused',
        }),
      )
      const store = useStudyParametersStore()
      const result = await store.update('S_DEMO', { collectDob: '1' })
      expect(result.ok).toBe(false)
      if (!result.ok) expect(result.message).toMatch(/locked/i)
      expect(store.error).toMatch(/locked/i)
    })

    it('refuses an empty OID without firing a network call', async () => {
      const store = useStudyParametersStore()
      const result = await store.update('', { collectDob: '1' })
      expect(apiPut).not.toHaveBeenCalled()
      expect(result.ok).toBe(false)
    })
  })

  describe('reset', () => {
    it('clears all in-memory state', async () => {
      ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(FIXTURE)
      const store = useStudyParametersStore()
      await store.load('S_DEMO')
      store.fieldErrors = { collectDob: 'stale' }
      store.error = 'stale'
      store.reset()
      expect(store.current).toBeNull()
      expect(store.error).toBeNull()
      expect(store.fieldErrors).toEqual({})
    })
  })
})
