/**
 * Phase E hardening — A5 (2026-06-10).
 *
 * Pins the load-bearing contract for `GlobalErrorToast`:
 *   - renders the title + message when the errors store has a latest
 *     entry; renders nothing when the store is empty;
 *   - shows the `reqId` mono pill only when the latest entry carries a
 *     non-empty reqId (A4 surface);
 *   - clicking the close button calls `errors.dismiss(latest.id)` and
 *     emits a `dismiss` event with the same id.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import GlobalErrorToast from '@/components/GlobalErrorToast.vue'
import { useErrorsStore } from '@/stores/errors'
import { ApiError } from '@/api/client'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function mountToast() {
  return mount(GlobalErrorToast, {
    global: { plugins: [i18n] },
  })
}

describe('GlobalErrorToast', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders nothing when the errors store is empty', () => {
    const w = mountToast()
    expect(w.find('[data-testid="global-error-toast"]').exists()).toBe(false)
  })

  it('renders the title + latest message when an entry is present', async () => {
    const w = mountToast()
    const errors = useErrorsStore()
    errors.push(new Error('Save failed'))
    await w.vm.$nextTick()
    expect(w.find('[data-testid="global-error-toast"]').exists()).toBe(true)
    expect(w.text()).toContain('Ein Fehler ist aufgetreten')
    expect(w.find('[data-testid="global-error-toast-message"]').text()).toBe(
      'Save failed',
    )
  })

  it('applies role="status" + aria-live="polite" for WCAG 4.1.3', async () => {
    const w = mountToast()
    useErrorsStore().push(new Error('x'))
    await w.vm.$nextTick()
    const toast = w.find('[data-testid="global-error-toast"]')
    expect(toast.attributes('role')).toBe('status')
    expect(toast.attributes('aria-live')).toBe('polite')
  })

  it('shows the reqId pill when the latest entry carries a reqId', async () => {
    const w = mountToast()
    const errors = useErrorsStore()
    const err = new ApiError(500, 'Backend exploded')
    ;(err as ApiError & { reqId: string }).reqId = '7f3eabc'
    errors.push(err)
    await w.vm.$nextTick()
    const pill = w.find('[data-testid="global-error-toast-reqid"]')
    expect(pill.exists()).toBe(true)
    expect(pill.text()).toContain('Fehler-ID')
    expect(pill.text()).toContain('7f3eabc')
  })

  it('hides the reqId pill when no reqId is present', async () => {
    const w = mountToast()
    useErrorsStore().push(new Error('plain'))
    await w.vm.$nextTick()
    expect(w.find('[data-testid="global-error-toast-reqid"]').exists()).toBe(false)
  })

  it('emits dismiss + clears the entry when the close button is clicked', async () => {
    const w = mountToast()
    const errors = useErrorsStore()
    const entry = errors.push(new Error('to be dismissed'))
    await w.vm.$nextTick()
    await w.find('[data-testid="global-error-toast-close"]').trigger('click')
    expect(errors.recent).toHaveLength(0)
    const events = w.emitted('dismiss')
    expect(events).toBeTruthy()
    expect(events?.[0]).toEqual([entry.id])
  })

  it('auto-dismisses the entry after 8 seconds', async () => {
    const w = mountToast()
    const errors = useErrorsStore()
    errors.push(new Error('auto'))
    await w.vm.$nextTick()
    expect(errors.recent).toHaveLength(1)
    vi.advanceTimersByTime(8000)
    await w.vm.$nextTick()
    expect(errors.recent).toHaveLength(0)
  })

  it('close button carries an i18n aria-label', async () => {
    const w = mountToast()
    useErrorsStore().push(new Error('x'))
    await w.vm.$nextTick()
    const btn = w.find('[data-testid="global-error-toast-close"]')
    expect(btn.attributes('aria-label')).toBe('Fehlermeldung schließen')
  })
})
