import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DateInput from '../DateInput.vue'

describe('DateInput', () => {
  it('displays the ISO value in DD/MM/YYYY in a text field', () => {
    const wrapper = mount(DateInput, { props: { modelValue: '2026-06-10' } })
    const input = wrapper.find<HTMLInputElement>('input[type="text"]')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('10/06/2026')
    expect(input.attributes('placeholder')).toBe('TT/MM/JJJJ')
  })

  it('emits the ISO YYYY-MM-DD string when a full DD/MM/YYYY is typed', async () => {
    const wrapper = mount(DateInput, { props: { modelValue: '' } })
    const input = wrapper.find<HTMLInputElement>('input[type="text"]')
    await input.setValue('10/06/2026')
    const emits = wrapper.emitted('update:modelValue')
    expect(emits).toBeTruthy()
    expect(emits?.[emits.length - 1][0]).toBe('2026-06-10')
  })

  it('does not emit a value for an impossible date (31/02/2026)', async () => {
    const wrapper = mount(DateInput, { props: { modelValue: '' } })
    const input = wrapper.find<HTMLInputElement>('input[type="text"]')
    await input.setValue('31/02/2026')
    const emits = wrapper.emitted('update:modelValue') ?? []
    expect(emits.some((e) => e[0] === '2026-02-31')).toBe(false)
  })

  it('provides a calendar button + a native date picker that emits ISO', async () => {
    const wrapper = mount(DateInput, { props: { modelValue: '' } })
    expect(wrapper.find('button[aria-label*="Kalender"]').exists()).toBe(true)
    const picker = wrapper.find<HTMLInputElement>('input[type="date"]')
    expect(picker.exists()).toBe(true)
    picker.element.value = '2026-06-10'
    await picker.trigger('change')
    const emits = wrapper.emitted('update:modelValue')
    expect(emits?.[emits.length - 1][0]).toBe('2026-06-10')
    // and the visible field reflects it in DD/MM/YYYY
    expect(wrapper.find<HTMLInputElement>('input[type="text"]').element.value).toBe('10/06/2026')
  })

  it('reflects the error prop via aria-invalid + the rose error class', () => {
    const wrapper = mount(DateInput, { props: { modelValue: '', error: true } })
    const input = wrapper.find<HTMLInputElement>('input[type="text"]')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.classes().join(' ')).toContain('border-rose-400')
  })

  it('reflects the required prop via aria-required + the native required attr', () => {
    const wrapper = mount(DateInput, { props: { modelValue: '', required: true } })
    const input = wrapper.find<HTMLInputElement>('input[type="text"]')
    expect(input.attributes('aria-required')).toBe('true')
    expect(input.attributes('required')).toBeDefined()
  })

  it('applies min and max to the calendar picker', () => {
    const wrapper = mount(DateInput, {
      props: { modelValue: '', min: '2020-01-01', max: '2030-12-31' },
    })
    const picker = wrapper.find<HTMLInputElement>('input[type="date"]')
    expect(picker.attributes('min')).toBe('2020-01-01')
    expect(picker.attributes('max')).toBe('2030-12-31')
  })
})
