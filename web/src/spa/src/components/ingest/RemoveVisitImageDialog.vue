<script setup lang="ts">
/**
 * 2026-09-24 — "Aus Visite entfernen" on the visit page.
 *
 * Two different intents hide behind "remove this image", and only one of
 * them means the image is not study data:
 *
 *   - wrong visit: the capture is real, filed in the wrong place. It goes
 *     back to the inbox, where the resolver can offer the right visit again.
 *   - not a study image: test exposure, wrong eye, duplicate. Unbind and
 *     dismiss as one request, with the reason, so the file never sits in
 *     the inbox unreviewed between two clicks.
 *
 * Collapsing both into one direction is wrong either way: straight to
 * dismissed would start the 30-day deletion clock on a real photograph over
 * a mis-click; straight to the pool merely costs the other case a click,
 * with the row visible where an unreviewed file should be. So the operator
 * says which — the state machine underneath is unchanged.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import { ApiError } from '@/api/client'
import { unbindIngestItem, type IngestItem } from '@/api/ingest'

const props = defineProps<{
  open: boolean
  image: IngestItem
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'removed', outcome: 'pool' | 'dismissed'): void
  (e: 'close'): void
}>()

const { t } = useI18n()

type Intent = 'pool' | 'dismiss'
const intent = ref<Intent>('pool')
const reason = ref('')
const isSubmitting = ref(false)
const formError = ref<string | null>(null)

watch(
  () => props.open,
  (isOpen) => {
    if (isOpen) {
      intent.value = 'pool'
      reason.value = ''
      formError.value = null
    }
  },
)

const subtitle = computed(() => {
  const img = props.image
  const eyeKey = (img.laterality ?? '').toUpperCase()
  const eye = eyeKey === 'OD' || eyeKey === 'OS' || eyeKey === 'OU'
    ? t(`eventDetail.images.eye.${eyeKey}`)
    : t('eventDetail.images.eye.unknown')
  return t('eventDetail.images.removeDialog.subtitle', {
    kind: img.kind.toUpperCase(),
    eye,
    date: img.acquisitionDate ?? '—',
  })
})

function close(): void {
  if (isSubmitting.value) return
  emit('update:open', false)
  emit('close')
}

async function onConfirm(): Promise<void> {
  if (isSubmitting.value) return
  formError.value = null
  isSubmitting.value = true
  try {
    const dismiss = intent.value === 'dismiss'
    await unbindIngestItem(props.image.id, {
      dismiss,
      reason: dismiss && reason.value.trim() ? reason.value.trim() : undefined,
    })
    emit('removed', dismiss ? 'dismissed' : 'pool')
    emit('update:open', false)
  } catch (e) {
    const body = e instanceof ApiError ? (e.body as { reason?: string; message?: string } | null) : null
    formError.value = body?.reason === 'VISIT_SEALED'
      ? t('eventDetail.images.removeDialog.errorSealed')
      : (body?.message ?? t('eventDetail.images.removeDialog.errorGeneric'))
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <Modal
    :open="open"
    labelled-by="remove-visit-image-title"
    panel-class="max-w-lg"
    @update:open="(v) => emit('update:open', v)"
    @close="emit('close')"
  >
    <template #header>
      <h2 id="remove-visit-image-title" class="text-base font-semibold">
        {{ t('eventDetail.images.removeDialog.title') }}
      </h2>
      <p class="text-xs text-slate-500 mt-0.5" data-testid="remove-visit-image-subtitle">{{ subtitle }}</p>
    </template>

    <form class="space-y-4" novalidate @submit.prevent="onConfirm">
      <fieldset class="space-y-3">
        <legend class="text-sm font-medium text-slate-800">
          {{ t('eventDetail.images.removeDialog.intro') }}
        </legend>

        <label class="flex items-start gap-3 rounded-md border border-slate-200 px-3 py-2.5 cursor-pointer hover:bg-slate-50">
          <input
            v-model="intent"
            type="radio"
            name="remove-intent"
            value="pool"
            class="mt-0.5"
            data-testid="remove-intent-pool"
          />
          <span>
            <span class="block text-sm text-slate-800">{{ t('eventDetail.images.removeDialog.optionPool') }}</span>
            <span class="block text-xs text-slate-500">{{ t('eventDetail.images.removeDialog.optionPoolHelp') }}</span>
          </span>
        </label>

        <label class="flex items-start gap-3 rounded-md border border-slate-200 px-3 py-2.5 cursor-pointer hover:bg-slate-50">
          <input
            v-model="intent"
            type="radio"
            name="remove-intent"
            value="dismiss"
            class="mt-0.5"
            data-testid="remove-intent-dismiss"
          />
          <span>
            <span class="block text-sm text-slate-800">{{ t('eventDetail.images.removeDialog.optionDismiss') }}</span>
            <span class="block text-xs text-slate-500">{{ t('eventDetail.images.removeDialog.optionDismissHelp') }}</span>
          </span>
        </label>
      </fieldset>

      <div v-if="intent === 'dismiss'">
        <FieldLabel for="remove-visit-image-reason">
          {{ t('eventDetail.images.removeDialog.reasonLabel') }}
        </FieldLabel>
        <textarea
          id="remove-visit-image-reason"
          v-model="reason"
          rows="2"
          maxlength="500"
          class="w-full rounded-md border border-slate-200 px-3 py-2 text-sm muw-focus"
          :placeholder="t('eventDetail.images.removeDialog.reasonPlaceholder')"
          data-testid="remove-visit-image-reason"
        />
      </div>

      <p
        v-if="formError"
        class="text-xs text-rose-700"
        role="alert"
        data-testid="remove-visit-image-error"
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
          data-testid="remove-visit-image-cancel"
          @click="close"
        >
          {{ t('eventDetail.images.removeDialog.cancel') }}
        </button>
        <button
          type="button"
          :class="[
            'px-4 py-1.5 text-xs text-white rounded-md disabled:opacity-50',
            intent === 'dismiss' ? 'bg-rose-600 hover:bg-rose-700' : 'bg-muw-blue hover:bg-muw-blue-700',
          ]"
          :disabled="isSubmitting"
          data-testid="remove-visit-image-confirm"
          @click="onConfirm"
        >
          {{ intent === 'dismiss'
            ? t('eventDetail.images.removeDialog.confirmDismiss')
            : t('eventDetail.images.removeDialog.confirmPool') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
