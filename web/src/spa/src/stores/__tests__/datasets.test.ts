import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDatasetsStore } from '../datasets'
import { ApiError, ApiNetworkError } from '@/api/client'
import type {
  ArchivedFileDto,
  DatasetDto,
  DatasetFilterDto,
  ExportTriggerResponse,
  FilterTestResult,
} from '@/types/export'

/**
 * Phase E.6 — Vitest coverage for the data-export store. Pins:
 *   - Phase 1: load/loadFiles/triggerExport/quickOdm + reset.
 *   - Phase 3: testFilter (debounce + cancellation + previewError).
 *   - 401/403 propagate; network failures surface in `error`.
 */
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
import type { EventTreeNode, InclusionFlags } from '@/types/export'
import { FLAG_DEFAULTS } from '@/types/export'

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

const FILTERS: DatasetFilterDto[] = [
  { itemOid: 'I_AGE', operator: '>=', value: '18' },
]

const FILTER_RESULT: FilterTestResult = {
  matchingSubjects: 12,
  totalSubjects: 50,
  matchingCrfs: 36,
  totalCrfs: 150,
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

describe('useDatasetsStore.testFilter (Phase 3)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('POSTs to /pages/api/v1/datasets/{id}:test-filter with the filters body', async () => {
    const datasets = useDatasetsStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(FILTER_RESULT)

    const out = await datasets.testFilter('42', FILTERS, 0)

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/datasets/42:test-filter',
      { filters: FILTERS },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(out).toEqual(FILTER_RESULT)
    expect(datasets.preview).toEqual(FILTER_RESULT)
    expect(datasets.isLoadingPreview).toBe(false)
    expect(datasets.previewError).toBeNull()
  })

  it('debounces — only the latest call hits the network', async () => {
    const datasets = useDatasetsStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue(FILTER_RESULT)

    vi.useFakeTimers()
    try {
      const p1 = datasets.testFilter('0', [
        { itemOid: 'I_AGE', operator: '=', value: '40' },
      ])
      const p2 = datasets.testFilter('0', [
        { itemOid: 'I_AGE', operator: '=', value: '41' },
      ])
      const p3 = datasets.testFilter('0', FILTERS)

      // The first two should resolve to null (superseded by p3).
      expect(apiPost).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(350)

      const [r1, r2, r3] = await Promise.all([p1, p2, p3])
      expect(r1).toBeNull()
      expect(r2).toBeNull()
      expect(r3).toEqual(FILTER_RESULT)
      // Only one request — the debounced winner.
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenLastCalledWith(
        '/pages/api/v1/datasets/0:test-filter',
        { filters: FILTERS },
        expect.any(Object),
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('surfaces ApiError into previewError without throwing', async () => {
    const datasets = useDatasetsStore()
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(400, "filters[0].operator 'like' is not supported"),
    )

    const out = await datasets.testFilter('0', FILTERS, 0)

    expect(out).toBeNull()
    expect(datasets.previewError).toContain('not supported')
    expect(datasets.isLoadingPreview).toBe(false)
  })

  it('reset() clears draft + preview state', async () => {
    const datasets = useDatasetsStore()
    // Phase 2 reshape: `draft` is now CreateDatasetDraft | null —
    // launching a wizard is what seeds it. Reset returns it to null
    // (no wizard in flight).
    datasets.startNewDraft()
    datasets.draft!.name = 'My export'
    datasets.draft!.filters = FILTERS
    datasets.preview = FILTER_RESULT
    datasets.previewError = 'oops'

    datasets.reset()

    expect(datasets.draft).toBeNull()
    expect(datasets.preview).toBeNull()
    expect(datasets.previewError).toBeNull()
  })
})

/* =================================================================== */
/* Phase 2 — create-dataset wizard (revival of PR #114).               */
/* =================================================================== */

const TREE: EventTreeNode[] = [
  {
    eventOid: 'SE_VISIT1',
    eventName: 'Visit 1',
    eventOrdinal: 1,
    repeating: false,
    crfs: [
      {
        crfOid: 'F_OPHTH',
        crfName: 'Ophthalmology core',
        versions: [
          {
            versionId: 901,
            versionOid: 'V_OPHTH_1',
            versionName: 'v1.0',
            items: [
              { itemId: 5001, oid: 'I_VA_OD', name: 'VA OD', dataType: 'real' },
              { itemId: 5002, oid: 'I_VA_OS', name: 'VA OS', dataType: 'real' },
            ],
          },
        ],
      },
    ],
  },
]

const WIZARD_ROW: DatasetDto = {
  oid: '88',
  id: 88,
  name: 'Visit 1 — OD/OS visual acuity',
  description: 'Smoke cohort',
  ownerName: 'datamanager',
  dateCreated: '2026-06-05T12:00:00Z',
  lastRunAt: null,
  fileCount: 0,
  studyId: 1,
  status: 'available',
  eventDefinitionOids: ['SE_VISIT1'],
  crfVersionIds: [901],
  itemIds: [5001, 5002],
  includeFlags: { ...FLAG_DEFAULTS } as InclusionFlags,
  numRuns: 0,
  hasRun: false,
}

describe('useDatasetsStore (Phase 2 wizard)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loadList() is an alias for load()', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce([WIZARD_ROW])
    await ds.loadList(STUDY_OID)
    expect(ds.rows).toEqual([WIZARD_ROW])
    expect(ds.loadedForStudyOid).toBe(STUDY_OID)
  })

  it('loadEventTree() GETs /event-tree and stores the tree', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(TREE)
    await ds.loadEventTree(STUDY_OID)
    expect(apiGet).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY_OID}/event-tree`,
    )
    expect(ds.eventTree).toEqual(TREE)
  })

  it('loadEventTree() resets to empty + surfaces error on failure', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiNetworkError('down', new Error('boom')),
    )
    await ds.loadEventTree(STUDY_OID)
    expect(ds.eventTree).toEqual([])
    expect(ds.eventTreeError).not.toBeNull()
  })

  it('loadOne() GETs /datasets/{id} and returns the row', async () => {
    const ds = useDatasetsStore()
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValueOnce(WIZARD_ROW)
    const out = await ds.loadOne(88)
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/datasets/88')
    expect(out).toEqual(WIZARD_ROW)
  })

  it('startNewDraft() seeds the draft with flag defaults + empty selection', () => {
    const ds = useDatasetsStore()
    ds.startNewDraft()
    expect(ds.draft).not.toBeNull()
    expect(ds.draft!.name).toBe('')
    expect(ds.draft!.eventDefinitionOids).toEqual([])
    expect(ds.draft!.includeFlags.gender).toBe(true) // FLAG_DEFAULTS.gender
    expect(ds.isEditing).toBe(false)
  })

  it('startEditDraft() hydrates from an existing dataset + flips isEditing', () => {
    const ds = useDatasetsStore()
    ds.startEditDraft(WIZARD_ROW)
    expect(ds.draft).not.toBeNull()
    expect(ds.draft!.editingDatasetId).toBe(88)
    expect(ds.draft!.itemIds).toEqual([5001, 5002])
    expect(ds.isEditing).toBe(true)
  })

  it('patchDraft() shallow-merges into the current draft', () => {
    const ds = useDatasetsStore()
    ds.startNewDraft()
    ds.patchDraft({ name: 'Foo' })
    expect(ds.draft!.name).toBe('Foo')
    // unrelated fields stay intact
    expect(ds.draft!.includeFlags.gender).toBe(true)
  })

  it('setFlag() flips one flag without disturbing the others', () => {
    const ds = useDatasetsStore()
    ds.startNewDraft()
    expect(ds.draft!.includeFlags.dob).toBe(false)
    ds.setFlag('dob', true)
    expect(ds.draft!.includeFlags.dob).toBe(true)
    expect(ds.draft!.includeFlags.gender).toBe(true)
  })

  it('setStep() updates the current step', () => {
    const ds = useDatasetsStore()
    ds.startNewDraft()
    ds.setStep(2)
    expect(ds.draft!.step).toBe(2)
  })

  it('createDataset() POSTs and prepends the new row', async () => {
    const ds = useDatasetsStore()
    ds.rows = [WIZARD_ROW]
    const created: DatasetDto = { ...WIZARD_ROW, id: 99, oid: '99', name: 'New' }
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce(created)
    const res = await ds.createDataset(STUDY_OID, {
      name: 'New',
      description: '',
      eventDefinitionOids: ['SE_VISIT1'],
      crfVersionIds: [901],
      itemIds: [5001],
      includeFlags: { ...FLAG_DEFAULTS },
    })
    expect(res.ok).toBe(true)
    expect(ds.rows[0].id).toBe(99)
    expect(apiPost).toHaveBeenCalledWith(
      `/pages/api/v1/studies/${STUDY_OID}/datasets`,
      expect.objectContaining({ name: 'New' }),
    )
  })

  it('createDataset() surfaces 400 field errors without throwing', async () => {
    const ds = useDatasetsStore()
    const err = new ApiError(400, 'Bad', { message: 'Validation failed', errors: [{ field: 'name', message: 'required' }] })
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(err)
    const res = await ds.createDataset(STUDY_OID, {
      name: '',
      description: '',
      eventDefinitionOids: [],
      crfVersionIds: [],
      itemIds: [],
      includeFlags: { ...FLAG_DEFAULTS },
    })
    expect(res.ok).toBe(false)
    if (!res.ok) {
      expect(res.fieldErrors.name).toBe('required')
      expect(res.message).toBe('Validation failed')
    }
  })

  it('updateDataset() PUTs and replaces the existing row in-place', async () => {
    const ds = useDatasetsStore()
    ds.rows = [WIZARD_ROW]
    const updated: DatasetDto = { ...WIZARD_ROW, name: 'Renamed' }
    ;(apiPut as ReturnType<typeof vi.fn>).mockResolvedValueOnce(updated)
    const res = await ds.updateDataset(88, {
      name: 'Renamed',
      description: '',
      eventDefinitionOids: ['SE_VISIT1'],
      crfVersionIds: [901],
      itemIds: [5001, 5002],
      includeFlags: { ...FLAG_DEFAULTS },
    })
    expect(res.ok).toBe(true)
    expect(ds.rows).toHaveLength(1)
    expect(ds.rows[0].name).toBe('Renamed')
    expect(apiPut).toHaveBeenCalledWith('/pages/api/v1/datasets/88', expect.any(Object))
  })

  it('removeDataset() DELETEs and drops the row from the in-memory list', async () => {
    const ds = useDatasetsStore()
    ds.rows = [WIZARD_ROW]
    const removed: DatasetDto = { ...WIZARD_ROW, status: 'removed' }
    ;(apiDelete as ReturnType<typeof vi.fn>).mockResolvedValueOnce(removed)
    const res = await ds.removeDataset(88)
    expect(res.ok).toBe(true)
    expect(ds.rows).toHaveLength(0)
    expect(apiDelete).toHaveBeenCalledWith('/pages/api/v1/datasets/88')
  })

  it('saveDraft() dispatches POST when editingDatasetId is undefined', async () => {
    const ds = useDatasetsStore()
    ds.startNewDraft()
    ds.patchDraft({
      name: 'X',
      eventDefinitionOids: ['SE_VISIT1'],
      crfVersionIds: [901],
      itemIds: [5001],
    })
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ ...WIZARD_ROW, id: 200, oid: '200', name: 'X' })
    const res = await ds.saveDraft(STUDY_OID)
    expect(res.ok).toBe(true)
    expect(apiPost).toHaveBeenCalled()
    expect(apiPut).not.toHaveBeenCalled()
  })

  it('saveDraft() dispatches PUT when editingDatasetId is set', async () => {
    const ds = useDatasetsStore()
    ds.startEditDraft(WIZARD_ROW)
    ;(apiPut as ReturnType<typeof vi.fn>).mockResolvedValueOnce(WIZARD_ROW)
    const res = await ds.saveDraft(STUDY_OID)
    expect(res.ok).toBe(true)
    expect(apiPut).toHaveBeenCalledWith('/pages/api/v1/datasets/88', expect.any(Object))
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('saveDraft() reports no-draft when nothing is in flight', async () => {
    const ds = useDatasetsStore()
    const res = await ds.saveDraft(STUDY_OID)
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.message).toBe('No active draft')
  })
})
