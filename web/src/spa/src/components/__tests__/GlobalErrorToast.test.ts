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

  it('auto-dismisses the entry after 30 seconds (clinical reading time)', async () => {
    // Wave 1C — bumped from 8s to 30s. Operators reported the prior
    // window was too short to read, copy a reqId, or decide how to
    // react before the toast disappeared.
    const w = mountToast()
    const errors = useErrorsStore()
    errors.push(new Error('auto'))
    await w.vm.$nextTick()
    expect(errors.recent).toHaveLength(1)
    // Still present after 8s (the old window).
    vi.advanceTimersByTime(8000)
    await w.vm.$nextTick()
    expect(errors.recent).toHaveLength(1)
    // Now flush the remaining 22s.
    vi.advanceTimersByTime(22000)
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

  // ---------------------------------------------------------------------------
  // Wave 1C (2026-06-19): Details expander + stacked dropdown for queued errors.
  // ---------------------------------------------------------------------------

  describe('Details expander', () => {
    it('renders the Details toggle button when an entry is present', async () => {
      const w = mountToast()
      useErrorsStore().push(new Error('x'))
      await w.vm.$nextTick()
      const toggle = w.find('[data-testid="global-error-toast-details-toggle"]')
      expect(toggle.exists()).toBe(true)
      expect(toggle.text()).toBe('Details anzeigen')
      expect(toggle.attributes('aria-expanded')).toBe('false')
    })

    it('expands the Details panel when the toggle is clicked', async () => {
      const w = mountToast()
      const errors = useErrorsStore()
      const err = new ApiError(
        500,
        'POST /LibreClinica/pages/api/v1/event-crfs/42/save → 500',
        { message: 'Validation failed: itemOid IOP_OD must be a number' },
        'abc-123',
      )
      errors.push(err)
      await w.vm.$nextTick()

      expect(w.find('[data-testid="global-error-toast-details"]').exists()).toBe(false)
      await w.find('[data-testid="global-error-toast-details-toggle"]').trigger('click')
      const panel = w.find('[data-testid="global-error-toast-details"]')
      expect(panel.exists()).toBe(true)

      // Server message + URL surfaced inside the panel.
      const srv = w.find('[data-testid="global-error-toast-server-message"]')
      expect(srv.exists()).toBe(true)
      expect(srv.text()).toContain('Validation failed')

      const url = w.find('[data-testid="global-error-toast-url"]')
      expect(url.exists()).toBe(true)
      expect(url.text()).toContain('POST')
      expect(url.text()).toContain('/pages/api/v1/event-crfs/42/save')

      // Toggle label flips when open.
      const toggle = w.find('[data-testid="global-error-toast-details-toggle"]')
      expect(toggle.text()).toBe('Details ausblenden')
      expect(toggle.attributes('aria-expanded')).toBe('true')
    })

    it('pauses the auto-dismiss timer while Details is open', async () => {
      const w = mountToast()
      const errors = useErrorsStore()
      errors.push(new Error('slow read'))
      await w.vm.$nextTick()

      // Open Details before the timer expires.
      await w.find('[data-testid="global-error-toast-details-toggle"]').trigger('click')

      // Advance well past the 30s auto-dismiss — the entry must still be present.
      vi.advanceTimersByTime(60000)
      await w.vm.$nextTick()
      expect(errors.recent).toHaveLength(1)

      // Close Details → timer re-arms; advance 30s → dismissed.
      await w.find('[data-testid="global-error-toast-details-toggle"]').trigger('click')
      vi.advanceTimersByTime(30000)
      await w.vm.$nextTick()
      expect(errors.recent).toHaveLength(0)
    })

    it('hides the URL row when the message is not API-shaped', async () => {
      const w = mountToast()
      useErrorsStore().push(new Error('Vue render exploded'))
      await w.vm.$nextTick()
      await w.find('[data-testid="global-error-toast-details-toggle"]').trigger('click')
      expect(w.find('[data-testid="global-error-toast-url"]').exists()).toBe(false)
      expect(w.find('[data-testid="global-error-toast-server-message"]').exists()).toBe(false)
    })
  })

  describe('Queued-errors dropdown', () => {
    it('hides the "weitere Fehler" pill when only 1 error is queued', async () => {
      const w = mountToast()
      useErrorsStore().push(new Error('only one'))
      await w.vm.$nextTick()
      expect(w.find('[data-testid="global-error-toast-queue-toggle"]').exists()).toBe(false)
    })

    it('shows the "weitere Fehler" pill when ≥2 errors are queued', async () => {
      const w = mountToast()
      const errors = useErrorsStore()
      errors.push(new Error('first'))
      errors.push(new Error('second'))
      await w.vm.$nextTick()

      const pill = w.find('[data-testid="global-error-toast-queue-toggle"]')
      expect(pill.exists()).toBe(true)
      // Latest is "second" → 1 other queued.
      expect(pill.text()).toContain('1 weitere Fehler')
    })

    it('opens the queued list and surfaces older entries when the pill is clicked', async () => {
      const w = mountToast()
      const errors = useErrorsStore()
      errors.push(new Error('oldest'))
      errors.push(new Error('middle'))
      errors.push(new Error('newest'))
      await w.vm.$nextTick()

      expect(w.find('[data-testid="global-error-toast-queue-list"]').exists()).toBe(false)
      await w.find('[data-testid="global-error-toast-queue-toggle"]').trigger('click')
      const list = w.find('[data-testid="global-error-toast-queue-list"]')
      expect(list.exists()).toBe(true)
      // Both older entries listed (newest is in the main view).
      const items = list.findAll('button')
      expect(items).toHaveLength(2)
      // Newest-of-the-others appears first → "middle", then "oldest".
      expect(items[0].text()).toContain('middle')
      expect(items[1].text()).toContain('oldest')
    })

    it('promotes a clicked queued entry to the main toast view', async () => {
      const w = mountToast()
      const errors = useErrorsStore()
      errors.push(new Error('older'))
      errors.push(new Error('newer'))
      await w.vm.$nextTick()
      expect(errors.latest?.message).toBe('newer')

      await w.find('[data-testid="global-error-toast-queue-toggle"]').trigger('click')
      // Click the only listed other entry ("older").
      const item = w.find('[data-testid^="global-error-toast-queue-item-"]')
      expect(item.exists()).toBe(true)
      await item.trigger('click')

      // "older" is now the latest; "newer" was dismissed to make room.
      expect(errors.latest?.message).toBe('older')
      expect(errors.recent).toHaveLength(1)
    })

    it('caps the dropdown at 5 queued entries even when the ring buffer holds more', async () => {
      const w = mountToast()
      const errors = useErrorsStore()
      for (let i = 0; i < 8; i++) errors.push(new Error(`err ${i}`))
      // Latest is "err 7"; 7 others queued — the dropdown caps at 5.
      await w.vm.$nextTick()
      await w.find('[data-testid="global-error-toast-queue-toggle"]').trigger('click')
      const items = w.find('[data-testid="global-error-toast-queue-list"]').findAll('button')
      expect(items).toHaveLength(5)
    })
  })

  describe('Details expander — URL parse hardening', () => {
    it('extracts METHOD + URL from a canonical ApiError message', async () => {
      const w = mountToast()
      const errors = useErrorsStore()
      const err = new ApiError(
        400,
        'PATCH /LibreClinica/pages/api/v1/foo/42 → 400',
        null,
        '',
      )
      errors.push(err)
      await w.vm.$nextTick()
      await w.find('[data-testid="global-error-toast-details-toggle"]').trigger('click')
      const url = w.find('[data-testid="global-error-toast-url"]')
      expect(url.exists()).toBe(true)
      expect(url.text()).toContain('PATCH')
      expect(url.text()).toContain('/LibreClinica/pages/api/v1/foo/42')
    })
  })
})
