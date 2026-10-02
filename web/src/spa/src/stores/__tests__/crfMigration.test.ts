import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn() }
})

import { ApiError, apiGet, apiPost } from '@/api/client'
import { migrationLogCsv, useCrfMigrationStore } from '../crfMigration'
import type { EventCrfMigrationPreview, EventCrfMigrationResult } from '@/types/crfMigration'

/**
 * Moving existing event CRFs to another CRF version: the store's calls, and
 * that the run carries the count and the selection its preview showed.
 */
const REQUEST = {
  studyOid: 'S_DEFAULTS1',
  sourceVersionOid: 'F_AE_V1',
  targetVersionOid: 'F_AE_V2',
  siteOids: [],
  eventDefinitionOids: [],
}

const ROW = {
  eventCrfId: 7,
  studySubjectLabel: 'M-001',
  siteOid: 'S_DEFAULTS1',
  siteName: 'Default Study',
  eventDefinitionOid: 'SE_V1',
  eventName: 'V1, Inclusion',
  eventOrdinal: 1,
  sdvVerified: true,
  subjectSigned: false,
  eventSigned: false,
  eventCrfSigned: true,
}

const PREVIEW: EventCrfMigrationPreview = {
  crfOid: 'F_AE',
  crfName: 'Adverse events',
  study: { oid: 'S_DEFAULTS1', name: 'Default Study' },
  sourceVersion: { oid: 'F_AE_V1', name: 'v1' },
  targetVersion: { oid: 'F_AE_V2', name: 'v2' },
  sites: [],
  eventDefinitions: [],
  studySubjectLabel: null,
  eventCrfCount: 12,
  subjectCount: 8,
  sdvVerifiedCount: 3,
  signedSubjectCount: 1,
  signedEventCount: 1,
  signedEventCrfCount: 2,
  eventCrfs: [ROW],
  eventCrfsTruncated: true,
  locked: [],
  notOffered: [],
  hiddenValueCount: 0,
  hiddenItems: [],
  selectionDigest: '3f0c',
}

const RESULT: EventCrfMigrationResult = {
  crfOid: 'F_AE',
  crfName: 'Adverse events',
  study: { oid: 'S_DEFAULTS1', name: 'Default Study' },
  sourceVersion: { oid: 'F_AE_V1', name: 'v1' },
  targetVersion: { oid: 'F_AE_V2', name: 'v2' },
  migratedEventCrfCount: 1,
  subjectCount: 1,
  sdvClearedCount: 1,
  unsignedSubjectCount: 0,
  unsignedEventCount: 0,
  unsignedEventCrfCount: 1,
  log: [ROW],
  completedAt: '2026-09-30T10:00:00Z',
}

describe('useCrfMigrationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
  })

  it('loads the options of one study', async () => {
    const store = useCrfMigrationStore()
    vi.mocked(apiGet).mockResolvedValue({ crfOid: 'F_AE', versions: [], sites: [], eventDefinitions: [] })

    expect(await store.loadOptions('F_AE', 'S_SITE A')).toBe(true)
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/crfs/F_AE/event-crf-migration/options?studyOid=S_SITE%20A')
    expect(store.options?.crfOid).toBe('F_AE')
  })

  it('previews without an expected count or selection', async () => {
    const store = useCrfMigrationStore()
    vi.mocked(apiPost).mockResolvedValue(PREVIEW)

    await store.runPreview('F_AE', { ...REQUEST, expectedEventCrfCount: 99, expectedSelectionDigest: 'stale' })

    expect(apiPost).toHaveBeenCalledWith('/pages/api/v1/crfs/F_AE/event-crf-migration/preview', REQUEST)
    expect(store.preview?.eventCrfCount).toBe(12)
  })

  it('runs with the count and the selection the preview showed, then drops the preview', async () => {
    const store = useCrfMigrationStore()
    vi.mocked(apiPost).mockResolvedValueOnce(PREVIEW).mockResolvedValueOnce(RESULT)
    await store.runPreview('F_AE', REQUEST)

    expect(await store.run('F_AE', REQUEST)).toBe(true)

    expect(apiPost).toHaveBeenLastCalledWith('/pages/api/v1/crfs/F_AE/event-crf-migration', {
      ...REQUEST,
      expectedEventCrfCount: 12,
      expectedSelectionDigest: '3f0c',
    })
    expect(store.result?.migratedEventCrfCount).toBe(1)
    expect(store.preview).toBeNull()
  })

  it('does not run without a preview', async () => {
    const store = useCrfMigrationStore()
    expect(await store.run('F_AE', REQUEST)).toBe(false)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('keeps the preview and shows the message when the selection changed in between', async () => {
    const store = useCrfMigrationStore()
    vi.mocked(apiPost).mockResolvedValueOnce(PREVIEW).mockRejectedValueOnce(new ApiError(409, 'Conflict', {
      message: 'The selection changed since the preview: 13 event CRF(s) would move now, the preview showed 12.',
    }))
    await store.runPreview('F_AE', REQUEST)

    expect(await store.run('F_AE', REQUEST)).toBe(false)
    expect(store.error).toContain('changed since the preview')
    expect(store.result).toBeNull()
  })

  it('maps field errors per field and rethrows a lost session', async () => {
    const store = useCrfMigrationStore()
    vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(400, 'Bad Request', {
      message: 'Validation failed',
      errors: [{ field: 'targetVersionOid', message: 'The current and the new version must differ' }],
    }))
    expect(await store.runPreview('F_AE', REQUEST)).toBe(false)
    expect(store.fieldErrors.targetVersionOid).toBe('The current and the new version must differ')
    expect(store.error).toBeNull()

    vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(401, 'Unauthorized', null))
    await expect(store.runPreview('F_AE', REQUEST)).rejects.toBeInstanceOf(ApiError)
  })

  it('shows a refusal of the options as a message', async () => {
    const store = useCrfMigrationStore()
    vi.mocked(apiGet).mockRejectedValue(new ApiError(403, 'Forbidden', {
      message: "Only a Data Manager or CRC of study 'Default Study' may move event CRFs to another CRF version",
    }))

    expect(await store.loadOptions('F_AE', 'S_DEFAULTS1')).toBe(false)
    expect(store.error).toContain('Only a Data Manager or CRC')
    expect(store.options).toBeNull()
  })
})

describe('migrationLogCsv', () => {
  it('writes the legacy report columns, then what the move cleared, quoting where needed', () => {
    const csv = migrationLogCsv(RESULT)
    const [header, line] = csv.trim().split('\r\n')
    expect(header).toBe('CRF_Name,Origin_Version,Target_Version,Subject_ID,Site,Event,Event_Ordinal,'
      + 'Event_CRF_ID,SDV_Cleared,Subject_Signature_Removed,Event_Signature_Removed,CRF_Signature_Removed')
    expect(line).toBe('Adverse events,v1,v2,M-001,Default Study,"V1, Inclusion",1,7,true,false,false,true')
  })

  it('writes a subject label or a name that starts like a formula as text', () => {
    const csv = migrationLogCsv({
      ...RESULT,
      log: [{ ...ROW, studySubjectLabel: '=1+2', siteName: '@Site', eventName: '-V1' }],
    })
    const [, line] = csv.trim().split('\r\n')
    expect(line).toBe("Adverse events,v1,v2,'=1+2,'@Site,'-V1,1,7,true,false,false,true")
  })
})
