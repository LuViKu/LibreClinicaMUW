<script setup lang="ts">
/**
 * Phase E.6 ophth-bilateral — per-item widget renderer.
 *
 * Extracted from {@link CrfEntryView.vue} during the bilateral-layout
 * work so the same widget chain can render inside a single-column row
 * AND inside a bilateral 3-column row (where each eye's cell hosts an
 * independent widget for its own item).
 *
 * The component is deliberately dumb: it reads from a model-value
 * passed by the caller (the store value for the item) and emits
 * {@code update:modelValue} when the operator edits it. The parent —
 * {@code CrfEntryView} — funnels that emit back into
 * {@code store.setValue(item.oid, …)}, keeping the dirty/save loop
 * exactly where it already lives.
 *
 * Boolean ('BL' in the legacy data model, dataType=11) is rendered as
 * a Ja / Nein radio pair. A single checkbox would conflate "Nein"
 * with "unbeantwortet" — the radio pair forces an explicit answer
 * which then drives downstream show-when rules (e.g. the imaging
 * "reason if not done" follow-up that appears only when the parent
 * BL is explicitly "Nein"). Wire contract: `'1'` = Yes, `'0'` = No,
 * empty / null / undefined = unanswered (neither radio selected).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from './FieldLabel.vue'
import TextInput from './TextInput.vue'
import SelectInput from './SelectInput.vue'
import HelperText from './HelperText.vue'
import ErrorText from './ErrorText.vue'
import CheckboxArrayInput from './CheckboxArrayInput.vue'
import FileUploadInput from './FileUploadInput.vue'

import type { CrfItem } from '@/types/crf'

interface Props {
  item: CrfItem
  /** Current store value for this item. */
  modelValue: unknown
  /** Inline-validation error message, if any. */
  errorMessage?: string | null
  /** Read-only sessions disable every control. */
  disabled?: boolean
  /** File-upload busy flag (shared across the form for now). */
  fileBusy?: boolean
  /** Per-form file-upload caps mirrored from the entry payload. */
  maxFileBytes?: number
  fileExtensions?: string
  /**
   * When true, the surrounding layout already owns the row label
   * (e.g. the bilateral 3-column row prints the shared label in
   * its first column). Suppresses the per-widget {@code FieldLabel}
   * so the cell doesn't render a duplicate "OD …" line.
   */
  suppressLabel?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  errorMessage: null,
  disabled: false,
  fileBusy: false,
  maxFileBytes: 0,
  fileExtensions: '',
  suppressLabel: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: unknown]
  'upload-file': [file: File]
  'clear-file': []
}>()

const { t } = useI18n()

const inputId = computed(() => `item-${props.item.oid}`)
const hasError = computed(() => props.errorMessage != null)

const textBindings = computed(() => ({
  id: inputId.value,
  modelValue: (props.modelValue == null ? '' : String(props.modelValue)) as string,
  error: hasError.value,
  'onUpdate:modelValue': (v: string) => emit('update:modelValue', v),
}))

function onNumberInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value
  emit('update:modelValue', raw === '' ? null : Number(raw))
}

const booleanRadioName = computed(() => `bl-radio-${props.item.oid}`)
const isBooleanYes = computed(() => props.modelValue === '1')
const isBooleanNo = computed(() => props.modelValue === '0')

function fileRef(): { filename: string; bytes: number } | null {
  const v = props.modelValue
  if (v && typeof v === 'object' && 'filename' in v && 'bytes' in v) {
    return v as { filename: string; bytes: number }
  }
  return null
}
</script>

<template>
  <div>
    <FieldLabel v-if="!suppressLabel" :for="inputId" :required="item.required">
      {{ item.label }}
      <slot name="label-extras" />
    </FieldLabel>

    <template v-if="item.dataType === 'select-one' && item.options">
      <SelectInput v-bind="textBindings">
        <option :value="undefined">— {{ t('common.search') }} —</option>
        <option v-for="opt in item.options" :key="opt.code" :value="opt.code">{{ opt.label }}</option>
      </SelectInput>
    </template>

    <template v-else-if="item.dataType === 'select-multi' && item.options">
      <CheckboxArrayInput
        :id-prefix="inputId"
        :model-value="(modelValue as string[] | null | undefined) ?? []"
        :options="item.options"
        :error="hasError"
        :disabled="disabled"
        @update:model-value="(v: string[]) => emit('update:modelValue', v)"
      />
    </template>

    <template v-else-if="item.dataType === 'file'">
      <FileUploadInput
        :id-prefix="inputId"
        :model-value="fileRef()"
        :max-bytes="maxFileBytes"
        :allowed-extensions="fileExtensions"
        :drop-prompt-label="t('crfEntry.file.dropPrompt')"
        :browse-label="t('crfEntry.file.browse')"
        :uploading-label="t('crfEntry.file.uploading')"
        :remove-label="t('crfEntry.file.remove')"
        :replace-label="t('crfEntry.file.replace')"
        :too-big-message="t('crfEntry.file.tooBig')"
        :bad-extension-message="t('crfEntry.file.badExtension')"
        :busy="fileBusy"
        :disabled="disabled"
        :error="hasError"
        @upload="(f: File) => emit('upload-file', f)"
        @clear="emit('clear-file')"
      />
    </template>

    <template v-else-if="item.dataType === 'integer' || item.dataType === 'real'">
      <input
        :id="inputId"
        :value="modelValue ?? ''"
        :aria-invalid="hasError || undefined"
        type="number"
        :min="item.min"
        :max="item.max"
        :step="item.dataType === 'integer' ? 1 : 0.1"
        :disabled="disabled"
        class="w-full px-3 py-2 border rounded-md focus:outline-none transition-colors muw-focus"
        :class="hasError
          ? 'border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100'
          : 'border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100'"
        @input="onNumberInput"
      />
    </template>

    <template v-else-if="item.dataType === 'date'">
      <TextInput
        v-bind="textBindings"
        type="text"
        placeholder="YYYY-MM-DD"
        inputmode="numeric"
      />
    </template>

    <template v-else-if="item.dataType === 'boolean'">
      <!-- Phase E.6 — BL renderer. data_type=11 in the legacy model.
           Wire contract: '1' = Yes, '0' = No, empty = unanswered.
           A single checkbox would conflate "Nein" with "unbeantwortet";
           the radio pair forces an explicit answer so downstream
           show-when rules (e.g. the imaging "reason if not done" text
           that appears only when the parent BL is "Nein") have a
           reliable signal to react to. -->
      <div
        role="radiogroup"
        :aria-invalid="hasError || undefined"
        :aria-labelledby="suppressLabel ? undefined : `${inputId}-label`"
        class="inline-flex items-center gap-4"
      >
        <label
          :for="`${inputId}-yes`"
          class="inline-flex items-center gap-1.5 cursor-pointer select-none text-xs text-slate-700"
          :class="{ 'cursor-not-allowed opacity-60': disabled }"
        >
          <input
            :id="`${inputId}-yes`"
            type="radio"
            :name="booleanRadioName"
            value="1"
            :checked="isBooleanYes"
            :disabled="disabled"
            class="h-4 w-4 border-slate-300 text-muw-blue focus:ring-muw-blue-100 muw-focus"
            @change="emit('update:modelValue', '1')"
          />
          <span>{{ t('crfEntry.boolean.yes') }}</span>
        </label>
        <label
          :for="`${inputId}-no`"
          class="inline-flex items-center gap-1.5 cursor-pointer select-none text-xs text-slate-700"
          :class="{ 'cursor-not-allowed opacity-60': disabled }"
        >
          <input
            :id="`${inputId}-no`"
            type="radio"
            :name="booleanRadioName"
            value="0"
            :checked="isBooleanNo"
            :disabled="disabled"
            class="h-4 w-4 border-slate-300 text-muw-blue focus:ring-muw-blue-100 muw-focus"
            @change="emit('update:modelValue', '0')"
          />
          <span>{{ t('crfEntry.boolean.no') }}</span>
        </label>
      </div>
    </template>

    <template v-else>
      <TextInput v-bind="textBindings" type="text" />
    </template>

    <HelperText v-if="item.helper">{{ item.helper }}</HelperText>
    <ErrorText v-if="errorMessage">{{ errorMessage }}</ErrorText>
  </div>
</template>
