import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import RepeatingGroupSection from '../RepeatingGroupSection.vue'
import type { CrfItem, CrfItemGroup } from '@/types/crf'

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
    vi.stubGlobal('confirm', vi.fn(() => true))
  })
  afterEach(() => {
    vi.unstubAllGlobals()
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
    expect(window.confirm).toHaveBeenCalledWith('Are you sure?')
    expect(wrapper.emitted('delete-row')).toEqual([[2]])
  })
})
