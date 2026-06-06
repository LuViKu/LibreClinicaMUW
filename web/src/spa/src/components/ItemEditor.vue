<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'
import ResponseSetPicker from '@/components/ResponseSetPicker.vue'

import {
  allowedResponseTypesForDataType,
  dataTypeIsBoolean,
  responseTypeRequiresOptions,
  useCrfAuthoringStore,
  type AuthoringDataType,
  type AuthoringItem,
  type AuthoringResponseSet,
  type AuthoringResponseType,
  type AuthoringSection,
  type ResponseSetCatalogEntry,
  type ShowWhenComparator,
} from '@/stores/crfAuthoring'

/**
 * Phase E.6 Milestone B — per-item editor.
 *
 * <p>Surfaces every field on {@link AuthoringItem} that the Milestone B
 * backend understands: name, OID (auto-suggested from name with
 * operator override), descriptionLabel, leftItemText, rightItemText,
 * units, dataType, responseType, defaultValue, required, validation
 * (regex + error message), responseSet.
 *
 * <p>The response-set picker is rendered only for response types that
 * carry a finite option list (radio, single-/multi-select, checkbox).
 * For text / textarea / file responses the picker is hidden and the
 * store synthesises an implicit open-text response set at submit.
 *
 * <p>Phase E.6 polish-authoring batch adds two features on top:
 * <ul>
 *   <li><b>Datentyp → Antworttyp restriction matrix</b> — the
 *       response-type dropdown is filtered to the entries returned by
 *       {@link allowedResponseTypesForDataType}. On data-type change
 *       the current response type is reset to the first allowed entry
 *       if it falls outside the new set.</li>
 *   <li><b>Per-item show-when rule editor</b> — single-condition
 *       conditional visibility. Source item picker lists only items
 *       declared BEFORE this one (so evaluation order can't break);
 *       comparator + literal value complete the rule.</li>
 * </ul>
 */

interface Props {
  /** The item being edited (a row in `crfAuthoring.draft.sections[].items`). */
  item: AuthoringItem
  /** Sections for the parentItemOid picker — Milestone B leaves the picker dormant. */
  sections: AuthoringSection[]
  /** Catalog entries returned by `GET /api/v1/response-sets`. */
  availableResponseSets: ResponseSetCatalogEntry[]
  /** Stable test id prefix — keeps the wizard's nested fields uniquely addressable. */
  idPrefix: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  remove: []
}>()

const { t } = useI18n()
const store = useCrfAuthoringStore()

const isBoolean = computed<boolean>(() => dataTypeIsBoolean(props.item.dataType))

const showResponseSetPicker = computed<boolean>(() =>
  // BL items use a fixed Yes/No option list synthesised at submit; the
  // picker would just confuse operators, so we hide it entirely.
  !isBoolean.value && responseTypeRequiresOptions(props.item.responseType),
)

const dataTypeOptions: Array<{ value: AuthoringDataType; labelKey: string }> = [
  { value: 'ST', labelKey: 'crfAuthoring.dataType.ST' },
  { value: 'INT', labelKey: 'crfAuthoring.dataType.INT' },
  { value: 'REAL', labelKey: 'crfAuthoring.dataType.REAL' },
  { value: 'DATE', labelKey: 'crfAuthoring.dataType.DATE' },
  { value: 'PDATE', labelKey: 'crfAuthoring.dataType.PDATE' },
  { value: 'FILE', labelKey: 'crfAuthoring.dataType.FILE' },
  // BL re-entered the taxonomy alongside the Phase E.6 Ophthalmology
  // bilateral preset (see {@code ophthPreset.ts}); the backend adapter
  // accepts BL via the same code path used by the XLS uploader.
  { value: 'BL', labelKey: 'crfAuthoring.dataType.BL' },
]

const responseTypeLabelKeys: Record<AuthoringResponseType, string> = {
  text: 'crfAuthoring.responseType.text',
  textarea: 'crfAuthoring.responseType.textarea',
  radio: 'crfAuthoring.responseType.radio',
  'single-select': 'crfAuthoring.responseType.single-select',
  'multi-select': 'crfAuthoring.responseType.multi-select',
  checkbox: 'crfAuthoring.responseType.checkbox',
  file: 'crfAuthoring.responseType.file',
}

/**
 * Response-type dropdown options — derived from the data-type matrix
 * (see {@link allowedResponseTypesForDataType}). BL keeps the dropdown
 * disabled at {@code single-select}; the matrix returns the same
 * singleton.
 */
const responseTypeOptions = computed<Array<{ value: AuthoringResponseType; labelKey: string }>>(() => {
  return allowedResponseTypesForDataType(props.item.dataType).map((value) => ({
    value,
    labelKey: responseTypeLabelKeys[value],
  }))
})

/**
 * Auto-suggest the OID from the item name as long as the operator
 * hasn't typed a custom OID yet. The brief calls this "hybrid": the
 * suggestion follows the name until the operator overrides it, after
 * which the user-typed value stays sticky.
 */
let oidUserTouched = props.item.oid.trim() !== ''

watch(
  () => props.item.name,
  (next) => {
    if (oidUserTouched) return
    props.item.oid = store.suggestOid(next)
  },
)

function onOidInput(value: string): void {
  oidUserTouched = true
  props.item.oid = value
}

/**
 * Flip the picker on when the operator picks a response type that
 * needs options, off when they revert to an open-text branch. This
 * keeps the wizard's data shape consistent with what the backend
 * validator + adapter expect.
 */
watch(
  () => props.item.responseType,
  (next) => {
    if (isBoolean.value) {
      // BL pins the response set to the synthesised Yes/No — never
      // let an inline set linger on the item.
      props.item.responseSet = null
      return
    }
    if (responseTypeRequiresOptions(next)) {
      if (props.item.responseSet == null || ('ref' in props.item.responseSet)) {
        // Seed an empty inline set so the picker has something to
        // bind to; the operator either creates options or swaps to a
        // catalog ref.
        props.item.responseSet = {
          type: next,
          label: '',
          options: [],
        }
      } else {
        // Inline set already present — sync its type to the new
        // response type.
        props.item.responseSet.type = next
      }
    } else {
      // Open-text branch — the store handles the implicit label.
      props.item.responseSet = null
    }
  },
)

/**
 * BL locks the response type to {@code single-select} (the synthesised
 * workbook emits a fixed Yes/No option list, which the parser only
 * accepts under one of the option-bearing response types). Flipping
 * away from BL leaves the operator's previous responseType selection
 * unless it would now require options against an empty inline set — in
 * which case we fall back to {@code text} so the picker doesn't
 * re-open with a half-bound state.
 *
 * <p>For non-BL transitions: enforce the Datentyp → Antworttyp matrix.
 * If the current response type falls outside the new allowed set,
 * snap to the first allowed entry. Mirrors how the dropdown filters
 * its visible options — without the reset the operator would see a
 * dropdown that no longer contains the selected value.
 */
watch(
  () => props.item.dataType,
  (next, prev) => {
    if (next === prev) return
    if (next === 'BL') {
      props.item.responseType = 'single-select'
      props.item.responseSet = null
      return
    }
    if (prev === 'BL') {
      // Coming back from BL — pick a safe open-text default. The
      // operator can re-pick a richer response type from the dropdown.
      props.item.responseType = 'text'
      props.item.responseSet = null
      return
    }
    // Non-BL transition — reconcile against the new matrix entry.
    const allowed = allowedResponseTypesForDataType(next)
    if (!allowed.includes(props.item.responseType)) {
      // Snap to the first allowed entry. The responseType watcher
      // takes care of clearing or seeding the response set as needed.
      props.item.responseType = allowed[0] ?? 'text'
    }
  },
)

function onResponseSetUpdate(next: AuthoringResponseSet): void {
  props.item.responseSet = next
}

/* ---------------- Show-when rule editor ---------------- */

/**
 * Conditional-visibility toggle state. Mirrors {@code item.showWhen
 * != null} so the operator can flip the rule on/off without losing
 * partial input — we seed an empty rule on toggle-on and clear it on
 * toggle-off.
 */
const showWhenEnabled = computed<boolean>({
  get: () => props.item.showWhen != null,
  set: (next) => {
    if (next) {
      if (props.item.showWhen == null) {
        props.item.showWhen = {
          sourceItemOid: '',
          comparator: '==',
          literal: '',
        }
      }
    } else {
      props.item.showWhen = undefined
    }
  },
})

/**
 * Source-item picker candidates. Lists every item across all sections
 * that is declared BEFORE the current item in canonical order — this
 * is the "no forward reference" constraint that lets the runtime
 * renderer evaluate rules in a single pass.
 *
 * <p>Each entry exposes the section title + the item's name / OID /
 * data type chip so the operator can disambiguate items with the same
 * label across sections.
 */
interface SourceCandidate {
  sectionTitle: string
  sectionIndex: number
  itemOid: string
  itemName: string
  itemDataType: AuthoringDataType
  itemResponseSet: AuthoringItem['responseSet']
}

const sourceCandidates = computed<SourceCandidate[]>(() => {
  const out: SourceCandidate[] = []
  outer: for (let sIdx = 0; sIdx < props.sections.length; sIdx++) {
    const section = props.sections[sIdx]!
    for (const candidate of section.items) {
      if (candidate === props.item) {
        // Reached self — every subsequent item is a forward reference
        // and disallowed.
        break outer
      }
      const oid = candidate.oid.trim()
      // Skip half-authored items without an OID — they can't be
      // referenced.
      if (oid === '') continue
      out.push({
        sectionTitle: section.title.trim() || section.label.trim() || `#${sIdx + 1}`,
        sectionIndex: sIdx,
        itemOid: oid,
        itemName: candidate.name.trim() || oid,
        itemDataType: candidate.dataType,
        itemResponseSet: candidate.responseSet,
      })
    }
  }
  return out
})

const selectedSourceCandidate = computed<SourceCandidate | null>(() => {
  const oid = props.item.showWhen?.sourceItemOid
  if (!oid) return null
  return sourceCandidates.value.find((c) => c.itemOid === oid) ?? null
})

const comparatorOptions: Array<{ value: ShowWhenComparator; labelKey: string; helperKey: string }> = [
  { value: '==', labelKey: 'crfAuthoring.showWhen.comparator.eq', helperKey: 'crfAuthoring.showWhen.comparator.eqHelper' },
  { value: '!=', labelKey: 'crfAuthoring.showWhen.comparator.ne', helperKey: 'crfAuthoring.showWhen.comparator.neHelper' },
  { value: '>', labelKey: 'crfAuthoring.showWhen.comparator.gt', helperKey: 'crfAuthoring.showWhen.comparator.gtHelper' },
  { value: '<', labelKey: 'crfAuthoring.showWhen.comparator.lt', helperKey: 'crfAuthoring.showWhen.comparator.ltHelper' },
  { value: '>=', labelKey: 'crfAuthoring.showWhen.comparator.gte', helperKey: 'crfAuthoring.showWhen.comparator.gteHelper' },
  { value: '<=', labelKey: 'crfAuthoring.showWhen.comparator.lte', helperKey: 'crfAuthoring.showWhen.comparator.lteHelper' },
]

const comparatorHelperText = computed<string>(() => {
  const c = props.item.showWhen?.comparator ?? '=='
  const entry = comparatorOptions.find((opt) => opt.value === c)
  return entry ? t(entry.helperKey) : ''
})

/**
 * Literal-value validation. Returns an i18n key when the literal is
 * incompatible with the source item's data type; null when valid or
 * when no source item is selected (the missing-source case is its own
 * error).
 */
const literalValidationKey = computed<string | null>(() => {
  if (!showWhenEnabled.value) return null
  const rule = props.item.showWhen
  if (rule == null) return null
  const literal = rule.literal.trim()
  if (literal === '') return 'crfAuthoring.showWhen.errors.literalRequired'
  const src = selectedSourceCandidate.value
  if (src == null) return null  // source-required error covers this case
  switch (src.itemDataType) {
    case 'INT': {
      if (!/^-?\d+$/.test(literal)) return 'crfAuthoring.showWhen.errors.literalNotInt'
      return null
    }
    case 'REAL': {
      if (!/^-?\d+(\.\d+)?$/.test(literal)) return 'crfAuthoring.showWhen.errors.literalNotReal'
      return null
    }
    case 'BL': {
      if (!/^(true|false|0|1)$/i.test(literal)) return 'crfAuthoring.showWhen.errors.literalNotBool'
      return null
    }
    default:
      return null
  }
})

const sourceValidationKey = computed<string | null>(() => {
  if (!showWhenEnabled.value) return null
  const rule = props.item.showWhen
  if (rule == null) return null
  if (rule.sourceItemOid.trim() === '') return 'crfAuthoring.showWhen.errors.sourceRequired'
  // Operator picked an OID but the item is no longer in the candidate
  // list (e.g. moved after this one, or deleted) — surface a clear
  // error so the rule can't silently break.
  if (selectedSourceCandidate.value == null) return 'crfAuthoring.showWhen.errors.sourceMissing'
  return null
})

/**
 * Options for the literal input — shape depends on the source item's
 * data type / response set. For BL we surface a two-option dropdown;
 * for option-bearing response types we offer the option list directly.
 */
interface LiteralOption {
  value: string
  label: string
}

const literalOptions = computed<LiteralOption[] | null>(() => {
  const src = selectedSourceCandidate.value
  if (src == null) return null
  if (src.itemDataType === 'BL') {
    return [
      { value: '1', label: t('crfAuthoring.showWhen.literal.true') },
      { value: '0', label: t('crfAuthoring.showWhen.literal.false') },
    ]
  }
  const rs = src.itemResponseSet
  if (rs && 'options' in rs && Array.isArray(rs.options) && rs.options.length > 0) {
    return rs.options
      .filter((opt) => opt.value.trim() !== '' || opt.text.trim() !== '')
      .map((opt) => ({ value: opt.value, label: opt.text || opt.value }))
  }
  return null
})

const literalInputMode = computed<'select' | 'numeric' | 'text'>(() => {
  if (literalOptions.value && literalOptions.value.length > 0) return 'select'
  const src = selectedSourceCandidate.value
  if (src == null) return 'text'
  if (src.itemDataType === 'INT' || src.itemDataType === 'REAL') return 'numeric'
  return 'text'
})
</script>

<template>
  <li
    class="rounded-md border border-slate-200 bg-slate-50/60 p-3 space-y-3"
    :data-testid="`${idPrefix}-item-editor`"
  >
    <div class="grid grid-cols-2 gap-3">
      <div>
        <FieldLabel :for="`${idPrefix}-name`" required>
          {{ t('crfAuthoring.item.name') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-name`"
          v-model="props.item.name"
          placeholder="AGE"
        />
      </div>
      <div>
        <FieldLabel :for="`${idPrefix}-oid`">
          {{ t('crfAuthoring.item.oid') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-oid`"
          :model-value="props.item.oid"
          @update:model-value="onOidInput"
        />
        <HelperText>{{ t('crfAuthoring.item.oidHelper') }}</HelperText>
      </div>
      <div class="col-span-2">
        <FieldLabel :for="`${idPrefix}-descriptionLabel`" required>
          {{ t('crfAuthoring.item.descriptionLabel') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-descriptionLabel`"
          v-model="props.item.descriptionLabel"
        />
      </div>
      <div class="col-span-2">
        <FieldLabel :for="`${idPrefix}-leftItemText`">
          {{ t('crfAuthoring.item.leftItemText') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-leftItemText`"
          v-model="props.item.leftItemText"
        />
      </div>
      <div>
        <FieldLabel :for="`${idPrefix}-rightItemText`">
          {{ t('crfAuthoring.item.rightItemText') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-rightItemText`"
          v-model="props.item.rightItemText"
        />
      </div>
      <div>
        <FieldLabel :for="`${idPrefix}-units`">
          {{ t('crfAuthoring.item.units') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-units`"
          v-model="props.item.units"
        />
      </div>
      <div>
        <FieldLabel :for="`${idPrefix}-dataType`" required>
          {{ t('crfAuthoring.item.dataType') }}
        </FieldLabel>
        <SelectInput
          :id="`${idPrefix}-dataType`"
          v-model="props.item.dataType"
        >
          <option v-for="opt in dataTypeOptions" :key="opt.value" :value="opt.value">
            {{ t(opt.labelKey) }}
          </option>
        </SelectInput>
      </div>
      <div>
        <FieldLabel :for="`${idPrefix}-responseType`" required>
          {{ t('crfAuthoring.item.responseType') }}
        </FieldLabel>
        <SelectInput
          :id="`${idPrefix}-responseType`"
          v-model="props.item.responseType"
          :disabled="isBoolean"
        >
          <option v-for="opt in responseTypeOptions" :key="opt.value" :value="opt.value">
            {{ t(opt.labelKey) }}
          </option>
        </SelectInput>
        <HelperText v-if="isBoolean">{{ t('crfAuthoring.dataType.BLHelper') }}</HelperText>
        <HelperText v-else>
          {{ t('crfAuthoring.responseType.matrixHelper', { dataType: t(`crfAuthoring.dataType.${props.item.dataType}`) }) }}
        </HelperText>
      </div>
      <div class="col-span-2">
        <FieldLabel :for="`${idPrefix}-defaultValue`">
          {{ t('crfAuthoring.item.defaultValue') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-defaultValue`"
          v-model="props.item.defaultValue"
        />
      </div>
      <div class="flex items-end pb-1 col-span-2">
        <label class="inline-flex items-center gap-2 text-xs text-slate-700">
          <input type="checkbox" v-model="props.item.required" />
          {{ t('crfAuthoring.item.required') }}
        </label>
      </div>
    </div>

    <!-- Validation (regex + error message) -->
    <div class="border-t border-slate-200 pt-3 grid grid-cols-2 gap-3">
      <div>
        <FieldLabel :for="`${idPrefix}-regex`">
          {{ t('crfAuthoring.item.regex') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-regex`"
          v-model="props.item.validation.regexp"
          placeholder="^[A-Z]{2,4}$"
        />
      </div>
      <div>
        <FieldLabel :for="`${idPrefix}-errorMessage`">
          {{ t('crfAuthoring.item.errorMessage') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-errorMessage`"
          v-model="props.item.validation.errorMessage"
        />
      </div>
    </div>

    <!-- Response-set picker (radio/select/checkbox/multi only) -->
    <div
      v-if="showResponseSetPicker"
      class="border-t border-slate-200 pt-3"
      :data-testid="`${idPrefix}-picker-host`"
    >
      <ResponseSetPicker
        :id-prefix="`${idPrefix}-rs`"
        :model-value="props.item.responseSet"
        :response-type="props.item.responseType"
        :available="props.availableResponseSets"
        @update:model-value="onResponseSetUpdate"
      />
    </div>

    <!-- BL preview — fixed Yes/No, no picker. -->
    <div
      v-if="isBoolean"
      class="border-t border-slate-200 pt-3 space-y-1"
      :data-testid="`${idPrefix}-bl-preview`"
    >
      <div class="text-[11px] font-medium text-slate-500 uppercase tracking-wide">
        {{ t('crfAuthoring.dataType.BLPreviewHeading') }}
      </div>
      <label class="inline-flex items-center gap-2 text-xs text-slate-700">
        <input
          type="checkbox"
          disabled
          :data-testid="`${idPrefix}-bl-checkbox`"
        />
        <span>{{ props.item.leftItemText.trim() || props.item.descriptionLabel.trim() || t('crfAuthoring.dataType.BLPlaceholder') }}</span>
      </label>
      <p class="text-[11px] text-slate-500 leading-snug">
        {{ t('crfAuthoring.dataType.BLHelper') }}
      </p>
    </div>

    <!-- Show-when rule editor (per-item conditional visibility). -->
    <div
      class="border-t border-slate-200 pt-3 space-y-3"
      :data-testid="`${idPrefix}-show-when`"
    >
      <label class="inline-flex items-center gap-2 text-xs text-slate-700">
        <input
          type="checkbox"
          v-model="showWhenEnabled"
          :data-testid="`${idPrefix}-show-when-toggle`"
        />
        <span class="font-medium">{{ t('crfAuthoring.showWhen.toggle') }}</span>
      </label>
      <p v-if="!showWhenEnabled" class="text-[11px] text-slate-500 leading-snug">
        {{ t('crfAuthoring.showWhen.toggleHint') }}
      </p>

      <div
        v-if="showWhenEnabled && props.item.showWhen"
        class="rounded-md border border-slate-200 bg-white p-3 space-y-3"
        :data-testid="`${idPrefix}-show-when-rule`"
      >
        <div>
          <FieldLabel :for="`${idPrefix}-show-when-source`" required>
            {{ t('crfAuthoring.showWhen.sourceLabel') }}
          </FieldLabel>
          <SelectInput
            :id="`${idPrefix}-show-when-source`"
            v-model="props.item.showWhen.sourceItemOid"
            :error="sourceValidationKey != null"
          >
            <option value="">{{ t('crfAuthoring.showWhen.sourcePlaceholder') }}</option>
            <option
              v-for="cand in sourceCandidates"
              :key="`${cand.sectionIndex}-${cand.itemOid}`"
              :value="cand.itemOid"
            >
              {{ cand.sectionTitle }} · {{ cand.itemName }} ({{ cand.itemDataType }})
            </option>
          </SelectInput>
          <HelperText v-if="sourceCandidates.length === 0">
            {{ t('crfAuthoring.showWhen.noEarlierItems') }}
          </HelperText>
          <HelperText v-else>
            {{ t('crfAuthoring.showWhen.sourceHelper') }}
          </HelperText>
          <ErrorText v-if="sourceValidationKey">{{ t(sourceValidationKey) }}</ErrorText>
        </div>

        <div>
          <FieldLabel :for="`${idPrefix}-show-when-comparator`" required>
            {{ t('crfAuthoring.showWhen.comparatorLabel') }}
          </FieldLabel>
          <SelectInput
            :id="`${idPrefix}-show-when-comparator`"
            v-model="props.item.showWhen.comparator"
          >
            <option v-for="opt in comparatorOptions" :key="opt.value" :value="opt.value">
              {{ t(opt.labelKey) }}
            </option>
          </SelectInput>
          <HelperText>{{ comparatorHelperText }}</HelperText>
        </div>

        <div>
          <FieldLabel :for="`${idPrefix}-show-when-literal`" required>
            {{ t('crfAuthoring.showWhen.literalLabel') }}
          </FieldLabel>
          <SelectInput
            v-if="literalInputMode === 'select' && literalOptions"
            :id="`${idPrefix}-show-when-literal`"
            v-model="props.item.showWhen.literal"
            :error="literalValidationKey != null"
          >
            <option value="">{{ t('crfAuthoring.showWhen.literalPlaceholder') }}</option>
            <option v-for="opt in literalOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }} ({{ opt.value }})
            </option>
          </SelectInput>
          <TextInput
            v-else-if="literalInputMode === 'numeric'"
            :id="`${idPrefix}-show-when-literal`"
            v-model="props.item.showWhen.literal"
            inputmode="decimal"
            :error="literalValidationKey != null"
            placeholder="0"
          />
          <TextInput
            v-else
            :id="`${idPrefix}-show-when-literal`"
            v-model="props.item.showWhen.literal"
            :error="literalValidationKey != null"
          />
          <HelperText>{{ t('crfAuthoring.showWhen.literalHelper') }}</HelperText>
          <ErrorText v-if="literalValidationKey">{{ t(literalValidationKey) }}</ErrorText>
        </div>
      </div>
    </div>

    <div class="text-right">
      <button
        type="button"
        class="text-[11px] text-rose-600 hover:underline"
        @click="emit('remove')"
      >{{ t('common.remove') }}</button>
    </div>
  </li>
</template>
