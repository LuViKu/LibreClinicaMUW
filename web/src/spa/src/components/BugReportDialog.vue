<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useBugReportsStore } from '@/stores/bugReports'
import { useClientLogsStore } from '@/stores/clientLogs'

/**
 * Phase E — in-app bug-report dialog.
 *
 * Three fields: title (required, ≤ 200), description (required, ≤
 * 5000), reproduction steps (optional, ≤ 5000). Submit is disabled
 * while either required field is empty + while the request is in
 * flight. On success the dialog surfaces the ticket id inline (a
 * tiny banner — distinct from the global toast lane to keep the
 * dialog self-contained for institutional triage UX research), then
 * the parent receives a {@code submitted} event with the ticket id so
 * it can fire its own toast if it prefers.
 *
 * Errors:
 *   - 503 recipient-not-configured → dedicated copy directing the
 *     operator to the sysadmin (no point retrying).
 *   - network / unknown → generic retry copy; the dialog stays open
 *     so the operator can re-submit without losing the typed body.
 */
interface Props {
  open: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** Fired after the backend confirms delivery. Payload is the ticket id. */
  submitted: [ticketId: string]
  'update:open': [value: boolean]
}>()

const { t } = useI18n()

const store = useBugReportsStore()
const clientLogs = useClientLogsStore()

interface Form {
  title: string
  description: string
  reproductionSteps: string
  attachPageUrl: boolean
  attachConsoleEntries: boolean
}

function blank(): Form {
  return {
    title: '',
    description: '',
    reproductionSteps: '',
    attachPageUrl: true,
    attachConsoleEntries: true,
  }
}

const form = ref<Form>(blank())
/** Banner shown after a successful send — cleared next time the dialog opens. */
const successTicketId = ref<string | null>(null)
/** Controls the disclosure for the "preview attached console entries" block. */
const showConsolePreview = ref(false)

/**
 * Snapshot of the current page URL the dialog renders inside the
 * "attach page URL" label. Computed (not a one-shot read in setup) so a
 * route change between dialog opens reflects in the label.
 */
const currentPageUrl = computed(() => {
  if (typeof window === 'undefined' || !window.location) return ''
  return window.location.pathname + (window.location.search ?? '')
})

/**
 * The slice of console entries the dialog would attach on submit —
 * capped at 50 per the brief. Read on render so the count + the
 * preview block stay in sync with the live ring buffer.
 */
const attachableConsoleEntries = computed(() => clientLogs.recent(50))

const consolePreviewLines = computed(() => {
  if (attachableConsoleEntries.value.length === 0) return []
  return attachableConsoleEntries.value.map((e) => {
    const line = `[${e.timestamp}]  ${e.level}  ${e.message}`
    // Visual cap only — wire payload still carries the full string.
    return line.length > 200 ? line.slice(0, 200) + ' …' : line
  })
})

// Re-seed form + clear the previous ticket banner every time the
// dialog opens. Don't touch state while closed so a programmatic
// {open: false} from the parent doesn't wipe the form mid-flight.
watch(
  () => props.open,
  (isOpen) => {
    if (!isOpen) return
    form.value = blank()
    successTicketId.value = null
    showConsolePreview.value = false
    store.reset()
  },
)

const canSubmit = computed(() => {
  if (form.value.title.trim() === '') return false
  if (form.value.description.trim() === '') return false
  return true
})

const errorCopy = computed<string | null>(() => {
  if (store.errorKind === null) return null
  if (store.errorKind === 'recipient-not-configured') {
    return t('bugReport.error.recipientNotConfigured')
  }
  // Network + unknown both fall to the generic copy. The verbatim
  // server message lives in errorMessage; surfacing it here would
  // leak Java exception text to the operator, so we keep the
  // generic line. The reqId on the underlying ApiError is still in
  // the network panel for sysadmin trace-back.
  return t('bugReport.error.network')
})

async function submit() {
  if (!canSubmit.value || store.isSubmitting) return
  const ticketId = await store.submit({
    title: form.value.title,
    description: form.value.description,
    reproductionSteps: form.value.reproductionSteps,
    attachPageUrl: form.value.attachPageUrl,
    consoleEntries: form.value.attachConsoleEntries
      ? attachableConsoleEntries.value
      : undefined,
  })
  if (ticketId !== null) {
    successTicketId.value = ticketId
    emit('submitted', ticketId)
    // Auto-close after a short delay so the user sees the
    // confirmation banner. Keep the dialog open if the test
    // harness has no timers — emit({submitted}) is the
    // load-bearing signal the parent listens for.
    if (typeof window !== 'undefined') {
      window.setTimeout(() => {
        emit('update:open', false)
      }, 1500)
    } else {
      emit('update:open', false)
    }
  }
}

function cancel() {
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="bug-report-title"
    panel-class="max-w-xl"
    @update:open="(v) => emit('update:open', v)"
    @close="cancel"
  >
    <template #header>
      <h2 id="bug-report-title" class="text-lg font-semibold tracking-tight">
        {{ t('bugReport.dialog.title') }}
      </h2>
      <p class="text-xs text-slate-500 mt-1">
        {{ t('bugReport.dialog.description') }}
      </p>
    </template>

    <div class="space-y-4">
      <div
        v-if="successTicketId !== null"
        class="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-800"
        data-testid="bug-report-success"
      >
        {{ t('bugReport.success', { ticketId: successTicketId }) }}
      </div>

      <div>
        <FieldLabel for="bug-report-title-input" required>
          {{ t('bugReport.field.title.label') }}
        </FieldLabel>
        <TextInput
          id="bug-report-title-input"
          v-model="form.title"
          :placeholder="t('bugReport.field.title.placeholder')"
          autocomplete="off"
          spellcheck="true"
          data-testid="bug-report-title-input"
        />
      </div>

      <div>
        <FieldLabel for="bug-report-description" required>
          {{ t('bugReport.field.description.label') }}
        </FieldLabel>
        <textarea
          id="bug-report-description"
          v-model="form.description"
          rows="5"
          class="w-full rounded-md border border-slate-200 px-3 py-2 text-sm muw-focus"
          :placeholder="t('bugReport.field.description.placeholder')"
          data-testid="bug-report-description"
        />
      </div>

      <div>
        <FieldLabel for="bug-report-steps">
          {{ t('bugReport.field.reproductionSteps.label') }}
        </FieldLabel>
        <textarea
          id="bug-report-steps"
          v-model="form.reproductionSteps"
          rows="4"
          class="w-full rounded-md border border-slate-200 px-3 py-2 text-sm muw-focus"
          :placeholder="t('bugReport.field.reproductionSteps.placeholder')"
          data-testid="bug-report-steps"
        />
      </div>

      <div
        class="rounded-md border border-slate-200 bg-slate-50 px-3 py-2 space-y-2"
        data-testid="bug-report-attach-panel"
      >
        <div class="text-xs font-medium text-slate-700">
          {{ t('bugReport.attach.heading') }}
        </div>
        <label class="flex items-start gap-2 text-xs text-slate-700">
          <input
            v-model="form.attachPageUrl"
            type="checkbox"
            class="mt-0.5"
            data-testid="bug-report-attach-page-url"
          />
          <span data-testid="bug-report-attach-page-url-label">
            {{ t('bugReport.attach.pageUrl', { url: currentPageUrl }) }}
          </span>
        </label>
        <label class="flex items-start gap-2 text-xs text-slate-700">
          <input
            v-model="form.attachConsoleEntries"
            type="checkbox"
            class="mt-0.5"
            data-testid="bug-report-attach-console"
          />
          <span>
            {{ t('bugReport.attach.console', { n: attachableConsoleEntries.length }) }}
          </span>
        </label>
        <div v-if="form.attachConsoleEntries">
          <button
            type="button"
            class="text-xs text-muw-blue hover:underline"
            data-testid="bug-report-attach-console-preview-toggle"
            @click="showConsolePreview = !showConsolePreview"
          >
            {{ t('bugReport.attach.preview.toggle') }}
          </button>
          <pre
            v-if="showConsolePreview"
            class="mt-1 max-h-40 overflow-auto rounded border border-slate-200 bg-white p-2 text-[10px] font-mono whitespace-pre text-slate-700"
            data-testid="bug-report-attach-console-preview"
          >{{ consolePreviewLines.length === 0
              ? t('bugReport.attach.preview.empty')
              : consolePreviewLines.join('\n') }}</pre>
        </div>
      </div>

      <ErrorText v-if="errorCopy" data-testid="bug-report-error">{{ errorCopy }}</ErrorText>
    </div>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          data-testid="bug-report-cancel"
          @click="cancel"
        >
          {{ t('bugReport.cancel') }}
        </button>
        <button
          type="button"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="!canSubmit || store.isSubmitting"
          data-testid="bug-report-submit"
          @click="submit"
        >
          {{ store.isSubmitting ? t('common.saving') : t('bugReport.submit') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
