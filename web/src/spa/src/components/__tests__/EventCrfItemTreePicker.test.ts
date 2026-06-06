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

  it('selecting one event does not visually check sibling events that share the same items (Phase E.6 export-tool fix)', () => {
    // OpenClinica's event_definition_crf is many-to-many: the same CRF
    // can be assigned to multiple study events, so two events can share
    // the same version + item ids. The old derivation marked every
    // sibling event as checked the moment any one of them was selected;
    // the fix reads eventSet directly.
    const SHARED_TREE: EventTreeNode[] = [
      {
        eventOid: 'SE_V1',
        eventName: 'V1 Baseline',
        eventOrdinal: 1,
        repeating: false,
        crfs: [
          {
            crfOid: 'F_SHARED',
            crfName: 'Shared exam form',
            versions: [
              {
                versionId: 99,
                versionOid: 'F_SHARED_V1',
                versionName: 'Shared v1',
                items: [
                  { itemId: 500, oid: 'I_BCVA', name: 'BCVA', dataType: 'number' },
                ],
              },
            ],
          },
        ],
      },
      {
        eventOid: 'SE_V4',
        eventName: 'V4 Repeat',
        eventOrdinal: 4,
        repeating: false,
        crfs: [
          {
            crfOid: 'F_SHARED',
            crfName: 'Shared exam form',
            versions: [
              {
                // Same version + same items as V1 — the export bug
                // pre-fix was the item-derived check flagging V4 as
                // selected the instant V1 got picked.
                versionId: 99,
                versionOid: 'F_SHARED_V1',
                versionName: 'Shared v1',
                items: [
                  { itemId: 500, oid: 'I_BCVA', name: 'BCVA', dataType: 'number' },
                ],
              },
            ],
          },
        ],
      },
    ]
    const wrapper = mount(EventCrfItemTreePicker, {
      global: { plugins: [i18n] },
      props: {
        tree: SHARED_TREE,
        // V1 is the only event explicitly in the eventOids set. The
        // item set carries the shared item id so the wizard payload
        // gets it from V1; V4 is NOT in scope.
        eventOids: ['SE_V1'],
        versionIds: [99],
        itemIds: [500],
      },
    })
    const v1Checkbox = wrapper
      .findAll('input[type="checkbox"]')
      .find((i) => i.attributes('aria-label') === 'Toggle V1 Baseline')
    const v4Checkbox = wrapper
      .findAll('input[type="checkbox"]')
      .find((i) => i.attributes('aria-label') === 'Toggle V4 Repeat')
    expect(v1Checkbox?.element).toBeInstanceOf(HTMLInputElement)
    expect((v1Checkbox!.element as HTMLInputElement).checked).toBe(true)
    expect(v4Checkbox?.element).toBeInstanceOf(HTMLInputElement)
    expect((v4Checkbox!.element as HTMLInputElement).checked).toBe(false)
  })

  it('unchecking an event removes ONLY the event oid (descendant items + versions stay so the operator can swap events)', async () => {
    const wrapper = mountPicker({
      eventOids: ['SE_BASELINE'],
      versionIds: [10, 20],
      itemIds: [100, 101, 200],
    })
    const eventCheckbox = wrapper
      .findAll('input[type="checkbox"]')
      .find((i) => i.attributes('aria-label') === 'Toggle Baseline visit')
    await eventCheckbox!.setValue(false)

    const eventEmits = wrapper.emitted('update:eventOids') ?? []
    expect(eventEmits.length).toBeGreaterThan(0)
    expect(eventEmits[eventEmits.length - 1]![0]).toEqual([])
    // No item / version emits — descendant selections preserved.
    expect(wrapper.emitted('update:itemIds') ?? []).toHaveLength(0)
    expect(wrapper.emitted('update:versionIds') ?? []).toHaveLength(0)
  })

  it('checking a CRF auto-adds its parent event to the eventOids set (positive-intent autopick)', async () => {
    const wrapper = mountPicker()
    const crfCheckbox = wrapper
      .findAll('input[type="checkbox"]')
      .find((i) => i.attributes('aria-label') === 'Toggle Vital signs')
    expect(crfCheckbox).toBeTruthy()
    await crfCheckbox!.setValue(true)

    const eventEmits = wrapper.emitted('update:eventOids') ?? []
    expect(eventEmits.length).toBeGreaterThan(0)
    const latestEvents = eventEmits[eventEmits.length - 1]![0] as string[]
    expect(latestEvents).toContain('SE_BASELINE')
  })

  it('checking an item auto-adds its parent event to the eventOids set', async () => {
    const wrapper = mountPicker()
    // Expand the CRF + version rows so the item checkbox is rendered.
    const crfBtn = wrapper
      .findAll('button[aria-expanded]')
      .find((b) => b.attributes('aria-label')?.includes('Vital signs'))
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

    const eventEmits = wrapper.emitted('update:eventOids') ?? []
    expect(eventEmits.length).toBeGreaterThan(0)
    const latestEvents = eventEmits[eventEmits.length - 1]![0] as string[]
    expect(latestEvents).toContain('SE_BASELINE')
  })

  it('an event sharing a CRF with a selected sibling stays fully unchecked, never indeterminate', () => {
    // V3 NOT in eventSet, V4 IS in eventSet; both reuse the same
    // Ophthalmology Visit form (same item ids). The indeterminate-on-
    // shared-items derivation was the regression the operator reported
    // — V3 must render fully unchecked.
    const SHARED_TREE: EventTreeNode[] = [
      {
        eventOid: 'SE_V3',
        eventName: 'V3 Day 90',
        eventOrdinal: 3,
        repeating: false,
        crfs: [
          {
            crfOid: 'F_OPHTH_VISIT',
            crfName: 'Ophthalmology Visit',
            versions: [
              {
                versionId: 700,
                versionOid: 'F_OPHTH_V1',
                versionName: 'Ophth v1',
                items: [{ itemId: 800, oid: 'I_BCVA', name: 'BCVA', dataType: 'number' }],
              },
            ],
          },
        ],
      },
      {
        eventOid: 'SE_V4',
        eventName: 'V4 Repeat',
        eventOrdinal: 4,
        repeating: false,
        crfs: [
          {
            crfOid: 'F_OPHTH_VISIT',
            crfName: 'Ophthalmology Visit',
            versions: [
              {
                versionId: 700,
                versionOid: 'F_OPHTH_V1',
                versionName: 'Ophth v1',
                items: [{ itemId: 800, oid: 'I_BCVA', name: 'BCVA', dataType: 'number' }],
              },
            ],
          },
        ],
      },
    ]
    const wrapper = mount(EventCrfItemTreePicker, {
      global: { plugins: [i18n] },
      props: {
        tree: SHARED_TREE,
        eventOids: ['SE_V4'],
        versionIds: [700],
        itemIds: [800],
      },
    })
    const v3 = wrapper
      .findAll('input[type="checkbox"]')
      .find((i) => i.attributes('aria-label') === 'Toggle V3 Day 90')
    expect(v3?.element).toBeInstanceOf(HTMLInputElement)
    const el = v3!.element as HTMLInputElement
    expect(el.checked).toBe(false)
    expect(el.indeterminate).toBe(false)
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
