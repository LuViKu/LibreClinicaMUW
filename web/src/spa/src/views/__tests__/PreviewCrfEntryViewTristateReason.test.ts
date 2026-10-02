/**
 * TRISTATE_REASON wiring in the authoring PREVIEW (2026-09-30).
 *
 * <p>The first fix wired {@link CrfEntryView} only, and the duplicate reason
 * field was then reported again from the preview window — the author previews
 * exactly what the operator will see, so the same pairing has to hold here.
 *
 * <p>Mounting the real view is the point: the defect was a missing template
 * binding, which a store-level unit test cannot catch.
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

import PreviewCrfEntryView from '@/views/PreviewCrfEntryView.vue'
import { useCrfPreviewStore } from '@/stores/crfPreview'
import enMessages from '@/locales/en.json'

const ACQUIRED_OID = 'OD_SPECTRALIS_ACQUIRED'
const REASON_OID = 'OD_SPECTRALIS_REASON'

function makeI18n() {
  return createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'home', component: { template: '<div />' } }],
  })
}

/** The shape imagingAcquisitionPreset emits: a tri-state plus its reason sibling. */
const SCHEMA = {
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
}

async function mountPreview(values: Record<string, unknown> = {}) {
  setActivePinia(createPinia())
  const store = useCrfPreviewStore()
  store.schema = JSON.parse(JSON.stringify(SCHEMA)) as never
  store.values = { ...values } as never
  store.isOpen = true as never
  store.crfName = 'Imaging' as never

  const router = makeRouter()
  router.push('/')
  await router.isReady()

  const wrapper = mount(PreviewCrfEntryView, { global: { plugins: [router, makeI18n()] } })
  await flushPromises()
  return { wrapper, store }
}

describe('PreviewCrfEntryView — TRISTATE_REASON reason routing', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('does not render the reason sibling as a row of its own', async () => {
    // Parent already on "Nein" so the sibling's show-when matches — otherwise
    // it is hidden anyway and the assertion would pass against the bug.
    const { wrapper, store } = await mountPreview({ [ACQUIRED_OID]: 'NEIN' })
    expect(store.isItemHidden(REASON_OID)).toBe(false)

    expect(wrapper.find(`#item-${REASON_OID}`).exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="tristate-reason-textarea"]').length).toBe(1)
  })

  it('routes text typed inline onto the sibling reason item', async () => {
    const { wrapper, store } = await mountPreview()

    await wrapper.find('[data-testid="tristate-radio-Nein"]').trigger('click')
    await flushPromises()

    const textarea = wrapper.find('[data-testid="tristate-reason-textarea"]')
    expect(textarea.exists()).toBe(true)
    await textarea.setValue('Gerät defekt')
    await flushPromises()

    expect(store.values[REASON_OID]).toBe('Gerät defekt')
    expect(store.values[ACQUIRED_OID]).toBe('NEIN')
  })

  it('seeds the inline textarea from a reason already present in the preview values', async () => {
    const { wrapper } = await mountPreview({ [ACQUIRED_OID]: 'NEIN', [REASON_OID]: 'Patient bewegt' })
    const textarea = wrapper.find('[data-testid="tristate-reason-textarea"]')
    expect(textarea.exists()).toBe(true)
    expect((textarea.element as HTMLTextAreaElement).value).toBe('Patient bewegt')
  })
})
