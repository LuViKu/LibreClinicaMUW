/**
 * Phase E.6 Milestone B — ResponseSetPicker spec.
 *
 * Pins the inline-create flow: the operator picks "Create new",
 * types a label + at least one option, then clicks Save. The picker
 * delegates to the authoring store's `createCatalogEntry`, which
 * POSTs to `/pages/api/v1/response-sets`. On success the picker
 * emits `update:modelValue` with a `{ ref: { label } }` link.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

import { apiPost } from '@/api/client'
import ResponseSetPicker from '@/components/ResponseSetPicker.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

describe('ResponseSetPicker — Milestone B', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiPost).mockReset()
  })

  it('renders the catalog select for radio response type', async () => {
    const wrapper = mount(ResponseSetPicker, {
      props: {
        modelValue: null,
        responseType: 'radio',
        available: [
          { label: 'yes_no', responseType: 'radio', options: [{ text: 'Yes', value: '1' }], usageCount: 5, inActiveStudy: true },
          { label: 'snellen', responseType: 'single-select', options: [], usageCount: 2, inActiveStudy: false },
        ],
        idPrefix: 'rs-test',
      },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await nextTick()

    // The select narrows to entries matching the responseType (radio).
    const options = wrapper.findAll('#rs-test-select option')
    // 1 placeholder + 1 matching entry (`yes_no`); `snellen` is single-select
    // so it's filtered out by the picker's eligibleCatalog computed.
    expect(options.length).toBe(2)
    expect(options[1]!.text()).toContain('yes_no')

    wrapper.unmount()
  })

  it('emits update:modelValue with a ref when an entry is picked', async () => {
    const wrapper = mount(ResponseSetPicker, {
      props: {
        modelValue: null,
        responseType: 'radio',
        available: [
          { label: 'yes_no', responseType: 'radio', options: [], usageCount: 5, inActiveStudy: true },
        ],
        idPrefix: 'rs-test',
      },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await nextTick()

    const select = wrapper.find<HTMLSelectElement>('#rs-test-select')
    select.element.value = 'yes_no'
    await select.trigger('change')

    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    const last = wrapper.emitted('update:modelValue')!.at(-1)!
    expect(last[0]).toEqual({ ref: { label: 'yes_no' } })
    wrapper.unmount()
  })

  it('inline-creates an entry + emits the resulting ref on save', async () => {
    vi.mocked(apiPost).mockResolvedValueOnce({
      label: 'snellen',
      responseType: 'single-select',
      options: [{ text: '20/20', value: '20' }],
      usageCount: 0,
      inActiveStudy: false,
    })

    const wrapper = mount(ResponseSetPicker, {
      props: {
        modelValue: null,
        responseType: 'single-select',
        available: [],
        idPrefix: 'rs-test',
      },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    await nextTick()

    // Switch to create mode.
    await wrapper.find('[data-testid="rs-test-create-trigger"]').trigger('click')
    await nextTick()
    expect(wrapper.find('[data-testid="rs-test-inline-editor"]').exists()).toBe(true)

    // Type label.
    const labelInput = wrapper.find<HTMLInputElement>('#rs-test-label')
    labelInput.element.value = 'snellen'
    await labelInput.trigger('input')

    // Fill first option (picker seeds two empty rows).
    const text0 = wrapper.find<HTMLInputElement>('#rs-test-opt-text-0')
    text0.element.value = '20/20'
    await text0.trigger('input')
    const value0 = wrapper.find<HTMLInputElement>('#rs-test-opt-value-0')
    value0.element.value = '20'
    await value0.trigger('input')

    // Save.
    await wrapper.find('[data-testid="rs-test-save"]').trigger('click')
    await flushPromises()

    expect(apiPost).toHaveBeenCalledTimes(1)
    const [path, payload] = vi.mocked(apiPost).mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe('/pages/api/v1/response-sets')
    expect(payload).toMatchObject({
      label: 'snellen',
      responseType: 'single-select',
      options: expect.arrayContaining([{ text: '20/20', value: '20' }]),
    })

    // Final emit carries the ref shape (catalog re-link).
    const emits = wrapper.emitted('update:modelValue')!
    const final = emits.at(-1)!
    expect(final[0]).toEqual({ ref: { label: 'snellen' } })
    wrapper.unmount()
  })
})
