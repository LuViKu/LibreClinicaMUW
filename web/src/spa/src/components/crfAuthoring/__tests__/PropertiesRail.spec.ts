/**
 * App-feedback Wave 2 (2026-06-19) — PropertiesRail spec.
 *
 * Pins:
 *   - empty-state hint when no item is selected,
 *   - the form renders when an item is selected,
 *   - typing into the name field dispatches store.setItemField,
 *   - changing the data type clamps the response type,
 *   - toggling show-when adds/removes the rule,
 *   - "Clear selection" button drops store.selectedItemUid.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import PropertiesRail from '@/components/crfAuthoring/PropertiesRail.vue'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function mountRail() {
  return mount(PropertiesRail, {
    global: { plugins: [i18n] },
  })
}

describe('PropertiesRail', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('shows the empty-state hint when no item is selected', () => {
    const w = mountRail()
    expect(w.find('[data-testid="crf-canvas-properties-empty"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-properties-form"]').exists()).toBe(false)
  })

  it('shows the form when an item is selected on the store', async () => {
    const w = mountRail()
    const store = useCrfAuthoringStore()
    store.addItem(0, { name: 'IOP_GEMESSEN', oid: 'IOP_GEMESSEN' })
    const uid = store.draft.sections[0]!.items[0]!.uid
    store.selectItem(uid)
    await flushPromises()
    expect(w.find('[data-testid="crf-canvas-properties-form"]').exists()).toBe(true)
    const nameInput = w.find('[data-testid="crf-canvas-properties-name"]')
    expect((nameInput.element as HTMLInputElement).value).toBe('IOP_GEMESSEN')
  })

  it('dispatches setItemField on name input', async () => {
    const w = mountRail()
    const store = useCrfAuthoringStore()
    store.addItem(0)
    const uid = store.draft.sections[0]!.items[0]!.uid
    store.selectItem(uid)
    await flushPromises()
    const nameInput = w.find('[data-testid="crf-canvas-properties-name"]')
    ;(nameInput.element as HTMLInputElement).value = 'BCVA'
    await nameInput.trigger('input')
    expect(store.draft.sections[0]!.items[0]!.name).toBe('BCVA')
  })

  it('clamps response type when data type changes', async () => {
    const w = mountRail()
    const store = useCrfAuthoringStore()
    // Seed an ST + textarea item then flip to REAL — textarea isn't
    // allowed for REAL so the response type should clamp to 'text'.
    store.addItem(0, { dataType: 'ST', responseType: 'textarea' })
    const uid = store.draft.sections[0]!.items[0]!.uid
    store.selectItem(uid)
    await flushPromises()
    const dtSelect = w.find('[data-testid="crf-canvas-properties-dataType"]')
    ;(dtSelect.element as HTMLSelectElement).value = 'REAL'
    await dtSelect.trigger('change')
    expect(store.draft.sections[0]!.items[0]!.dataType).toBe('REAL')
    expect(store.draft.sections[0]!.items[0]!.responseType).toBe('text')
  })

  it('toggling show-when adds a rule, untoggling removes it', async () => {
    const w = mountRail()
    const store = useCrfAuthoringStore()
    // Need two items so there's an earlier item to pick.
    store.addItem(0, { name: 'PARENT', oid: 'PARENT' })
    store.addItem(0, { name: 'CHILD', oid: 'CHILD' })
    const childUid = store.draft.sections[0]!.items[1]!.uid
    store.selectItem(childUid)
    await flushPromises()
    const toggle = w.find('[data-testid="crf-canvas-properties-showWhen-toggle"]')
    ;(toggle.element as HTMLInputElement).checked = true
    await toggle.trigger('change')
    expect(store.draft.sections[0]!.items[1]!.showWhen).toBeDefined()
    expect(store.draft.sections[0]!.items[1]!.showWhen!.sourceItemOid).toBe('PARENT')
    // Untoggle.
    ;(toggle.element as HTMLInputElement).checked = false
    await toggle.trigger('change')
    expect(store.draft.sections[0]!.items[1]!.showWhen).toBeUndefined()
  })

  it('Clear selection drops the store selection', async () => {
    const w = mountRail()
    const store = useCrfAuthoringStore()
    store.addItem(0)
    const uid = store.draft.sections[0]!.items[0]!.uid
    store.selectItem(uid)
    await flushPromises()
    await w.find('[data-testid="crf-canvas-properties-clear"]').trigger('click')
    expect(store.selectedItemUid).toBeNull()
  })
})
