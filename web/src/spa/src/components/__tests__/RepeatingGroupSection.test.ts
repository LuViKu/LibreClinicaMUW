import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import RepeatingGroupSection from '../RepeatingGroupSection.vue'
import TerminologyAutocomplete from '../TerminologyAutocomplete.vue'
import type { CrfItem, CrfItemGroup } from '@/types/crf'
import enMessages from '@/locales/en.json'

// #19 — row deletion now goes through the useConfirm() modal instead of
// window.confirm(). Mock the composable so the component doesn't need an
// active Pinia + a mounted ConfirmDialog; the modal itself is covered by
// ConfirmDialog.test.ts.
const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn(() => Promise.resolve(true)) }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

// #26 Slice 3 — a terminology-marked column renders TerminologyAutocomplete,
// which queries the search endpoint. Stub it so the cell mounts cleanly.
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn().mockResolvedValue([]) }
})

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

const ITEMS_BY_OID: Record<string, CrfItem> = {
  I_EYE: {
    oid: 'I_EYE',
    label: 'Eye',
    dataType: 'select-one',
    required: true,
    options: [
      { code: 'OD', label: 'OD' },
      { code: 'OS', label: 'OS' },
    ],
  },
  I_IOP: {
    oid: 'I_IOP',
    label: 'IOP',
    dataType: 'integer',
    required: false,
    min: 0,
    max: 100,
  },
}

const I18N = {
  addRowLabel: 'Add row',
  deleteRowLabel: 'Delete',
  deleteRowConfirm: 'Are you sure?',
  repeatMaxReachedLabel: 'Max reached',
  emptyLabel: 'No rows yet',
}

function makeGroup(rows: CrfItemGroup['rows'] = [], repeatMax = 4): CrfItemGroup {
  return {
    oid: 'G_EYE_FINDINGS',
    label: 'Per-eye findings',
    repeatMax,
    itemOids: ['I_EYE', 'I_IOP'],
    rows,
  }
}

describe('RepeatingGroupSection', () => {
  beforeEach(() => {
    confirmMock.mockReset()
    confirmMock.mockResolvedValue(true)
  })

  it('renders the empty-state label when no rows', () => {
    const wrapper = mount(RepeatingGroupSection, {
      props: { group: makeGroup([]), itemsByOid: ITEMS_BY_OID, ...I18N },
    })
    expect(wrapper.text()).toContain('No rows yet')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('renders a row table with one cell per item OID + a delete button per row', () => {
    const wrapper = mount(RepeatingGroupSection, {
      props: {
        group: makeGroup([
          { ordinal: 1, values: { I_EYE: 'OD', I_IOP: 14 } },
          { ordinal: 2, values: { I_EYE: 'OS', I_IOP: 16 } },
        ]),
        itemsByOid: ITEMS_BY_OID,
        ...I18N,
      },
    })
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(wrapper.findAll('button').filter((b) => b.text() === 'Delete')).toHaveLength(2)
    // First row's IOP cell carries the right pre-bound value.
    const iopInputs = wrapper.findAll<HTMLInputElement>('input[type="number"]')
    expect(iopInputs[0].element.value).toBe('14')
    expect(iopInputs[1].element.value).toBe('16')
  })

  it('emits add-row when the add button is clicked and rows < repeatMax', async () => {
    const wrapper = mount(RepeatingGroupSection, {
      props: { group: makeGroup([{ ordinal: 1, values: {} }], 4), itemsByOid: ITEMS_BY_OID, ...I18N },
    })
    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('Add row'))!
    expect(addBtn.attributes('disabled')).toBeUndefined()
    await addBtn.trigger('click')
    expect(wrapper.emitted('add-row')).toBeTruthy()
  })

  it('disables the add button + shows repeatMaxReached label once full', () => {
    const rows = Array.from({ length: 4 }, (_, i) => ({ ordinal: i + 1, values: {} }))
    const wrapper = mount(RepeatingGroupSection, {
      props: { group: makeGroup(rows, 4), itemsByOid: ITEMS_BY_OID, ...I18N },
    })
    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('Add row'))!
    expect(addBtn.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Max reached')
  })

  it('emits delete-row with the matching ordinal after the confirm prompt', async () => {
    const wrapper = mount(RepeatingGroupSection, {
      props: { group: makeGroup([{ ordinal: 1, values: {} }, { ordinal: 2, values: {} }]), itemsByOid: ITEMS_BY_OID, ...I18N },
    })
    const deletes = wrapper.findAll('button').filter((b) => b.text() === 'Delete')
    await deletes[1].trigger('click')
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ message: 'Are you sure?' }))
    expect(wrapper.emitted('delete-row')).toEqual([[2]])
  })

  it('#26 Slice 3 — renders TerminologyAutocomplete for a terminology-marked column', async () => {
    const itemsByOid: Record<string, CrfItem> = {
      RX_DRUG_TXMED: { oid: 'RX_DRUG_TXMED', label: 'Medikament', dataType: 'string', required: false } as CrfItem,
      RX_DOSE: { oid: 'RX_DOSE', label: 'Dosis', dataType: 'string', required: false } as CrfItem,
    }
    const group: CrfItemGroup = {
      oid: 'RX', label: 'Medikation', repeatMax: 5,
      itemOids: ['RX_DRUG_TXMED', 'RX_DOSE'],
      rows: [{ ordinal: 1, values: {} }],
    }
    const wrapper = mount(RepeatingGroupSection, {
      props: { group, itemsByOid, ...I18N },
      global: { plugins: [i18n] },
    })
    await flushPromises()
    // The marked column cell hosts the autocomplete; the plain column doesn't.
    expect(wrapper.findAll('[data-testid="terminology-autocomplete-input"]')).toHaveLength(1)
  })

  it('#26 binding store — a pick fans fill-map properties into sibling cells', async () => {
    const itemsByOid: Record<string, CrfItem> = {
      RX_DRUG_TXMED: {
        oid: 'RX_DRUG_TXMED', label: 'Medikament', dataType: 'string', required: false,
        autocomplete: { system: 'medication', fills: [{ fromProperty: 'strength', toKey: 'RX_DOSE' }, { fromProperty: 'unit', toKey: 'RX_UNIT' }] },
      } as CrfItem,
      RX_DOSE: { oid: 'RX_DOSE', label: 'Dosis', dataType: 'string', required: false } as CrfItem,
      RX_UNIT: { oid: 'RX_UNIT', label: 'Einheit', dataType: 'string', required: false } as CrfItem,
    }
    const group: CrfItemGroup = {
      oid: 'RX', label: 'Medikation', repeatMax: 5,
      itemOids: ['RX_DRUG_TXMED', 'RX_DOSE', 'RX_UNIT'],
      rows: [{ ordinal: 1, values: {} }],
    }
    const wrapper = mount(RepeatingGroupSection, {
      props: { group, itemsByOid, ...I18N },
      global: { plugins: [i18n] },
    })
    await flushPromises()
    // Simulate a concept pick carrying strength + unit properties.
    wrapper.findComponent(TerminologyAutocomplete).vm.$emit('pick', {
      code: 'B01AC06', display: 'ASS', value: 'ASS', properties: { strength: '100', unit: 'mg' },
    })
    await flushPromises()
    const setValues = (wrapper.emitted('set-value') ?? []) as Array<[{ rowOrdinal: number; itemOid: string; value: unknown }]>
    // Fill map fans strength → RX_DOSE and unit → RX_UNIT of the SAME row.
    expect(setValues.map((e) => e[0])).toEqual(
      expect.arrayContaining([
        { rowOrdinal: 1, itemOid: 'RX_DOSE', value: '100' },
        { rowOrdinal: 1, itemOid: 'RX_UNIT', value: 'mg' },
      ]),
    )
  })
})
