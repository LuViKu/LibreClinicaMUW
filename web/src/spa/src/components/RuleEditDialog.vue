<script setup lang="ts">
/**
 * Phase E.5 RX.6b — inline rule edit dialog.
 *
 * Counterpart to {@link RuleAuthoringWizard} (which creates new
 * rules). This dialog edits an existing rule's name / description /
 * expression. The OID is intentionally read-only — renaming an OID
 * would invalidate every {@code rule_set_rule} binding that references
 * it, and the backend record already excludes OID from the update
 * surface for that reason.
 *
 * The dialog opens with the persisted values pre-populated, computes
 * a per-field diff on submit, and sends only the changed fields. The
 * backend treats nullable fields as "leave unchanged".
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useRulesStore, type TestExpressionResult } from '@/stores/rules'
import type { AttachedRule } from '@/types/rule'

const props = defineProps<{
  open: boolean
  /** The rule to edit. Pinia rule_set detail is the source of truth. */
  rule: AttachedRule | null
}>()

const emit = defineEmits<{
  close: []
  'update:open': [value: boolean]
  /** Fired after a successful save so the parent can refresh / close. */
  saved: []
}>()

const { t } = useI18n()
const rules = useRulesStore()

const name = ref('')
const description = ref('')
const expression = ref('')

const fieldErrors = ref<Record<string, string>>({})
const formError = ref<string | null>(null)
const isSubmitting = ref(false)

// Re-seed the inputs every time the dialog opens.
watch(
  () => [props.open, props.rule] as const,
  ([open, rule]) => {
    if (open && rule) {
      name.value = rule.ruleName ?? ''
      description.value = rule.ruleDescription ?? ''
      expression.value = rule.ruleExpression ?? ''
      fieldErrors.value = {}
      formError.value = null
    }
  },
)

// === Test-expression integration ===========================================
// Mirrors the wizard's pattern: empty testValues map, store call returns
// the discriminated-union result type directly.

const testResult = ref<TestExpressionResult | null>(null)
const isTestingExpression = ref(false)

async function onTestExpression() {
  if (isTestingExpression.value) return
  const trimmed = expression.value.trim()
  if (trimmed.length === 0) {
    testResult.value = { ok: false, message: 'Expression must not be blank' }
    return
  }
  isTestingExpression.value = true
  try {
    testResult.value = await rules.testExpression(trimmed, {})
  } finally {
    isTestingExpression.value = false
  }
}

const testResultLabel = computed(() => {
  if (!testResult.value || !testResult.value.ok) return ''
  return testResult.value.result === 'true'
    ? t('rules.test.resultTrue')
    : testResult.value.result === 'false'
      ? t('rules.test.resultFalse')
      : t('rules.test.resultNull')
})

const testResultVariant = computed<'success' | 'warning' | 'neutral'>(() => {
  if (!testResult.value || !testResult.value.ok) return 'neutral'
  return testResult.value.result === 'true' ? 'success'
    : testResult.value.result === 'false' ? 'warning'
      : 'neutral'
})

// === Submit ================================================================

const hasChanges = computed(() => {
  if (!props.rule) return false
  return (
    name.value !== (props.rule.ruleName ?? '') ||
    description.value !== (props.rule.ruleDescription ?? '') ||
    expression.value !== (props.rule.ruleExpression ?? '')
  )
})

const canSubmit = computed(() => {
  if (!props.rule) return false
  return (
    hasChanges.value &&
    name.value.trim().length > 0 &&
    expression.value.trim().length > 0 &&
    !isSubmitting.value
  )
})

async function onSubmit() {
  if (!props.rule || !canSubmit.value) return
  fieldErrors.value = {}
  formError.value = null
  isSubmitting.value = true
  try {
    const r = await rules.updateRule(props.rule.ruleId, {
      // Only ship fields that actually changed — the backend per-field
      // diff still works either way, but the audit log + wire payload
      // stay tighter.
      name: name.value !== (props.rule.ruleName ?? '') ? name.value.trim() : undefined,
      description: description.value !== (props.rule.ruleDescription ?? '') ? description.value : undefined,
      expression: expression.value !== (props.rule.ruleExpression ?? '') ? expression.value : undefined,
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
    labelled-by="rule-edit-title"
    panel-class="max-w-2xl"
    @update:open="(v) => emit('update:open', v)"
    @close="onCancel"
  >
    <template #header>
      <h2 id="rule-edit-title" class="text-lg font-semibold tracking-tight">
        {{ t('rules.edit.title') }}
      </h2>
      <p v-if="props.rule" class="mt-1 text-[10px] font-mono text-slate-400">
        {{ props.rule.ruleOid }}
      </p>
    </template>

    <div v-if="props.rule" class="space-y-4">
      <div>
        <FieldLabel for="rule-edit-name" required>{{ t('rules.create.field.name') }}</FieldLabel>
        <TextInput
          id="rule-edit-name"
          v-model="name"
          :error="fieldErrors.name != null"
        />
        <ErrorText v-if="fieldErrors.name">{{ fieldErrors.name }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="rule-edit-description">{{ t('rules.create.field.description') }}</FieldLabel>
        <textarea
          id="rule-edit-description"
          v-model="description"
          rows="2"
          class="w-full px-3 py-2 text-xs border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
        />
        <ErrorText v-if="fieldErrors.description">{{ fieldErrors.description }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="rule-edit-expression" required>{{ t('rules.create.field.expression') }}</FieldLabel>
        <textarea
          id="rule-edit-expression"
          v-model="expression"
          rows="4"
          class="w-full px-3 py-2 text-xs font-mono border rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
          :class="fieldErrors.expression ? 'border-rose-400 bg-rose-50/40' : 'border-slate-300'"
          :aria-invalid="fieldErrors.expression != null ? true : undefined"
        />
        <div class="mt-1 flex items-center justify-between gap-2">
          <p v-if="!fieldErrors.expression" class="text-[11px] text-slate-500">
            {{ t('rules.create.field.expressionHint') }}
          </p>
          <ErrorText v-else class="flex-1">{{ fieldErrors.expression }}</ErrorText>
          <button
            type="button"
            class="shrink-0 px-2 py-1 text-[11px] border border-slate-200 rounded-md hover:bg-slate-50 disabled:opacity-50"
            :disabled="isTestingExpression || !expression.trim()"
            @click="onTestExpression"
          >
            {{ isTestingExpression ? t('rules.test.running') : t('rules.create.button.testExpression') }}
          </button>
        </div>
        <div v-if="testResult" class="mt-2 rounded-md border p-2"
             :class="testResult.ok ? 'border-slate-200 bg-slate-50' : 'border-rose-200 bg-rose-50'">
          <div v-if="testResult.ok" class="flex items-center gap-2">
            <span class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">{{ t('rules.test.resultHeading') }}</span>
            <StatusPill :variant="testResultVariant">{{ testResultLabel }}</StatusPill>
          </div>
          <p v-else class="text-xs font-mono text-rose-800 whitespace-pre-wrap">{{ testResult.message }}</p>
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
