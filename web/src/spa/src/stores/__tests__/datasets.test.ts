import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDatasetsStore } from '../datasets'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { ArchivedFileDto, DatasetDto, ExportTriggerResponse } from '@/types/export'

/**
 * Phase E.6 — Vitest coverage for the data-export store. Pins:
 *   - `load(studyOid)` hits /pages/api/v1/studies/{oid}/datasets and stores rows.
 *   - `loadFiles(studyOid, datasetId)` caches per-dataset file lists.
 *   - `triggerExport(...)` POSTs the right body + bumps fileCount + lastRunAt.
 *   - `quickOdm(studyOid)` POSTs to the :quick-odm verb endpoint.
 *   - `reset()` clears every piece of state (covers the auth.pickStudy hook).
 *   - 401/403 propagate; network failures surface in `error`.
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

const STUDY_OID = 'S_DEFAULT'

const ROW_A: DatasetDto = {
  oid: '11',
  id: 11,
  name: 'Alpha cohort',
  description: 'All visits, all CRFs',
  ownerName: 'datamanager',
  dateCreated: '2026-05-01T08:00:00Z',
  lastRunAt: '2026-05-02T09:00:00Z',
  fileCount: 2,
}

const ROW_B: DatasetDto = {
  oid: '12',
  id: 12,
  name: 'Beta safety subset',
  description: null,
  ownerName: 'monitor_demo',
  dateCreated: '2026-05-03T08:00:00Z',
  lastRunAt: null,
  fileCount: 0,
}

const FILE_FOR_11: ArchivedFileDto = {
  id: 501,
  name: 'Alpha_cohort_odm.xml.zip',
  formatName: 'ODM',
  sizeBytes: 12345,
  generatedAt: '2026-05-02T09:00:00Z',
  downloadUrl: '/LibreClinica/pages/api/v1/archived-files/501/download',
}

const EXPORT_RESPONSE: ExportTriggerResponse = {
  archivedDatasetFileId: 777,
  downloadUrl: '/LibreClinica/pages/api/v1/archived-files/777/download',
}

describe('useDatasetsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('load() hits the right URL and stores rows', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce([ROW_A, ROW_B])

    await ds.load(STUDY_OID)

    expect(apiGet).toHaveBeenCalledWith(`/pages/api/v1/studies/${STUDY_OID}/datasets`)
    expect(ds.rows).toEqual([ROW_A, ROW_B])
    expect(ds.isLoading).toBe(false)
    expect(ds.error).toBeNull()
  })

  it('load() no-ops on empty studyOid', async () => {
    const ds = useDatasetsStore()
    await ds.load('')
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('load() surfaces a network failure via `error`', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiNetworkError('offline', new Error('ECONNREFUSED')),
    )

    await ds.load(STUDY_OID)

    expect(ds.error).toMatch(/Backend nicht erreichbar/)
  })

  it('load() rethrows 401 so the router can bounce to /login', async () => {
    const ds = useDatasetsStore()
    const unauth = new ApiError(401, 'Unauthorized', { message: 'gone' })
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(unauth)

    await expect(ds.load(STUDY_OID)).rejects.toBe(unauth)
  })

  it('loadFiles() caches per-dataset file lists', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce([FILE_FOR_11])

    await ds.loadFiles(STUDY_OID, 11)

    expect(apiGet).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY_OID}/datasets/11/files`,
    )
    expect(ds.filesByDataset.get(11)).toEqual([FILE_FOR_11])
  })

  it('loadFiles() caches an empty list on non-auth failures so expand doesn\'t re-fetch in a loop', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiNetworkError('offline', new Error('ECONNREFUSED')),
    )

    await ds.loadFiles(STUDY_OID, 11)

    expect(ds.filesByDataset.get(11)).toEqual([])
    expect(ds.error).toMatch(/Backend nicht erreichbar/)
  })

  it('triggerExport() POSTs the right body and returns the response', async () => {
    const ds = useDatasetsStore()
    ds.rows = [ROW_A, ROW_B]
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(EXPORT_RESPONSE)

    const result = await ds.triggerExport(STUDY_OID, ROW_A.id, 'csv')

    expect(apiPost).toHaveBeenCalledWith(
      `/pages/api/v1/datasets/${ROW_A.id}/export`,
      { format: 'csv' },
    )
    expect(result).toEqual(EXPORT_RESPONSE)
  })

  it('triggerExport() bumps the matching row\'s lastRunAt + fileCount in-place', async () => {
    const ds = useDatasetsStore()
    ds.rows = [{ ...ROW_A }, { ...ROW_B }]
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(EXPORT_RESPONSE)

    const before = ds.rows[0]!.fileCount
    await ds.triggerExport(STUDY_OID, ROW_A.id, 'odm')

    const after = ds.rows.find((r) => r.id === ROW_A.id)!
    expect(after.fileCount).toBe(before + 1)
    expect(after.lastRunAt).not.toBe(ROW_A.lastRunAt)
    // Other rows untouched.
    expect(ds.rows.find((r) => r.id === ROW_B.id)).toEqual(ROW_B)
  })

  it('triggerExport() surfaces 403 via `error` and rethrows so callers can react', async () => {
    const ds = useDatasetsStore()
    ds.rows = [ROW_A]
    const forbidden = new ApiError(403, 'Forbidden', { message: 'Your role does not permit exporting data.' })
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(forbidden)

    await expect(ds.triggerExport(STUDY_OID, ROW_A.id, 'odm')).rejects.toBe(forbidden)
    expect(ds.error).toBe('Your role does not permit exporting data.')
  })

  it('quickOdm() POSTs to the :quick-odm verb endpoint with empty body', async () => {
    const ds = useDatasetsStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(EXPORT_RESPONSE)
    // load() is fired async-but-ignored after quick-ODM; pre-stub apiGet so
    // it doesn't throw and surface a confusing error.
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce([])

    const result = await ds.quickOdm(STUDY_OID)

    expect(apiPost).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY_OID}/datasets:quick-odm`,
      {},
    )
    expect(result).toEqual(EXPORT_RESPONSE)
  })

  it('reset() clears every piece of state', async () => {
    const ds = useDatasetsStore()
    ds.rows = [ROW_A, ROW_B]
    ds.filesByDataset = new Map([[11, [FILE_FOR_11]]])
    ds.error = 'some prior error'

    ds.reset()

    expect(ds.rows).toEqual([])
    expect(ds.filesByDataset.size).toBe(0)
    expect(ds.error).toBeNull()
    expect(ds.isLoading).toBe(false)
    expect(ds.isQuickOdm).toBe(false)
  })
})
