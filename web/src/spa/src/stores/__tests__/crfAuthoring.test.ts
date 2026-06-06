import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  dataTypeIsBoolean,
  responseTypeRequiresOptions,
  useCrfAuthoringStore,
} from '../crfAuthoring'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { CrfVersion } from '@/types/crfLibrary'

/**
 * Phase E.6 Milestone B — Vitest coverage for the CRF authoring
 * Pinia store.
 *
 * Strategy mirrors `auth.test.ts`: vi.mock('@/api/client') stubs the
 * HTTP helpers so we can assert call shape + replay error branches
 * without a live backend.
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

const FIXTURE_VERSION: CrfVersion = {
  oid: 'F_DEMO_V1',
  name: 'v1.0',
  description: 'Demo',
  revisionNotes: 'Initial',
  status: 'available',
  uploadedAt: '2026-06-03T10:00:00Z',
}

describe('useCrfAuthoringStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiPost).mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('initial state', () => {
    it('seeds an empty draft with one default section', () => {
      const store = useCrfAuthoringStore()
      expect(store.draft.versionName).toBe('')
      expect(store.draft.sections).toHaveLength(1)
      expect(store.draft.sections[0]!.label).toBe('S1')
      expect(store.draft.sections[0]!.items).toEqual([])
    })
  })

  describe('setMetadata + setVersionName + setVersionDescription', () => {
    it('updates version-name and -description independently', () => {
      const store = useCrfAuthoringStore()
      store.setVersionName('v1.0')
      store.setVersionDescription('Demo CRF')
      store.setMetadata({ revisionNotes: 'Initial draft' })
      expect(store.draft.versionName).toBe('v1.0')
      expect(store.draft.versionDescription).toBe('Demo CRF')
      expect(store.draft.revisionNotes).toBe('Initial draft')
    })
  })

  describe('addSection', () => {
    it('appends a new section with auto-numbered label + title', () => {
      const store = useCrfAuthoringStore()
      store.addSection()
      expect(store.draft.sections).toHaveLength(2)
      expect(store.draft.sections[1]!.label).toBe('S2')
      expect(store.draft.sections[1]!.title).toBe('Section 2')
      expect(store.draft.sections[1]!.ordinal).toBe(2)
    })

    it('respects an explicit seed', () => {
      const store = useCrfAuthoringStore()
      store.addSection({ label: 'DEMO', title: 'Demographics' })
      expect(store.draft.sections[1]!.label).toBe('DEMO')
      expect(store.draft.sections[1]!.title).toBe('Demographics')
    })
  })

  describe('removeSection + reorderSections', () => {
    it('refuses to remove the last section', () => {
      const store = useCrfAuthoringStore()
      store.removeSection(0)
      expect(store.draft.sections).toHaveLength(1)
    })

    it('removes additional sections + renumbers ordinals', () => {
      const store = useCrfAuthoringStore()
      store.addSection({ label: 'B', title: 'B' })
      store.addSection({ label: 'C', title: 'C' })
      store.removeSection(1)
      expect(store.draft.sections.map((s) => s.label)).toEqual(['S1', 'C'])
      expect(store.draft.sections.map((s) => s.ordinal)).toEqual([1, 2])
    })

    it('reorderSections replaces the list and rewrites ordinals', () => {
      const store = useCrfAuthoringStore()
      store.addSection({ label: 'B', title: 'B' })
      store.addSection({ label: 'C', title: 'C' })
      const flipped = [...store.draft.sections].reverse()
      store.reorderSections(flipped)
      expect(store.draft.sections.map((s) => s.label)).toEqual(['C', 'B', 'S1'])
      expect(store.draft.sections.map((s) => s.ordinal)).toEqual([1, 2, 3])
    })
  })

  describe('addItem + setItemField', () => {
    it('appends an item to the targeted section', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0)
      expect(store.draft.sections[0]!.items).toHaveLength(1)
      expect(store.draft.sections[0]!.items[0]!.dataType).toBe('ST')
      expect(store.draft.sections[0]!.items[0]!.responseType).toBe('text')
      expect(store.draft.sections[0]!.items[0]!.required).toBe(false)
      expect(store.draft.sections[0]!.items[0]!.validation).toEqual({ regexp: '', errorMessage: '' })
    })

    it('is a no-op when the section index is out of range', () => {
      const store = useCrfAuthoringStore()
      store.addItem(99)
      expect(store.draft.sections[0]!.items).toHaveLength(0)
    })

    it('mutates item fields idempotently — repeated calls converge', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0)
      store.setItemField(0, 0, 'name', 'AGE')
      store.setItemField(0, 0, 'name', 'AGE')
      store.setItemField(0, 0, 'descriptionLabel', 'Age')
      store.setItemField(0, 0, 'dataType', 'INT')
      store.setItemField(0, 0, 'required', true)
      const item = store.draft.sections[0]!.items[0]!
      expect(item.name).toBe('AGE')
      expect(item.descriptionLabel).toBe('Age')
      expect(item.dataType).toBe('INT')
      expect(item.required).toBe(true)
    })

    it('is a no-op when the item index is out of range', () => {
      const store = useCrfAuthoringStore()
      store.setItemField(0, 99, 'name', 'X')
      expect(store.draft.sections[0]!.items).toHaveLength(0)
    })
  })

  describe('reorderItems', () => {
    it('replaces the items array in the targeted section', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { name: 'A' })
      store.addItem(0, { name: 'B' })
      const items = store.draft.sections[0]!.items
      const flipped = [items[1]!, items[0]!]
      store.reorderItems(0, flipped)
      expect(store.draft.sections[0]!.items.map((i) => i.name)).toEqual(['B', 'A'])
    })
  })

  describe('suggestOid', () => {
    it('uppercases + underscores the item name', () => {
      const store = useCrfAuthoringStore()
      expect(store.suggestOid('Age at consent')).toBe('AGE_AT_CONSENT')
      expect(store.suggestOid('  hbA1c % ')).toBe('HBA1C')
      expect(store.suggestOid('')).toBe('')
    })
  })

  describe('buildPayload', () => {
    it('trims whitespace + preserves ordinals + drops empty optionals', () => {
      const store = useCrfAuthoringStore()
      store.setVersionName('  v1.0 ')
      store.setVersionDescription('  Demo  ')
      store.addItem(0)
      store.setItemField(0, 0, 'name', ' AGE ')
      store.setItemField(0, 0, 'descriptionLabel', ' Age ')
      store.setItemField(0, 0, 'dataType', 'INT')
      store.setItemField(0, 0, 'required', true)
      const payload = store.buildPayload() as {
        versionName: string
        versionDescription: string
        sections: Array<{
          items: Array<{
            name: string
            dataType: string
            required: boolean
            validation?: unknown
          }>
        }>
      }
      expect(payload.versionName).toBe('v1.0')
      expect(payload.versionDescription).toBe('Demo')
      expect(payload.sections).toHaveLength(1)
      const item = payload.sections[0]!.items[0]!
      expect(item.name).toBe('AGE')
      expect(item.dataType).toBe('INT')
      expect(item.required).toBe(true)
      // Empty validation is dropped from the wire.
      expect(item.validation).toBeUndefined()
    })

    it('serialises an inline response set with options', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        name: 'CONSENT',
        dataType: 'ST',
        responseType: 'radio',
        responseSet: {
          type: 'radio',
          label: 'yes_no',
          options: [
            { text: 'Yes', value: '1' },
            { text: 'No', value: '0' },
          ],
        },
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet?: { type?: string; label?: string; options?: unknown[] } }> }>
      }
      const rs = payload.sections[0]!.items[0]!.responseSet!
      expect(rs.type).toBe('radio')
      expect(rs.label).toBe('yes_no')
      expect(rs.options).toEqual([
        { text: 'Yes', value: '1' },
        { text: 'No', value: '0' },
      ])
    })

    it('serialises a by-ref response set', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        name: 'CONSENT',
        responseType: 'radio',
        responseSet: { ref: { label: 'yes_no' } },
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet?: { ref?: { label?: string } } }> }>
      }
      expect(payload.sections[0]!.items[0]!.responseSet).toEqual({ ref: { label: 'yes_no' } })
    })
  })

  describe('submit()', () => {
    it('POSTs the trimmed payload to the JSON authoring endpoint', async () => {
      vi.mocked(apiPost).mockResolvedValueOnce(FIXTURE_VERSION)
      const store = useCrfAuthoringStore()
      store.setVersionName('v1.0')
      store.setVersionDescription('Demo')
      store.addItem(0)
      store.setItemField(0, 0, 'name', 'AGE')
      store.setItemField(0, 0, 'descriptionLabel', 'Age')
      store.setItemField(0, 0, 'dataType', 'INT')
      store.setItemField(0, 0, 'required', true)

      const result = await store.submit('F_DEMO')
      expect(result.ok).toBe(true)
      if (!result.ok) return  // type narrowing
      expect(result.version).toEqual(FIXTURE_VERSION)
      expect(apiPost).toHaveBeenCalledWith(
        '/pages/api/v1/crfs/F_DEMO/versions',
        expect.objectContaining({
          versionName: 'v1.0',
          versionDescription: 'Demo',
          sections: expect.arrayContaining([
            expect.objectContaining({
              label: 'S1',
              title: 'Section 1',
              items: expect.arrayContaining([
                expect.objectContaining({
                  name: 'AGE',
                  descriptionLabel: 'Age',
                  dataType: 'INT',
                  required: true,
                }),
              ]),
            }),
          ]),
        }),
      )
    })

    it('returns ok:false with fieldErrors + parseErrors on 400', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(400, 'Validation failed', {
        message: 'Validation failed',
        errors: [
          { field: 'versionName', message: 'versionName is required' },
          { field: 'body', message: 'The DESCRIPTION_LABEL column was blank' },
        ],
      }))
      const store = useCrfAuthoringStore()
      const result = await store.submit('F_DEMO')
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.fieldErrors.versionName).toBe('versionName is required')
      expect(result.parseErrors).toEqual([
        'The DESCRIPTION_LABEL column was blank',
      ])
    })

    it('records error + returns ok:false on 401 (auth-denied)', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(401, 'Unauthorized', null))
      const store = useCrfAuthoringStore()
      const result = await store.submit('F_DEMO')
      expect(result.ok).toBe(false)
      expect(store.error).not.toBeNull()
    })

    it('returns ok:false on network failure', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiNetworkError('boom', new Error()))
      const store = useCrfAuthoringStore()
      const result = await store.submit('F_DEMO')
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.message).toContain('Backend nicht erreichbar')
    })
  })

  describe('preview()', () => {
    it('resolves OK on a synthetic valid draft', async () => {
      vi.mocked(apiPost).mockResolvedValueOnce({
        crfOid: 'F_DEMO',
        versionName: 'v1.0',
        sectionCount: 1,
        itemCount: 1,
      })
      const store = useCrfAuthoringStore()
      store.setVersionName('v1.0')
      store.addItem(0)
      store.setItemField(0, 0, 'name', 'AGE')
      store.setItemField(0, 0, 'descriptionLabel', 'Age')
      store.setItemField(0, 0, 'dataType', 'INT')

      const result = await store.preview('F_DEMO')
      expect(result.ok).toBe(true)
      if (!result.ok) return
      expect(result.preview.sectionCount).toBe(1)
      expect(result.preview.itemCount).toBe(1)
      expect(apiPost).toHaveBeenCalledWith(
        '/pages/api/v1/crfs/F_DEMO/versions:preview',
        expect.objectContaining({ versionName: 'v1.0' }),
      )
    })

    it('returns ok:false with fieldErrors on 400', async () => {
      vi.mocked(apiPost).mockRejectedValueOnce(new ApiError(400, 'Validation failed', {
        message: 'Validation failed',
        errors: [
          { field: 'sections[0].items[0].descriptionLabel', message: 'Description label is required' },
        ],
      }))
      const store = useCrfAuthoringStore()
      const result = await store.preview('F_DEMO')
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.fieldErrors['sections[0].items[0].descriptionLabel']).toContain('required')
    })
  })

  describe('loadResponseSetCatalog + createCatalogEntry', () => {
    it('populates the catalog from the backend', async () => {
      vi.mocked(apiGet).mockResolvedValueOnce([
        { label: 'yes_no', responseType: 'radio', options: [], usageCount: 3, inActiveStudy: true },
      ])
      const store = useCrfAuthoringStore()
      await store.loadResponseSetCatalog()
      expect(store.responseSetCatalog).toHaveLength(1)
      expect(store.responseSetCatalog[0]!.label).toBe('yes_no')
    })

    it('createCatalogEntry pushes the echoed entry to the catalog', async () => {
      vi.mocked(apiPost).mockResolvedValueOnce({
        label: 'snellen',
        responseType: 'single-select',
        options: [{ text: '20/20', value: '20' }],
        usageCount: 0,
        inActiveStudy: false,
      })
      const store = useCrfAuthoringStore()
      const created = await store.createCatalogEntry({
        label: 'snellen',
        responseType: 'single-select',
        options: [{ text: '20/20', value: '20' }],
      })
      expect(created).not.toBeNull()
      expect(store.responseSetCatalog).toHaveLength(1)
      expect(store.responseSetCatalog[0]!.label).toBe('snellen')
    })
  })

  describe('BL (boolean) data type', () => {
    it('dataTypeIsBoolean discriminates BL from the other tokens', () => {
      expect(dataTypeIsBoolean('BL')).toBe(true)
      expect(dataTypeIsBoolean('ST')).toBe(false)
      expect(dataTypeIsBoolean('INT')).toBe(false)
      expect(dataTypeIsBoolean('REAL')).toBe(false)
      expect(dataTypeIsBoolean('DATE')).toBe(false)
      expect(dataTypeIsBoolean('PDATE')).toBe(false)
      expect(dataTypeIsBoolean('FILE')).toBe(false)
    })

    it('round-trips dataType=BL through setItemField + buildPayload', () => {
      const store = useCrfAuthoringStore()
      store.setVersionName('v1.0')
      store.addItem(0)
      store.setItemField(0, 0, 'name', 'HAS_CONSENT')
      store.setItemField(0, 0, 'descriptionLabel', 'Consent on file')
      store.setItemField(0, 0, 'dataType', 'BL')
      expect(store.draft.sections[0]!.items[0]!.dataType).toBe('BL')

      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{
          dataType: string
          responseSet?: { type?: string; label?: string; options?: Array<{ text: string; value: string }> }
        }> }>
      }
      const item = payload.sections[0]!.items[0]!
      expect(item.dataType).toBe('BL')
    })

    it('synthesises a fixed Yes/No response set regardless of operator input', () => {
      const store = useCrfAuthoringStore()
      // Seed an operator-authored inline set with junk options — the BL
      // branch must overwrite it on the wire so the parser sees the
      // canonical Yes/No pair.
      store.addItem(0, {
        name: 'PREGNANT',
        descriptionLabel: 'Pregnant at baseline',
        dataType: 'BL',
        responseSet: {
          type: 'radio',
          label: 'junk',
          options: [{ text: 'A', value: 'a' }, { text: 'B', value: 'b' }],
        },
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{
          responseSet?: { type?: string; label?: string; options?: Array<{ text: string; value: string }> }
        }> }>
      }
      const rs = payload.sections[0]!.items[0]!.responseSet!
      expect(rs.type).toBe('single-select')
      expect(rs.options).toEqual([
        { text: 'Yes', value: '1' },
        { text: 'No', value: '0' },
      ])
      // Label derived from item name when present.
      expect(rs.label).toBe('pregnant_yes_no')
    })

    it('falls back to generic label when item name is blank', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        descriptionLabel: 'Yes-or-no?',
        dataType: 'BL',
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet?: { label?: string } }> }>
      }
      expect(payload.sections[0]!.items[0]!.responseSet!.label).toBe('yes_no')
    })

    it('keeps dataType=BL even when responseSet is null on the draft', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        name: 'IS_SMOKER',
        descriptionLabel: 'Smoker?',
        dataType: 'BL',
        responseSet: null,
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{
          dataType: string
          responseSet?: { type?: string; options?: Array<{ value: string }> }
        }> }>
      }
      const item = payload.sections[0]!.items[0]!
      expect(item.dataType).toBe('BL')
      expect(item.responseSet!.type).toBe('single-select')
      expect(item.responseSet!.options!.map((o) => o.value)).toEqual(['1', '0'])
    })

    it('BL response set has options — does not collapse to open-text branch', () => {
      // Guard against a regression where responseTypeRequiresOptions is
      // consulted before the dataType=BL branch.
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        name: 'FLAG',
        descriptionLabel: 'Flag',
        dataType: 'BL',
        responseType: 'text',  // open-text — should be ignored for BL
        responseSet: null,
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet?: { type?: string; options?: unknown[] } }> }>
      }
      const rs = payload.sections[0]!.items[0]!.responseSet!
      expect(rs.type).toBe('single-select')
      expect(rs.options).toHaveLength(2)
    })

    it('non-BL items remain unaffected by the BL synthesis branch', () => {
      // Sanity check — the BL helper does not leak Yes/No options onto a
      // text item authored alongside it.
      const store = useCrfAuthoringStore()
      store.addItem(0, { name: 'AGE', descriptionLabel: 'Age', dataType: 'INT' })
      store.addItem(0, { name: 'FLAG', descriptionLabel: 'Flag', dataType: 'BL' })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{
          dataType: string
          responseSet?: { type?: string; options?: Array<{ value: string }> }
        }> }>
      }
      const [intItem, blItem] = payload.sections[0]!.items
      expect(intItem!.dataType).toBe('INT')
      // INT was authored without an explicit response set — store
      // synthesises the implicit open-text branch (type=text, no options).
      expect(intItem!.responseSet!.type).toBe('text')
      expect(intItem!.responseSet!.options).toBeUndefined()
      expect(blItem!.dataType).toBe('BL')
      expect(blItem!.responseSet!.type).toBe('single-select')
    })

    it('responseTypeRequiresOptions still returns true for option-bearing types', () => {
      // Smoke check the export — BL bypasses this helper but the
      // helper itself is unchanged.
      expect(responseTypeRequiresOptions('radio')).toBe(true)
      expect(responseTypeRequiresOptions('single-select')).toBe(true)
      expect(responseTypeRequiresOptions('multi-select')).toBe(true)
      expect(responseTypeRequiresOptions('checkbox')).toBe(true)
      expect(responseTypeRequiresOptions('text')).toBe(false)
    })
  })

  describe('reset()', () => {
    it('clears mutations to a fresh empty draft', () => {
      const store = useCrfAuthoringStore()
      store.setVersionName('v1.0')
      store.addSection()
      store.reset()
      expect(store.draft.versionName).toBe('')
      expect(store.draft.sections).toHaveLength(1)
      expect(store.error).toBeNull()
    })
  })
})
