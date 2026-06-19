<script setup lang="ts">
/**
 * Wave 1A (app-feedback, 2026-06-19) — Cancel-event dialog.
 *
 * Replaces the native browser `confirm()` previously used in
 * {@link SubjectDetailView.vue} with a proper modal that requires the
 * operator to pick an institutional cancel reason from a backend-seeded
 * catalog and (when the picked row is the "Other" entry) supply a
 * free-text rationale.
 *
 * On confirm:
 *  - {@code DELETE /pages/api/v1/events/{eventId}} carries a JSON body
 *    `{ reasonCode, reasonText }`.
 *  - On 204 the dialog emits `cancelled` and closes; the parent
 *    re-fetches the subject to refresh the events table.
 *  - On 4xx the dialog stays open with an inline error so the operator
 *    can adjust; failures also push to the global errors store so the
 *    GlobalErrorToast surfaces a reqId-linkable message.
 *
 * The reason catalog (`GET /pages/api/v1/event-cancel-reasons`) is
 * fetched on the first open per dialog instance and cached for the
 * lifetime of that instance. The list is small (6 seeded rows at
 * MUW); re-opening the dialog after a brief pause does not refetch.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import SelectInput from '@/components/SelectInput.vue'
import ErrorText from '@/components/ErrorText.vue'

import { apiGet, ApiError, ApiNetworkError } from '@/api/client'
import { useEventsStore } from '@/stores/events'
import { useErrorsStore } from '@/stores/errors'

interface CancelReason {
  code: string
  labelDe: string
  labelEn: string
  sortOrder: number
  isOther: boolean
}

interface Props {
  /** v-model:open — controls dialog visibility. */
  open: boolean
  /** study_event_id of the visit being cancelled. */
  eventId: string
  /** Human-readable label rendered in the dialog header (e.g. "Visite 3 — Follow-up"). */
  eventLabel: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** Fired on successful 204 from the backend. */
  cancelled: []
  /** Fired when the operator dismisses without confirming. */
  close: []
  'update:open': [value: boolean]
}>()

const { t, locale } = useI18n()
const events = useEventsStore()
const errors = useErrorsStore()

const reasons = ref<CancelReason[]>([])
const isLoadingReasons = ref(false)
const loadError = ref<string | null>(null)
const reasonsLoaded = ref(false)

const reasonCode = ref('')
const reasonText = ref('')
const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)

const pickedReason = computed<CancelReason | null>(() => {
  if (!reasonCode.value) return null
  return reasons.value.find((r) => r.code === reasonCode.value) ?? null
})
const isOtherSelected = computed(() => pickedReason.value?.isOther === true)

function labelFor(r: CancelReason): string {
  return locale.value.startsWith('de') ? r.labelDe : r.labelEn
}

async function loadReasons(): Promise<void> {
  if (reasonsLoaded.value && reasons.value.length > 0) return
  isLoadingReasons.value = true
  loadError.value = null
  try {
    const rows = await apiGet<CancelReason[]>('/pages/api/v1/event-cancel-reasons')
    reasons.value = rows
    reasonsLoaded.value = true
  } catch (e) {
    reasons.value = []
    reasonsLoaded.value = false
    if (e instanceof ApiNetworkError) {
      loadError.value = t('events.cancel.errorLoadReasons')
    } else if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      loadError.value = body?.message ?? t('events.cancel.errorLoadReasons')
    } else {
      loadError.value = e instanceof Error ? e.message : t('events.cancel.errorLoadReasons')
    }
    // Surface in the global toast lane too so the operator sees the
    // failure even if they instinctively close the dialog without
    // reading the inline error.
    errors.push(e)
  } finally {
    isLoadingReasons.value = false
  }
}

function resetForm() {
  reasonCode.value = ''
  reasonText.value = ''
  fieldErrors.value = {}
  formError.value = null
}

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      resetForm()
      void loadReasons()
    }
  },
)

function close() {
  emit('close')
  emit('update:open', false)
}

async function onConfirm() {
  fieldErrors.value = {}
  formError.value = null

  if (!reasonCode.value) {
    fieldErrors.value.reasonCode = t('events.cancel.reasonRequired')
    return
  }
  if (isOtherSelected.value && !reasonText.value.trim()) {
    fieldErrors.value.reasonText = t('events.cancel.otherTextRequired')
    return
  }

  isSubmitting.value = true
  try {
    const ok = await events.cancelEvent(props.eventId, {
      reasonCode: reasonCode.value,
      reasonText: isOtherSelected.value ? reasonText.value.trim() : undefined,
    })
    if (ok) {
      emit('cancelled')
      emit('update:open', false)
    } else {
      formError.value = events.error ?? t('events.cancel.errorSubmit')
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <Modal
    :open="open"
    labelled-by="cancel-event-dialog-title"
    panel-class="max-w-lg"
    @update:open="(v) => emit('update:open', v)"
    @close="emit('close')"
  >
    <template #header>
      <h2 id="cancel-event-dialog-title" class="text-base font-semibold">
        {{ t('events.cancel.title') }}
      </h2>
      <p class="text-xs text-slate-500 mt-0.5" data-testid="cancel-event-dialog-subtitle">
        {{ t('events.cancel.subtitle', { label: eventLabel }) }}
      </p>
    </template>

    <form class="space-y-4" novalidate @submit.prevent="onConfirm">
      <div>
        <FieldLabel for="cancel-event-reason" required>
          {{ t('events.cancel.reasonLabel') }}
        </FieldLabel>
        <SelectInput
          id="cancel-event-reason"
          v-model="reasonCode"
          :error="!!fieldErrors.reasonCode"
          :disabled="isLoadingReasons || reasons.length === 0"
          data-testid="cancel-event-reason"
        >
          <option value="" disabled>
            {{ isLoadingReasons ? t('common.loading') : t('events.cancel.reasonPlaceholder') }}
          </option>
          <option v-for="r in reasons" :key="r.code" :value="r.code">
            {{ labelFor(r) }}
          </option>
        </SelectInput>
        <ErrorText v-if="fieldErrors.reasonCode">{{ fieldErrors.reasonCode }}</ErrorText>
        <p
          v-else-if="loadError"
          class="mt-1 text-xs text-rose-700"
          data-testid="cancel-event-load-error"
        >
          {{ loadError }}
        </p>
      </div>

      <div v-if="isOtherSelected">
        <FieldLabel for="cancel-event-other-text" required>
          {{ t('events.cancel.otherTextLabel') }}
        </FieldLabel>
        <textarea
          id="cancel-event-other-text"
          v-model="reasonText"
          rows="3"
          class="w-full rounded-md border border-slate-200 px-3 py-2 text-sm muw-focus"
          :placeholder="t('events.cancel.otherPlaceholder')"
          data-testid="cancel-event-other-text"
        />
        <ErrorText v-if="fieldErrors.reasonText">{{ fieldErrors.reasonText }}</ErrorText>
      </div>

      <p
        v-if="formError"
        class="text-xs text-rose-700"
        role="alert"
        data-testid="cancel-event-form-error"
      >
        {{ formError }}
      </p>
    </form>

    <template #footer>
      <div class="flex justify-end gap-2 w-full">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
          :disabled="isSubmitting"
          data-testid="cancel-event-dismiss"
          @click="close"
        >
          {{ t('events.cancel.cancel') }}
        </button>
        <button
          type="button"
          class="px-4 py-1.5 text-xs bg-rose-600 text-white rounded-md hover:bg-rose-700 disabled:opacity-50"
          :disabled="isSubmitting || isLoadingReasons || !reasonCode"
          data-testid="cancel-event-confirm"
          @click="onConfirm"
        >
          {{ isSubmitting ? t('common.saving') : t('events.cancel.confirm') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
