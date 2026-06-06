/**
 * Phase E.6 Milestone B — ItemEditor spec.
 *
 * Pins the load-bearing rendering rule: the response-set picker is
 * mounted only when the item's responseType belongs to the
 * option-bearing set (radio/single-select/multi-select/checkbox).
 * Open-text branches (text/textarea/file) hide the picker.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { nextTick, reactive } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
  }
})

import ItemEditor from '@/components/ItemEditor.vue'
import { useCrfAuthoringStore, type AuthoringItem } from '@/stores/crfAuthoring'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function makeItem(seed: Partial<AuthoringItem> = {}): AuthoringItem {
  // Use Vue's `reactive` so the in-test mutations trigger the
  // ItemEditor's watchers (props pass through to the child without
  // re-reactifying, so a plain JS object would freeze the watchers).
  return reactive({
    name: '',
    oid: '',
    descriptionLabel: '',
    leftItemText: '',
    rightItemText: '',
    units: '',
    dataType: 'ST',
    responseType: 'text',
    defaultValue: '',
    required: false,
    responseSet: null,
    validation: { regexp: '', errorMessage: '' },
    ...seed,
  }) as AuthoringItem
}

function mountEditor(item: AuthoringItem) {
  return mount(ItemEditor, {
    props: {
      item,
      sections: [],
      availableResponseSets: [],
      idPrefix: 'test',
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('ItemEditor — Milestone B', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('hides the response-set picker for open-text response types', async () => {
    const item = makeItem({ responseType: 'text' })
    const wrapper = mountEditor(item)
    await nextTick()
    expect(wrapper.find('[data-testid="test-picker-host"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the picker once the responseType flips to radio', async () => {
    const item = makeItem({ responseType: 'text' })
    const wrapper = mountEditor(item)
    await nextTick()

    // Flip via the v-modeled select — mutate the item prop directly,
    // then trigger the watcher.
    item.responseType = 'radio'
    await nextTick()

    expect(wrapper.find('[data-testid="test-picker-host"]').exists()).toBe(true)
    // The watcher seeds an inline response set on the item.
    expect(item.responseSet).not.toBeNull()
    expect(item.responseSet && 'type' in item.responseSet ? item.responseSet.type : null).toBe('radio')
    wrapper.unmount()
  })

  it('hides the picker when flipping back from radio to textarea', async () => {
    const item = makeItem({
      responseType: 'radio',
      responseSet: { type: 'radio', label: 'yes_no', options: [{ text: 'Yes', value: '1' }] },
    })
    const wrapper = mountEditor(item)
    await nextTick()
    expect(wrapper.find('[data-testid="test-picker-host"]').exists()).toBe(true)

    item.responseType = 'textarea'
    await nextTick()
    expect(wrapper.find('[data-testid="test-picker-host"]').exists()).toBe(false)
    // Open-text branch — responseSet is cleared.
    expect(item.responseSet).toBeNull()
    wrapper.unmount()
  })

  describe('BL (boolean) data type', () => {
    it('renders the BL preview block + hides the response-set picker', async () => {
      const item = makeItem({
        name: 'CONSENT',
        descriptionLabel: 'Consent on file',
        dataType: 'BL',
      })
      const wrapper = mountEditor(item)
      await nextTick()

      // BL is a fixed yes/no — the picker is hidden regardless of
      // responseType (the watcher already locked it to single-select).
      expect(wrapper.find('[data-testid="test-picker-host"]').exists()).toBe(false)
      // The preview block is shown with a disabled checkbox.
      const preview = wrapper.find('[data-testid="test-bl-preview"]')
      expect(preview.exists()).toBe(true)
      const cb = wrapper.find<HTMLInputElement>('[data-testid="test-bl-checkbox"]')
      expect(cb.exists()).toBe(true)
      expect(cb.element.disabled).toBe(true)
      wrapper.unmount()
    })

    it('locks the responseType select to single-select while BL is selected', async () => {
      const item = makeItem({ dataType: 'ST', responseType: 'radio' })
      const wrapper = mountEditor(item)
      await nextTick()

      // Flip to BL via the v-modeled select.
      item.dataType = 'BL'
      await nextTick()

      expect(item.responseType).toBe('single-select')
      // Picker is hidden once the dataType watcher runs.
      expect(wrapper.find('[data-testid="test-picker-host"]').exists()).toBe(false)
      // The responseType <select> is rendered as disabled.
      const respSelect = wrapper.find<HTMLSelectElement>('#test-responseType')
      expect(respSelect.element.disabled).toBe(true)
      wrapper.unmount()
    })

    it('restores a safe open-text default when flipping away from BL', async () => {
      const item = makeItem({ dataType: 'BL', responseType: 'single-select' })
      const wrapper = mountEditor(item)
      await nextTick()
      expect(wrapper.find('[data-testid="test-bl-preview"]').exists()).toBe(true)

      item.dataType = 'INT'
      await nextTick()

      // Flipping away from BL clears the synthesised lock and falls
      // back to plain text so the operator can re-pick a richer
      // responseType from the dropdown.
      expect(item.responseType).toBe('text')
      expect(item.responseSet).toBeNull()
      expect(wrapper.find('[data-testid="test-bl-preview"]').exists()).toBe(false)
      wrapper.unmount()
    })

    it('BL appears in the data-type dropdown', async () => {
      const item = makeItem()
      const wrapper = mountEditor(item)
      await nextTick()
      const opts = wrapper.findAll('#test-dataType option').map((o) => o.attributes('value'))
      expect(opts).toContain('BL')
    })
  })

  it('auto-suggests the OID from the name until the operator overrides', async () => {
    const item = makeItem()
    const wrapper = mountEditor(item)
    await nextTick()

    // Operator types a name → OID is auto-suggested by the store's
    // suggestOid + the editor's watcher.
    const useCrf = useCrfAuthoringStore()
    expect(useCrf.suggestOid('Hb A1c')).toBe('HB_A1C')
    item.name = 'Hb A1c'
    await nextTick()
    expect(item.oid).toBe('HB_A1C')

    // Operator types a custom OID — name changes after that no longer
    // overwrite the OID.
    const oidInput = wrapper.find<HTMLInputElement>('#test-oid')
    oidInput.element.value = 'HBA'
    await oidInput.trigger('input')
    item.name = 'Hemoglobin A1c'
    await nextTick()
    expect(item.oid).toBe('HBA')

    wrapper.unmount()
  })
})
