/**
 * Phase E.6 Data Export — Phase 3 (filters) FilterBuilder spec.
 *
 * Pins the load-bearing model + emit behaviour of the row authoring
 * UI:
 *
 *   - Adding a row emits a {@code update:modelValue} with the
 *     defaults (first selected item, '=' operator, empty value).
 *   - Type-appropriate operator filtering: ordering operators
 *     ({@code <}, {@code <=}, {@code >}, {@code >=}, {@code between})
 *     appear only on numeric/date items; on string items the selector
 *     omits them.
 *   - Operator change reshapes the row's value/values shape so the
 *     payload the backend sees stays well-formed (no stale scalar in a
 *     list-op slot, etc.).
 *
 * The component is presentational — it emits {@code preview-requested}
 * but doesn't reach into the Pinia store. The wizard owns the debounce
 * via the store; this spec asserts the emitted shape.
 */
import { describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import FilterBuilder from '@/components/FilterBuilder.vue'
import type { DatasetFilterDto, FilterItemDto } from '@/types/export'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

const NUMERIC_ITEM: FilterItemDto = {
  oid: 'I_AGE',
  label: 'Age',
  dataType: 'integer',
}

const STRING_ITEM: FilterItemDto = {
  oid: 'I_GENDER',
  label: 'Gender',
  dataType: 'character_string',
}

const DATE_ITEM: FilterItemDto = {
  oid: 'I_DOB',
  label: 'Date of birth',
  dataType: 'date',
}

function mountBuilder(
  selectedItems: FilterItemDto[],
  modelValue: DatasetFilterDto[] = [],
) {
  return mount(FilterBuilder, {
    props: {
      selectedItems,
      modelValue,
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('FilterBuilder — row model', () => {
  it('emits update:modelValue with one default row when "Add filter" is clicked', async () => {
    const wrapper = mountBuilder([NUMERIC_ITEM, STRING_ITEM])
    await flushPromises()

    await wrapper.get('[data-testid="filter-add"]').trigger('click')
    await flushPromises()

    const emits = wrapper.emitted('update:modelValue')
    expect(emits).toBeDefined()
    expect(emits!.length).toBeGreaterThan(0)
    const last = emits![emits!.length - 1]?.[0] as DatasetFilterDto[]
    expect(last).toHaveLength(1)
    expect(last[0].itemOid).toBe(NUMERIC_ITEM.oid)
    expect(last[0].operator).toBe('=')
    expect(last[0].value).toBe('')

    wrapper.unmount()
  })

  it('also emits preview-requested when a row is added', async () => {
    const wrapper = mountBuilder([NUMERIC_ITEM])
    await flushPromises()

    await wrapper.get('[data-testid="filter-add"]').trigger('click')
    await flushPromises()

    const previewEmits = wrapper.emitted('preview-requested')
    expect(previewEmits).toBeDefined()
    expect(previewEmits!.length).toBeGreaterThan(0)
    const payload = previewEmits![previewEmits!.length - 1]?.[0] as DatasetFilterDto[]
    expect(payload).toHaveLength(1)
    expect(payload[0].operator).toBe('=')

    wrapper.unmount()
  })

  it('disables the add button when no items are picked', async () => {
    const wrapper = mountBuilder([])
    await flushPromises()

    const button = wrapper.get('[data-testid="filter-add"]')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)

    wrapper.unmount()
  })

  it('removes the right row and emits the trimmed list', async () => {
    const wrapper = mountBuilder([NUMERIC_ITEM], [
      { itemOid: NUMERIC_ITEM.oid, operator: '=', value: '40' },
      { itemOid: NUMERIC_ITEM.oid, operator: '>=', value: '18' },
    ])
    await flushPromises()

    await wrapper.get('[data-testid="filter-remove-0"]').trigger('click')
    await flushPromises()

    const emits = wrapper.emitted('update:modelValue')
    const last = emits![emits!.length - 1]?.[0] as DatasetFilterDto[]
    expect(last).toHaveLength(1)
    expect(last[0].operator).toBe('>=')

    wrapper.unmount()
  })
})

describe('FilterBuilder — operator filtering by item dataType', () => {
  it("exposes ordering operators (<, <=, >, >=, between) for a numeric item", async () => {
    const wrapper = mountBuilder([NUMERIC_ITEM], [
      { itemOid: NUMERIC_ITEM.oid, operator: '=', value: '' },
    ])
    await flushPromises()

    const opSelect = wrapper.get('#filter-op-0').element as HTMLSelectElement
    const values = Array.from(opSelect.options).map((o) => o.value)
    for (const expected of ['=', '!=', '<', '<=', '>', '>=', 'in', 'between', 'is-null', 'not-null']) {
      expect(values).toContain(expected)
    }

    wrapper.unmount()
  })

  it('hides ordering operators for a string item', async () => {
    const wrapper = mountBuilder([STRING_ITEM], [
      { itemOid: STRING_ITEM.oid, operator: '=', value: '' },
    ])
    await flushPromises()

    const opSelect = wrapper.get('#filter-op-0').element as HTMLSelectElement
    const values = Array.from(opSelect.options).map((o) => o.value)
    expect(values).toContain('=')
    expect(values).toContain('!=')
    expect(values).toContain('in')
    expect(values).toContain('is-null')
    expect(values).toContain('not-null')
    expect(values).not.toContain('<')
    expect(values).not.toContain('<=')
    expect(values).not.toContain('>')
    expect(values).not.toContain('>=')
    expect(values).not.toContain('between')

    wrapper.unmount()
  })

  it('exposes ordering operators for a date item', async () => {
    const wrapper = mountBuilder([DATE_ITEM], [
      { itemOid: DATE_ITEM.oid, operator: '=', value: '' },
    ])
    await flushPromises()

    const opSelect = wrapper.get('#filter-op-0').element as HTMLSelectElement
    const values = Array.from(opSelect.options).map((o) => o.value)
    expect(values).toContain('<')
    expect(values).toContain('>')
    expect(values).toContain('between')

    wrapper.unmount()
  })
})

describe('FilterBuilder — operator change reshapes row payload', () => {
  it('drops value + populates values[] when switching to "in"', async () => {
    const wrapper = mountBuilder([STRING_ITEM], [
      { itemOid: STRING_ITEM.oid, operator: '=', value: 'F' },
    ])
    await flushPromises()

    const opSelect = wrapper.get('#filter-op-0')
    await opSelect.setValue('in')
    await flushPromises()

    const emits = wrapper.emitted('update:modelValue')
    const last = emits![emits!.length - 1]?.[0] as DatasetFilterDto[]
    expect(last[0].operator).toBe('in')
    expect(last[0].value).toBeNull()
    expect(last[0].values).toEqual([])

    wrapper.unmount()
  })

  it('populates values[low, high] when switching to "between"', async () => {
    const wrapper = mountBuilder([NUMERIC_ITEM], [
      { itemOid: NUMERIC_ITEM.oid, operator: '=', value: '40' },
    ])
    await flushPromises()

    await wrapper.get('#filter-op-0').setValue('between')
    await flushPromises()

    const emits = wrapper.emitted('update:modelValue')
    const last = emits![emits!.length - 1]?.[0] as DatasetFilterDto[]
    expect(last[0].operator).toBe('between')
    expect(last[0].values).toHaveLength(2)
    expect(last[0].value).toBeNull()

    wrapper.unmount()
  })

  it('clears value + values when switching to a unary operator', async () => {
    const wrapper = mountBuilder([STRING_ITEM], [
      { itemOid: STRING_ITEM.oid, operator: '=', value: 'F' },
    ])
    await flushPromises()

    await wrapper.get('#filter-op-0').setValue('is-null')
    await flushPromises()

    const emits = wrapper.emitted('update:modelValue')
    const last = emits![emits!.length - 1]?.[0] as DatasetFilterDto[]
    expect(last[0].operator).toBe('is-null')
    expect(last[0].value).toBeNull()
    expect(last[0].values).toBeUndefined()

    wrapper.unmount()
  })
})

describe('FilterBuilder — preview pane', () => {
  it('renders the formatted preview line when preview is supplied', async () => {
    const wrapper = mountBuilder([NUMERIC_ITEM], [
      { itemOid: NUMERIC_ITEM.oid, operator: '=', value: '40' },
    ])
    await wrapper.setProps({
      preview: {
        matchingSubjects: 12,
        totalSubjects: 50,
        matchingCrfs: 36,
        totalCrfs: 150,
      },
    })
    await flushPromises()

    const text = wrapper.get('[data-testid="filter-preview"]').text()
    expect(text).toContain('12')
    expect(text).toContain('50')
    expect(text).toContain('36')
    expect(text).toContain('150')

    wrapper.unmount()
  })

  it('renders the loading state while a preview is in flight', async () => {
    const wrapper = mountBuilder([NUMERIC_ITEM], [
      { itemOid: NUMERIC_ITEM.oid, operator: '=', value: '40' },
    ])
    await wrapper.setProps({ previewLoading: true })
    await flushPromises()

    const text = wrapper.get('[data-testid="filter-preview"]').text()
    expect(text.length).toBeGreaterThan(0)
    expect(text).toMatch(/[A-Za-zÄÖÜäöüß…\.]/)

    wrapper.unmount()
  })
})
