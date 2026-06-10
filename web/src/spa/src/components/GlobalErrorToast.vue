<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useErrorsStore, type TrackedError } from '@/stores/errors'

/**
 * Phase E hardening — A5 (SPA global error boundary).
 *
 * Singleton toast mounted once in {@link App.vue}. Surfaces the most
 * recent entry from {@link useErrorsStore} as a fixed bottom-right
 * panel until the user dismisses it or the 8s auto-dismiss elapses.
 *
 * The audit-trail decision (2026-06-10) dictates that operator
 * "this didn't save" reports must be tied to a backend log line and
 * audit row via a trace id. The `reqId` pill renders that id verbatim
 * (mono font, copy-friendly) so the operator can quote it to the
 * sysadmin. The pill is hidden when no `reqId` is present so dev-time
 * render errors (which never had a request) don't display "Fehler-ID: ".
 *
 * Accessibility — WCAG SC 4.1.3 (Status Messages):
 *   - `role="status"` + `aria-live="polite"` so the toast announces
 *     without stealing focus from the user's current task.
 *   - The close button has an i18n aria-label.
 */

const errors = useErrorsStore()
const { t } = useI18n()

const AUTO_DISMISS_MS = 8000

const current = computed<TrackedError | null>(() => errors.latest)

const emit = defineEmits<{
  dismiss: [id: number]
}>()

/**
 * Auto-dismiss timer — re-armed whenever the latest entry id changes.
 * Cleared on unmount so a stale entry can't trigger a dismiss after
 * the host view has gone away (e.g. logout → /login navigation).
 */
let timer: ReturnType<typeof setTimeout> | null = null

function clearTimer() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

watch(
  () => current.value?.id ?? null,
  (id) => {
    clearTimer()
    if (id == null) return
    timer = setTimeout(() => close(id), AUTO_DISMISS_MS)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  clearTimer()
})

function close(id: number) {
  errors.dismiss(id)
  emit('dismiss', id)
}
</script>

<template>
  <div
    v-if="current"
    class="fixed bottom-4 right-4 z-50 max-w-sm rounded-md border border-muw-coral-700 bg-white text-slate-900 shadow-lg ring-1 ring-muw-coral-700/40"
    role="status"
    aria-live="polite"
    data-testid="global-error-toast"
  >
    <div class="flex items-start gap-3 p-3">
      <svg
        class="mt-0.5 h-5 w-5 flex-shrink-0 text-muw-coral-700"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        aria-hidden="true"
      >
        <circle cx="12" cy="12" r="10" />
        <path d="M12 8v4M12 16h.01" />
      </svg>
      <div class="flex-1 min-w-0">
        <div class="font-semibold text-muw-coral-700">
          {{ t('topBar.error.title') }}
        </div>
        <div
          class="mt-1 break-words text-xs text-slate-700"
          data-testid="global-error-toast-message"
        >
          {{ current.message }}
        </div>
        <div
          v-if="current.reqId"
          class="mt-2 inline-block rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] text-slate-700"
          data-testid="global-error-toast-reqid"
        >
          {{ t('topBar.error.reqIdLabel') }}: {{ current.reqId }}
        </div>
      </div>
      <button
        type="button"
        class="ml-1 -mr-1 -mt-1 rounded p-1 text-slate-400 hover:text-slate-700 focus:outline-none focus:ring-2 focus:ring-muw-coral-700"
        :aria-label="t('topBar.error.dismiss')"
        data-testid="global-error-toast-close"
        @click="close(current.id)"
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
