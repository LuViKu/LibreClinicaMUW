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
import {
  useCrfAuthoringStore,
  type AuthoringItem,
  type AuthoringSection,
} from '@/stores/crfAuthoring'
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

function mountEditor(item: AuthoringItem, sections: AuthoringSection[] = []) {
  return mount(ItemEditor, {
    props: {
      item,
      sections,
      availableResponseSets: [],
      idPrefix: 'test',
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

function makeSection(
  label: string,
  items: AuthoringItem[],
  partial: Partial<AuthoringSection> = {},
): AuthoringSection {
  return reactive({
    uid: `sec-${label}`,
    label,
    title: partial.title ?? label,
    instructions: partial.instructions ?? '',
    ordinal: partial.ordinal ?? 1,
    items,
  }) as AuthoringSection
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

  describe('Datentyp → Antworttyp matrix', () => {
    it('filters the response-type dropdown to entries allowed by the data type', async () => {
      const item = makeItem({ dataType: 'REAL', responseType: 'text' })
      const wrapper = mountEditor(item)
      await nextTick()
      const opts = wrapper.findAll('#test-responseType option').map((o) => o.attributes('value'))
      // REAL is restricted to the numeric text bucket — single entry.
      expect(opts).toEqual(['text'])
      wrapper.unmount()
    })

    it('ST surfaces every existing entry (text, textarea, all option-bearing)', async () => {
      const item = makeItem({ dataType: 'ST', responseType: 'text' })
      const wrapper = mountEditor(item)
      await nextTick()
      const opts = wrapper.findAll('#test-responseType option').map((o) => o.attributes('value'))
      expect(opts).toEqual(['text', 'textarea', 'radio', 'single-select', 'multi-select', 'checkbox'])
      wrapper.unmount()
    })

    it('resets the responseType when the data type changes to an incompatible one', async () => {
      const item = makeItem({ dataType: 'ST', responseType: 'textarea' })
      const wrapper = mountEditor(item)
      await nextTick()
      expect(item.responseType).toBe('textarea')

      // Flip to INT — textarea is not in the INT allowed set ⇒ snap
      // to the first allowed entry (text).
      item.dataType = 'INT'
      await nextTick()
      expect(item.responseType).toBe('text')
      wrapper.unmount()
    })

    it('does NOT reset the responseType when the new data type keeps it allowed', async () => {
      const item = makeItem({ dataType: 'ST', responseType: 'radio' })
      const wrapper = mountEditor(item)
      await nextTick()

      // ST → INT — radio is still allowed under INT, so the
      // selection survives.
      item.dataType = 'INT'
      await nextTick()
      expect(item.responseType).toBe('radio')
      wrapper.unmount()
    })

    it('renders the matrix helper text under the response-type field', async () => {
      const item = makeItem({ dataType: 'INT', responseType: 'text' })
      const wrapper = mountEditor(item)
      await nextTick()
      const txt = wrapper.text()
      expect(txt).toContain('Response types compatible with data type')
      wrapper.unmount()
    })
  })

  describe('Show-when rule editor', () => {
    it('toggle is off by default and the rule editor is not mounted', async () => {
      const item = makeItem()
      const wrapper = mountEditor(item)
      await nextTick()
      const toggle = wrapper.find<HTMLInputElement>('[data-testid="test-show-when-toggle"]')
      expect(toggle.exists()).toBe(true)
      expect(toggle.element.checked).toBe(false)
      expect(wrapper.find('[data-testid="test-show-when-rule"]').exists()).toBe(false)
      expect(item.showWhen).toBeUndefined()
      wrapper.unmount()
    })

    it('toggling on seeds an empty rule + mounts the editor', async () => {
      const item = makeItem()
      const wrapper = mountEditor(item)
      await nextTick()
      const toggle = wrapper.find<HTMLInputElement>('[data-testid="test-show-when-toggle"]')
      toggle.element.checked = true
      await toggle.trigger('change')
      await nextTick()
      expect(item.showWhen).toEqual({
        sourceItemOid: '',
        comparator: '==',
        literal: '',
      })
      expect(wrapper.find('[data-testid="test-show-when-rule"]').exists()).toBe(true)
      wrapper.unmount()
    })

    it('toggling off clears the rule', async () => {
      const item = makeItem({
        showWhen: { sourceItemOid: 'X', comparator: '==', literal: '1' },
      })
      const wrapper = mountEditor(item)
      await nextTick()
      const toggle = wrapper.find<HTMLInputElement>('[data-testid="test-show-when-toggle"]')
      expect(toggle.element.checked).toBe(true)
      toggle.element.checked = false
      await toggle.trigger('change')
      await nextTick()
      expect(item.showWhen).toBeUndefined()
      expect(wrapper.find('[data-testid="test-show-when-rule"]').exists()).toBe(false)
      wrapper.unmount()
    })

    it('source-item picker lists only items declared BEFORE the current one', async () => {
      // Two earlier items (one in the same section, one in an earlier
      // section) + the target item + one later item that must be
      // excluded.
      const earlierInS1 = makeItem({ name: 'A', oid: 'A_OID', dataType: 'INT' })
      const earlierInS2 = makeItem({ name: 'B', oid: 'B_OID', dataType: 'BL' })
      const current = makeItem({ name: 'C', oid: 'C_OID', dataType: 'DATE' })
      const laterInS2 = makeItem({ name: 'D', oid: 'D_OID', dataType: 'ST' })
      const s1 = makeSection('S1', [earlierInS1])
      const s2 = makeSection('S2', [earlierInS2, current, laterInS2], { ordinal: 2 })

      // Enable the rule to surface the picker.
      current.showWhen = { sourceItemOid: '', comparator: '==', literal: '' }

      const wrapper = mountEditor(current, [s1, s2])
      await nextTick()
      const options = wrapper.findAll('#test-show-when-source option')
      const values = options.map((o) => o.attributes('value'))
      // First entry is the empty placeholder, followed by earlier items.
      expect(values).toEqual(['', 'A_OID', 'B_OID'])
      wrapper.unmount()
    })

    it('source-item picker omits items with blank OID (half-authored)', async () => {
      const earlier = makeItem({ name: 'A', oid: '', dataType: 'INT' })
      const current = makeItem({ name: 'C', oid: 'C_OID', dataType: 'DATE' })
      current.showWhen = { sourceItemOid: '', comparator: '==', literal: '' }
      const section = makeSection('S1', [earlier, current])

      const wrapper = mountEditor(current, [section])
      await nextTick()
      const values = wrapper.findAll('#test-show-when-source option').map((o) => o.attributes('value'))
      // Just the placeholder — the half-authored item is skipped.
      expect(values).toEqual([''])
      wrapper.unmount()
    })

    it('literal input renders a True/False dropdown when source is BL', async () => {
      const src = makeItem({ name: 'DONE', oid: 'DONE', dataType: 'BL' })
      const current = makeItem({ name: 'DATE', oid: 'DATE_X', dataType: 'DATE' })
      current.showWhen = { sourceItemOid: 'DONE', comparator: '==', literal: '' }
      const section = makeSection('S1', [src, current])

      const wrapper = mountEditor(current, [section])
      await nextTick()
      const literalSelect = wrapper.find('#test-show-when-literal')
      expect(literalSelect.exists()).toBe(true)
      // The element should be a <select> with 1+2 options (placeholder + True + False).
      expect(literalSelect.element.tagName.toLowerCase()).toBe('select')
      const opts = wrapper.findAll('#test-show-when-literal option').map((o) => o.attributes('value'))
      expect(opts).toEqual(['', '1', '0'])
      wrapper.unmount()
    })

    it('literal input is a plain text input with inputmode=decimal when source is INT', async () => {
      const src = makeItem({ name: 'AGE', oid: 'AGE', dataType: 'INT' })
      const current = makeItem({ name: 'X', oid: 'X', dataType: 'DATE' })
      current.showWhen = { sourceItemOid: 'AGE', comparator: '>', literal: '' }
      const section = makeSection('S1', [src, current])

      const wrapper = mountEditor(current, [section])
      await nextTick()
      const literal = wrapper.find<HTMLInputElement>('#test-show-when-literal')
      expect(literal.exists()).toBe(true)
      expect(literal.element.tagName.toLowerCase()).toBe('input')
      expect(literal.element.getAttribute('inputmode')).toBe('decimal')
      wrapper.unmount()
    })
  })
})
