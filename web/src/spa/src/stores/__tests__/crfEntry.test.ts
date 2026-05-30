import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { computeItemErrors, useCrfEntryStore } from '../crfEntry'
import type { CrfSchema, CrfValues } from '@/types/crf'

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

describe('useCrfEntryStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts empty + not loading', () => {
    const store = useCrfEntryStore()
    expect(store.entry).toBeNull()
    expect(store.isLoading).toBe(false)
    expect(store.status).toBe('not-started')
    expect(store.isComplete).toBe(false)
  })

  it('hydrates from the mock loader + advances to in-progress on first edit', async () => {
    const store = useCrfEntryStore()
    await store.load('EC_M001_V1_DEMO')
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
    // Fill every required item per the mock schema.
    store.setValue('I_CONSENT_DATE', '2026-05-01')
    store.setValue('I_CONSENT_SIGNED', 'Y')
    store.setValue('I_HEIGHT_CM', 172)
    store.setValue('I_WEIGHT_KG', 70.5)
    await store.markComplete()
    expect(store.status).toBe('complete')
    expect(store.error).toBeNull()
  })
})
