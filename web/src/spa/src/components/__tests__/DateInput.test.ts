import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DateInput from '../DateInput.vue'

describe('DateInput', () => {
  it('renders a native input[type="date"] with lang="de-AT"', () => {
    const wrapper = mount(DateInput, {
      props: { modelValue: '2026-06-10' },
    })
    const input = wrapper.find<HTMLInputElement>('input')
    expect(input.exists()).toBe(true)
    expect(input.element.type).toBe('date')
    expect(input.attributes('lang')).toBe('de-AT')
    expect(input.element.value).toBe('2026-06-10')
  })

  it('emits the raw ISO YYYY-MM-DD string on user input', async () => {
    const wrapper = mount(DateInput, { props: { modelValue: '' } })
    const input = wrapper.find<HTMLInputElement>('input')
    await input.setValue('2026-06-10')
    const emits = wrapper.emitted('update:modelValue')
    expect(emits).toBeTruthy()
    expect(emits?.[0][0]).toBe('2026-06-10')
  })

  it('reflects the error prop via aria-invalid + the rose error class', () => {
    const wrapper = mount(DateInput, {
      props: { modelValue: '', error: true },
    })
    const input = wrapper.find<HTMLInputElement>('input')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.classes().join(' ')).toContain('border-rose-400')
  })

  it('reflects the required prop via aria-required + the native required attr', () => {
    const wrapper = mount(DateInput, {
      props: { modelValue: '', required: true },
    })
    const input = wrapper.find<HTMLInputElement>('input')
    expect(input.attributes('aria-required')).toBe('true')
    expect(input.attributes('required')).toBeDefined()
  })

  it('respects min and max props', () => {
    const wrapper = mount(DateInput, {
      props: { modelValue: '', min: '2020-01-01', max: '2030-12-31' },
    })
    const input = wrapper.find<HTMLInputElement>('input')
    expect(input.attributes('min')).toBe('2020-01-01')
    expect(input.attributes('max')).toBe('2030-12-31')
  })
})
