import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCrfLibraryStore } from '../crfLibrary'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { Crf, CrfVersion, MigrateVersionResult, VersionUsageReport } from '@/types/crfLibrary'

/**
 * Phase E.6 crf-library — Vitest coverage for the new version-
 * lifecycle store actions (lock, unlock, restore, hardRemove,
 * downloadVersionXls, migrateVersion).
 *
 * Stubs `@/api/client` so we can assert call shape + reproduce error
 * branches without a live backend. `fetch` is stubbed globally for the
 * XLS-download path because it bypasses apiGet (multipart-style raw
 * Response handling).
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiDelete: vi.fn(),
    apiPut: vi.fn(),
  }
})

import { apiDelete, apiGet, apiPost } from '@/api/client'

const V1: CrfVersion = {
  oid: 'F_DEMOS_V1',
  name: 'v1.0',
  description: 'Initial',
  revisionNotes: '',
  status: 'available',
  uploadedAt: '2026-06-01T00:00:00Z',
}
const V2: CrfVersion = { ...V1, oid: 'F_DEMOS_V2', name: 'v2.0' }
const DEMOS: Crf = {
  oid: 'F_DEMOS',
  name: 'Demographics',
  description: '',
  status: 'available',
  versions: [V1, V2],
}

function seed(store: ReturnType<typeof useCrfLibraryStore>, ...crfs: Crf[]) {
  store.crfs.splice(0, store.crfs.length, ...crfs)
}

describe('useCrfLibraryStore — Phase E.6 lifecycle actions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiDelete).mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('lockVersion', () => {
    it('patches the version in-place on success and returns true', async () => {
      const store = useCrfLibraryStore()
      seed(store, DEMOS)
      const locked: CrfVersion = { ...V1, status: 'locked' }
      vi.mocked(apiPost).mockResolvedValue(locked)

      const ok = await store.lockVersion('F_DEMOS', 'F_DEMOS_V1')

      expect(ok).toBe(true)
      expect(apiPost).toHaveBeenCalledWith(
        '/pages/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/lock',
        {},
      )
      expect(store.crfs[0]!.versions[0]!.status).toBe('locked')
      // V2 is untouched
      expect(store.crfs[0]!.versions[1]!.status).toBe('available')
    })

    it('surfaces network errors via store.error and returns false', async () => {
      const store = useCrfLibraryStore()
      seed(store, DEMOS)
      vi.mocked(apiPost).mockRejectedValue(new ApiNetworkError('offline'))

      const ok = await store.lockVersion('F_DEMOS', 'F_DEMOS_V1')

      expect(ok).toBe(false)
      expect(store.error).toMatch(/Backend nicht erreichbar/i)
      // local state stays consistent on failure
      expect(store.crfs[0]!.versions[0]!.status).toBe('available')
    })
  })

  describe('unlockVersion', () => {
    it('patches the version in-place on success', async () => {
      const store = useCrfLibraryStore()
      seed(store, { ...DEMOS, versions: [{ ...V1, status: 'locked' }, V2] })
      const unlocked: CrfVersion = { ...V1, status: 'available' }
      vi.mocked(apiPost).mockResolvedValue(unlocked)

      const ok = await store.unlockVersion('F_DEMOS', 'F_DEMOS_V1')

      expect(ok).toBe(true)
      expect(apiPost).toHaveBeenCalledWith(
        '/pages/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/unlock',
        {},
      )
      expect(store.crfs[0]!.versions[0]!.status).toBe('available')
    })
  })

  describe('restoreVersion', () => {
    it('patches the version status from removed → available on success', async () => {
      const store = useCrfLibraryStore()
      seed(store, { ...DEMOS, versions: [{ ...V1, status: 'removed' }, V2] })
      const restored: CrfVersion = { ...V1, status: 'available' }
      vi.mocked(apiPost).mockResolvedValue(restored)

      const ok = await store.restoreVersion('F_DEMOS', 'F_DEMOS_V1')

      expect(ok).toBe(true)
      expect(apiPost).toHaveBeenCalledWith(
        '/pages/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/restore',
        {},
      )
      expect(store.crfs[0]!.versions[0]!.status).toBe('available')
    })
  })

  describe('hardRemoveVersion', () => {
    it('drops the version from local state on 204', async () => {
      const store = useCrfLibraryStore()
      seed(store, DEMOS)
      vi.mocked(apiDelete).mockResolvedValue(undefined)

      const result = await store.hardRemoveVersion('F_DEMOS', 'F_DEMOS_V1')

      expect(result).toEqual({ ok: true })
      expect(store.crfs[0]!.versions.map((v) => v.oid)).toEqual(['F_DEMOS_V2'])
    })

    it('returns ok:false + blocker payload on 409', async () => {
      const store = useCrfLibraryStore()
      seed(store, DEMOS)
      const report: VersionUsageReport = {
        crfOid: 'F_DEMOS',
        versionOid: 'F_DEMOS_V1',
        versionName: 'v1.0',
        blockingEventDefinitions: [
          { studyOid: 'S_DEFAULTS1', sedOid: 'SE_VISIT1', sedName: 'Baseline Visit' },
        ],
        eventCrfCount: 12,
        sampleSubjectLabels: ['studySubject#42'],
      }
      vi.mocked(apiDelete).mockRejectedValue(new ApiError(409, 'Conflict', report))

      const result = await store.hardRemoveVersion('F_DEMOS', 'F_DEMOS_V1')

      expect(result.ok).toBe(false)
      if (result.ok === false && 'blocker' in result) {
        expect(result.blocker.blockingEventDefinitions[0]!.sedOid).toBe('SE_VISIT1')
        expect(result.blocker.eventCrfCount).toBe(12)
      } else {
        expect.fail('expected blocker variant')
      }
      // Local state still has the version — 409 doesn't drop it.
      expect(store.crfs[0]!.versions.length).toBe(2)
    })

    it('rethrows 403 (auth failure) instead of swallowing', async () => {
      const store = useCrfLibraryStore()
      seed(store, DEMOS)
      vi.mocked(apiDelete).mockRejectedValue(new ApiError(403, 'Forbidden', { message: 'sysadmin-only' }))

      await expect(store.hardRemoveVersion('F_DEMOS', 'F_DEMOS_V1')).rejects.toBeInstanceOf(ApiError)
    })
  })

  describe('migrateVersion', () => {
    it('issues a dry-run POST and returns the plan', async () => {
      const store = useCrfLibraryStore()
      const planResponse: MigrateVersionResult = {
        crfOid: 'F_DEMOS',
        fromVersionOid: 'F_DEMOS_V1',
        toVersionOid: 'F_DEMOS_V2',
        dryRun: true,
        totalMigrated: 2,
        perSed: [
          { studyOid: 'S_X', sedOid: 'SE_A', sedName: 'A', migrated: true, reasonSkipped: null },
          { studyOid: 'S_X', sedOid: 'SE_B', sedName: 'B', migrated: true, reasonSkipped: null },
        ],
      }
      vi.mocked(apiPost).mockResolvedValue(planResponse)

      const result = await store.migrateVersion('F_DEMOS', 'F_DEMOS_V1', 'F_DEMOS_V2', { dryRun: true })

      expect(result.ok).toBe(true)
      if (result.ok) {
        expect(result.result.dryRun).toBe(true)
        expect(result.result.totalMigrated).toBe(2)
      }
      expect(apiPost).toHaveBeenCalledWith(
        '/pages/api/v1/crfs/F_DEMOS/versions/F_DEMOS_V1/migrate-to/F_DEMOS_V2',
        { dryRun: true },
      )
    })

    it('relays the sedOids filter through to the request body', async () => {
      const store = useCrfLibraryStore()
      vi.mocked(apiPost).mockResolvedValue({
        crfOid: 'F_DEMOS', fromVersionOid: 'V1', toVersionOid: 'V2',
        dryRun: false, totalMigrated: 1, perSed: [],
      })

      await store.migrateVersion('F_DEMOS', 'V1', 'V2', { dryRun: false, sedOids: ['SE_A'] })

      expect(apiPost).toHaveBeenCalledWith(
        '/pages/api/v1/crfs/F_DEMOS/versions/V1/migrate-to/V2',
        { dryRun: false, sedOids: ['SE_A'] },
      )
    })

    it('returns ok:false + message on validation error', async () => {
      const store = useCrfLibraryStore()
      vi.mocked(apiPost).mockRejectedValue(new ApiError(400, 'Bad Request',
        { message: 'from and to version OIDs must differ' }))

      const result = await store.migrateVersion('F_DEMOS', 'V1', 'V1', { dryRun: true })

      expect(result.ok).toBe(false)
      if (!result.ok) {
        expect(result.message).toMatch(/must differ/)
      }
    })
  })

  describe('downloadVersionXls', () => {
    it('returns blob + filename when content-disposition is set', async () => {
      const store = useCrfLibraryStore()
      const blob = new Blob(['xls-bytes'], { type: 'application/vnd.ms-excel' })
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        headers: new Headers({ 'content-disposition': 'attachment; filename="demographics.xls"' }),
        blob: () => Promise.resolve(blob),
      }))

      const result = await store.downloadVersionXls('F_DEMOS', 'F_DEMOS_V1')

      expect(result.ok).toBe(true)
      if (result.ok) {
        expect(result.filename).toBe('demographics.xls')
        expect(result.blob).toBe(blob)
      }
    })

    it('falls back to synthesized filename when header is absent', async () => {
      const store = useCrfLibraryStore()
      const blob = new Blob(['xls-bytes'])
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        headers: new Headers(),
        blob: () => Promise.resolve(blob),
      }))

      const result = await store.downloadVersionXls('F_DEMOS', 'F_DEMOS_V1')

      expect(result.ok).toBe(true)
      if (result.ok) {
        expect(result.filename).toBe('F_DEMOS-F_DEMOS_V1.xls')
      }
    })

    it('surfaces 404 body message on missing-file backend response', async () => {
      const store = useCrfLibraryStore()
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        json: () => Promise.resolve({ message: 'This version was authored in-app' }),
      }))

      const result = await store.downloadVersionXls('F_DEMOS', 'F_DEMOS_V1')

      expect(result.ok).toBe(false)
      if (!result.ok) {
        expect(result.message).toMatch(/authored in-app/)
      }
    })
  })
})
