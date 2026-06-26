<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useErrorsStore, type TrackedError } from '@/stores/errors'

/**
 * Singleton global error toast mounted once in {@link App.vue}. Surfaces
 * the most recent {@link useErrorsStore} entry as a fixed bottom-right
 * panel until dismissed or auto-dismiss elapses. The `reqId` pill renders
 * the audit-trace id verbatim (copy-friendly) so operators can quote it to
 * the sysadmin; hidden when no reqId. Server message + URL sit behind a
 * Details disclosure. A "{N} weitere Fehler" pill lists recent queued
 * errors when ≥2 are present.
 *
 * Accessibility — WCAG SC 4.1.3 (Status Messages): `role="status"` +
 * `aria-live="polite"` announce without stealing focus.
 */

const errors = useErrorsStore()
const { t } = useI18n()

/** Auto-dismiss window — 30s gives clinical operators time to read and copy the reqId. */
const AUTO_DISMISS_MS = 30000

const current = computed<TrackedError | null>(() => errors.latest)

/**
 * Wave 1C — last 5 queued errors EXCLUDING the one currently shown,
 * newest-first. The full ring buffer caps at 20 (see errors.ts) but
 * the dropdown only surfaces a manageable handful; older entries are
 * still in `errors.recent` for inspection via dev tools.
 */
const queuedOthers = computed<TrackedError[]>(() => {
  const cur = current.value
  if (!cur) return []
  const list = errors.recent.filter((e) => e.id !== cur.id)
  // Recent ones are at the tail of `errors.recent`; reverse so the
  // most-recent of the others is first in the dropdown.
  return list.slice().reverse().slice(0, 5)
})

const emit = defineEmits<{
  dismiss: [id: number]
}>()

/**
 * Auto-dismiss timer — re-armed whenever the latest entry id changes.
 * Cleared on unmount so a stale entry can't trigger a dismiss after
 * the host view has gone away (e.g. logout → /login navigation).
 *
 * Wave 1C: paused while the Details disclosure is open so an operator
 * mid-read isn't surprised by the toast vanishing. Re-arms on close.
 */
let timer: ReturnType<typeof setTimeout> | null = null

function clearTimer() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function armTimer(id: number) {
  clearTimer()
  timer = setTimeout(() => close(id), AUTO_DISMISS_MS)
}

/** Disclosure state for the Details expander. */
const detailsOpen = ref(false)

/** Dropdown state for the "{N} weitere Fehler" stack. */
const queueOpen = ref(false)

watch(
  () => current.value?.id ?? null,
  (id) => {
    // Re-arm when a new entry becomes latest. Reset the dropdowns so
    // each fresh error starts in the collapsed default state.
    clearTimer()
    detailsOpen.value = false
    queueOpen.value = false
    if (id == null) return
    armTimer(id)
  },
  { immediate: true },
)

// Pause / resume the auto-dismiss while Details is open. Watching
// `detailsOpen` rather than handling clicks directly keeps the logic
// declarative and avoids missed-event corner cases.
watch(detailsOpen, (open) => {
  const cur = current.value
  if (!cur) return
  if (open) {
    clearTimer()
  } else {
    armTimer(cur.id)
  }
})

onBeforeUnmount(() => {
  clearTimer()
})

function close(id: number) {
  errors.dismiss(id)
  emit('dismiss', id)
}

/**
 * Wave 1C — promote a queued entry to the main toast view. The errors
 * store's `latest` is "newest entry in the ring buffer"; dismissing the
 * current entry leaves the next-most-recent as latest. To jump to an
 * arbitrary queued entry, we dismiss every entry between it and the
 * tail (exclusive) so the chosen entry becomes the tail = latest.
 *
 * Side-effects: the dismissed-in-between entries are lost. That's
 * acceptable per the spec — the operator chose to focus on this one
 * and is presumed to have read the others if relevant.
 */
function promote(entryId: number) {
  const recent = errors.recent
  const idx = recent.findIndex((e) => e.id === entryId)
  if (idx < 0) return
  // Dismiss everything after the chosen index (in the tail) so the
  // chosen entry becomes the new tail (= latest).
  for (let i = recent.length - 1; i > idx; i--) {
    errors.dismiss(recent[i].id)
  }
  queueOpen.value = false
  detailsOpen.value = false
}

function toggleDetails() {
  detailsOpen.value = !detailsOpen.value
}

function toggleQueue() {
  queueOpen.value = !queueOpen.value
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

        <div class="mt-2 flex flex-wrap items-center gap-2">
          <div
            v-if="current.reqId"
            class="inline-block rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] text-slate-700"
            data-testid="global-error-toast-reqid"
          >
            {{ t('topBar.error.reqIdLabel') }}: {{ current.reqId }}
          </div>

          <button
            type="button"
            class="inline-block rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-700 hover:bg-slate-200 focus:outline-none focus:ring-2 focus:ring-muw-coral-700"
            :aria-expanded="detailsOpen"
            data-testid="global-error-toast-details-toggle"
            @click="toggleDetails"
          >
            {{ detailsOpen ? t('topBar.error.detailsHide') : t('topBar.error.detailsToggle') }}
          </button>

          <button
            v-if="queuedOthers.length > 0"
            type="button"
            class="inline-block rounded bg-amber-100 px-1.5 py-0.5 text-[10px] text-amber-900 hover:bg-amber-200 focus:outline-none focus:ring-2 focus:ring-amber-600"
            :aria-expanded="queueOpen"
            data-testid="global-error-toast-queue-toggle"
            @click="toggleQueue"
          >
            {{ t('topBar.error.moreErrors', { n: queuedOthers.length }) }}
          </button>
        </div>

        <div
          v-if="detailsOpen"
          class="mt-2 space-y-1 rounded border border-slate-200 bg-slate-50 p-2 text-[11px] text-slate-700"
          data-testid="global-error-toast-details"
        >
          <div v-if="current.serverMessage" class="break-words">
            <span class="font-semibold">{{ t('topBar.error.serverMessageLabel') }}:</span>
            <span class="ml-1 font-mono" data-testid="global-error-toast-server-message">
              {{ current.serverMessage }}
            </span>
          </div>
          <div v-if="current.url" class="break-all">
            <span class="font-semibold">{{ t('topBar.error.urlLabel') }}:</span>
            <span class="ml-1 font-mono" data-testid="global-error-toast-url">
              {{ current.method }} {{ current.url }}
            </span>
          </div>
        </div>

        <div
          v-if="queueOpen && queuedOthers.length > 0"
          class="mt-2 rounded border border-amber-200 bg-amber-50 p-2 text-[11px] text-amber-900"
          data-testid="global-error-toast-queue-list"
        >
          <div class="mb-1 font-semibold">{{ t('topBar.error.queuedListTitle') }}</div>
          <ul class="space-y-1">
            <li v-for="entry in queuedOthers" :key="entry.id">
              <button
                type="button"
                class="block w-full break-words rounded px-1 py-0.5 text-left hover:bg-amber-100 focus:outline-none focus:ring-2 focus:ring-amber-600"
                :data-testid="`global-error-toast-queue-item-${entry.id}`"
                @click="promote(entry.id)"
              >
                {{ entry.message }}
              </button>
            </li>
          </ul>
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
