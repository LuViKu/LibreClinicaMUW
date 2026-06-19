/**
 * App-feedback Wave 2 (2026-06-19) — CrfAuthoringCanvasView smoke spec.
 *
 * Pins:
 *   - the three rails mount + render their headers,
 *   - a drop of the IOP preset onto the empty section materialises 3 items
 *     AND auto-selects the parent so the right rail fills in.
 *
 * The canvas view is the integration point — its sub-components have
 * dedicated specs; here we only confirm the wiring still works end-to-end.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn().mockResolvedValue([]),
    apiPost: vi.fn().mockResolvedValue({}),
  }
})

import CrfAuthoringCanvasView from '@/views/CrfAuthoringCanvasView.vue'
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

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/crf-authoring-canvas/:crfOid',
        name: 'crfAuthoringCanvas',
        component: CrfAuthoringCanvasView,
      },
      { path: '/crf-library', name: 'crf-library', component: { template: '<div />' } },
    ],
  })
}

async function mountView() {
  const router = makeRouter()
  await router.push({ name: 'crfAuthoringCanvas', params: { crfOid: 'DEMO_CRF' }, query: { name: 'Demo CRF' } })
  await router.isReady()
  return mount(CrfAuthoringCanvasView, {
    global: { plugins: [i18n, router] },
    attachTo: document.body,
  })
}

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

describe('CrfAuthoringCanvasView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('mounts the three rails + a default section', async () => {
    const w = await mountView()
    await flushPromises()
    expect(w.find('[data-testid="crf-canvas-view"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-palette-rail"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-section-root"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-properties-rail"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-section-0"]').exists()).toBe(true)
    w.unmount()
  })

  it('dropping the IOP preset on the empty section materialises 3 items + auto-selects parent', async () => {
    const w = await mountView()
    await flushPromises()
    const section = w.find('[data-testid="crf-canvas-section-0"]')
    section.element.dispatchEvent(makeDropEvent({ kind: 'preset', value: 'iop' }))
    await flushPromises()
    const store = useCrfAuthoringStore()
    expect(store.draft.sections[0]!.items).toHaveLength(3)
    expect(store.draft.sections[0]!.items[0]!.oid).toBe('IOP_GEMESSEN')
    expect(store.selectedItemUid).toBe(store.draft.sections[0]!.items[0]!.uid)
    // Properties rail should be in form mode now.
    expect(w.find('[data-testid="crf-canvas-properties-form"]').exists()).toBe(true)
    w.unmount()
  })
})
