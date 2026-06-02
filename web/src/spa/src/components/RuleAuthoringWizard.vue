<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import StatusPill from '@/components/StatusPill.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import ErrorText from '@/components/ErrorText.vue'

import { useRulesStore, type TestExpressionResult, type ValidateTargetResult } from '@/stores/rules'

/**
 * Phase E RX.5b — operator-facing 3-step wizard for the inline rule
 * create endpoints shipped in PR #84 (RX.5 backend).
 *
 * <ol>
 *   <li><b>Rule definition</b> — POST {@code /api/v1/rules} with
 *       {@code {oid, name, description, expression}}. The operator can
 *       run the existing {@code POST /api/v1/rules/test-expression}
 *       sanity check from inside the step before persisting.</li>
 *   <li><b>Target + scope</b> — POST {@code /api/v1/rule-sets} with
 *       {@code {target, ruleOids: [step1.oid]}} and the three optional
 *       scope OIDs. Target validation is live: as the operator types,
 *       a debounced 500ms call to {@code /validate-target} surfaces a
 *       "Valid" / "Invalid" pill with the backend's error message.</li>
 *   <li><b>Action</b> — POST {@code /api/v1/rule-sets/{id}/actions} for
 *       one of the four inline-supported types (Discrepancy / Email /
 *       Show / Hide). The phase-gates checkbox group and the
 *       expression-evaluates-to toggle map 1:1 to the backend record.</li>
 * </ol>
 *
 * <p>State persists only inside the wizard — closing at any step
 * discards the in-progress local form data. Rows that already landed
 * in the DB on earlier steps stay (no auto-rollback) — operators
 * either continue or clean up via the existing disable/restore UX.
 *
 * <p>On the final "Done" the parent runs {@code rules.load()} to
 * reconcile state with the DB (the rule_set + action ids are now
 * fully cascade-persisted, including the action that was attached
 * after the eager append in {@code createRuleSet}).
 */

interface Props { open: boolean }
const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [v: boolean]
  close: []
  /** Emitted after the operator hits Done; parent should reload list. */
  done: []
}>()

const { t } = useI18n()
const rules = useRulesStore()

/** 1-3 are wizard steps; 4 is the post-action success pane. */
type Step = 1 | 2 | 3 | 4
const step = ref<Step>(1)

/* ------------------------------------------------------------------ */
/* Step 1 — rule body                                                  */
/* ------------------------------------------------------------------ */

const ruleOid = ref('')
const ruleName = ref('')
const ruleDescription = ref('')
const ruleExpression = ref('')
const ruleFieldErrors = ref<Record<string, string>>({})
const ruleGlobalError = ref<string | null>(null)
const isSavingRule = ref(false)

/**
 * Local "test syntax" branch — fires the same RX.3 endpoint the
 * detail-pane test tool uses, but with empty {@code testValues}. A
 * green result pill on TRUE / neutral on FALSE matches the rest of
 * the platform; parse failures render the backend's error text.
 */
const isTestingExpression = ref(false)
const testResult = ref<TestExpressionResult | null>(null)
async function onTestExpression() {
  if (isTestingExpression.value) return
  const trimmed = ruleExpression.value.trim()
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
  const r = testResult.value
  if (!r || !r.ok) return ''
  if (r.result === 'true') return t('rules.test.resultTrue')
  if (r.result === 'false') return t('rules.test.resultFalse')
  return t('rules.test.resultOther', { value: r.result })
})
const testResultVariant = computed<'success' | 'neutral'>(() => {
  const r = testResult.value
  if (r && r.ok && r.result === 'true') return 'success'
  return 'neutral'
})

/**
 * Step-1 success carries the rule's id + canonical OID forward to
 * step 2 (where it's the sole entry in the "rules to attach" list).
 */
const createdRuleOid = ref<string | null>(null)
const createdRuleName = ref<string | null>(null)

async function onSubmitStep1() {
  if (isSavingRule.value) return
  ruleFieldErrors.value = {}
  ruleGlobalError.value = null
  isSavingRule.value = true
  try {
    const result = await rules.createRule({
      oid: ruleOid.value.trim(),
      name: ruleName.value.trim(),
      description: ruleDescription.value.trim(),
      expression: ruleExpression.value.trim(),
    })
    if (!result.ok) {
      ruleFieldErrors.value = result.fieldErrors
      ruleGlobalError.value = Object.keys(result.fieldErrors).length === 0
        ? (result.message ?? null)
        : null
      return
    }
    createdRuleOid.value = result.rule.oid
    createdRuleName.value = result.rule.name
    step.value = 2
  } finally {
    isSavingRule.value = false
  }
}

/* ------------------------------------------------------------------ */
/* Step 2 — rule set target + scope                                    */
/* ------------------------------------------------------------------ */

const targetExpression = ref('')
const targetSedOid = ref('')
const targetCrfOid = ref('')
const targetCrfVersionOid = ref('')
const ruleSetFieldErrors = ref<Record<string, string>>({})
const ruleSetGlobalError = ref<string | null>(null)
const isSavingRuleSet = ref(false)

/**
 * Debounced live target-validation state. The probe fires 500ms after
 * the last keystroke (cheap on the backend — read-only — but still
 * worth throttling to avoid one HTTP call per character). Status
 * values map to the inline pill text via the i18n keys below.
 */
type ValidationStatus = 'idle' | 'checking' | 'valid' | 'invalid'
const targetValidationStatus = ref<ValidationStatus>('idle')
const targetValidationErrors = ref<Array<{ message: string }>>([])
let targetValidationTimer: ReturnType<typeof setTimeout> | null = null
/**
 * Bumped on every keystroke; the in-flight probe checks against this
 * before applying its result so a stale response from a slow request
 * doesn't overwrite a fresher status. Without the token, fast typing
 * followed by a slow response would flicker the pill between states.
 */
let targetValidationToken = 0

watch(targetExpression, (next) => {
  if (targetValidationTimer != null) clearTimeout(targetValidationTimer)
  const trimmed = next.trim()
  if (trimmed.length === 0) {
    targetValidationStatus.value = 'idle'
    targetValidationErrors.value = []
    return
  }
  targetValidationStatus.value = 'checking'
  targetValidationToken += 1
  const myToken = targetValidationToken
  targetValidationTimer = setTimeout(async () => {
    try {
      const result: ValidateTargetResult = await rules.validateTarget(trimmed)
      if (myToken !== targetValidationToken) return
      if (result.valid) {
        targetValidationStatus.value = 'valid'
        targetValidationErrors.value = []
      } else {
        targetValidationStatus.value = 'invalid'
        targetValidationErrors.value = result.errors
      }
    } catch {
      // Auth failures bubble through the router guard via the store —
      // collapse anything else into an inline invalid result so the
      // wizard doesn't render an undefined state.
      if (myToken !== targetValidationToken) return
      targetValidationStatus.value = 'invalid'
      targetValidationErrors.value = [{ message: t('rules.create.validation.invalid') }]
    }
  }, 500)
})

const createdRuleSetId = ref<number | null>(null)
const createdRuleSetRuleId = ref<number | null>(null)

async function onSubmitStep2() {
  if (isSavingRuleSet.value || createdRuleOid.value == null) return
  ruleSetFieldErrors.value = {}
  ruleSetGlobalError.value = null
  isSavingRuleSet.value = true
  try {
    const result = await rules.createRuleSet({
      target: targetExpression.value.trim(),
      studyEventDefinitionOid: targetSedOid.value,
      crfOid: targetCrfOid.value,
      crfVersionOid: targetCrfVersionOid.value,
      ruleOids: [createdRuleOid.value],
    })
    if (!result.ok) {
      ruleSetFieldErrors.value = result.fieldErrors
      ruleSetGlobalError.value = Object.keys(result.fieldErrors).length === 0
        ? (result.message ?? null)
        : null
      return
    }
    createdRuleSetId.value = result.ruleSet.id
    // The cascade-persisted attached rules carry the rule_set_rule
    // ids the action endpoint expects on its body. The first (only)
    // entry is the rule we just bound in step 1.
    const firstAttached = result.ruleSet.attachedRules[0]
    if (firstAttached != null) {
      createdRuleSetRuleId.value = firstAttached.ruleSetRuleId
    }
    step.value = 3
  } finally {
    isSavingRuleSet.value = false
  }
}

/* ------------------------------------------------------------------ */
/* Step 3 — action                                                     */
/* ------------------------------------------------------------------ */

const actionType = ref<'FILE_DISCREPANCY_NOTE' | 'EMAIL' | 'SHOW' | 'HIDE'>('FILE_DISCREPANCY_NOTE')
const actionMessage = ref('')
const actionTo = ref('')
const actionExpressionEvaluatesTo = ref(true)
const actionPhaseAdmin = ref(false)
const actionPhaseInitial = ref(true)
const actionPhaseDouble = ref(false)
const actionPhaseImport = ref(false)
const actionPhaseBatch = ref(false)
const actionFieldErrors = ref<Record<string, string>>({})
const actionGlobalError = ref<string | null>(null)
const isSavingAction = ref(false)

async function onSubmitStep3() {
  if (isSavingAction.value || createdRuleSetId.value == null || createdRuleSetRuleId.value == null) return
  actionFieldErrors.value = {}
  actionGlobalError.value = null
  isSavingAction.value = true
  try {
    const result = await rules.createAction(createdRuleSetId.value, {
      ruleSetRuleId: createdRuleSetRuleId.value,
      actionType: actionType.value,
      expressionEvaluatesTo: actionExpressionEvaluatesTo.value,
      message: actionMessage.value.trim(),
      to: actionType.value === 'EMAIL' ? actionTo.value.trim() : undefined,
      phaseGates: {
        administrativeDataEntry: actionPhaseAdmin.value,
        initialDataEntry: actionPhaseInitial.value,
        doubleDataEntry: actionPhaseDouble.value,
        importDataEntry: actionPhaseImport.value,
        batch: actionPhaseBatch.value,
      },
    })
    if (!result.ok) {
      actionFieldErrors.value = result.fieldErrors
      actionGlobalError.value = Object.keys(result.fieldErrors).length === 0
        ? (result.message ?? null)
        : null
      return
    }
    step.value = 4
  } finally {
    isSavingAction.value = false
  }
}

/* ------------------------------------------------------------------ */
/* Wizard lifecycle                                                    */
/* ------------------------------------------------------------------ */

function resetAll() {
  step.value = 1
  ruleOid.value = ''
  ruleName.value = ''
  ruleDescription.value = ''
  ruleExpression.value = ''
  ruleFieldErrors.value = {}
  ruleGlobalError.value = null
  isSavingRule.value = false
  isTestingExpression.value = false
  testResult.value = null
  createdRuleOid.value = null
  createdRuleName.value = null

  targetExpression.value = ''
  targetSedOid.value = ''
  targetCrfOid.value = ''
  targetCrfVersionOid.value = ''
  ruleSetFieldErrors.value = {}
  ruleSetGlobalError.value = null
  isSavingRuleSet.value = false
  targetValidationStatus.value = 'idle'
  targetValidationErrors.value = []
  if (targetValidationTimer != null) {
    clearTimeout(targetValidationTimer)
    targetValidationTimer = null
  }
  createdRuleSetId.value = null
  createdRuleSetRuleId.value = null

  actionType.value = 'FILE_DISCREPANCY_NOTE'
  actionMessage.value = ''
  actionTo.value = ''
  actionExpressionEvaluatesTo.value = true
  actionPhaseAdmin.value = false
  actionPhaseInitial.value = true
  actionPhaseDouble.value = false
  actionPhaseImport.value = false
  actionPhaseBatch.value = false
  actionFieldErrors.value = {}
  actionGlobalError.value = null
  isSavingAction.value = false
}

watch(
  () => props.open,
  (next) => {
    if (next) resetAll()
  },
)

function onCancel() {
  emit('update:open', false)
  emit('close')
}

function onDone() {
  emit('update:open', false)
  emit('close')
  emit('done')
}

const headingKey = computed(() => {
  switch (step.value) {
    case 1: return 'rules.create.step.1'
    case 2: return 'rules.create.step.2'
    case 3: return 'rules.create.step.3'
    case 4: return 'rules.create.step.done'
    default: return 'rules.create.heading'
  }
})

const actionTypeOptions: Array<{ value: typeof actionType.value; labelKey: string }> = [
  { value: 'FILE_DISCREPANCY_NOTE', labelKey: 'rules.actionType.FILE_DISCREPANCY_NOTE' },
  { value: 'EMAIL', labelKey: 'rules.actionType.EMAIL' },
  { value: 'SHOW', labelKey: 'rules.actionType.SHOW' },
  { value: 'HIDE', labelKey: 'rules.actionType.HIDE' },
]
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="rule-wizard-heading"
    panel-class="max-w-3xl"
    @update:open="(v) => emit('update:open', v)"
    @close="onCancel"
  >
    <template #header>
      <div>
        <h2 id="rule-wizard-heading" class="text-base font-semibold tracking-tight">
          {{ t('rules.create.heading') }}
        </h2>
        <p class="text-[11px] text-slate-500 mt-0.5">
          {{ t(headingKey) }}
        </p>
      </div>
    </template>

    <!-- ---------------- Step 1: Rule definition ---------------- -->
    <div v-if="step === 1" class="space-y-4">
      <p class="text-[11px] text-slate-500 leading-relaxed">
        {{ t('rules.create.actionTypeNote') }}
      </p>

      <div>
        <FieldLabel for="rule-oid" required>{{ t('rules.create.field.oid') }}</FieldLabel>
        <TextInput
          id="rule-oid"
          v-model="ruleOid"
          :error="ruleFieldErrors.oid != null"
          placeholder="RUL_BP_HIGH"
        />
        <p v-if="!ruleFieldErrors.oid" class="mt-1 text-[11px] text-slate-500">
          {{ t('rules.create.field.oidHint') }}
        </p>
        <ErrorText v-else>{{ ruleFieldErrors.oid }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="rule-name" required>{{ t('rules.create.field.name') }}</FieldLabel>
        <TextInput
          id="rule-name"
          v-model="ruleName"
          :error="ruleFieldErrors.name != null"
        />
        <ErrorText v-if="ruleFieldErrors.name">{{ ruleFieldErrors.name }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="rule-description">{{ t('rules.create.field.description') }}</FieldLabel>
        <textarea
          id="rule-description"
          v-model="ruleDescription"
          rows="2"
          class="w-full px-3 py-2 text-xs border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
        />
        <ErrorText v-if="ruleFieldErrors.description">{{ ruleFieldErrors.description }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="rule-expression" required>{{ t('rules.create.field.expression') }}</FieldLabel>
        <textarea
          id="rule-expression"
          v-model="ruleExpression"
          rows="4"
          class="w-full px-3 py-2 text-xs font-mono border rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
          :class="ruleFieldErrors.expression ? 'border-rose-400 bg-rose-50/40' : 'border-slate-300'"
          :aria-invalid="ruleFieldErrors.expression != null ? true : undefined"
        />
        <div class="mt-1 flex items-center justify-between gap-2">
          <p v-if="!ruleFieldErrors.expression" class="text-[11px] text-slate-500">
            {{ t('rules.create.field.expressionHint') }}
          </p>
          <ErrorText v-else class="flex-1">{{ ruleFieldErrors.expression }}</ErrorText>
          <button
            type="button"
            class="shrink-0 px-2 py-1 text-[11px] border border-slate-200 rounded-md hover:bg-slate-50 disabled:opacity-50"
            :disabled="isTestingExpression"
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

      <p v-if="ruleGlobalError" class="text-xs text-rose-700">{{ ruleGlobalError }}</p>
    </div>

    <!-- ---------------- Step 2: Target + scope ---------------- -->
    <div v-else-if="step === 2" class="space-y-4">
      <div>
        <FieldLabel for="rule-target" required>{{ t('rules.create.field.target') }}</FieldLabel>
        <textarea
          id="rule-target"
          v-model="targetExpression"
          rows="2"
          class="w-full px-3 py-2 text-xs font-mono border rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
          :class="ruleSetFieldErrors.target ? 'border-rose-400 bg-rose-50/40' : 'border-slate-300'"
          :aria-invalid="ruleSetFieldErrors.target != null ? true : undefined"
        />
        <div class="mt-1 flex items-center gap-2 flex-wrap">
          <p v-if="!ruleSetFieldErrors.target" class="text-[11px] text-slate-500 flex-1">
            {{ t('rules.create.field.targetHint') }}
          </p>
          <ErrorText v-else class="flex-1">{{ ruleSetFieldErrors.target }}</ErrorText>
          <StatusPill
            v-if="targetValidationStatus === 'checking'"
            variant="neutral"
          >
            {{ t('rules.create.validation.checking') }}
          </StatusPill>
          <StatusPill
            v-else-if="targetValidationStatus === 'valid'"
            variant="success"
          >
            {{ t('rules.create.validation.valid') }}
          </StatusPill>
          <StatusPill
            v-else-if="targetValidationStatus === 'invalid'"
            variant="warning"
          >
            {{ t('rules.create.validation.invalid') }}
          </StatusPill>
        </div>
        <ul
          v-if="targetValidationStatus === 'invalid' && targetValidationErrors.length > 0"
          class="mt-1 text-[11px] text-rose-700 list-disc ml-4 space-y-0.5"
        >
          <li v-for="(err, i) in targetValidationErrors" :key="i">{{ err.message }}</li>
        </ul>
      </div>

      <div class="grid grid-cols-3 gap-3">
        <div>
          <FieldLabel for="rule-sed-oid">{{ t('rules.create.field.sedOid') }}</FieldLabel>
          <TextInput
            id="rule-sed-oid"
            v-model="targetSedOid"
            :error="ruleSetFieldErrors.studyEventDefinitionOid != null"
            placeholder="—"
          />
          <ErrorText v-if="ruleSetFieldErrors.studyEventDefinitionOid">{{ ruleSetFieldErrors.studyEventDefinitionOid }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="rule-crf-oid">{{ t('rules.create.field.crfOid') }}</FieldLabel>
          <TextInput
            id="rule-crf-oid"
            v-model="targetCrfOid"
            :error="ruleSetFieldErrors.crfOid != null"
            placeholder="—"
          />
          <ErrorText v-if="ruleSetFieldErrors.crfOid">{{ ruleSetFieldErrors.crfOid }}</ErrorText>
        </div>
        <div>
          <FieldLabel for="rule-crf-version-oid">{{ t('rules.create.field.crfVersionOid') }}</FieldLabel>
          <TextInput
            id="rule-crf-version-oid"
            v-model="targetCrfVersionOid"
            :error="ruleSetFieldErrors.crfVersionOid != null"
            placeholder="—"
          />
          <ErrorText v-if="ruleSetFieldErrors.crfVersionOid">{{ ruleSetFieldErrors.crfVersionOid }}</ErrorText>
        </div>
      </div>

      <div>
        <div class="text-xs font-medium text-slate-700 mb-1">{{ t('rules.create.field.rulesToAttach') }}</div>
        <ul class="space-y-1">
          <li class="flex items-center gap-2 rounded-md border border-slate-200 bg-slate-50 px-2 py-1.5">
            <input
              type="checkbox"
              checked
              disabled
              class="rounded border-slate-300"
            />
            <span class="text-xs text-slate-700">{{ createdRuleName ?? '—' }}</span>
            <code class="ml-auto text-[10px] font-mono text-slate-500">{{ createdRuleOid }}</code>
          </li>
        </ul>
        <ErrorText v-if="ruleSetFieldErrors.ruleOids">{{ ruleSetFieldErrors.ruleOids }}</ErrorText>
      </div>

      <p v-if="ruleSetGlobalError" class="text-xs text-rose-700">{{ ruleSetGlobalError }}</p>
    </div>

    <!-- ---------------- Step 3: Action ---------------- -->
    <div v-else-if="step === 3" class="space-y-4">
      <div>
        <FieldLabel for="rule-action-type" required>{{ t('rules.create.field.actionType') }}</FieldLabel>
        <SelectInput
          id="rule-action-type"
          v-model="actionType"
          :error="actionFieldErrors.actionType != null"
        >
          <option
            v-for="opt in actionTypeOptions"
            :key="opt.value"
            :value="opt.value"
          >
            {{ t(opt.labelKey) }}
          </option>
        </SelectInput>
        <ErrorText v-if="actionFieldErrors.actionType">{{ actionFieldErrors.actionType }}</ErrorText>
      </div>

      <div>
        <FieldLabel for="rule-action-message" required>{{ t('rules.create.field.message') }}</FieldLabel>
        <textarea
          id="rule-action-message"
          v-model="actionMessage"
          rows="2"
          class="w-full px-3 py-2 text-xs border rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
          :class="actionFieldErrors.message ? 'border-rose-400 bg-rose-50/40' : 'border-slate-300'"
          :aria-invalid="actionFieldErrors.message != null ? true : undefined"
        />
        <ErrorText v-if="actionFieldErrors.message">{{ actionFieldErrors.message }}</ErrorText>
      </div>

      <div v-if="actionType === 'EMAIL'">
        <FieldLabel for="rule-action-to" required>{{ t('rules.create.field.to') }}</FieldLabel>
        <TextInput
          id="rule-action-to"
          v-model="actionTo"
          type="email"
          :error="actionFieldErrors.to != null"
          placeholder="trial-monitor@example.org"
        />
        <ErrorText v-if="actionFieldErrors.to">{{ actionFieldErrors.to }}</ErrorText>
      </div>

      <div>
        <div class="text-xs font-medium text-slate-700 mb-1">{{ t('rules.create.field.phaseGates') }}</div>
        <div class="grid grid-cols-2 gap-1.5">
          <label class="flex items-center gap-2 text-xs text-slate-700">
            <input v-model="actionPhaseAdmin" type="checkbox" class="rounded border-slate-300" />
            {{ t('rules.create.phase.admin') }}
          </label>
          <label class="flex items-center gap-2 text-xs text-slate-700">
            <input v-model="actionPhaseInitial" type="checkbox" class="rounded border-slate-300" />
            {{ t('rules.create.phase.initial') }}
          </label>
          <label class="flex items-center gap-2 text-xs text-slate-700">
            <input v-model="actionPhaseDouble" type="checkbox" class="rounded border-slate-300" />
            {{ t('rules.create.phase.double') }}
          </label>
          <label class="flex items-center gap-2 text-xs text-slate-700">
            <input v-model="actionPhaseImport" type="checkbox" class="rounded border-slate-300" />
            {{ t('rules.create.phase.import') }}
          </label>
          <label class="flex items-center gap-2 text-xs text-slate-700">
            <input v-model="actionPhaseBatch" type="checkbox" class="rounded border-slate-300" />
            {{ t('rules.create.phase.batch') }}
          </label>
        </div>
        <ErrorText v-if="actionFieldErrors.phaseGates">{{ actionFieldErrors.phaseGates }}</ErrorText>
      </div>

      <div>
        <label class="flex items-center gap-2 text-xs text-slate-700">
          <input v-model="actionExpressionEvaluatesTo" type="checkbox" class="rounded border-slate-300" />
          {{ t('rules.create.field.expressionEvaluatesTo') }}
        </label>
      </div>

      <p v-if="actionGlobalError" class="text-xs text-rose-700">{{ actionGlobalError }}</p>
    </div>

    <!-- ---------------- Step 4: Done ---------------- -->
    <div v-else-if="step === 4" class="space-y-3">
      <div class="rounded-md border border-emerald-200 bg-emerald-50 p-3">
        <div class="text-[10px] uppercase tracking-wider text-emerald-700 font-semibold">
          {{ t('rules.create.result.heading') }}
        </div>
        <p class="mt-1 text-xs text-emerald-900">
          {{ t('rules.create.result.summary') }}
        </p>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2 w-full">
        <!-- Cancel is always available except on Done -->
        <button
          v-if="step !== 4"
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md hover:bg-slate-50"
          @click="onCancel"
        >
          {{ t('rules.create.button.cancel') }}
        </button>

        <!-- Step 1 -->
        <template v-if="step === 1">
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90 disabled:opacity-40"
            :disabled="isSavingRule"
            @click="onSubmitStep1"
          >
            {{ isSavingRule ? t('common.loading') : t('rules.create.button.saveRule') }}
          </button>
        </template>

        <!-- Step 2 -->
        <template v-else-if="step === 2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90 disabled:opacity-40"
            :disabled="isSavingRuleSet"
            @click="onSubmitStep2"
          >
            {{ isSavingRuleSet ? t('common.loading') : t('rules.create.button.saveRuleSet') }}
          </button>
        </template>

        <!-- Step 3 -->
        <template v-else-if="step === 3">
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90 disabled:opacity-40"
            :disabled="isSavingAction"
            @click="onSubmitStep3"
          >
            {{ isSavingAction ? t('common.loading') : t('rules.create.button.saveAction') }}
          </button>
        </template>

        <!-- Step 4: Done -->
        <template v-else-if="step === 4">
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90"
            @click="onDone"
          >
            {{ t('rules.create.button.done') }}
          </button>
        </template>
      </div>
    </template>
  </Modal>
</template>
