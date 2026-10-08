/**
 * TRISTATE_REASON end-to-end wiring (2026-09-30).
 *
 * <p>Reported from the running app: a SPECTRALIS OCT row showed TWO reason
 * fields — one inline under the "Nein" chip, one as a row of its own — and
 * what was typed into the inline one was never saved.
 *
 * <p>Cause: CrfItemWidget emitted {@code update:tristate-reason} and nothing
 * listened, so the text stayed in component-local state and the sibling
 * reason item was still rendered as an ordinary row.
 *
 * <p>These tests mount the real view, so they fail if the template binding
 * is ever dropped again. The pre-existing widget tests asserted the textarea
 * appears and hides, which is why the missing wiring survived review: the
 * path that mattered — the text reaching the sibling item — had no coverage.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn(() => Promise.resolve(true)) }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

const { apiGetMock, apiPostMock } = vi.hoisted(() => ({
  apiGetMock: vi.fn(),
  apiPostMock: vi.fn(),
}))
vi.mock('@/api/client', () => ({
  apiGet: apiGetMock,
  apiPost: apiPostMock,
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))
vi.mock('@/api/upload', () => ({ uploadItemFile: vi.fn(), clearItemFile: vi.fn() }))

import CrfEntryView from '@/views/CrfEntryView.vue'
import { useAuthStore } from '@/stores/auth'
import { useCrfEntryStore } from '@/stores/crfEntry'
import enMessages from '@/locales/en.json'

const ACQUIRED_OID = 'OD_SPECTRALIS_ACQUIRED'
const REASON_OID = 'OD_SPECTRALIS_REASON'

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/event-crfs/:eventCrfOid', name: 'crf-entry', component: CrfEntryView },
      { path: '/dde/:eventCrfOid/reconcile', name: 'dde-reconcile', component: { template: '<div />' } },
      { path: '/event-crfs/:eventCrfOid/print', name: 'printable-crf', component: { template: '<div />' } },
    ],
  })
}

/** The shape imagingAcquisitionPreset emits: a tri-state plus its reason sibling. */
function makeEntry(values: Record<string, unknown>) {
  return {
    eventCrfOid: 'EC_TEST',
    subjectId: 'M-001',
    eventLabel: 'V1',
    schema: {
      oid: 'F_OCT_V1',
      name: 'Imaging',
      version: 'v1.0',
      sections: [
        {
          oid: 'S_OCT',
          title: 'SPECTRALIS SD-OCT',
          items: [
            {
              oid: ACQUIRED_OID,
              label: 'OCT acquired?',
              dataType: 'select-one',
              required: false,
              options: [{ code: 'JA' }, { code: 'NEIN' }, { code: 'UNBEKANNT' }],
            },
            {
              oid: REASON_OID,
              label: 'Reason (not acquired)',
              dataType: 'string',
              required: false,
              showWhen: JSON.stringify({
                sourceItemOid: ACQUIRED_OID,
                comparator: '==',
                literal: 'NEIN',
              }),
            },
          ],
        },
      ],
    },
    values,
    status: 'initial',
    lastSavedAt: null,
    groups: [],
    maxFileBytes: 0,
    fileExtensions: '',
    dde: null,
  } as never
}

async function mountView(values: Record<string, unknown> = {}) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    username: 'demo', displayName: 'Demo', email: null, role: 'Investigator' as never,
    siteLabel: null, source: 'local', mfaSatisfied: true, profileComplete: true,
    locale: null, timezone: null, mustChangePassword: false, passwordChangeReason: null,
    activeStudy: null,
  } as never

  const router = makeRouter()
  router.push('/event-crfs/EC_TEST')
  await router.isReady()

  apiGetMock.mockImplementation(async (path: string) => {
    if (path.startsWith('/pages/api/v1/eventCrfs/EC_TEST')) {
      if (path.includes('/section-status')) return []
      if (path.includes('/lock-status')) return null
      if (path.includes('/notes')) return { eventCrfOid: 'EC_TEST', totalCount: 0, openCount: 0, byItemOid: {} }
      return makeEntry(values)
    }
    return null
  })
  apiPostMock.mockResolvedValue({})

  const wrapper = mount(CrfEntryView, { global: { plugins: [router, makeI18n()] } })
  await flushPromises()
  return wrapper
}

describe('CrfEntryView — TRISTATE_REASON reason routing', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
    apiPostMock.mockReset()
  })

  it('does not render the reason sibling as a row of its own', async () => {
    // Mount with the parent ALREADY on "Nein" so the sibling's show-when
    // matches. Without that the sibling is hidden by show-when anyway and the
    // assertion would pass against the very bug it is meant to catch — which
    // is how the duplicate field reached production in the first place.
    const w = await mountView({ [ACQUIRED_OID]: 'NEIN' })
    await flushPromises()

    // Its show-when is satisfied, so it would render as a row if the parent
    // did not consume it inline.
    const store = useCrfEntryStore()
    expect(store.isItemHidden(REASON_OID)).toBe(false)

    // ...and yet there must be exactly one reason input: the inline one.
    expect(w.find(`#item-${REASON_OID}`).exists()).toBe(false)
    expect(w.findAll('[data-testid="tristate-reason-textarea"]').length).toBe(1)
  })

  it('routes text typed inline onto the sibling reason item', async () => {
    const w = await mountView()
    const store = useCrfEntryStore()

    await w.find('[data-testid="tristate-radio-Nein"]').trigger('click')
    await flushPromises()

    const textarea = w.find('[data-testid="tristate-reason-textarea"]')
    expect(textarea.exists()).toBe(true)

    await textarea.setValue('Gerät defekt')
    await flushPromises()

    // The point of the whole fix: the text lands on the SIBLING item, not on
    // the parent (whose value stays the Ja/Nein/Unbekannt token).
    expect(store.values[REASON_OID]).toBe('Gerät defekt')
    expect(store.values[ACQUIRED_OID]).toBe('NEIN')
  })

  it('seeds the inline textarea from a reason already stored on the sibling', async () => {
    // Reopening a saved CRF must show the reason that was captured earlier,
    // rather than an empty box over a non-empty item_data row.
    const w = await mountView({ [ACQUIRED_OID]: 'NEIN', [REASON_OID]: 'Patient bewegt' })
    await flushPromises()

    const textarea = w.find('[data-testid="tristate-reason-textarea"]')
    expect(textarea.exists()).toBe(true)
    expect((textarea.element as HTMLTextAreaElement).value).toBe('Patient bewegt')
  })
})
