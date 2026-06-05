/**
 * Phase E.6 — Data Export Phase 2 — EventCrfItemTreePicker spec.
 *
 * Pins the wizard-side semantics of the checkbox tree:
 *
 *   - Select-all at each level propagates to every descendant.
 *   - Per-level counts ("X / Y items") update reactively.
 *   - Search filters the visible tree by name + OID.
 *
 * The component is fully controlled — the parent owns the three id
 * sets, so the test mounts with controlled v-model bindings and
 * inspects the emitted update payloads.
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import EventCrfItemTreePicker from '@/components/EventCrfItemTreePicker.vue'
import type { EventTreeNode } from '@/types/export'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

const TREE: EventTreeNode[] = [
  {
    eventOid: 'SE_BASELINE',
    eventName: 'Baseline visit',
    eventOrdinal: 1,
    repeating: false,
    crfs: [
      {
        crfOid: 'F_VITAL',
        crfName: 'Vital signs',
        versions: [
          {
            versionId: 10,
            versionOid: 'F_VITAL_V1',
            versionName: 'Vitals v1',
            items: [
              { itemId: 100, oid: 'I_HR', name: 'Heart rate', dataType: 'number' },
              { itemId: 101, oid: 'I_BP', name: 'Blood pressure', dataType: 'text' },
            ],
          },
        ],
      },
      {
        crfOid: 'F_DEMO',
        crfName: 'Demographics',
        versions: [
          {
            versionId: 20,
            versionOid: 'F_DEMO_V1',
            versionName: 'Demo v1',
            items: [
              { itemId: 200, oid: 'I_AGE', name: 'Age', dataType: 'number' },
            ],
          },
        ],
      },
    ],
  },
  {
    eventOid: 'SE_FOLLOWUP',
    eventName: 'Follow-up',
    eventOrdinal: 2,
    repeating: true,
    crfs: [
      {
        crfOid: 'F_FOLLOWUP',
        crfName: 'Follow-up form',
        versions: [
          {
            versionId: 30,
            versionOid: 'F_FOLLOWUP_V1',
            versionName: 'Follow-up v1',
            items: [
              { itemId: 300, oid: 'I_NOTES', name: 'Notes', dataType: 'text' },
            ],
          },
        ],
      },
    ],
  },
]

function mountPicker(overrides: Partial<{ eventOids: string[]; versionIds: number[]; itemIds: number[] }> = {}) {
  return mount(EventCrfItemTreePicker, {
    global: { plugins: [i18n] },
    props: {
      tree: TREE,
      eventOids: overrides.eventOids ?? [],
      versionIds: overrides.versionIds ?? [],
      itemIds: overrides.itemIds ?? [],
    },
  })
}

describe('EventCrfItemTreePicker', () => {
  it('renders the totals header with picked-vs-total counts', () => {
    const wrapper = mountPicker({ itemIds: [100] })
    const header = wrapper.text()
    // 2 events total · 4 items total · 1 item picked.
    expect(header).toContain('0 / 2 events')
    expect(header).toContain('1 / 4 items')
  })

  it('selecting an event emits every descendant item, version, and event oid', async () => {
    const wrapper = mountPicker()
    // First event-level checkbox is the first input matching "Toggle Baseline visit".
    const eventCheckbox = wrapper
      .findAll('input[type="checkbox"]')
      .find((i) => i.attributes('aria-label') === 'Toggle Baseline visit')
    expect(eventCheckbox).toBeTruthy()
    await eventCheckbox!.setValue(true)

    const itemEmits = wrapper.emitted('update:itemIds') ?? []
    const versionEmits = wrapper.emitted('update:versionIds') ?? []
    const eventEmits = wrapper.emitted('update:eventOids') ?? []
    expect(itemEmits.length).toBeGreaterThan(0)
    const latestItems = itemEmits[itemEmits.length - 1]![0] as number[]
    expect(new Set(latestItems)).toEqual(new Set([100, 101, 200]))
    const latestVersions = versionEmits[versionEmits.length - 1]![0] as number[]
    expect(new Set(latestVersions)).toEqual(new Set([10, 20]))
    const latestEvents = eventEmits[eventEmits.length - 1]![0] as string[]
    expect(latestEvents).toContain('SE_BASELINE')
  })

  it('search filters the visible tree by event / item name', async () => {
    const wrapper = mountPicker()
    const searchInput = wrapper.find('#event-tree-search')
    await searchInput.setValue('Follow-up')
    // The Follow-up event must remain; the Baseline event must disappear.
    expect(wrapper.text()).toContain('Follow-up')
    expect(wrapper.text()).not.toContain('Baseline visit')
  })

  it('clicking an item-level checkbox emits the item id', async () => {
    const wrapper = mountPicker()
    // Expand event 1's CRF + version so the item checkbox is rendered.
    // The first event auto-expands on mount; we expand the CRF + version.
    const allButtons = wrapper.findAll('button[aria-expanded]')
    // Open Vital signs CRF.
    const crfBtn = allButtons.find((b) => b.attributes('aria-label')?.includes('Vital signs'))
    if (crfBtn) await crfBtn.trigger('click')
    const versionBtn = wrapper
      .findAll('button[aria-expanded]')
      .find((b) => b.attributes('aria-label')?.includes('Vitals v1'))
    if (versionBtn) await versionBtn.trigger('click')

    const itemCheckbox = wrapper
      .findAll('input[type="checkbox"]')
      .find((i) => i.attributes('aria-label') === 'Toggle Heart rate')
    expect(itemCheckbox).toBeTruthy()
    await itemCheckbox!.setValue(true)

    const itemEmits = wrapper.emitted('update:itemIds') ?? []
    expect(itemEmits.length).toBeGreaterThan(0)
    expect(itemEmits[itemEmits.length - 1]![0]).toEqual([100])
  })

  it('renders an empty-state when the tree is empty', () => {
    const wrapper = mount(EventCrfItemTreePicker, {
      global: { plugins: [i18n] },
      props: {
        tree: [],
        eventOids: [],
        versionIds: [],
        itemIds: [],
      },
    })
    expect(wrapper.text()).toContain('keine Visiten')
  })
})
