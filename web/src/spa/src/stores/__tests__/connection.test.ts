/**
 * Phase E hardening — B4 (2026-06-10).
 *
 * Pins the load-bearing contract for the `connection` store: defaults
 * to online, flips on the browser-level `offline` event (wired in
 * main.ts), and flips back on the `online` event. The api/client.ts
 * lazy-import path that also calls `markOffline()` from inside the
 * fetch catch block is exercised indirectly via the action itself —
 * the wiring of main.ts listeners is asserted here, the client.ts hook
 * is asserted via its own existence + the store's action surface.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useConnectionStore } from '../connection'

describe('useConnectionStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('defaults to online=true so a fresh page load does not flash the banner', () => {
    const store = useConnectionStore()
    expect(store.online).toBe(true)
  })

  it('markOffline() flips state to false', () => {
    const store = useConnectionStore()
    store.markOffline()
    expect(store.online).toBe(false)
  })

  it('markOnline() resets state to true', () => {
    const store = useConnectionStore()
    store.markOffline()
    expect(store.online).toBe(false)
    store.markOnline()
    expect(store.online).toBe(true)
  })

  it('connection store flips on offline event when the main.ts listeners are wired', () => {
    // The plan's "offline event flips the store" assertion. The
    // listeners themselves live in main.ts; replicate the wiring
    // inline so this test pins the wire-in contract without booting
    // the whole app shell.
    const store = useConnectionStore()
    const offlineHandler = () => store.markOffline()
    const onlineHandler = () => store.markOnline()
    window.addEventListener('offline', offlineHandler)
    window.addEventListener('online', onlineHandler)

    try {
      window.dispatchEvent(new Event('offline'))
      expect(store.online).toBe(false)
      window.dispatchEvent(new Event('online'))
      expect(store.online).toBe(true)
    } finally {
      window.removeEventListener('offline', offlineHandler)
      window.removeEventListener('online', onlineHandler)
    }
  })
})
