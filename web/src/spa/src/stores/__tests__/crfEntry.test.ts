import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { computeItemErrors, useCrfEntryStore } from '../crfEntry'
import type { CrfEntry, CrfSchema, CrfValues } from '@/types/crf'

// Phase E.4 M13 — backend wiring is real (no `loadMock` to fall back to).
// Stub `apiGet` / `apiPost` from the shared HTTP client so unit tests
// can exercise the store without a live backend.
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiDelete: vi.fn(),
  }
})

// eslint-disable-next-line import/first
import { apiDelete, apiGet, apiPost } from '@/api/client'

const SIMPLE_SCHEMA: CrfSchema = {
  oid: 'F_T',
  name: 'Test CRF',
  version: 'v1.0',
  sections: [
    {
      oid: 'S_A',
      title: 'A',
      items: [
        { oid: 'I_NAME', label: 'Name', dataType: 'string', required: true },
        { oid: 'I_AGE',  label: 'Age',  dataType: 'integer', required: true, min: 0, max: 150 },
        { oid: 'I_SEX',  label: 'Sex',  dataType: 'select-one', required: false, options: [{ code: 'F', label: 'F' }, { code: 'M', label: 'M' }] },
        { oid: 'I_DOB',  label: 'DOB',  dataType: 'date', required: false },
      ],
    },
  ],
}

describe('computeItemErrors', () => {
  it('flags missing required items', () => {
    const errs = computeItemErrors(SIMPLE_SCHEMA, {})
    expect(errs.I_NAME).toBeDefined()
    expect(errs.I_AGE).toBeDefined()
    expect(errs.I_SEX).toBeUndefined()
    expect(errs.I_DOB).toBeUndefined()
  })

  it('passes when all required items have values + optional items omitted', () => {
    const values: CrfValues = { I_NAME: 'Müller', I_AGE: 42 }
    expect(computeItemErrors(SIMPLE_SCHEMA, values)).toEqual({})
  })

  it('flags integer out-of-range', () => {
    const errs = computeItemErrors(SIMPLE_SCHEMA, { I_NAME: 'x', I_AGE: 999 })
    expect(errs.I_AGE).toMatch(/≤ 150/)
  })

  it('flags non-integer for integer items', () => {
    const errs = computeItemErrors(SIMPLE_SCHEMA, { I_NAME: 'x', I_AGE: 3.14 })
    expect(errs.I_AGE).toMatch(/whole number/)
  })

  it('flags unknown select-one code', () => {
    const errs = computeItemErrors(SIMPLE_SCHEMA, { I_NAME: 'x', I_AGE: 1, I_SEX: 'Z' })
    expect(errs.I_SEX).toBeDefined()
  })

  it('flags malformed date', () => {
    const errs = computeItemErrors(SIMPLE_SCHEMA, { I_NAME: 'x', I_AGE: 1, I_DOB: '1990-1-1' })
    expect(errs.I_DOB).toBeDefined()
  })
})

// Fixture that mirrors what GET /pages/api/v1/eventCrfs/{oid} returns
// for the seeded Demographics CRF (per the M5 wire shape). The 4
// required items map to the IDs the markComplete tests fill in.
const DEMOGRAPHICS_ENTRY: CrfEntry = {
  eventCrfOid: 'EC_M001_V1_DEMO',
  subjectId: 'M-001',
  eventLabel: 'V1 Inclusion',
  schema: {
    oid: 'F_DEMOGRAPHICS_V1',
    name: 'Demographics',
    version: 'v1.0',
    sections: [
      {
        oid: 'S_IDENT',
        title: 'Identification',
        items: [
          { oid: 'I_CONSENT_DATE',   label: 'Date of informed consent', dataType: 'date',       required: true },
          { oid: 'I_CONSENT_SIGNED', label: 'Consent signed?',          dataType: 'select-one', required: true,
            options: [{ code: 'Y', label: 'Yes' }, { code: 'N', label: 'No' }] },
        ],
      },
      {
        oid: 'S_VITALS',
        title: 'Vitals',
        items: [
          { oid: 'I_HEIGHT_CM',           label: 'Height (cm)',       dataType: 'integer', required: true, min: 50, max: 250 },
          { oid: 'I_WEIGHT_KG',           label: 'Weight (kg)',       dataType: 'real',    required: true, min: 1,  max: 300 },
          { oid: 'I_BLOOD_PRESSURE_SYS',  label: 'Systolic BP (mmHg)',dataType: 'integer', required: false, min: 50, max: 250 },
        ],
      },
    ],
  },
  values: {},
  status: 'not-started',
  lastSavedAt: null,
  // Phase E.6 fields default to empty so legacy assertions stay valid.
  groups: [],
  maxFileBytes: 52_428_800,
  fileExtensions: 'pdf,jpg,jpeg,png,tif,tiff',
  dde: null,
}

describe('useCrfEntryStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
    vi.mocked(apiDelete).mockReset()
    // Each load() call gets a fresh deep clone — Pinia stores reuse the
    // reactive proxy and we don't want one test's setValue() to leak into
    // the next test's mocked response.
    vi.mocked(apiGet).mockImplementation(async () => structuredClone(DEMOGRAPHICS_ENTRY))
    // crfEntry calls apiPost twice: once for `save` (items) and once for
    // `markComplete`. The save endpoint returns no body; markComplete
    // returns `{ status, lastSavedAt }`. The store treats undefined as
    // "no shape change", so a generic stubbed response is fine for save —
    // but markComplete needs the `status` field to flip the local state.
    // Both POST endpoints return `{ lastSavedAt, status }` shapes the
    // store reads back into the entry. Mock both with sensible defaults
    // so save() flips pendingChanges → false and markComplete() flips
    // status → complete.
    vi.mocked(apiPost).mockImplementation(async (path) => {
      const ts = '2026-06-01T12:00:00.000Z'
      if (path.endsWith('/markComplete')) {
        return { status: 'complete', lastSavedAt: ts } as never
      }
      // /items save response — return an in-progress hint.
      return { status: 'in-progress', lastSavedAt: ts } as never
    })
  })

  it('starts empty + not loading', () => {
    const store = useCrfEntryStore()
    expect(store.entry).toBeNull()
    expect(store.isLoading).toBe(false)
    expect(store.status).toBe('not-started')
    expect(store.isComplete).toBe(false)
  })

  it('hydrates from the backend + advances to in-progress on first edit', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    expect(apiGet).toHaveBeenCalledWith('/pages/api/v1/eventCrfs/EC_M001_V1_DEMO')
    expect(store.entry).not.toBeNull()
    expect(store.schema?.sections.length).toBeGreaterThan(0)
    expect(store.status).toBe('not-started')

    store.setValue('I_HEIGHT_CM', 172)
    expect(store.status).toBe('in-progress')
    expect(store.pendingChanges).toBe(true)
  })

  it('clears pendingChanges after a successful save', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    store.setValue('I_HEIGHT_CM', 172)
    expect(store.pendingChanges).toBe(true)
    await store.save()
    expect(apiPost).toHaveBeenCalled()
    expect(store.pendingChanges).toBe(false)
    expect(store.entry?.lastSavedAt).not.toBeNull()
  })

  it('refuses markComplete when required items are missing', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    await store.markComplete()
    expect(store.status).not.toBe('complete')
    expect(store.error).toMatch(/Required/)
  })

  it('marks complete when every required item is valid', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    // Fill every required item per the Demographics schema.
    store.setValue('I_CONSENT_DATE', '2026-05-01')
    store.setValue('I_CONSENT_SIGNED', 'Y')
    store.setValue('I_HEIGHT_CM', 172)
    store.setValue('I_WEIGHT_KG', 70.5)
    await store.markComplete()
    expect(store.status).toBe('complete')
    expect(store.error).toBeNull()
  })

  /* ---------------------------------------------------------------- */
  /* Phase E A5 — reopen()                                            */
  /* ---------------------------------------------------------------- */

  it('reopen POSTs to /markIncomplete with the right URL', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    // Force the entry into a completed state without running the
    // markComplete flow (that would consume the apiPost mock too).
    if (store.entry) store.entry.status = 'complete'
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      eventCrfOid: 'EC_M001_V1_DEMO',
      status: 'in-progress',
      lastSavedAt: '2026-06-02T10:00:00Z',
    })

    await store.reopen()

    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/eventCrfs/EC_M001_V1_DEMO/markIncomplete',
      {},
    )
    expect(store.entry?.status).toBe('in-progress')
    expect(store.error).toBeNull()
  })

  it('reopen refuses early when status is not complete', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    // The seeded demo loader leaves the entry at 'in-progress'.
    expect(store.entry?.status).not.toBe('complete')
    await store.reopen()
    expect(apiPost).not.toHaveBeenCalled()
    expect(store.error).toMatch(/nicht abgeschlossen/)
  })

  it('reopen surfaces a 403 forbidden message via error', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    if (store.entry) store.entry.status = 'complete'
    const { ApiError } = await import('@/api/client')
    const forbidden = new ApiError(403, 'Forbidden',
      { message: 'Your role does not permit reopening completed CRFs' })
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(forbidden)

    await expect(store.reopen()).rejects.toBe(forbidden)
    expect(store.error).toBe('Your role does not permit reopening completed CRFs')
    expect(store.entry?.status).toBe('complete')
  })

  it('reopen surfaces a 409 lock conflict via error', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    if (store.entry) store.entry.status = 'complete'
    const { ApiError } = await import('@/api/client')
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(409, 'Conflict',
        { message: 'event_crf 1 is locked or signed' }),
    )

    await store.reopen()
    expect(store.error).toBe('event_crf 1 is locked or signed')
    expect(store.entry?.status).toBe('complete')
  })

  /* ---------------------------------------------------------------- */
  /* Phase E.6 admin-rfc — Reason-For-Change capture on save           */
  /* ---------------------------------------------------------------- */

  it('stageReason records trimmed reasons + clears prompt for the staged oid', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    if (store.entry) store.entry.requiresReasonForChange = true
    store.setValue('I_HEIGHT_CM', 174)
    // Simulate the backend re-arming for one OID.
    store.missingReasonItemOids = ['I_HEIGHT_CM']
    store.stageReason('I_HEIGHT_CM', '  re-keyed from corrected source  ')
    expect(store.pendingReasons.I_HEIGHT_CM).toBe('re-keyed from corrected source')
    expect(store.missingReasonItemOids).not.toContain('I_HEIGHT_CM')
    // Empty input drops the staged entry.
    store.stageReason('I_HEIGHT_CM', '   ')
    expect(store.pendingReasons.I_HEIGHT_CM).toBeUndefined()
  })

  it('save omits reasons when the entry is pre-complete', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    // Default fixture is not post-complete; entry.requiresReasonForChange undefined.
    store.setValue('I_HEIGHT_CM', 172)
    await store.save()
    expect(apiPost).toHaveBeenCalledTimes(1)
    const [, body] = vi.mocked(apiPost).mock.calls[0]
    expect(body).toHaveProperty('values')
    expect(body).not.toHaveProperty('reasons')
  })

  it('save sends reasons + clears staged reasons on success when post-complete', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    if (store.entry) store.entry.requiresReasonForChange = true
    store.setValue('I_HEIGHT_CM', 172)
    store.stageReason('I_HEIGHT_CM', 'corrected source document')
    await store.save()
    expect(apiPost).toHaveBeenCalledTimes(1)
    const [, body] = vi.mocked(apiPost).mock.calls[0]
    expect((body as { reasons?: Record<string, string> }).reasons).toEqual({
      I_HEIGHT_CM: 'corrected source document',
    })
    expect(store.pendingReasons).toEqual({})
    expect(store.missingReasonItemOids).toEqual([])
  })

  it('save short-circuits + arms the modal when dirty oids lack reasons', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    if (store.entry) store.entry.requiresReasonForChange = true
    store.setValue('I_HEIGHT_CM', 172)
    store.setValue('I_WEIGHT_KG', 71.3)
    // No reasons staged → save() shouldn't POST.
    await store.save()
    expect(apiPost).not.toHaveBeenCalled()
    expect(store.missingReasonItemOids.sort()).toEqual(['I_HEIGHT_CM', 'I_WEIGHT_KG'])
  })

  it('save re-arms the modal on backend 400 missingReasonItemOids', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    if (store.entry) store.entry.requiresReasonForChange = true
    store.setValue('I_HEIGHT_CM', 172)
    store.stageReason('I_HEIGHT_CM', 'too short — backend rejects')
    const { ApiError } = await import('@/api/client')
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(400, 'Bad Request', {
        message: 'reason text too short',
        missingReasonItemOids: ['I_HEIGHT_CM'],
      }),
    )
    await store.save()
    expect(store.missingReasonItemOids).toEqual(['I_HEIGHT_CM'])
    // The offending oid's staged reason should be cleared so the modal re-asks.
    expect(store.pendingReasons.I_HEIGHT_CM).toBeUndefined()
    expect(store.error).toBe('reason text too short')
  })

  it('dismissReasonModal clears missing oids but preserves staged reasons', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
    if (store.entry) store.entry.requiresReasonForChange = true
    store.setValue('I_HEIGHT_CM', 172)
    store.missingReasonItemOids = ['I_HEIGHT_CM']
    store.stageReason('I_HEIGHT_CM', 'will retry later')
    store.dismissReasonModal()
    expect(store.missingReasonItemOids).toEqual([])
    expect(store.pendingReasons.I_HEIGHT_CM).toBe('will retry later')
  })

  /* ---------------------------------------------------------------- */
  /* Phase E.6 — repeating groups + select-multi + file               */
  /* ---------------------------------------------------------------- */

  it('setValueInRow appends a new row when the ordinal is unseen', async () => {
    const store = useCrfEntryStore()
    // Mock GET to return an entry with one repeating group.
    vi.mocked(apiGet).mockResolvedValueOnce(
      structuredClone({
        ...DEMOGRAPHICS_ENTRY,
        groups: [{
          oid: 'G_EYE_FINDINGS', label: 'Per-eye findings', repeatMax: 4,
          itemOids: ['I_EYE', 'I_IOP'], rows: [],
        }],
      }),
    )
    await store.load('EC_M001_V1_DEMO')
    store.setValueInRow('G_EYE_FINDINGS', 1, 'I_EYE', 'OD')
    const g = store.groups.find((gr) => gr.oid === 'G_EYE_FINDINGS')!
    expect(g.rows).toHaveLength(1)
    expect(g.rows[0].values.I_EYE).toBe('OD')
    expect(store.pendingChanges).toBe(true)
  })

  it('addGroupRow POSTs + appends the returned row', async () => {
    const store = useCrfEntryStore()
    vi.mocked(apiGet).mockResolvedValueOnce(
      structuredClone({
        ...DEMOGRAPHICS_ENTRY,
        groups: [{
          oid: 'G_EYE_FINDINGS', label: 'Per-eye findings', repeatMax: 4,
          itemOids: ['I_EYE'], rows: [{ ordinal: 1, values: { I_EYE: 'OD' } }],
        }],
      }),
    )
    await store.load('EC_M001_V1_DEMO')
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      groupOid: 'G_EYE_FINDINGS', rowOrdinal: 2, values: {},
    })
    const newRow = await store.addGroupRow('G_EYE_FINDINGS')
    expect(apiPost).toHaveBeenCalledWith(
      '/pages/api/v1/eventCrfs/EC_M001_V1_DEMO/groups/G_EYE_FINDINGS/rows',
      {},
    )
    expect(newRow).toEqual({ ordinal: 2, values: {} })
    expect(store.groups.find((g) => g.oid === 'G_EYE_FINDINGS')!.rows).toHaveLength(2)
  })

  it('addGroupRow surfaces REPEAT_MAX_REACHED via the i18n key when backend 409s', async () => {
    const store = useCrfEntryStore()
    vi.mocked(apiGet).mockResolvedValueOnce(
      structuredClone({
        ...DEMOGRAPHICS_ENTRY,
        groups: [{
          oid: 'G_EYE_FINDINGS', label: 'Per-eye findings', repeatMax: 4,
          itemOids: ['I_EYE'], rows: [{ ordinal: 1, values: {} }],
        }],
      }),
    )
    await store.load('EC_M001_V1_DEMO')
    const { ApiError } = await import('@/api/client')
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new ApiError(409, 'Conflict', { code: 'REPEAT_MAX_REACHED', message: 'cap' }),
    )
    const row = await store.addGroupRow('G_EYE_FINDINGS')
    expect(row).toBeNull()
    expect(store.error).toBe('crfEntry.group.repeatMaxReached')
  })

  it('addGroupRow refuses locally when rows already meet repeatMax', async () => {
    const store = useCrfEntryStore()
    vi.mocked(apiGet).mockResolvedValueOnce(
      structuredClone({
        ...DEMOGRAPHICS_ENTRY,
        groups: [{
          oid: 'G_EYE_FINDINGS', label: 'Per-eye findings', repeatMax: 1,
          itemOids: ['I_EYE'], rows: [{ ordinal: 1, values: {} }],
        }],
      }),
    )
    await store.load('EC_M001_V1_DEMO')
    const row = await store.addGroupRow('G_EYE_FINDINGS')
    expect(row).toBeNull()
    expect(apiPost).not.toHaveBeenCalled()
    expect(store.error).toBe('crfEntry.group.repeatMaxReached')
  })

  it('deleteGroupRow DELETEs + drops the row on success', async () => {
    const store = useCrfEntryStore()
    vi.mocked(apiGet).mockResolvedValueOnce(
      structuredClone({
        ...DEMOGRAPHICS_ENTRY,
        groups: [{
          oid: 'G_EYE_FINDINGS', label: 'Per-eye findings', repeatMax: 4,
          itemOids: ['I_EYE'],
          rows: [
            { ordinal: 1, values: { I_EYE: 'OD' } },
            { ordinal: 2, values: { I_EYE: 'OS' } },
          ],
        }],
      }),
    )
    await store.load('EC_M001_V1_DEMO')
    const { apiDelete } = await import('@/api/client')
    const apiDeleteMock = vi.mocked(apiDelete) as ReturnType<typeof vi.fn>
    apiDeleteMock.mockResolvedValueOnce(undefined)
    const ok = await store.deleteGroupRow('G_EYE_FINDINGS', 2)
    expect(ok).toBe(true)
    const g = store.groups.find((gr) => gr.oid === 'G_EYE_FINDINGS')!
    expect(g.rows.map((r) => r.ordinal)).toEqual([1])
  })
})
