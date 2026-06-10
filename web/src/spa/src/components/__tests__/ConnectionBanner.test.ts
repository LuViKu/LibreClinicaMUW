/**
 * Phase E hardening — B4 (2026-06-10).
 *
 * Pins the load-bearing contract for `ConnectionBanner`:
 *   - hidden when `connection.online === true`;
 *   - rendered with role="alert" + aria-live="assertive" + the
 *     German title/body when the store has been flipped offline;
 *   - dismiss button refuses to hide the banner while
 *     `navigator.onLine === false` (so a real outage cannot be
 *     hidden by an impatient click);
 *   - dismiss button calls `markOnline()` when `navigator.onLine`
 *     has actually returned true.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import ConnectionBanner from '@/components/ConnectionBanner.vue'
import { useConnectionStore } from '@/stores/connection'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function mountBanner() {
  return mount(ConnectionBanner, {
    global: { plugins: [i18n] },
  })
}

describe('ConnectionBanner', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders nothing when the connection store is online', () => {
    const w = mountBanner()
    expect(w.find('[data-testid="connection-banner"]').exists()).toBe(false)
  })

  it('ConnectionBanner renders when offline (role=alert + dismiss button)', async () => {
    const w = mountBanner()
    const connection = useConnectionStore()
    connection.markOffline()
    await w.vm.$nextTick()

    const banner = w.find('[data-testid="connection-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.attributes('role')).toBe('alert')
    expect(banner.attributes('aria-live')).toBe('assertive')
    expect(w.find('[data-testid="connection-banner-close"]').exists()).toBe(true)
    expect(w.text()).toContain('Verbindung unterbrochen')
    expect(w.text()).toContain('Sie sind offline')
  })

  it('dismiss button refuses to hide the banner while navigator.onLine is false', async () => {
    const w = mountBanner()
    const connection = useConnectionStore()
    connection.markOffline()
    await w.vm.$nextTick()

    // jsdom reports navigator.onLine = true by default; force false
    // for this scenario.
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    try {
      await w.find('[data-testid="connection-banner-close"]').trigger('click')
      // Still hidden — the dismiss is a no-op while we still
      // believe we're offline.
      expect(connection.online).toBe(false)
      expect(w.find('[data-testid="connection-banner"]').exists()).toBe(true)
    } finally {
      Object.defineProperty(navigator, 'onLine', { value: true, configurable: true })
    }
  })

  it('dismiss button flips the store back online when navigator.onLine has returned', async () => {
    const w = mountBanner()
    const connection = useConnectionStore()
    connection.markOffline()
    await w.vm.$nextTick()

    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true })
    await w.find('[data-testid="connection-banner-close"]').trigger('click')
    expect(connection.online).toBe(true)
    await w.vm.$nextTick()
    expect(w.find('[data-testid="connection-banner"]').exists()).toBe(false)
  })

  it('close button carries an i18n aria-label', async () => {
    const w = mountBanner()
    useConnectionStore().markOffline()
    await w.vm.$nextTick()
    const btn = w.find('[data-testid="connection-banner-close"]')
    expect(btn.attributes('aria-label')).toBe('Schließen')
  })
})
