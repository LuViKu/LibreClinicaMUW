import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  allowedResponseTypesForDataType,
  dataTypeIsBoolean,
  findDuplicateOidItems,
  hasShowWhen,
  newRepeatingTableColumn,
  reseedTableColumnKeySeq,
  reseedUidCounter,
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

  describe('allowedResponseTypesForDataType (matrix)', () => {
    it('ST permits text + textarea + the option-bearing entries', () => {
      const allowed = allowedResponseTypesForDataType('ST')
      expect(allowed).toEqual(['text', 'textarea', 'radio', 'single-select', 'multi-select', 'checkbox'])
    })

    it('INT permits text + Likert-style discrete options', () => {
      const allowed = allowedResponseTypesForDataType('INT')
      expect(allowed).toEqual(['text', 'radio', 'single-select'])
      expect(allowed).not.toContain('textarea')
      expect(allowed).not.toContain('file')
    })

    it('REAL is restricted to the numeric text bucket', () => {
      const allowed = allowedResponseTypesForDataType('REAL')
      expect(allowed).toEqual(['text'])
    })

    it('DATE collapses to text (no date-specific response type yet)', () => {
      expect(allowedResponseTypesForDataType('DATE')).toEqual(['text'])
    })

    it('PDATE collapses to text (no partial-date response type yet)', () => {
      expect(allowedResponseTypesForDataType('PDATE')).toEqual(['text'])
    })

    it('FILE is restricted to file', () => {
      expect(allowedResponseTypesForDataType('FILE')).toEqual(['file'])
    })

    it('BL hardwires single-select (Yes/No)', () => {
      expect(allowedResponseTypesForDataType('BL')).toEqual(['single-select'])
    })
  })

  describe('hasShowWhen', () => {
    it('returns false when showWhen is absent', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0)
      const item = store.draft.sections[0]!.items[0]!
      expect(hasShowWhen(item)).toBe(false)
    })

    it('returns false when sourceItemOid is blank', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { showWhen: { sourceItemOid: '   ', comparator: '==', literal: '1' } })
      const item = store.draft.sections[0]!.items[0]!
      expect(hasShowWhen(item)).toBe(false)
    })

    it('returns true when the rule has a non-blank source', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { showWhen: { sourceItemOid: 'SPECTRALIS_DONE', comparator: '==', literal: '1' } })
      const item = store.draft.sections[0]!.items[0]!
      expect(hasShowWhen(item)).toBe(true)
    })
  })

  describe('buildPayload — show-when', () => {
    it('includes showWhen on the wire when the rule is set', () => {
      const store = useCrfAuthoringStore()
      store.setVersionName('v1.0')
      store.addItem(0, {
        name: 'SPECTRALIS_DONE',
        descriptionLabel: 'Spectralis done',
        dataType: 'BL',
      })
      store.addItem(0, {
        name: 'SPECTRALIS_DATE',
        descriptionLabel: 'Spectralis date',
        dataType: 'DATE',
        showWhen: {
          sourceItemOid: 'SPECTRALIS_DONE',
          comparator: '==',
          literal: '1',
        },
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ showWhen?: Record<string, unknown>; showItem?: unknown; parentItemOid?: unknown }> }>
      }
      const items = payload.sections[0]!.items
      expect(items[0]!.showWhen).toBeUndefined()
      expect(items[1]!.showWhen).toEqual({
        sourceItemOid: 'SPECTRALIS_DONE',
        comparator: '==',
        literal: '1',
      })
      // #26 — an equality rule is ALSO mirrored onto the legacy fields the
      // workbook adapter persists (→ scd_item_metadata), so it survives save.
      expect(items[1]!.parentItemOid).toBe('SPECTRALIS_DONE')
      expect(items[1]!.showItem).toBe('1')
    })

    it('mirrors only equality rules onto showItem/parentItemOid (legacy scd is =-only)', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { name: 'SRC', descriptionLabel: 'src', dataType: 'INT' })
      store.addItem(0, {
        name: 'GT',
        descriptionLabel: 'gt',
        dataType: 'ST',
        showWhen: { sourceItemOid: 'SRC', comparator: '>', literal: '5' },
      })
      const gt = (store.buildPayload() as {
        sections: Array<{ items: Array<Record<string, unknown>> }>
      }).sections[0]!.items[1]!
      // Display-only: the rule is on showWhen but not the persisted fields.
      expect(gt.showWhen).toEqual({ sourceItemOid: 'SRC', comparator: '>', literal: '5' })
      expect('showItem' in gt).toBe(false)
      expect('parentItemOid' in gt).toBe(false)
    })

    it('omits showWhen when undefined', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { name: 'AGE', descriptionLabel: 'Age', dataType: 'INT' })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ showWhen?: unknown }> }>
      }
      expect('showWhen' in payload.sections[0]!.items[0]!).toBe(false)
    })

    it('omits showWhen when sourceItemOid is blank (incomplete rule)', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        name: 'X',
        descriptionLabel: 'X',
        showWhen: { sourceItemOid: '', comparator: '==', literal: '1' },
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ showWhen?: unknown }> }>
      }
      expect('showWhen' in payload.sections[0]!.items[0]!).toBe(false)
    })
  })

  /**
   * Response-shape reconciliation — the invariant that used to live in a
   * watcher inside the (dead) ItemEditor.vue, which is why the palette
   * drop path never got it.
   */
  describe('reconcileItemResponseShape', () => {
    type Inline = { type: string; label: string; options: Array<{ text: string; value: string }> }
    const inlineOf = (store: ReturnType<typeof useCrfAuthoringStore>, i = 0): Inline =>
      store.draft.sections[0]!.items[i]!.responseSet as unknown as Inline

    it('seeds the Ja/Nein/Unbekannt trio for a bare TRISTATE_REASON drop', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { dataType: 'TRISTATE_REASON' })
      const item = store.draft.sections[0]!.items[0]!
      // Previously left at 'text' — out of the matrix — with a null set,
      // which the rail then rendered as an uncorrectable disabled select.
      expect(item.responseType).toBe('single-select')
      expect(inlineOf(store).options.map((o) => o.value)).toEqual(['JA', 'NEIN', 'UNBEKANNT'])
    })

    it('clamps a bare FILE drop to the file response type', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { dataType: 'FILE' })
      const item = store.draft.sections[0]!.items[0]!
      expect(item.responseType).toBe('file')
      expect(item.responseSet).toBeNull()
    })

    it('leaves BL without an inline set (Yes/No is synthesised at wire time)', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { dataType: 'BL' })
      const item = store.draft.sections[0]!.items[0]!
      expect(item.responseType).toBe('single-select')
      expect(item.responseSet).toBeNull()
    })

    it('seeds two blank rows when the response type gains options', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0)
      store.setItemField(0, 0, 'responseType', 'single-select')
      expect(inlineOf(store).options).toHaveLength(2)
      expect(inlineOf(store).options.every((o) => o.text === '' && o.value === '')).toBe(true)
    })

    it('clears the inline set when the response type drops options again', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0)
      store.setItemField(0, 0, 'responseType', 'single-select')
      store.setItemField(0, 0, 'responseType', 'text')
      expect(store.draft.sections[0]!.items[0]!.responseSet).toBeNull()
    })

    it('preserves authored options across a compatible data-type change', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        responseType: 'single-select',
        responseSet: { type: 'single-select', label: '', options: [{ text: 'A', value: '1' }] },
      })
      // single-select is in INT's allowed matrix, so nothing is lost.
      store.setItemField(0, 0, 'dataType', 'INT')
      expect(inlineOf(store).options).toEqual([{ text: 'A', value: '1' }])
    })

    it('clears options when the new data type cannot carry them', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        responseType: 'single-select',
        responseSet: { type: 'single-select', label: '', options: [{ text: 'A', value: '1' }] },
      })
      store.setItemField(0, 0, 'dataType', 'DATE')
      const item = store.draft.sections[0]!.items[0]!
      expect(item.responseType).toBe('text')
      expect(item.responseSet).toBeNull()
    })

    it('does not disturb options when an unrelated field is edited', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { dataType: 'TRISTATE_REASON' })
      store.setItemField(0, 0, 'name', 'SMOKER')
      expect(inlineOf(store).options).toHaveLength(3)
    })

    it('leaves a catalog-linked (ref) response set untouched', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        responseType: 'radio',
        responseSet: { ref: { label: 'yes_no' } },
      })
      expect(store.draft.sections[0]!.items[0]!.responseSet).toEqual({ ref: { label: 'yes_no' } })
    })

    it('preserves preset-supplied inline options verbatim', () => {
      const store = useCrfAuthoringStore()
      const options = [
        { text: 'Ja', value: 'JA' },
        { text: 'Nein', value: 'NEIN' },
      ]
      store.addItem(0, {
        responseType: 'single-select',
        responseSet: { type: 'single-select', label: 'iop_tristate', options },
      })
      expect(inlineOf(store).label).toBe('iop_tristate')
      expect(inlineOf(store).options).toEqual(options)
    })
  })

  describe('buildPayload — choice response sets', () => {
    const CHOICE_TYPES = ['radio', 'single-select', 'multi-select', 'checkbox'] as const

    it.each(CHOICE_TYPES)('always emits an options array for %s', (rt) => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { name: 'Q1', responseType: rt })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet: { type: string; options?: unknown } }> }>
      }
      const rs = payload.sections[0]!.items[0]!.responseSet
      expect(rs.type).toBe(rt)
      // Regression guard: the canvas used to ship a choice type with no
      // options key at all, which the backend rejected with an
      // unlocalised "requires at least one option".
      expect(Array.isArray(rs.options)).toBe(true)
    })

    it('gives two blank-labelled dropdowns distinct response-set labels', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        name: 'CONSENT',
        responseType: 'single-select',
        responseSet: { type: 'single-select', label: '', options: [{ text: 'Ja', value: 'JA' }] },
      })
      store.addItem(0, {
        name: 'SEX',
        responseType: 'single-select',
        responseSet: { type: 'single-select', label: '', options: [{ text: 'W', value: 'F' }] },
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet: { label: string } }> }>
      }
      const [first, second] = payload.sections[0]!.items
      // Both used to serialise with a blank label, which the adapter
      // stamped as a shared `single_select_options` token — and the
      // parser then rejected the whole version because one label carried
      // two different option lists. A CRF could hold only ONE dropdown.
      expect(first!.responseSet.label).toBe('consent_options')
      expect(second!.responseSet.label).toBe('sex_options')
      expect(first!.responseSet.label).not.toBe(second!.responseSet.label)
    })

    it('lets an operator-typed label win over the derived one', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, {
        name: 'CONSENT',
        responseType: 'single-select',
        responseSet: { type: 'single-select', label: 'ja_nein', options: [{ text: 'Ja', value: 'JA' }] },
      })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet: { label: string } }> }>
      }
      expect(payload.sections[0]!.items[0]!.responseSet.label).toBe('ja_nein')
    })

    it('filters blank starter rows off the wire', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { name: 'Q1', responseType: 'single-select' })
      const payload = store.buildPayload() as {
        sections: Array<{ items: Array<{ responseSet: { options: unknown[] } }> }>
      }
      expect(payload.sections[0]!.items[0]!.responseSet.options).toEqual([])
    })

    it('emits the full four-option dropdown the HealthAEye CRF needs', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0, { name: 'CHILDBEARING', responseType: 'single-select' })
      store.setItemResponseSet(0, 0, {
        type: 'single-select',
        label: '',
        options: [
          { text: 'Ja', value: 'JA' },
          { text: 'Nein', value: 'NEIN' },
          { text: 'Nicht zutreffend', value: 'NICHT_ZUTREFFEND' },
          { text: 'Unbekannt', value: 'UNBEKANNT' },
        ],
      })
      const payload = store.buildPayload() as {
        sections: Array<{
          items: Array<{ responseSet: { label: string; options: Array<{ value: string }> } }>
        }>
      }
      const rs = payload.sections[0]!.items[0]!.responseSet
      expect(rs.label).toBe('childbearing_options')
      expect(rs.options.map((o) => o.value)).toEqual([
        'JA',
        'NEIN',
        'NICHT_ZUTREFFEND',
        'UNBEKANNT',
      ])
    })
  })
})

describe('buildPayload — repeating-table persistence (#26 Slice 3)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('expands a table item into grouped column items sharing a groupLabel', () => {
    const store = useCrfAuthoringStore()
    store.addItem(0, {
      name: 'MEDS',
      oid: 'MEDS',
      table: {
        minRows: 1,
        maxRows: 5,
        columns: [
          { key: 'med', label: 'Medikament', type: 'text' },
          { key: 'dose', label: 'Dosis', type: 'number' },
          { key: 'start', label: 'Beginn', type: 'date' },
        ],
      },
    })
    const payload = store.buildPayload() as {
      sections: { items: { oid: string; dataType: string; descriptionLabel: string; groupLabel?: string }[] }[]
    }
    const items = payload.sections[0]!.items
    const grouped = items.filter((i) => i.groupLabel === 'MEDS')
    expect(grouped).toHaveLength(3)
    expect(grouped.map((i) => i.oid)).toEqual(['MEDS_MED', 'MEDS_DOSE', 'MEDS_START'])
    expect(grouped.map((i) => i.dataType)).toEqual(['ST', 'REAL', 'DATE'])
    expect(grouped.map((i) => i.descriptionLabel)).toEqual(['Medikament', 'Dosis', 'Beginn'])
    // The table item does NOT leak through as a lone scalar item.
    expect(items.some((i) => i.oid === 'MEDS' && !i.groupLabel)).toBe(false)
  })

  it('emits per-group row bounds so the operator min/max persists (not the 1/40 default)', () => {
    const store = useCrfAuthoringStore()
    store.addItem(0, {
      name: 'MEDS',
      oid: 'MEDS',
      table: { minRows: 2, maxRows: 8, columns: [{ key: 'med', label: 'Medikament', type: 'text' }] },
    })
    const payload = store.buildPayload() as {
      groups: { label: string; repeatNumber: number; repeatMax: number }[]
    }
    expect(payload.groups).toEqual([{ label: 'MEDS', repeatNumber: 2, repeatMax: 8 }])
  })

  it('encodes an autocomplete column system in its persisted OID', () => {
    const store = useCrfAuthoringStore()
    store.addItem(0, {
      name: 'RX',
      oid: 'RX',
      table: {
        minRows: 1,
        maxRows: 5,
        columns: [
          { key: 'drug', label: 'Medikament', type: 'text', autocomplete: { system: 'medication', fills: [] } },
          { key: 'dose', label: 'Dosis', type: 'number' },
        ],
      },
    })
    const payload = store.buildPayload() as { sections: { items: { oid: string }[] }[] }
    const oids = payload.sections[0]!.items.map((i) => i.oid)
    // The medication-bound text column carries the _TXMED marker; the plain
    // numeric column does not.
    expect(oids).toContain('RX_DRUG_TXMED')
    expect(oids).toContain('RX_DOSE')
  })

  it('persists a laterality column as a single-select over OD/OS/OU', () => {
    const store = useCrfAuthoringStore()
    store.addItem(0, {
      name: 'DX',
      oid: 'DX',
      table: {
        minRows: 1,
        maxRows: 5,
        columns: [
          { key: 'dx', label: 'Diagnose', type: 'text' },
          { key: 'eye', label: 'Auge', type: 'laterality' },
        ],
      },
    })
    const payload = store.buildPayload() as {
      sections: { items: { oid: string; responseSet?: { type?: string; options?: { value: string }[] } }[] }[]
    }
    const eye = payload.sections[0]!.items.find((i) => i.oid === 'DX_EYE')!
    expect(eye.responseSet?.type).toBe('single-select')
    expect(eye.responseSet?.options?.map((o) => o.value)).toEqual(['OD', 'OS', 'OU'])
  })
})

describe('reseedTableColumnKeySeq (draft restore — column-key collisions)', () => {
  it('advances the key counter past a restored draft so new columns never reuse a key', () => {
    // A draft restored from an earlier session carries high column keys, but
    // the module counter resets to 0 on page load. Use a value no prior test
    // could have reached so the assertion is order-independent.
    const draft = {
      sections: [
        { items: [{ table: { columns: [{ key: 'col_999' }, { key: 'col_3' }] } }] },
      ],
    } as unknown as Parameters<typeof reseedTableColumnKeySeq>[0]
    reseedTableColumnKeySeq(draft)
    // The next minted key clears the restored maximum — no collision, no
    // silent cell-state mirroring across columns.
    expect(newRepeatingTableColumn('x').key).toBe('col_1000')
    expect(newRepeatingTableColumn('y').key).toBe('col_1001')
  })
})

describe('reseedUidCounter (draft restore — item/section UID collisions)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('advances the UID counter past a restored draft so an added item never collides', () => {
    // A restored draft holds sec-/item- UIDs minted before the page reload.
    // Use suffixes no prior test could reach so the assertion is order-free.
    const draft = {
      sections: [{ uid: 'sec-4000', items: [{ uid: 'item-4200' }, { uid: 'item-7' }] }],
    } as unknown as Parameters<typeof reseedUidCounter>[0]
    reseedUidCounter(draft)
    // Adding through a fresh store now mints a UID past the restored maximum.
    const store = useCrfAuthoringStore()
    store.addItem(0, { name: 'fresh', oid: 'FRESH' })
    const added = store.draft.sections[0]!.items.at(-1)!.uid
    expect(Number(/-(\d+)$/.exec(added)![1])).toBeGreaterThan(4200)
  })
})

describe('findDuplicateOidItems (unique-OID guard)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('flags every item whose OID collides; ignores unique and empty OIDs', () => {
    const store = useCrfAuthoringStore()
    store.addItem(0, { name: 'drugs', oid: 'D' })
    store.addItem(0, { name: 'diag', oid: 'D' })
    store.addItem(0, { name: 'notes', oid: 'NOTES' })
    store.addItem(0, { name: 'blank', oid: '' })
    const dups = findDuplicateOidItems(store.draft)
    // Both 'D' items are flagged; the unique and the empty OID are not.
    expect(dups.map((d) => d.oid)).toEqual(['D', 'D'])
    expect(dups.some((d) => d.oid === 'NOTES' || d.oid === '')).toBe(false)
  })

  it('trims before comparing and returns nothing when all OIDs are unique', () => {
    const store = useCrfAuthoringStore()
    store.addItem(0, { name: 'a', oid: 'A' })
    store.addItem(0, { name: 'b', oid: 'B ' })
    expect(findDuplicateOidItems(store.draft)).toEqual([])
  })
})

describe('loadFromVersion (fork recovery — tables, autocomplete, laterality, show-when)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
  })

  it('folds grouped items back into one table with decoded autocomplete + laterality', async () => {
    vi.mocked(apiGet).mockResolvedValue({
      sections: [{
        label: 'S1', title: 'Meds', ordinal: 1,
        items: [
          { name: 'RX_DRUG_TXMED', oid: 'RX_DRUG_TXMED', descriptionLabel: 'Medikament', dataType: 'ST', groupLabel: 'RX', responseSet: { type: 'text' } },
          { name: 'RX_DOSE', oid: 'RX_DOSE', descriptionLabel: 'Dosis', dataType: 'REAL', groupLabel: 'RX', responseSet: { type: 'text' } },
          { name: 'RX_EYE', oid: 'RX_EYE', descriptionLabel: 'Auge', dataType: 'ST', groupLabel: 'RX', responseSet: { type: 'single-select', options: [{ text: 'OD (rechts)', value: 'OD' }, { text: 'OS (links)', value: 'OS' }, { text: 'OU (beide)', value: 'OU' }] } },
        ],
      }],
      groups: [{ label: 'RX', repeatNumber: 3, repeatMax: 12 }],
    })
    const store = useCrfAuthoringStore()
    const ok = await store.loadFromVersion('CRF_X', 'CRF_X_V1')
    expect(ok).toBe(true)
    const items = store.draft.sections[0]!.items
    // Three grouped items → ONE table item named after the group label.
    expect(items).toHaveLength(1)
    expect(items[0]!.oid).toBe('RX')
    const table = items[0]!.table!
    expect(table.columns.map((c) => c.type)).toEqual(['text', 'number', 'laterality'])
    expect(table.columns.map((c) => c.label)).toEqual(['Medikament', 'Dosis', 'Auge'])
    // Autocomplete system decoded from the OID marker; fill-map not persisted.
    expect(table.columns[0]!.autocomplete?.system).toBe('medication')
    expect(table.columns[0]!.autocomplete?.fills).toEqual([])
    // Row bounds recovered from wire groups[] (not the 1/20 default).
    expect(table.minRows).toBe(3)
    expect(table.maxRows).toBe(12)
  })

  it('reattaches a show-when rule and keeps ungrouped items flat + in order', async () => {
    vi.mocked(apiGet).mockResolvedValue({
      sections: [{
        label: 'S1', title: 'Visit', ordinal: 1,
        items: [
          { name: 'intro', oid: 'INTRO', dataType: 'ST', responseSet: { type: 'text' } },
          { name: 'T_A', oid: 'T_A', descriptionLabel: 'A', dataType: 'ST', groupLabel: 'T', responseSet: { type: 'text' } },
          { name: 'T_B', oid: 'T_B', descriptionLabel: 'B', dataType: 'DATE', groupLabel: 'T', responseSet: { type: 'text' } },
          { name: 'reason', oid: 'REASON', dataType: 'ST', responseSet: { type: 'text' }, parentItemOid: 'INTRO', showItem: 'N' },
        ],
      }],
    })
    const store = useCrfAuthoringStore()
    await store.loadFromVersion('CRF_X', 'CRF_X_V1')
    const items = store.draft.sections[0]!.items
    // Order preserved: flat, table (at group's first appearance), flat.
    expect(items.map((i) => i.oid)).toEqual(['INTRO', 'T', 'REASON'])
    expect(items[1]!.table!.columns.map((c) => c.type)).toEqual(['text', 'date'])
    expect(items[2]!.showWhen).toEqual({ sourceItemOid: 'INTRO', comparator: '==', literal: 'N' })
  })
})
