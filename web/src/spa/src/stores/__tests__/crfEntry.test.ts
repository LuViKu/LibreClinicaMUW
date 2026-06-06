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
  }
})

// eslint-disable-next-line import/first
import { apiGet, apiPost } from '@/api/client'

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
  dde: null,
}

describe('useCrfEntryStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
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
})
