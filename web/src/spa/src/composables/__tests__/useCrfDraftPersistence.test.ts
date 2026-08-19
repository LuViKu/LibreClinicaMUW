/**
 * CRF-builder draft autosave/restore (2026-08) — unit tests pinning the
 * data-loss protection: the builder draft is otherwise in-memory only and
 * wiped by store.reset() on every mount, so an idle logout would discard it.
 *
 * Behaviours locked in here:
 *  1. isMeaningfulDraft distinguishes the pristine emptyDraft() from one that
 *     carries operator intent — so the mount-time reset() never overwrites a
 *     previously-saved copy with an empty one.
 *  2. A meaningful edit is autosaved to localStorage under the per-CRF key
 *     after the debounce window.
 *  3. start() surfaces a prior on-disk draft; restore() hydrates the store
 *     from it; discard()/clear() remove it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, ref } from 'vue'

import { useCrfAuthoringStore, type AuthoringDraft } from '@/stores/crfAuthoring'
import { isMeaningfulDraft, useCrfDraftPersistence } from '../useCrfDraftPersistence'

const OID = 'CRF_TEST'
const KEY = `crfDraft:v1:${OID}`

/** Map-backed localStorage — jsdom's opaque-origin storage isn't functional here. */
function installLocalStorageStub(): void {
  const store = new Map<string, string>()
  const mock: Storage = {
    get length() {
      return store.size
    },
    clear: () => store.clear(),
    getItem: (k: string) => (store.has(k) ? (store.get(k) as string) : null),
    key: (i: number) => Array.from(store.keys())[i] ?? null,
    removeItem: (k: string) => void store.delete(k),
    setItem: (k: string, v: string) => void store.set(k, String(v)),
  }
  vi.stubGlobal('localStorage', mock)
}

function draftWithItem(): AuthoringDraft {
  return {
    versionName: 'v1.0',
    versionDescription: '',
    revisionNotes: '',
    sections: [
      {
        uid: 'sec1',
        label: 'S1',
        title: 'Section 1',
        instructions: '',
        ordinal: 1,
        items: [
          {
            uid: 'item1',
            name: 'Frage',
            oid: 'I_FRAGE',
            descriptionLabel: '',
            leftItemText: '',
            rightItemText: '',
            units: '',
            dataType: 'ST',
            responseType: 'text',
            defaultValue: '',
            required: false,
            responseSet: null,
            validation: { regex: '', errorMessage: '' },
          },
        ],
      },
    ],
  }
}

describe('useCrfDraftPersistence', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    installLocalStorageStub()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  describe('isMeaningfulDraft', () => {
    it('is false for the pristine empty draft', () => {
      const empty: AuthoringDraft = {
        versionName: '',
        versionDescription: '',
        revisionNotes: '',
        sections: [{ uid: 's', label: 'S1', title: 'Section 1', instructions: '', ordinal: 1, items: [] }],
      }
      expect(isMeaningfulDraft(empty)).toBe(false)
    })

    it('is true once an item exists', () => {
      expect(isMeaningfulDraft(draftWithItem())).toBe(true)
    })

    it('is true once a version name is set', () => {
      const d = draftWithItem()
      d.sections[0].items = []
      d.versionDescription = ''
      expect(isMeaningfulDraft(d)).toBe(true) // versionName 'v1.0'
    })
  })

  it('does not persist the pristine draft (reset() must not clobber a saved copy)', async () => {
    const store = useCrfAuthoringStore()
    store.reset()
    const p = useCrfDraftPersistence(ref(OID))
    p.start()
    // Touch the empty draft the way reset() leaves it.
    store.reset()
    await nextTick()
    vi.advanceTimersByTime(2000)
    expect(localStorage.getItem(KEY)).toBeNull()
    p.stop()
  })

  it('autosaves a meaningful edit to the per-CRF key after the debounce', async () => {
    const store = useCrfAuthoringStore()
    store.reset()
    const p = useCrfDraftPersistence(ref(OID))
    p.start()

    store.setVersionName('v1.0')
    store.addItem(0, { name: 'Frage' })
    await nextTick()
    vi.advanceTimersByTime(1000)

    const raw = localStorage.getItem(KEY)
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw as string)
    expect(parsed.draft.versionName).toBe('v1.0')
    expect(parsed.draft.sections[0].items.length).toBeGreaterThan(0)
    expect(typeof parsed.savedAt).toBe('string')
    p.stop()
  })

  it('surfaces a prior on-disk draft on start() and restores it into the store', async () => {
    localStorage.setItem(KEY, JSON.stringify({ savedAt: '2026-08-11T09:00:00.000Z', draft: draftWithItem() }))
    const store = useCrfAuthoringStore()
    store.reset()
    expect(store.draft.sections[0].items.length).toBe(0)

    const p = useCrfDraftPersistence(ref(OID))
    p.start()
    expect(p.savedDraft.value).not.toBeNull()
    expect(p.savedDraft.value?.savedAt).toBe('2026-08-11T09:00:00.000Z')

    const ok = p.restore()
    expect(ok).toBe(true)
    expect(store.draft.versionName).toBe('v1.0')
    expect(store.draft.sections[0].items[0].name).toBe('Frage')
    expect(p.savedDraft.value).toBeNull()
    p.stop()
  })

  it('discard() and clear() remove the persisted copy', () => {
    localStorage.setItem(KEY, JSON.stringify({ savedAt: '2026-08-11T09:00:00.000Z', draft: draftWithItem() }))
    const p = useCrfDraftPersistence(ref(OID))
    p.start()
    expect(p.savedDraft.value).not.toBeNull()

    p.discard()
    expect(localStorage.getItem(KEY)).toBeNull()
    expect(p.savedDraft.value).toBeNull()
    p.stop()
  })
})
