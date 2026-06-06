/**
 * Phase E.6 admin-rfc — ReasonForChangeModal spec.
 *
 * Pins three pieces of load-bearing behaviour:
 *
 *  1. Confirm stays disabled until every prompt has a non-blank reason,
 *     so the operator can't slip through with whitespace and trip a
 *     400 from the backend's RFC validator.
 *  2. On Confirm, the modal emits trimmed reasons keyed by item OID +
 *     closes itself (via {@code update:open=false}). The parent's
 *     handler is what dispatches `store.stageReason` + retries the
 *     save — this test pins the wire payload so a refactor of either
 *     side can't silently drop the trim.
 *  3. Cancel emits `cancel` + closes without leaking the (possibly
 *     half-typed) draft to the parent. Pending reasons stay in the
 *     store for the next attempt.
 */
import { describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import ReasonForChangeModal from '@/components/ReasonForChangeModal.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

const BASE_PROMPTS = [
  { oid: 'I_HEIGHT_CM', label: 'Height (cm)', originalValue: '170', currentValue: '174' },
  { oid: 'I_WEIGHT_KG', label: 'Weight (kg)', originalValue: '70.0', currentValue: '71.3' },
]

function mountModal(overrides: Partial<{ open: boolean; prompts: typeof BASE_PROMPTS; initialReasons: Record<string, string> }> = {}) {
  return mount(ReasonForChangeModal, {
    global: { plugins: [i18n] },
    props: {
      open: overrides.open ?? true,
      prompts: overrides.prompts ?? BASE_PROMPTS,
      initialReasons: overrides.initialReasons ?? {},
    },
    attachTo: document.body,
  })
}

describe('ReasonForChangeModal', () => {
  it('disables Confirm until every prompt has a non-blank reason', async () => {
    const wrapper = mountModal()
    await flushPromises()

    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const confirmBtn = buttons.find((b) => b.textContent?.includes('Confirm reasons'))!
    expect(confirmBtn).toBeTruthy()
    expect(confirmBtn.disabled).toBe(true)

    // Fill only the first textarea — Confirm should still be disabled.
    const textareas = document.body.querySelectorAll('textarea')
    expect(textareas.length).toBe(2)
    ;(textareas[0] as HTMLTextAreaElement).value = 'corrected source'
    textareas[0].dispatchEvent(new Event('input'))
    await flushPromises()
    expect(confirmBtn.disabled).toBe(true)

    // Fill the second with whitespace only → still blocked.
    ;(textareas[1] as HTMLTextAreaElement).value = '   '
    textareas[1].dispatchEvent(new Event('input'))
    await flushPromises()
    expect(confirmBtn.disabled).toBe(true)

    // Real text in both unlocks Confirm.
    ;(textareas[1] as HTMLTextAreaElement).value = 'lab re-key'
    textareas[1].dispatchEvent(new Event('input'))
    await flushPromises()
    expect(confirmBtn.disabled).toBe(false)

    wrapper.unmount()
  })

  it('emits trimmed reasons keyed by item OID on Confirm + closes', async () => {
    const wrapper = mountModal()
    await flushPromises()

    const textareas = document.body.querySelectorAll('textarea')
    ;(textareas[0] as HTMLTextAreaElement).value = '  corrected source  '
    textareas[0].dispatchEvent(new Event('input'))
    ;(textareas[1] as HTMLTextAreaElement).value = 'lab re-key'
    textareas[1].dispatchEvent(new Event('input'))
    await flushPromises()

    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    const confirmBtn = buttons.find((b) => b.textContent?.includes('Confirm reasons'))!
    confirmBtn.click()
    await flushPromises()

    const confirmEvents = wrapper.emitted('confirm')
    expect(confirmEvents).toBeTruthy()
    expect(confirmEvents?.[0][0]).toEqual({
      I_HEIGHT_CM: 'corrected source',
      I_WEIGHT_KG: 'lab re-key',
    })
    // The modal closes itself so the parent's v-model:open flips back.
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])

    wrapper.unmount()
  })

  it('emits cancel + closes without leaking draft when Cancel is clicked', async () => {
    const wrapper = mountModal()
    await flushPromises()

    const textareas = document.body.querySelectorAll('textarea')
    ;(textareas[0] as HTMLTextAreaElement).value = 'partial reason'
    textareas[0].dispatchEvent(new Event('input'))
    await flushPromises()

    const buttons = Array.from(document.body.querySelectorAll('button')) as HTMLButtonElement[]
    // The footer cancel button (text "Cancel") — not the close-X (aria-label="Close").
    const cancelBtn = buttons.find(
      (b) => b.textContent?.trim() === 'Cancel' && b.getAttribute('aria-label') !== 'Close',
    )!
    expect(cancelBtn).toBeTruthy()
    cancelBtn.click()
    await flushPromises()

    expect(wrapper.emitted('cancel')).toBeTruthy()
    expect(wrapper.emitted('confirm')).toBeUndefined()
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])

    wrapper.unmount()
  })
})
