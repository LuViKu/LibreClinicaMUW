import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSubjectsStore } from '../subjects'
import type { Subject, SubjectDetail } from '@/types/subject'
import { ApiError } from '@/api/client'

/**
 * Phase E.6 subject-lifecycle store cases.
 *
 * Focused on the new actions (setShowRemoved, restoreSubject,
 * replaceGroups, add() with personId + groupAssignments). Mocks the
 * api/client module so tests don't depend on a live backend.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
  }
})

import { apiGet, apiPost, apiPut } from '@/api/client'

function makeSubject(over: Partial<Subject> = {}): Subject {
  return {
    id: over.id ?? 'M-001',
    secondaryId: over.secondaryId ?? null,
    siteOid: over.siteOid ?? 'TDS0004',
    siteLabel: over.siteLabel ?? 'München',
    gender: over.gender ?? 'F',
    yearOfBirth: over.yearOfBirth ?? 1962,
    groupLabel: over.groupLabel ?? null,
    enrolledOn: over.enrolledOn ?? '2020-10-06',
    signed: over.signed ?? false,
    openQueries: over.openQueries ?? 0,
    studyEye: over.studyEye ?? null,
    events: over.events ?? [],
    status: over.status ?? 'available',
    groupAssignments: over.groupAssignments ?? null,
  }
}

function makeDetail(over: Partial<SubjectDetail> = {}): SubjectDetail {
  return {
    id: over.id ?? 'M-001',
    secondaryId: over.secondaryId ?? null,
    siteOid: over.siteOid ?? 'TDS0004',
    siteLabel: over.siteLabel ?? 'München',
    studyOid: over.studyOid ?? 'TDS0004',
    studyName: over.studyName ?? 'Demo study',
    gender: over.gender ?? 'F',
    yearOfBirth: over.yearOfBirth ?? 1962,
    groupLabel: over.groupLabel ?? null,
    enrolledOn: over.enrolledOn ?? '2020-10-06',
    events: over.events ?? [],
    signed: over.signed ?? false,
    locked: over.locked ?? false,
    openQueries: over.openQueries ?? 0,
    studyEye: over.studyEye ?? null,
    screeningDate: over.screeningDate ?? null,
    status: over.status ?? 'available',
    groupAssignments: over.groupAssignments ?? [],
  }
}

describe('subjects store — Phase E.6 lifecycle', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiPut).mockReset()
  })

  describe('setShowRemoved', () => {
    it('flips the flag AND re-fetches with ?includeRemoved=true', async () => {
      vi.mocked(apiGet).mockResolvedValue([])
      const store = useSubjectsStore()
      expect(store.showRemoved).toBe(false)
      await store.setShowRemoved(true)
      expect(store.showRemoved).toBe(true)
      expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/subjects?includeRemoved=true')
    })

    it('does not re-fetch when the flag is already in the target state', async () => {
      vi.mocked(apiGet).mockResolvedValue([])
      const store = useSubjectsStore()
      await store.setShowRemoved(false)
      expect(apiGet).not.toHaveBeenCalled()
    })

    it('passes no query string when toggled back off', async () => {
      vi.mocked(apiGet).mockResolvedValue([])
      const store = useSubjectsStore()
      await store.setShowRemoved(true)
      vi.mocked(apiGet).mockClear()
      await store.setShowRemoved(false)
      expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/subjects')
    })
  })

  describe('restoreSubject', () => {
    it('POSTs to /restore and flips the in-memory row to available', async () => {
      const store = useSubjectsStore()
      store.rows = [makeSubject({ id: 'M-007', status: 'removed' })]
      vi.mocked(apiPost).mockResolvedValue({})

      const ok = await store.restoreSubject('M-007')

      expect(ok).toBe(true)
      expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/subjects/M-007/restore', {})
      expect(store.rows[0].status).toBe('available')
    })

    it('clears the selected detail when restoring the currently selected subject', async () => {
      const store = useSubjectsStore()
      store.selected = makeDetail({ id: 'M-007', status: 'removed' })
      vi.mocked(apiPost).mockResolvedValue({})

      await store.restoreSubject('M-007')

      expect(store.selected).toBeNull()
    })

    it('re-throws on 403 so the auth guard can react', async () => {
      const store = useSubjectsStore()
      const forbidden = new ApiError(403, 'forbidden', { message: 'nope' })
      vi.mocked(apiPost).mockRejectedValue(forbidden)
      await expect(store.restoreSubject('M-001')).rejects.toBe(forbidden)
      expect(store.error).toBe('nope')
    })

    it('surfaces the server message on a non-auth ApiError without throwing', async () => {
      const store = useSubjectsStore()
      vi.mocked(apiPost).mockRejectedValue(new ApiError(409, 'conflict', { message: 'not currently removed' }))
      const ok = await store.restoreSubject('M-001')
      expect(ok).toBe(false)
      expect(store.error).toBe('not currently removed')
    })
  })

  describe('replaceGroups', () => {
    it('PUTs to /groups + replaces the selected detail on 200', async () => {
      const store = useSubjectsStore()
      const before = makeDetail({ id: 'M-001', groupAssignments: [] })
      store.selected = before
      store.rows = [makeSubject({ id: 'M-001' })]

      const afterDetail = makeDetail({
        id: 'M-001',
        groupAssignments: [
          { groupClassId: 7, groupClassName: 'Arm', groupId: 12, groupName: 'A', subjectAssignment: 'REQUIRED' },
        ],
      })
      vi.mocked(apiPut).mockResolvedValue(afterDetail)

      const result = await store.replaceGroups('M-001', [{ groupClassId: 7, groupId: 12 }])

      expect(result.ok).toBe(true)
      if (result.ok) {
        expect(result.detail.groupAssignments?.length).toBe(1)
      }
      expect(apiPut).toHaveBeenCalledWith(
        '/pages/api/v1/subjects/M-001/groups',
        { assignments: [{ groupClassId: 7, groupId: 12 }] },
      )
      expect(store.selected?.groupAssignments?.length).toBe(1)
      expect(store.rows[0].groupAssignments?.length).toBe(1)
    })

    it('returns structured field errors on 400', async () => {
      const store = useSubjectsStore()
      const err = new ApiError(400, 'Validation failed', {
        message: 'Validation failed',
        errors: [{ field: 'assignments[7].groupId', message: 'REQUIRED — pick a group' }],
      })
      vi.mocked(apiPut).mockRejectedValue(err)

      const result = await store.replaceGroups('M-001', [{ groupClassId: 7, groupId: null }])

      expect(result.ok).toBe(false)
      if (!result.ok) {
        expect(result.fieldErrors['assignments[7].groupId']).toContain('REQUIRED')
      }
    })

    it('re-throws on 403', async () => {
      const store = useSubjectsStore()
      const forbidden = new ApiError(403, 'forbidden', { message: 'no role' })
      vi.mocked(apiPut).mockRejectedValue(forbidden)
      await expect(store.replaceGroups('M-001', [])).rejects.toBe(forbidden)
    })

    it('passes an empty assignments list verbatim — used for "remove all groups"', async () => {
      const store = useSubjectsStore()
      vi.mocked(apiPut).mockResolvedValue(makeDetail({ id: 'M-001', groupAssignments: [] }))
      await store.replaceGroups('M-001', [])
      expect(apiPut).toHaveBeenCalledWith(
        '/pages/api/v1/subjects/M-001/groups',
        { assignments: [] },
      )
    })
  })

  describe('add() — Person-ID + groupAssignments forwarding', () => {
    it('forwards personId + groupAssignments to the POST payload', async () => {
      const store = useSubjectsStore()
      vi.mocked(apiPost).mockResolvedValue(makeDetail({ id: 'M-099' }))
      await store.add({
        id: 'M-099',
        siteOid: 'TDS0004',
        siteLabel: 'München',
        gender: 'F',
        enrolledOn: '2026-06-01',
        personId: '  ABC123  ',
        groupAssignments: [{ groupClassId: 7, groupId: 12 }],
      })
      const [, payload] = vi.mocked(apiPost).mock.calls[0]
      const body = payload as Record<string, unknown>
      expect(body.personId).toBe('ABC123')
      expect(body.groupAssignments).toEqual([{ groupClassId: 7, groupId: 12 }])
    })

    it('sends null personId when the field is omitted', async () => {
      const store = useSubjectsStore()
      vi.mocked(apiPost).mockResolvedValue(makeDetail({ id: 'M-099' }))
      await store.add({
        id: 'M-099',
        siteOid: 'TDS0004',
        siteLabel: 'München',
        gender: 'F',
        enrolledOn: '2026-06-01',
      })
      const [, payload] = vi.mocked(apiPost).mock.calls[0]
      const body = payload as Record<string, unknown>
      expect(body.personId).toBeNull()
      expect(body.groupAssignments).toBeNull()
    })
  })
})
