<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useNotificationsStore } from '@/stores/notifications'

/**
 * UX sweep (#11/#15, 2026-08-12) — singleton success/info toast stack,
 * mounted once in {@link App.vue} at top-right so it never collides with
 * the bottom-right {@link GlobalErrorToast}. Each entry auto-dismisses
 * (store-driven) and can be closed early.
 *
 * Accessibility — WCAG SC 4.1.3: the region is {@code role="status"} +
 * {@code aria-live="polite"} so confirmations are announced without
 * stealing focus.
 */
const notifications = useNotificationsStore()
const { t } = useI18n()
</script>

<template>
  <div
    class="fixed top-4 right-4 z-50 flex flex-col gap-2 max-w-sm"
    role="status"
    aria-live="polite"
    data-testid="global-toast"
  >
    <div
      v-for="toast in notifications.toasts"
      :key="toast.id"
      class="flex items-start gap-2.5 rounded-md border bg-white px-3 py-2.5 shadow-lg"
      :class="toast.kind === 'success'
        ? 'border-emerald-300 ring-1 ring-emerald-500/30'
        : 'border-muw-blue/40 ring-1 ring-muw-blue/20'"
      :data-testid="`global-toast-${toast.kind}`"
    >
      <svg
        class="mt-0.5 h-4.5 w-4.5 flex-shrink-0"
        :class="toast.kind === 'success' ? 'text-emerald-600' : 'text-muw-blue'"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        aria-hidden="true"
      >
        <template v-if="toast.kind === 'success'">
          <circle cx="12" cy="12" r="10" />
          <path d="m9 12 2 2 4-4" />
        </template>
        <template v-else>
          <circle cx="12" cy="12" r="10" />
          <path d="M12 8h.01M11 12h1v4h1" />
        </template>
      </svg>
      <div class="flex-1 min-w-0 text-xs text-slate-800 break-words" data-testid="global-toast-message">
        {{ toast.message }}
      </div>
      <button
        type="button"
        class="ml-1 -mr-1 -mt-1 rounded p-1 text-slate-400 hover:text-slate-700 focus:outline-none focus:ring-2 focus:ring-muw-blue"
        :aria-label="t('common.dismiss')"
        :data-testid="`global-toast-close-${toast.id}`"
        @click="notifications.dismiss(toast.id)"
      >
        <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </div>
  </div>
</template>
