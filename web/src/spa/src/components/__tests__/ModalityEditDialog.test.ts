/**
 * Phase E.6 — ModalityEditDialog spec.
 *
 * Pins:
 *   - all form fields render
 *   - submit emits a CreateModalityRequest in create mode
 *   - submit emits an UpdateModalityRequest in edit mode (code stripped)
 *   - cancel emits + closes
 *   - errorMessage prop surfaces inline so 409 / 400 copy lands
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

import ModalityEditDialog from '@/components/ModalityEditDialog.vue'
import type { Modality } from '@/types/modality'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { cancel: 'Cancel', saving: 'Saving…' },
      modalities: {
        action: { new: 'Neue Modalität', edit: 'Bearbeiten' },
        dialog: {
          titleNew: 'Neue Modalität',
          titleEdit: 'Modalität bearbeiten',
          field: {
            code: 'Code', labelEn: 'Label EN', labelDe: 'Label DE',
            ordinal: 'Ordinal', itemOidOd: 'OD OID', itemOidOs: 'OS OID',
            dataType: 'Type', unit: 'Unit',
          },
        },
        error: {
          duplicateCode: 'Code already exists',
          unknownOid: 'Unknown OID',
          missingOid: 'OD or OS OID required',
          network: 'Backend unreachable',
        },
      },
    },
  },
})

const SAMPLE: Modality = {
  modalityId: 7,
  code: 'OCT',
  labelEn: 'OCT thickness',
  labelDe: 'OCT-Dicke',
  ordinal: 30,
  itemOidOd: 'I_OCT_OD',
  itemOidOs: null,
  dataType: 'numeric',
  unit: 'μm',
}

function mountDialog(props: Record<string, unknown> = {}) {
  return mount(ModalityEditDialog, {
    props: { open: true, ...props },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

function setVal(sel: string, val: string) {
  const el = document.body.querySelector(sel) as HTMLInputElement | HTMLSelectElement
  el.value = val
  el.dispatchEvent(new Event('input', { bubbles: true }))
  el.dispatchEvent(new Event('change', { bubbles: true }))
}

describe('ModalityEditDialog', () => {
  beforeEach(() => { document.body.innerHTML = '' })

  it('renders all form fields in create mode', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    expect(document.body.querySelector('#modality-code')).not.toBeNull()
    expect(document.body.querySelector('#modality-label-en')).not.toBeNull()
    expect(document.body.querySelector('#modality-label-de')).not.toBeNull()
    expect(document.body.querySelector('#modality-ordinal')).not.toBeNull()
    expect(document.body.querySelector('#modality-oid-od')).not.toBeNull()
    expect(document.body.querySelector('#modality-oid-os')).not.toBeNull()
    expect(document.body.querySelector('#modality-datatype')).not.toBeNull()
    expect(document.body.querySelector('#modality-unit')).not.toBeNull()
    // Code editable in create mode.
    const code = document.body.querySelector('#modality-code') as HTMLInputElement
    expect(code.readOnly).toBe(false)
    wrapper.unmount()
  })

  it('readonlys + disables the code field in edit mode', async () => {
    const wrapper = mountDialog({ existing: SAMPLE })
    await flushPromises()
    const code = document.body.querySelector('#modality-code') as HTMLInputElement
    expect(code.value).toBe('OCT')
    expect(code.readOnly).toBe(true)
    expect(code.disabled).toBe(true)
    wrapper.unmount()
  })

  it('submit emits a CreateModalityRequest with the typed values', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    setVal('#modality-code', 'IOP')
    setVal('#modality-label-en', 'Pressure')
    setVal('#modality-label-de', 'Augeninnendruck')
    setVal('#modality-ordinal', '20')
    setVal('#modality-oid-od', 'I_IOP_OD')
    setVal('#modality-oid-os', 'I_IOP_OS')
    setVal('#modality-datatype', 'numeric')
    setVal('#modality-unit', 'mmHg')
    await nextTick()

    const submit = document.body.querySelector('[data-testid="modality-edit-submit"]') as HTMLButtonElement
    expect(submit.disabled).toBe(false)
    submit.click()
    await flushPromises()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toEqual({
      code: 'IOP',
      labelEn: 'Pressure',
      labelDe: 'Augeninnendruck',
      ordinal: 20,
      itemOidOd: 'I_IOP_OD',
      itemOidOs: 'I_IOP_OS',
      dataType: 'numeric',
      unit: 'mmHg',
    })
    wrapper.unmount()
  })

  it('submit in edit mode emits UpdateModalityRequest WITHOUT code', async () => {
    const wrapper = mountDialog({ existing: SAMPLE })
    await flushPromises()
    setVal('#modality-label-en', 'OCT thickness — updated')
    setVal('#modality-ordinal', '31')
    await nextTick()

    const submit = document.body.querySelector('[data-testid="modality-edit-submit"]') as HTMLButtonElement
    submit.click()
    await flushPromises()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const payload = emitted![0][0] as Record<string, unknown>
    expect(payload).not.toHaveProperty('code')
    expect(payload.labelEn).toBe('OCT thickness — updated')
    expect(payload.ordinal).toBe(31)
    expect(payload.itemOidOd).toBe('I_OCT_OD')
    // itemOidOs was null in the seed → not present in payload.
    expect(payload).not.toHaveProperty('itemOidOs')
    expect(payload.dataType).toBe('numeric')
    expect(payload.unit).toBe('μm')
    wrapper.unmount()
  })

  it('submit is disabled until code + at least one OID + dataType are set', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    const submit = document.body.querySelector('[data-testid="modality-edit-submit"]') as HTMLButtonElement
    // Empty form → disabled.
    expect(submit.disabled).toBe(true)

    setVal('#modality-code', 'X')
    await nextTick()
    // Code only — still no OID set → disabled.
    expect(submit.disabled).toBe(true)

    setVal('#modality-oid-od', 'I_X_OD')
    await nextTick()
    // dataType defaults to 'numeric' on the select; code + OD OID are set.
    expect(submit.disabled).toBe(false)

    wrapper.unmount()
  })

  it('cancel emits cancel + update:open=false', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const cancelBtn = buttons.find((b) => b.textContent?.trim() === 'Cancel')!
    expect(cancelBtn).toBeTruthy()
    cancelBtn.click()
    await flushPromises()

    expect(wrapper.emitted('cancel')).toBeTruthy()
    const openEv = wrapper.emitted('update:open')
    expect(openEv).toBeTruthy()
    expect(openEv![openEv!.length - 1][0]).toBe(false)
    wrapper.unmount()
  })

  it('surfaces errorMessage prop inline (e.g. 409 duplicate code copy)', async () => {
    const wrapper = mountDialog({ errorMessage: 'Code already exists' })
    await flushPromises()
    // ErrorText component renders the message somewhere in the dialog;
    // the body text should include it.
    expect(document.body.textContent).toContain('Code already exists')
    wrapper.unmount()
  })
})
