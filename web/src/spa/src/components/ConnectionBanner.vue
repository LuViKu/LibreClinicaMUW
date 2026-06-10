<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useConnectionStore } from '@/stores/connection'

/**
 * Phase E hardening — B4 (SPA offline banner).
 *
 * Top-of-page banner mounted once in {@link App.vue}, above
 * `<main id="main-content">`. Renders ONLY when the
 * {@link useConnectionStore} flips to `online === false` — either via
 * the browser's `offline` event or via `api/client.ts` catching an
 * `ApiNetworkError` (captive portal, DNS fail, transient router
 * drop where `navigator.onLine` still lies).
 *
 * Accessibility — WCAG SC 4.1.3 (Status Messages):
 *   - `role="alert"` + `aria-live="assertive"` — the loss of
 *     connectivity is unsafe-to-miss (the user's next form submit
 *     would silently fail), so we use the assertive politeness
 *     setting unlike the polite GlobalErrorToast.
 *   - Dismiss button carries an i18n aria-label.
 *
 * Dismiss policy: the close (✕) button only closes the banner when
 * `navigator.onLine === true` — i.e. the user dismisses after the
 * connection has actually recovered but the browser hasn't fired its
 * `online` event yet, or the connectivity is back but client.ts hasn't
 * had a chance to confirm. We refuse to dismiss while still offline,
 * because hiding the banner during a real outage would mask the cause
 * of the next failed save.
 */

const connection = useConnectionStore()
const { t } = useI18n()

function dismiss(): void {
  // Refuse to hide a real offline. Only honour the dismiss when the
  // browser's view of connectivity has actually returned — otherwise
  // the user would dismiss the banner and immediately lose data on
  // the next submit with no visible warning.
  if (typeof navigator !== 'undefined' && navigator.onLine) {
    connection.markOnline()
  }
}
</script>

<template>
  <div
    v-if="!connection.online"
    class="sticky top-0 z-40 w-full border-b border-muw-coral-700 bg-muw-coral-700 text-white"
    role="alert"
    aria-live="assertive"
    data-testid="connection-banner"
  >
    <div class="max-w-7xl mx-auto px-4 py-2 flex items-start gap-3">
      <svg
        class="mt-0.5 h-5 w-5 flex-shrink-0"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        aria-hidden="true"
      >
        <path d="M1 1l22 22" />
        <path d="M16.72 11.06a10.94 10.94 0 0 1 5.28 4.94" />
        <path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39" />
        <path d="M10.71 5.05A16 16 0 0 1 22.58 9" />
        <path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88" />
        <path d="M8.53 16.11a6 6 0 0 1 6.95 0" />
        <path d="M12 20h.01" />
      </svg>
      <div class="flex-1 min-w-0">
        <div
          class="font-semibold"
          data-testid="connection-banner-title"
        >
          {{ t('network.offline.title') }}
        </div>
        <div
          class="mt-0.5 break-words text-xs"
          data-testid="connection-banner-body"
        >
          {{ t('network.offline.body') }}
        </div>
      </div>
      <button
        type="button"
        class="ml-1 -mr-1 -mt-1 rounded p-1 text-white/80 hover:text-white focus:outline-none focus:ring-2 focus:ring-white"
        :aria-label="t('network.offline.dismiss')"
        data-testid="connection-banner-close"
        @click="dismiss"
      >
        <svg
          class="h-4 w-4"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          aria-hidden="true"
        >
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </div>
  </div>
</template>
