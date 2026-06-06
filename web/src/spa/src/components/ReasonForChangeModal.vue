<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

/**
 * Phase E.6 admin-rfc — Reason-For-Change capture modal.
 *
 * Shown when the operator edits an item on a CRF whose backing
 * `event_crf` is past `date_completed`. The component lets the
 * operator type one short reason per dirty item OID before Save can
 * fire; the parent view binds the resulting `pendingReasons` into
 * the store via {@code stageReason}.
 *
 * Wiring contract:
 *  - `v-model:open`  — open/close. The parent flips it open whenever
 *                       `store.missingReasonItemOids` is non-empty;
 *                       the modal emits `update:open=false` after the
 *                       operator confirms or cancels.
 *  - `prompts`        — `{ oid, label, currentValue?, originalValue? }[]`,
 *                       one row per item that still needs a reason.
 *  - `initialReasons` — pre-filled text per OID (echoes any partial
 *                       work in the store so the operator doesn't
 *                       retype on a re-arm).
 *  - `@confirm`       — fires `(reasons: Record<string,string>)`
 *                       when the operator clicks Confirm with every
 *                       prompt filled. Empty/blank prompts block.
 *  - `@cancel`        — fires when Cancel/close pressed. The parent
 *                       decides whether to drop pending reasons.
 *
 * Accessibility: built on the existing `Modal.vue` primitive so it
 * inherits the focus management + escape-key handling. Each textarea
 * is labelled by the item OID + label.
 */
interface Prompt {
  oid: string
  /** Operator-facing label (e.g. "Height (cm)"). */
  label: string
  /** Current typed value (the operator's edit, not the saved value). */
  currentValue?: string
  /** Saved value before the edit. Shown next to the input as a tell. */
  originalValue?: string
}

interface Props {
  open: boolean
  prompts: Prompt[]
  initialReasons?: Record<string, string>
}

const props = withDefaults(defineProps<Props>(), {
  initialReasons: () => ({}),
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [reasons: Record<string, string>]
  cancel: []
}>()

const { t } = useI18n()

/** Local mutable copy of the operator's typed reasons. */
const draft = ref<Record<string, string>>({ ...props.initialReasons })

watch(
  () => props.prompts,
  (next) => {
    // Reset draft entries to the initialReasons whenever the prompt
    // list changes — covers both the open-fresh case + the re-arm
    // case where the store dropped offending oids.
    const nextDraft: Record<string, string> = {}
    for (const p of next) {
      nextDraft[p.oid] = props.initialReasons[p.oid] ?? draft.value[p.oid] ?? ''
    }
    draft.value = nextDraft
  },
  { immediate: true },
)

const allFilled = computed(() =>
  props.prompts.every((p) => (draft.value[p.oid] ?? '').trim().length > 0),
)

function onInput(oid: string, value: string): void {
  draft.value = { ...draft.value, [oid]: value }
}

function onConfirm(): void {
  if (!allFilled.value) return
  // Trim before emitting so consumers don't get whitespace-only entries.
  const trimmed: Record<string, string> = {}
  for (const p of props.prompts) trimmed[p.oid] = (draft.value[p.oid] ?? '').trim()
  emit('confirm', trimmed)
  emit('update:open', false)
}

function onCancel(): void {
  emit('cancel')
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="open"
    labelled-by="rfc-modal-heading"
    panel-class="max-w-lg"
    @close="onCancel"
    @update:open="(v: boolean) => emit('update:open', v)"
  >
    <template #header>
      <h2 id="rfc-modal-heading" class="text-base font-semibold text-slate-800">
        {{ t('crfEntry.rfc.modalTitle') }}
      </h2>
      <p class="text-[11px] text-slate-500 mt-0.5">
        {{ t('crfEntry.rfc.headerTell') }}
      </p>
    </template>

    <p class="text-xs text-slate-600 mb-4">
      {{ t('crfEntry.rfc.prompt', { count: prompts.length }) }}
    </p>

    <div class="space-y-4">
      <div
        v-for="p in prompts"
        :key="p.oid"
        class="border-l-2 border-amber-300 pl-3"
      >
        <FieldLabel :for="`rfc-${p.oid}`" :required="true">
          {{ p.label || p.oid }}
        </FieldLabel>
        <div
          v-if="p.originalValue != null || p.currentValue != null"
          class="text-[11px] text-slate-500 -mt-1 mb-1"
        >
          <span v-if="p.originalValue != null">
            <span class="font-medium">{{ t('crfEntry.rfc.fromTo.from') }}:</span>
            <span class="font-mono">{{ p.originalValue || '∅' }}</span>
          </span>
          <span v-if="p.currentValue != null" class="ml-3">
            <span class="font-medium">{{ t('crfEntry.rfc.fromTo.to') }}:</span>
            <span class="font-mono">{{ p.currentValue || '∅' }}</span>
          </span>
        </div>
        <textarea
          :id="`rfc-${p.oid}`"
          :value="draft[p.oid] ?? ''"
          rows="2"
          :placeholder="t('crfEntry.rfc.placeholder')"
          class="w-full px-3 py-2 border border-slate-300 rounded-md text-sm focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
          @input="onInput(p.oid, ($event.target as HTMLTextAreaElement).value)"
        />
        <ErrorText
          v-if="(draft[p.oid] ?? '').trim().length === 0"
          class="opacity-60"
        >
          {{ t('crfEntry.rfc.missingForItem') }}
        </ErrorText>
      </div>
    </div>

    <template #footer>
      <button
        type="button"
        class="text-xs text-slate-600 hover:text-slate-800"
        @click="onCancel"
      >
        {{ t('crfEntry.rfc.cancel') }}
      </button>
      <button
        type="button"
        class="px-3 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:bg-slate-300 disabled:cursor-not-allowed font-medium"
        :disabled="!allFilled"
        @click="onConfirm"
      >
        {{ t('crfEntry.rfc.confirm') }}
      </button>
    </template>
  </Modal>
</template>
