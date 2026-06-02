<script setup lang="ts">
/**
 * Phase E.5 RX.6b — inline rule_action edit dialog.
 *
 * Edits a {@code rule_action} attached to a {@code rule_set_rule}.
 * Surface matches the backend's RX.6 contract: {@code message},
 * {@code expressionEvaluatesTo}, {@code to} (Email only), plus the
 * five {@code phaseGates} booleans.
 *
 * Action-type morph (Insert → Randomize etc.) is intentionally out of
 * scope per the backend record's javadoc — the operator deletes and
 * recreates via the wizard. Insert / Event / Notification / Randomize
 * actions are not editable here; the backend returns 404 for those
 * types and the dialog refuses to open for them.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useRulesStore } from '@/stores/rules'
import type { RuleAction, RuleSet } from '@/types/rule'

const props = defineProps<{
  open: boolean
  ruleSet: RuleSet | null
  action: RuleAction | null
}>()

const emit = defineEmits<{
  close: []
  'update:open': [value: boolean]
  saved: []
}>()

const { t } = useI18n()
const rules = useRulesStore()

const message = ref('')
const expressionEvaluatesTo = ref(true)
const to = ref('')
const gateAdmin = ref(false)
const gateInitial = ref(false)
const gateDouble = ref(false)
const gateImport = ref(false)
const gateBatch = ref(false)

const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)

watch(
  () => [props.open, props.action] as const,
  ([open, a]) => {
    if (open && a) {
      message.value = a.message ?? ''
      expressionEvaluatesTo.value = a.expressionEvaluatesTo
      to.value = (a.typeSpecific && 'to' in a.typeSpecific && typeof a.typeSpecific.to === 'string')
        ? a.typeSpecific.to
        : ''
      gateAdmin.value = a.phaseGates.administrativeDataEntry
      gateInitial.value = a.phaseGates.initialDataEntry
      gateDouble.value = a.phaseGates.doubleDataEntry
      gateImport.value = a.phaseGates.importDataEntry
      gateBatch.value = a.phaseGates.batch
      fieldErrors.value = {}
      formError.value = null
    }
  },
)

const showsToField = computed(
  () => props.action?.actionType === 'EMAIL',
)

const showsMessageField = computed(
  () =>
    props.action?.actionType === 'EMAIL' ||
    props.action?.actionType === 'FILE_DISCREPANCY_NOTE',
)

/** RX.6 backend supports edit on these four; others return 404. */
const isEditable = computed(() => {
  const t = props.action?.actionType
  return (
    t === 'FILE_DISCREPANCY_NOTE' ||
    t === 'EMAIL' ||
    t === 'SHOW' ||
    t === 'HIDE'
  )
})

const hasChanges = computed(() => {
  const a = props.action
  if (!a) return false
  return (
    message.value !== (a.message ?? '') ||
    expressionEvaluatesTo.value !== a.expressionEvaluatesTo ||
    (showsToField.value && to.value !== (
      a.typeSpecific && 'to' in a.typeSpecific && typeof a.typeSpecific.to === 'string'
        ? a.typeSpecific.to
        : ''
    )) ||
    gateAdmin.value !== a.phaseGates.administrativeDataEntry ||
    gateInitial.value !== a.phaseGates.initialDataEntry ||
    gateDouble.value !== a.phaseGates.doubleDataEntry ||
    gateImport.value !== a.phaseGates.importDataEntry ||
    gateBatch.value !== a.phaseGates.batch
  )
})

const canSubmit = computed(() => {
  if (!props.action || !props.ruleSet) return false
  return isEditable.value && hasChanges.value && !isSubmitting.value
})

async function onSubmit() {
  if (!props.action || !props.ruleSet || !canSubmit.value) return
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    const r = await rules.updateAction(props.ruleSet.id, props.action.id, {
      message: showsMessageField.value ? message.value : undefined,
      expressionEvaluatesTo: expressionEvaluatesTo.value,
      to: showsToField.value ? to.value : undefined,
      phaseGates: {
        administrativeDataEntry: gateAdmin.value,
        initialDataEntry: gateInitial.value,
        doubleDataEntry: gateDouble.value,
        importDataEntry: gateImport.value,
        batch: gateBatch.value,
      },
    })
    if (r.ok) {
      emit('saved')
      emit('update:open', false)
    } else {
      fieldErrors.value = r.fieldErrors
      formError.value = r.message ?? null
    }
  } finally {
    isSubmitting.value = false
  }
}

function onCancel() {
  emit('close')
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="action-edit-title"
    panel-class="max-w-xl"
    @update:open="(v) => emit('update:open', v)"
    @close="onCancel"
  >
    <template #header>
      <h2 id="action-edit-title" class="text-lg font-semibold tracking-tight">
        {{ t('rules.actionEdit.title') }}
      </h2>
      <p v-if="props.action" class="mt-1 text-[10px] text-slate-400">
        {{ t(`rules.actionType.${props.action.actionType}`) }}
      </p>
    </template>

    <div v-if="props.action && !isEditable" class="text-xs text-slate-700">
      <p class="text-rose-700">
        {{ t('rules.actionEdit.notEditable', { type: t(`rules.actionType.${props.action.actionType}`) }) }}
      </p>
    </div>

    <div v-else-if="props.action" class="space-y-4">
      <div>
        <FieldLabel for="action-edit-fires-on">{{ t('rules.actionEdit.firesOn') }}</FieldLabel>
        <select
          id="action-edit-fires-on"
          v-model="expressionEvaluatesTo"
          class="w-full px-3 py-2 text-xs border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
        >
          <option :value="true">{{ t('rules.detail.firesWhenTrue') }}</option>
          <option :value="false">{{ t('rules.detail.firesWhenFalse') }}</option>
        </select>
      </div>

      <div v-if="showsMessageField">
        <FieldLabel for="action-edit-message">{{ t('rules.actionEdit.message') }}</FieldLabel>
        <textarea
          id="action-edit-message"
          v-model="message"
          rows="2"
          class="w-full px-3 py-2 text-xs border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
        />
        <ErrorText v-if="fieldErrors.message">{{ fieldErrors.message }}</ErrorText>
      </div>

      <div v-if="showsToField">
        <FieldLabel for="action-edit-to">{{ t('rules.actionEdit.to') }}</FieldLabel>
        <TextInput
          id="action-edit-to"
          v-model="to"
          :error="fieldErrors.to != null"
        />
        <ErrorText v-if="fieldErrors.to">{{ fieldErrors.to }}</ErrorText>
      </div>

      <div class="border-t border-slate-100 pt-3">
        <p class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold mb-2">
          {{ t('rules.actionEdit.phaseGatesLabel') }}
        </p>
        <div class="grid grid-cols-2 gap-2 text-xs">
          <label class="flex items-center gap-2">
            <input v-model="gateAdmin" type="checkbox" class="muw-focus" />
            <span>{{ t('rules.phaseGate.administrativeDataEntry') }}</span>
          </label>
          <label class="flex items-center gap-2">
            <input v-model="gateInitial" type="checkbox" class="muw-focus" />
            <span>{{ t('rules.phaseGate.initialDataEntry') }}</span>
          </label>
          <label class="flex items-center gap-2">
            <input v-model="gateDouble" type="checkbox" class="muw-focus" />
            <span>{{ t('rules.phaseGate.doubleDataEntry') }}</span>
          </label>
          <label class="flex items-center gap-2">
            <input v-model="gateImport" type="checkbox" class="muw-focus" />
            <span>{{ t('rules.phaseGate.importDataEntry') }}</span>
          </label>
          <label class="flex items-center gap-2 col-span-2">
            <input v-model="gateBatch" type="checkbox" class="muw-focus" />
            <span>{{ t('rules.phaseGate.batch') }}</span>
          </label>
        </div>
      </div>

      <p v-if="formError" class="text-xs text-rose-700">{{ formError }}</p>
    </div>

    <template #footer>
      <div class="flex items-center justify-end gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="onCancel"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          v-if="isEditable"
          type="button"
          class="px-3 py-1.5 text-xs border border-transparent rounded-md bg-muw-blue text-white hover:bg-muw-blue-700 disabled:bg-slate-300 disabled:cursor-not-allowed"
          :disabled="!canSubmit"
          @click="onSubmit"
        >
          {{ isSubmitting ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
