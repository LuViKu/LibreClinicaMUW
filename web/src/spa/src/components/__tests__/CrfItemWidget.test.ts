/**
 * Phase E.6 — CrfItemWidget BL rendering + preview show-when wiring.
 *
 * Locks two things into the test suite:
 *
 *   1. BL items render as a `Ja` / `Nein` radio pair, NOT a single
 *      checkbox. A checkbox conflates "unanswered" with "Nein"; the
 *      radio pair forces an explicit answer which downstream show-when
 *      rules depend on (e.g. the imaging "reason if not done" follow-up
 *      that appears only when the parent BL is explicitly "Nein").
 *   2. The preview store honours per-item show-when. A reason item
 *      depending on `OD_SPECTRALIS_DONE == '0'` stays hidden until the
 *      operator picks "Nein"; flipping back to "Ja" hides it again and
 *      the in-memory value is excluded from the payload the preview
 *      submit would build.
 */
import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'

import CrfItemWidget from '../CrfItemWidget.vue'
import { useCrfPreviewStore } from '@/stores/crfPreview'
import type { CrfItem, CrfSchema } from '@/types/crf'
import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'

function mkItem(
  oid: string,
  label: string,
  dataType: CrfItem['dataType'] = 'string',
  extra: Partial<CrfItem> = {},
): CrfItem {
  return {
    oid,
    label,
    dataType,
    required: false,
    ...extra,
  } as unknown as CrfItem
}

function mkI18n(locale: 'en' | 'de' = 'en') {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en',
    messages: { en: enMessages, de: deMessages },
  })
}

describe('CrfItemWidget — BL Yes/No radio', () => {
  it('renders two radio inputs sharing one name + Ja / Nein labels (de)', () => {
    const item = mkItem('OD_SPECTRALIS_DONE', 'Spectralis-OCT durchgeführt', 'boolean')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '', suppressLabel: true },
    })

    const radios = wrapper.findAll<HTMLInputElement>('input[type="radio"]')
    expect(radios).toHaveLength(2)
    // Same name attribute → browser groups them.
    expect(radios[0].element.name).toBe('bl-radio-OD_SPECTRALIS_DONE')
    expect(radios[1].element.name).toBe('bl-radio-OD_SPECTRALIS_DONE')
    // Values are the wire-contract tokens.
    expect(radios[0].element.value).toBe('1')
    expect(radios[1].element.value).toBe('0')
    // Empty modelValue → neither selected (reads as "unbeantwortet").
    expect(radios[0].element.checked).toBe(false)
    expect(radios[1].element.checked).toBe(false)

    expect(wrapper.text()).toContain('Ja')
    expect(wrapper.text()).toContain('Nein')
  })

  it('selects the Ja radio when modelValue === "1"', () => {
    const item = mkItem('OD_SPECTRALIS_DONE', 'Spectralis-OCT durchgeführt', 'boolean')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '1', suppressLabel: true },
    })
    const radios = wrapper.findAll<HTMLInputElement>('input[type="radio"]')
    expect(radios[0].element.checked).toBe(true)
    expect(radios[1].element.checked).toBe(false)
  })

  it('selects the Nein radio when modelValue === "0"', () => {
    const item = mkItem('OD_SPECTRALIS_DONE', 'Spectralis-OCT durchgeführt', 'boolean')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '0', suppressLabel: true },
    })
    const radios = wrapper.findAll<HTMLInputElement>('input[type="radio"]')
    expect(radios[0].element.checked).toBe(false)
    expect(radios[1].element.checked).toBe(true)
  })

  it('neither selected when modelValue is null / undefined / empty', () => {
    const item = mkItem('OD_X', 'X', 'boolean')
    for (const v of [null, undefined, '']) {
      const wrapper = mount(CrfItemWidget, {
        global: { plugins: [mkI18n()] },
        props: { item, modelValue: v as unknown, suppressLabel: true },
      })
      const radios = wrapper.findAll<HTMLInputElement>('input[type="radio"]')
      expect(radios[0].element.checked).toBe(false)
      expect(radios[1].element.checked).toBe(false)
    }
  })

  it('emits "1" when the Ja radio is selected', async () => {
    const item = mkItem('OD_SPECTRALIS_DONE', 'Spectralis-OCT durchgeführt', 'boolean')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '', suppressLabel: true },
    })
    const yes = wrapper.findAll<HTMLInputElement>('input[type="radio"]')[0]
    await yes.setValue(true)
    const emits = wrapper.emitted('update:modelValue')
    expect(emits).toBeTruthy()
    expect(emits?.[0][0]).toBe('1')
  })

  it('emits "0" when the Nein radio is selected', async () => {
    const item = mkItem('OD_SPECTRALIS_DONE', 'Spectralis-OCT durchgeführt', 'boolean')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '', suppressLabel: true },
    })
    const no = wrapper.findAll<HTMLInputElement>('input[type="radio"]')[1]
    await no.setValue(true)
    const emits = wrapper.emitted('update:modelValue')
    expect(emits).toBeTruthy()
    expect(emits?.[0][0]).toBe('0')
  })

  it('renders English Yes/No under the en locale', () => {
    const item = mkItem('OD_X', 'X', 'boolean')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('en')] },
      props: { item, modelValue: '', suppressLabel: true },
    })
    expect(wrapper.text()).toContain('Yes')
    expect(wrapper.text()).toContain('No')
  })
})

describe('CrfItemWidget — DATE / PDATE rendering', () => {
  it('renders a native date picker (input[type=date]) for dataType="date"', () => {
    const item = mkItem('I_DOB', 'Geburtsdatum', 'date')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '1980-01-15', suppressLabel: true },
    })
    const input = wrapper.find<HTMLInputElement>('input[type="date"]')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('1980-01-15')
  })

  it('emits the raw YYYY-MM-DD string on date input', async () => {
    const item = mkItem('I_DOB', 'Geburtsdatum', 'date')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '', suppressLabel: true },
    })
    const input = wrapper.find<HTMLInputElement>('input[type="date"]')
    await input.setValue('2026-06-07')
    const emits = wrapper.emitted('update:modelValue')
    expect(emits).toBeTruthy()
    expect(emits?.[0][0]).toBe('2026-06-07')
  })

  it('renders a constrained text input for dataType="partial-date"', () => {
    const item = mkItem('I_DX_DATE', 'Diagnosedatum (Monat / Jahr)', 'partial-date')
    const wrapper = mount(CrfItemWidget, {
      global: { plugins: [mkI18n('de')] },
      props: { item, modelValue: '2024-03', suppressLabel: true },
    })
    // PDATE stays as a text input because no native control covers both
    // YYYY and YYYY-MM. Pattern + inputmode guide the user instead.
    const input = wrapper.find<HTMLInputElement>(`input#item-${item.oid}`)
    expect(input.exists()).toBe(true)
    expect(input.element.type).toBe('text')
    expect(input.attributes('pattern')).toBe('\\d{4}(-\\d{2})?')
    expect(input.attributes('inputmode')).toBe('numeric')
    expect(input.element.value).toBe('2024-03')
  })
})

describe('crfPreview store — BL show-when filter for the reason follow-up', () => {
  // Schema mirrors the Spectralis-OCT ophth-preset shape: a parent BL
  // item + a reason text follow-up whose show-when fires only when the
  // parent is explicitly "Nein" (value === '0').
  const PARENT_OID = 'OD_SPECTRALIS_DONE'
  const REASON_OID = 'OD_SPECTRALIS_DONE_REASON'

  function mkSchema(): CrfSchema {
    return {
      oid: 'F_PREVIEW',
      name: 'preview',
      version: 'v0',
      sections: [
        {
          oid: 'S1',
          title: 'Imaging',
          items: [
            mkItem(PARENT_OID, 'Spectralis-OCT durchgeführt', 'boolean'),
            mkItem(REASON_OID, 'Grund (falls nein)', 'string', {
              showWhen: JSON.stringify({
                sourceItemOid: PARENT_OID,
                comparator: '==',
                literal: '0',
              }),
            }),
          ],
        },
      ],
    }
  }

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('hides the reason while the parent BL is unanswered', () => {
    const store = useCrfPreviewStore()
    store.load(mkSchema())
    expect(store.isItemHidden(REASON_OID)).toBe(true)
    expect(store.hiddenItemOids.has(REASON_OID)).toBe(true)
  })

  it('hides the reason when the parent BL is "Ja" (value="1")', () => {
    const store = useCrfPreviewStore()
    store.load(mkSchema())
    store.setValue(PARENT_OID, '1')
    expect(store.isItemHidden(REASON_OID)).toBe(true)
  })

  it('shows the reason when the parent BL is "Nein" (value="0")', () => {
    const store = useCrfPreviewStore()
    store.load(mkSchema())
    store.setValue(PARENT_OID, '0')
    expect(store.isItemHidden(REASON_OID)).toBe(false)
  })

  it('re-hides the reason when the operator toggles "Nein" back to "Ja"', () => {
    const store = useCrfPreviewStore()
    store.load(mkSchema())
    store.setValue(PARENT_OID, '0')
    store.setValue(REASON_OID, 'OP nicht möglich')
    expect(store.isItemHidden(REASON_OID)).toBe(false)

    store.setValue(PARENT_OID, '1')
    expect(store.isItemHidden(REASON_OID)).toBe(true)
    // The hidden value lives on in hiddenValues so a flip back restores
    // the operator's typed reason without losing it.
    expect(store.hiddenValues[REASON_OID]).toBe('OP nicht möglich')
  })

  it('excludes the hidden reason from buildPreviewPayload', () => {
    const store = useCrfPreviewStore()
    store.load(mkSchema())
    store.setValue(PARENT_OID, '0')
    store.setValue(REASON_OID, 'OP nicht möglich')
    expect(store.buildPreviewPayload().values).toEqual({
      [PARENT_OID]: '0',
      [REASON_OID]: 'OP nicht möglich',
    })
    // Flip parent to Ja → reason hides → payload drops the reason.
    store.setValue(PARENT_OID, '1')
    expect(store.buildPreviewPayload().values).toEqual({
      [PARENT_OID]: '1',
    })
  })

  it('visibleValues mirrors the buildPreviewPayload filter', () => {
    const store = useCrfPreviewStore()
    store.load(mkSchema())
    store.setValue(PARENT_OID, '1')
    expect(store.visibleValues).toEqual({ [PARENT_OID]: '1' })
  })
})
