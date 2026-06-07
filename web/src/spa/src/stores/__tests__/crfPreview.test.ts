import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { draftToPreviewSchema, sampleValueFor, useCrfPreviewStore } from '../crfPreview'
import type { AuthoringDraft } from '../crfAuthoring'
import type { CrfItem, CrfSchema } from '@/types/crf'

/**
 * Phase E.6 — Vitest coverage for the CRF authoring **live preview**
 * Pinia store. Verifies:
 *
 * <ul>
 *   <li>{@code load(draftSchema)} accepts both a runtime
 *       {@link CrfSchema} and an {@link AuthoringDraft} (the wizard's
 *       common path) and rejects no network call;</li>
 *   <li>{@code fillSampleData()} populates every item with a
 *       type-appropriate value per the Phase E.6 preview spec;</li>
 *   <li>{@code reset()} wipes typed values without dropping the
 *       schema;</li>
 *   <li>{@code save()} is in-memory only — no `apiPost` ever fires;
 *       and so {@code markComplete()};</li>
 *   <li>Validation parity with the runtime — IOP > 80 or BCVA > 100
 *       trip the same range check the entry store applies.</li>
 * </ul>
 */

// Stub the HTTP client so we can assert no call is made via save() /
// markComplete(). We don't need to mock anything else — the preview
// store doesn't import from `@/api/client` today (the point of the
// store), but the mock is a safety net.
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
import { apiGet, apiPost } from '@/api/client'

/** A minimal runtime schema that exercises every preview branch. */
const RUNTIME_SCHEMA: CrfSchema = {
  oid: 'F_PREVIEW',
  name: 'Demo CRF',
  version: 'draft',
  sections: [
    {
      oid: 'S_VITALS',
      title: 'Vitals',
      items: [
        { oid: 'I_NAME', label: 'Name', dataType: 'string', required: true },
        { oid: 'I_AGE', label: 'Age', dataType: 'integer', required: true, min: 0, max: 120 },
        // BCVA letters: 0–100, > 100 must fail
        { oid: 'I_BCVA_OD', label: 'BCVA OD (ETDRS letters)', dataType: 'integer', required: false, min: 0, max: 100 },
        // IOP mmHg: 0–80, > 80 must fail
        { oid: 'I_IOP_OD', label: 'IOP OD (mmHg)', dataType: 'real', required: false, min: 0, max: 80 },
        { oid: 'I_DOB', label: 'DOB', dataType: 'date', required: false },
        {
          oid: 'I_SEX',
          label: 'Sex',
          dataType: 'select-one',
          required: false,
          options: [
            { code: 'F', label: 'F' },
            { code: 'M', label: 'M' },
          ],
        },
        {
          oid: 'I_COMORBID',
          label: 'Comorbidities',
          dataType: 'select-multi',
          required: false,
          options: [
            { code: 'HTN', label: 'Hypertension' },
            { code: 'DM', label: 'Diabetes' },
            { code: 'CKD', label: 'CKD' },
          ],
        },
        { oid: 'I_CONSENT', label: 'Consent obtained?', dataType: 'boolean', required: false },
      ],
    },
  ],
}

describe('useCrfPreviewStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
  })

  describe('load()', () => {
    it('hydrates from a runtime CrfSchema and flips isOpen on', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      expect(store.schema).toEqual(RUNTIME_SCHEMA)
      expect(store.values).toEqual({})
      expect(store.status).toBe('not-started')
      expect(store.isOpen).toBe(true)
      expect(store.crfName).toBe('Demo CRF')
    })

    it('accepts a crfName override', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA, { crfName: 'Overridden CRF' })
      expect(store.crfName).toBe('Overridden CRF')
    })

    it('hydrates from an AuthoringDraft via the converter branch', () => {
      const draft: AuthoringDraft = {
        versionName: 'v1.0',
        versionDescription: 'demo',
        revisionNotes: '',
        sections: [
          {
            uid: 'sec-1',
            label: 'S1',
            title: 'Section 1',
            instructions: 'fill it in',
            ordinal: 1,
            items: [
              {
                uid: 'item-1',
                name: 'NAME',
                oid: 'I_NAME',
                descriptionLabel: 'Name',
                leftItemText: '',
                rightItemText: '',
                units: '',
                dataType: 'ST',
                responseType: 'text',
                defaultValue: '',
                required: true,
                responseSet: null,
                validation: { regexp: '', errorMessage: '' },
              },
              {
                uid: 'item-2',
                name: 'AGE',
                oid: 'I_AGE',
                descriptionLabel: 'Age',
                leftItemText: '',
                rightItemText: '',
                units: 'years',
                dataType: 'INT',
                responseType: 'text',
                defaultValue: '',
                required: true,
                responseSet: null,
                validation: { regexp: '', errorMessage: '' },
              },
            ],
          },
        ],
      }
      const store = useCrfPreviewStore()
      store.load(draft)
      expect(store.schema).not.toBeNull()
      expect(store.schema!.sections).toHaveLength(1)
      const items = store.schema!.sections[0]!.items
      expect(items[0]!.oid).toBe('I_NAME')
      expect(items[0]!.dataType).toBe('string')
      expect(items[1]!.dataType).toBe('integer')
    })

    it('does NOT call apiGet on load', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      expect(apiGet).not.toHaveBeenCalled()
    })
  })

  describe('fillSampleData()', () => {
    it('populates every item with a type-appropriate value', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.fillSampleData()
      expect(store.values.I_NAME).toBe('Sample text')
      // 0..120 midpoint = 60
      expect(store.values.I_AGE).toBe(60)
      // 0..100 midpoint = 50
      expect(store.values.I_BCVA_OD).toBe(50)
      // 0..80 midpoint = 40.0 (one decimal)
      expect(store.values.I_IOP_OD).toBe(40)
      // Fixed seed date
      expect(store.values.I_DOB).toBe('2026-06-15')
      // First option for select-one
      expect(store.values.I_SEX).toBe('F')
      // First two options for select-multi
      expect(store.values.I_COMORBID).toEqual(['HTN', 'DM'])
      // BL → '1' (Yes). New radio rendering uses string values
      // '1' / '0' / '' (unanswered) so the show-when comparator works.
      expect(store.values.I_CONSENT).toBe('1')
      // Status promoted from not-started → in-progress
      expect(store.status).toBe('in-progress')
    })

    it('is a no-op when no schema is loaded', () => {
      const store = useCrfPreviewStore()
      store.fillSampleData()
      expect(store.values).toEqual({})
    })
  })

  describe('sampleValueFor()', () => {
    it('rounds REAL midpoint to one decimal', () => {
      const item: CrfItem = {
        oid: 'I_X', label: 'X', dataType: 'real', required: false, min: 0, max: 7,
      } as CrfItem
      // 0+7 = 7 → midpoint 3.5 → rounds to 3.5
      expect(sampleValueFor(item)).toBe(3.5)
    })

    it('falls back to 0..100 when no range is declared', () => {
      const intItem: CrfItem = { oid: 'I_X', label: 'X', dataType: 'integer', required: false } as CrfItem
      expect(sampleValueFor(intItem)).toBe(50)
    })

    it('emits empty value when select-one has no options', () => {
      const item: CrfItem = { oid: 'I_X', label: 'X', dataType: 'select-one', required: false } as CrfItem
      expect(sampleValueFor(item)).toBe('')
    })
  })

  describe('reset()', () => {
    it('clears typed values + status but preserves schema', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.fillSampleData()
      expect(Object.keys(store.values).length).toBeGreaterThan(0)
      store.reset()
      expect(store.values).toEqual({})
      expect(store.status).toBe('not-started')
      expect(store.schema).not.toBeNull()
    })
  })

  describe('save() + markComplete()', () => {
    it('save() never calls apiPost', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.setValue('I_NAME', 'x')
      store.save()
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('markComplete() never calls apiPost, even when complete', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.fillSampleData()
      store.markComplete()
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('markComplete() flips status when isComplete is true', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.fillSampleData()
      // I_NAME + I_AGE are the only required items; sample fills them
      expect(store.isComplete).toBe(true)
      store.markComplete()
      expect(store.status).toBe('complete')
    })

    it('markComplete() refuses to flip status when invalid', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      // I_NAME + I_AGE blank → not complete
      store.markComplete()
      expect(store.status).toBe('not-started')
    })
  })

  describe('validation parity', () => {
    it('flags IOP > 80 via itemErrors (range)', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.setValue('I_IOP_OD', 99)
      expect(store.itemErrors.I_IOP_OD).toBeDefined()
      expect(store.itemErrors.I_IOP_OD).toMatch(/≤ 80/)
    })

    it('flags BCVA letters > 100 via itemErrors (range)', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.setValue('I_BCVA_OD', 105)
      expect(store.itemErrors.I_BCVA_OD).toBeDefined()
      expect(store.itemErrors.I_BCVA_OD).toMatch(/≤ 100/)
    })

    it('flags missing required item I_NAME', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      expect(store.itemErrors.I_NAME).toBeDefined()
      expect(store.itemErrors.I_NAME).toMatch(/required/i)
    })
  })

  describe('close()', () => {
    it('drops the loaded schema + flips isOpen off', () => {
      const store = useCrfPreviewStore()
      store.load(RUNTIME_SCHEMA)
      store.close()
      expect(store.isOpen).toBe(false)
      expect(store.schema).toBeNull()
      expect(store.values).toEqual({})
      expect(store.crfName).toBe('')
    })
  })
})

describe('draftToPreviewSchema()', () => {
  it('maps response-type-driven render: radio → select-one, multi-select → select-multi', () => {
    const draft: AuthoringDraft = {
      versionName: 'v1.0',
      versionDescription: '',
      revisionNotes: '',
      sections: [
        {
          uid: 'sec-1', label: 'S1', title: 'Section 1', instructions: '', ordinal: 1,
          items: [
            {
              uid: 'item-1',
              name: 'YN', oid: 'I_YN', descriptionLabel: 'Yes/No',
              leftItemText: '', rightItemText: '', units: '',
              dataType: 'ST',
              responseType: 'radio',
              defaultValue: '', required: false,
              responseSet: {
                type: 'radio',
                label: 'yn_set',
                options: [{ text: 'Yes', value: 'Y' }, { text: 'No', value: 'N' }],
              },
              validation: { regexp: '', errorMessage: '' },
            },
            {
              uid: 'item-2',
              name: 'COMORBID', oid: 'I_COMORBID', descriptionLabel: 'Comorbidities',
              leftItemText: '', rightItemText: '', units: '',
              dataType: 'ST',
              responseType: 'multi-select',
              defaultValue: '', required: false,
              responseSet: {
                type: 'multi-select',
                label: 'comorbid_set',
                options: [{ text: 'HTN', value: 'HTN' }, { text: 'DM', value: 'DM' }],
              },
              validation: { regexp: '', errorMessage: '' },
            },
          ],
        },
      ],
    }
    const schema = draftToPreviewSchema(draft)
    expect(schema.sections[0]!.items[0]!.dataType).toBe('select-one')
    expect(schema.sections[0]!.items[0]!.options).toEqual([
      { code: 'Y', label: 'Yes' },
      { code: 'N', label: 'No' },
    ])
    expect(schema.sections[0]!.items[1]!.dataType).toBe('select-multi')
  })

  /* -------------------------------------------------------------------- */
  /* Phase E.6 polish-runtime — preview-parity tests for show-when.        */
  /*                                                                       */
  /* The preview store shares the same {@link buildItemIndex} +            */
  /* {@link isItemHiddenByRule} evaluator the runtime store uses, so       */
  /* these tests pin the same semantics on the preview path.               */
  /* -------------------------------------------------------------------- */

  it('preview: isItemHidden flips with the source value', () => {
    const store = useCrfPreviewStore()
    const schema: CrfSchema = {
      oid: 'F_SW', name: 'SW', version: 'v1',
      sections: [{
        oid: 'S_SW', title: 'SW', items: [
          { oid: 'I_GROUP', label: 'Group', dataType: 'string', required: false },
          {
            oid: 'I_DEP', label: 'Dep', dataType: 'string', required: false,
            showWhen: '{"sourceItemOid":"I_GROUP","comparator":"==","literal":"cohort-A"}',
          },
        ],
      }],
    }
    store.load(schema)
    expect(store.isItemHidden('I_DEP')).toBe(true)
    store.setValue('I_GROUP', 'cohort-A')
    expect(store.isItemHidden('I_DEP')).toBe(false)
    store.setValue('I_GROUP', 'cohort-B')
    expect(store.isItemHidden('I_DEP')).toBe(true)
  })

  it('preview: hide → show preserves the typed value', () => {
    const store = useCrfPreviewStore()
    const schema: CrfSchema = {
      oid: 'F_SW', name: 'SW', version: 'v1',
      sections: [{
        oid: 'S_SW', title: 'SW', items: [
          { oid: 'I_GROUP', label: 'Group', dataType: 'string', required: false },
          {
            oid: 'I_DEP', label: 'Dep', dataType: 'string', required: false,
            showWhen: '{"sourceItemOid":"I_GROUP","comparator":"==","literal":"cohort-A"}',
          },
        ],
      }],
    }
    store.load(schema)
    store.setValue('I_GROUP', 'cohort-A')
    store.setValue('I_DEP', 'preserved')
    store.setValue('I_GROUP', 'cohort-B')
    expect(store.values.I_DEP).toBeUndefined()
    expect(store.hiddenValues.I_DEP).toBe('preserved')
    store.setValue('I_GROUP', 'cohort-A')
    expect(store.values.I_DEP).toBe('preserved')
  })

  it('preview: markComplete is not blocked by hidden required item', () => {
    const store = useCrfPreviewStore()
    const schema: CrfSchema = {
      oid: 'F_SW', name: 'SW', version: 'v1',
      sections: [{
        oid: 'S_SW', title: 'SW', items: [
          { oid: 'I_GROUP', label: 'Group', dataType: 'string', required: true },
          {
            oid: 'I_DEP', label: 'Dep', dataType: 'string', required: true,
            showWhen: '{"sourceItemOid":"I_GROUP","comparator":"==","literal":"cohort-A"}',
          },
        ],
      }],
    }
    store.load(schema)
    store.setValue('I_GROUP', 'cohort-B')
    // I_DEP is hidden — should not block completion.
    expect(store.isItemHidden('I_DEP')).toBe(true)
    expect(store.isComplete).toBe(true)
  })
})
