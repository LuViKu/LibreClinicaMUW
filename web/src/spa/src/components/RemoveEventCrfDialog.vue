<script setup lang="ts">
/**
 * "Remove" on a CRF of the visit page: the soft removal of a CRF entered on
 * the wrong subject or visit (legacy RemoveEventCRF). Before asking for the
 * reason, the dialog says what the removal takes out of the subject's data,
 * as the server counts it: the values entered on the CRF and the open
 * queries on them, which are closed. Nothing is deleted; Restore on the
 * same row brings the CRF and its values back.
 *
 * The reason is required; the server records it in the audit trail. A
 * refusal the server gives before any change (the CRF or visit is signed or
 * locked, the role may not remove) is shown instead of the form.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import { useEventDetailStore } from '@/stores/eventDetail'
import type { EventCrfRemovalImpact, EventCrfRowDto } from '@/types/event'

const REASON_MAX_LENGTH = 1000

const props = defineProps<{
  open: boolean
  crf: EventCrfRowDto
  subjectLabel: string
  eventLabel: string
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'removed'): void
  (e: 'close'): void
}>()

const { t } = useI18n()
const store = useEventDetailStore()

const impact = ref<EventCrfRemovalImpact | null>(null)
const refused = ref<string | null>(null)
const isLoading = ref(false)
const reason = ref('')
const reasonError = ref<string | null>(null)
const formError = ref<string | null>(null)
const isSubmitting = ref(false)

const names = computed(() => ({
  crf: props.crf.crfName,
  version: props.crf.crfVersionName,
  event: props.eventLabel,
  subject: props.subjectLabel,
}))
const canConfirm = computed(
  () => impact.value !== null && !isSubmitting.value && reason.value.trim().length > 0,
)

function message(raw: string | null, fallback: string): string {
  if (raw === 'network') return t('eventDetail.crf.removeDialog.errorNetwork')
  return raw ?? fallback
}

async function loadImpact(): Promise<void> {
  impact.value = null
  refused.value = null
  if (props.crf.eventCrfId == null) return
  isLoading.value = true
  try {
    const result = await store.removalImpact(props.crf.eventCrfId)
    if ('impact' in result) impact.value = result.impact
    else refused.value = message(result.refused, t('eventDetail.crf.removeDialog.errorGeneric'))
  } finally {
    isLoading.value = false
  }
}

watch(
  () => props.open,
  (isOpen) => {
    if (!isOpen) return
    reason.value = ''
    reasonError.value = null
    formError.value = null
    void loadImpact()
  },
  { immediate: true },
)

function close(): void {
  if (isSubmitting.value) return
  emit('update:open', false)
  emit('close')
}

async function onConfirm(): Promise<void> {
  if (isSubmitting.value || props.crf.eventCrfId == null) return
  formError.value = null
  const trimmed = reason.value.trim()
  if (!trimmed) {
    reasonError.value = t('eventDetail.crf.removeDialog.reasonRequired')
    return
  }
  reasonError.value = null
  isSubmitting.value = true
  try {
    if (await store.removeCrf(props.crf.eventCrfId, trimmed)) {
      emit('removed')
      emit('update:open', false)
    } else {
      formError.value = message(store.removeCrfError, t('eventDetail.crf.removeDialog.errorGeneric'))
    }
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <Modal
    :open="open"
    labelled-by="remove-event-crf-title"
    panel-class="max-w-lg"
    @update:open="(v) => emit('update:open', v)"
    @close="emit('close')"
  >
    <template #header>
      <h2 id="remove-event-crf-title" class="text-base font-semibold">
        {{ t('eventDetail.crf.removeDialog.title') }}
      </h2>
      <p class="text-xs text-slate-500 mt-0.5" data-testid="remove-event-crf-subtitle">
        {{ t('eventDetail.crf.removeDialog.subtitle', names) }}
      </p>
    </template>

    <p v-if="isLoading" class="text-sm text-slate-500 italic" data-testid="remove-event-crf-loading">
      {{ t('eventDetail.crf.removeDialog.loading') }}
    </p>
    <p
      v-else-if="refused"
      class="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900"
      role="alert"
      data-testid="remove-event-crf-refused"
    >
      {{ refused }}
    </p>
    <form v-else-if="impact" class="space-y-4" novalidate @submit.prevent="onConfirm">
      <div class="text-sm text-slate-800 space-y-2" data-testid="remove-event-crf-impact">
        <p>{{ t('eventDetail.crf.removeDialog.intro', names) }}</p>
        <ul v-if="impact.values > 0" class="list-disc pl-5">
          <li data-testid="remove-event-crf-values">
            {{ t('eventDetail.crf.removeDialog.values', { n: impact.values }) }}
          </li>
          <li v-if="impact.openNoteThreads > 0" data-testid="remove-event-crf-notes">
            {{ t('eventDetail.crf.removeDialog.notes', { n: impact.openNoteThreads }) }}
          </li>
        </ul>
        <p v-else data-testid="remove-event-crf-no-values">{{ t('eventDetail.crf.removeDialog.nothing') }}</p>
        <p class="text-xs text-slate-500">{{ t('eventDetail.crf.removeDialog.restoreHint') }}</p>
      </div>

      <div>
        <FieldLabel for="remove-event-crf-reason" required>
          {{ t('eventDetail.crf.removeDialog.reasonLabel') }}
        </FieldLabel>
        <textarea
          id="remove-event-crf-reason"
          v-model="reason"
          rows="3"
          :maxlength="REASON_MAX_LENGTH"
          class="w-full rounded-md border border-slate-200 px-3 py-2 text-sm muw-focus"
          :placeholder="t('eventDetail.crf.removeDialog.reasonPlaceholder')"
          data-testid="remove-event-crf-reason"
        />
        <ErrorText v-if="reasonError">{{ reasonError }}</ErrorText>
      </div>

      <p v-if="formError" class="text-xs text-rose-700" role="alert" data-testid="remove-event-crf-error">
        {{ formError }}
      </p>
    </form>

    <template #footer>
      <div class="flex justify-end gap-2 w-full">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
          :disabled="isSubmitting"
          data-testid="remove-event-crf-cancel"
          @click="close"
        >
          {{ t('eventDetail.crf.removeDialog.cancel') }}
        </button>
        <button
          type="button"
          class="px-4 py-1.5 text-xs bg-rose-600 text-white rounded-md hover:bg-rose-700 disabled:opacity-50"
          :disabled="!canConfirm"
          data-testid="remove-event-crf-confirm"
          @click="onConfirm"
        >
          {{ isSubmitting ? t('eventDetail.crf.removeDialog.removing') : t('eventDetail.crf.removeDialog.confirm') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
