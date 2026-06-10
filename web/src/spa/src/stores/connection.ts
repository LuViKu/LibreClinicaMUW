import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Phase E hardening — B4 (SPA offline banner + connection store).
 *
 * Singleton boolean store mirroring the browser's connectivity to the
 * backend. The store is the single source of truth for the
 * `ConnectionBanner` component and any future per-form `:disabled`
 * integration (deferred to a follow-up per the 2026-06-10 hardening
 * plan). There is no ring buffer and no event log — a flaky network
 * fires `offline`/`online` repeatedly and a list would balloon without
 * adding signal.
 *
 * Three signals flip `online`:
 *   1. `window.addEventListener('offline', …)` in main.ts — the
 *      browser-level NIC / WiFi disconnect event.
 *   2. `window.addEventListener('online',  …)` in main.ts — the
 *      complementary reconnect event.
 *   3. `api/client.ts` catch block — when `ApiNetworkError` is thrown
 *      we flip to offline manually, because the browser still reports
 *      `navigator.onLine === true` for the captive-portal / DNS /
 *      transient-router-drop case but our fetch already failed. We
 *      deliberately do NOT flip back to online on fetch success —
 *      that's left to the browser `online` event so a single lucky
 *      retry on a still-flaky network doesn't dismiss the banner.
 *
 * Mirror the style of {@link useErrorsStore} (Phase A5): setup-style
 * `defineStore`, ref-based state, named actions for the wire points.
 */

export const useConnectionStore = defineStore('connection', () => {
  /** `true` when we believe the backend is reachable. Defaults to true so
   * a freshly-loaded SPA doesn't flash the banner before any signal has
   * arrived; the browser fires `online`/`offline` on subsequent state
   * changes only. */
  const online = ref(true)

  function markOffline(): void {
    online.value = false
  }

  function markOnline(): void {
    online.value = true
  }

  return { online, markOffline, markOnline }
})
