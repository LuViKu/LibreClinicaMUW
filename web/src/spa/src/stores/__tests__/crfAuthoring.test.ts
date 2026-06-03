import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCrfAuthoringStore } from '../crfAuthoring'
import { ApiError, ApiNetworkError } from '@/api/client'
import type { CrfVersion } from '@/types/crfLibrary'

/**
 * Phase E.6 Milestone A — Vitest coverage for the CRF authoring
 * Pinia store.
 *
 * Strategy mirrors `auth.test.ts`: vi.mock('@/api/client') stubs
 * apiPost so we can assert call shape + replay error branches without
 * a live backend.
 */
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiPost: vi.fn(),
  }
})

import { apiPost } from '@/api/client'

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

  describe('addItem + setItemField', () => {
    it('appends an item to the targeted section', () => {
      const store = useCrfAuthoringStore()
      store.addItem(0)
      expect(store.draft.sections[0]!.items).toHaveLength(1)
      expect(store.draft.sections[0]!.items[0]!.dataType).toBe('ST')
      expect(store.draft.sections[0]!.items[0]!.required).toBe(false)
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
      store.setItemField(0, 0, 'dataType', 'INTEGER')
      store.setItemField(0, 0, 'required', true)
      const item = store.draft.sections[0]!.items[0]!
      expect(item.name).toBe('AGE')
      expect(item.descriptionLabel).toBe('Age')
      expect(item.dataType).toBe('INTEGER')
      expect(item.required).toBe(true)
    })

    it('is a no-op when the item index is out of range', () => {
      const store = useCrfAuthoringStore()
      store.setItemField(0, 99, 'name', 'X')
      expect(store.draft.sections[0]!.items).toHaveLength(0)
    })
  })

  describe('buildPayload', () => {
    it('trims whitespace + preserves ordinals', () => {
      const store = useCrfAuthoringStore()
      store.setVersionName('  v1.0 ')
      store.setVersionDescription('  Demo  ')
      store.addItem(0)
      store.setItemField(0, 0, 'name', ' AGE ')
      store.setItemField(0, 0, 'descriptionLabel', ' Age ')
      store.setItemField(0, 0, 'dataType', 'INTEGER')
      store.setItemField(0, 0, 'required', true)
      const payload = store.buildPayload()
      expect(payload.versionName).toBe('v1.0')
      expect(payload.versionDescription).toBe('Demo')
      expect(payload.sections).toHaveLength(1)
      expect(payload.sections[0]!.items[0]).toEqual({
        name: 'AGE',
        oid: '',
        descriptionLabel: 'Age',
        leftItemText: '',
        dataType: 'INTEGER',
        required: true,
      })
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
      store.setItemField(0, 0, 'dataType', 'INTEGER')
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
                  dataType: 'INTEGER',
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
      // The store records the user-visible error so the toast layer
      // can render it without the wizard needing to reach into the
      // ApiError instance directly.
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
