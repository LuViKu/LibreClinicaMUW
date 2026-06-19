/**
 * App-feedback Wave 2 (2026-06-19) — SectionCanvas spec.
 *
 * Pins:
 *   - empty-state placeholder when a section has no items,
 *   - drop of a primitive payload appends an item with the right dataType,
 *   - drop of a preset payload materialises 3 items (IOP),
 *   - clicking an item selects it,
 *   - "Add section" button extends the section list,
 *   - bilateral section renders in OD/OS grid mode.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import SectionCanvas from '@/components/crfAuthoring/SectionCanvas.vue'
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

function mountCanvas() {
  return mount(SectionCanvas, {
    global: { plugins: [i18n] },
  })
}

/**
 * Build a DragEvent-like with a fake DataTransfer that returns the
 * canvas drag payload.
 */
function makeDropEvent(payload: { kind: 'primitive' | 'preset'; value: string }): DragEvent {
  const data: Record<string, string> = {
    'application/x-crf-palette': JSON.stringify(payload),
  }
  const ev = new Event('drop', { bubbles: true, cancelable: true })
  Object.defineProperty(ev, 'dataTransfer', {
    value: {
      getData: (key: string): string => data[key] ?? '',
      setData: (): void => undefined,
      effectAllowed: 'copy',
      dropEffect: 'copy',
    } as unknown as DataTransfer,
  })
  return ev as DragEvent
}

describe('SectionCanvas', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders the empty-section placeholder on a fresh draft', () => {
    const w = mountCanvas()
    expect(w.find('[data-testid="crf-canvas-section-empty-0"]').exists()).toBe(true)
  })

  it('appends an item with the dropped primitive dataType', async () => {
    const w = mountCanvas()
    const store = useCrfAuthoringStore()
    const section = w.find('[data-testid="crf-canvas-section-0"]')
    section.element.dispatchEvent(makeDropEvent({ kind: 'primitive', value: 'INT' }))
    await flushPromises()
    expect(store.draft.sections[0]!.items).toHaveLength(1)
    expect(store.draft.sections[0]!.items[0]!.dataType).toBe('INT')
  })

  it('appends the IOP preset (3 items) when an iop preset payload is dropped', async () => {
    const w = mountCanvas()
    const store = useCrfAuthoringStore()
    const section = w.find('[data-testid="crf-canvas-section-0"]')
    section.element.dispatchEvent(makeDropEvent({ kind: 'preset', value: 'iop' }))
    await flushPromises()
    expect(store.draft.sections[0]!.items).toHaveLength(3)
    expect(store.draft.sections[0]!.items[0]!.oid).toBe('IOP_GEMESSEN')
  })

  it('clicking an item selects it on the store', async () => {
    const w = mountCanvas()
    const store = useCrfAuthoringStore()
    // Seed an item via the store so it exists in the rendered list.
    store.addItem(0, { name: 'X', oid: 'X' })
    await flushPromises()
    const itemBtn = w.find('[data-testid="crf-canvas-item-0-0"]')
    await itemBtn.trigger('click')
    expect(store.selectedItemUid).toBe(store.draft.sections[0]!.items[0]!.uid)
  })

  it('"Add section" extends the section list', async () => {
    const w = mountCanvas()
    const store = useCrfAuthoringStore()
    const before = store.draft.sections.length
    await w.find('[data-testid="crf-canvas-add-section"]').trigger('click')
    expect(store.draft.sections.length).toBe(before + 1)
  })

  it('renders the bilateral grid when section.bilateral is true', async () => {
    const w = mountCanvas()
    const store = useCrfAuthoringStore()
    store.draft.sections[0]!.bilateral = true
    store.addItem(0, { name: 'OD_TEST', oid: 'OD_TEST', descriptionLabel: 'Test' })
    store.addItem(0, { name: 'OS_TEST', oid: 'OS_TEST', descriptionLabel: 'Test' })
    await flushPromises()
    expect(w.find('[data-testid="crf-canvas-section-bilateral-0"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-item-od-TEST"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-item-os-TEST"]').exists()).toBe(true)
  })
})
