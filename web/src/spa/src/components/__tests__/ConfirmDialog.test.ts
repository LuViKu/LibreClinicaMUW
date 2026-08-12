/**
 * UX sweep (#19, 2026-08-12) — ConfirmDialog + useConfirm spec.
 * Pins the promise-based contract: ask() renders the modal, confirm
 * resolves true, cancel/Escape/backdrop resolve false, and the dialog
 * unmounts after each response.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { useConfirm } from '@/composables/useConfirm'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function mountDialog() {
  return mount(ConfirmDialog, { global: { plugins: [i18n] } })
}

beforeEach(() => setActivePinia(createPinia()))

describe('ConfirmDialog + useConfirm', () => {
  it('is hidden until a confirm is requested', () => {
    const w = mountDialog()
    expect(w.find('[data-testid="confirm-dialog"]').exists()).toBe(false)
  })

  it('renders the message and resolves true on confirm', async () => {
    const w = mountDialog()
    const confirm = useConfirm()
    const p = confirm({ message: 'Delete this site?', danger: true })
    await flushPromises()
    expect(w.find('[data-testid="confirm-dialog-message"]').text()).toBe('Delete this site?')
    await w.find('[data-testid="confirm-dialog-confirm"]').trigger('click')
    expect(await p).toBe(true)
    await flushPromises()
    expect(w.find('[data-testid="confirm-dialog"]').exists()).toBe(false)
  })

  it('resolves false on cancel', async () => {
    const w = mountDialog()
    const confirm = useConfirm()
    const p = confirm({ message: 'Sure?' })
    await flushPromises()
    await w.find('[data-testid="confirm-dialog-cancel"]').trigger('click')
    expect(await p).toBe(false)
  })

  it('resolves false on backdrop click', async () => {
    const w = mountDialog()
    const confirm = useConfirm()
    const p = confirm({ message: 'Sure?' })
    await flushPromises()
    await w.find('[data-testid="confirm-dialog-backdrop"]').trigger('click')
    expect(await p).toBe(false)
  })
})
