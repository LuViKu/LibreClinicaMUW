<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'

import type {
  CreateModalityRequest,
  Modality,
  UpdateModalityRequest,
} from '@/types/modality'

/**
 * Phase E.6 — Create + Edit modality dialog.
 *
 * One form for both modes. In create mode the parent passes
 * `existing = undefined`; in edit mode the parent passes the row to
 * be patched. The `code` field becomes readonly in edit mode (the
 * backend uses (code) as the natural key + does NOT support
 * renames — a rename would orphan analytics references downstream).
 *
 * Validation gates the submit button on:
 *   - non-empty code
 *   - at least one of itemOidOd / itemOidOs set
 *   - dataType set
 *
 * Other invariants (ordinal numeric, label lengths, etc.) live on the
 * backend; this dialog just gates the obvious cases so the operator
 * gets fast feedback.
 *
 * Errors:
 *   - The parent passes the server error back via the `errorMessage`
 *     prop; the dialog surfaces it inline above the footer so the
 *     duplicate-code / unknown-OID copy lands in front of the
 *     operator instead of in a toast.
 */
interface Props {
  open: boolean
  existing?: Modality
  /** Inline error from the parent (409 duplicate code, 400 validation, etc). */
  errorMessage?: string | null
  /** Disable the submit button while the parent's mutate request is in flight. */
  isSubmitting?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  existing: undefined,
  errorMessage: null,
  isSubmitting: false,
})

const emit = defineEmits<{
  submit: [payload: CreateModalityRequest | UpdateModalityRequest]
  cancel: []
  'update:open': [value: boolean]
}>()

const { t } = useI18n()

interface Form {
  code: string
  labelEn: string
  labelDe: string
  /**
   * Stored as string for binding compatibility with `<TextInput>` (which
   * is `string | null`); coerced via `Number(...)` at submit time. The
   * native input's `type="number"` keeps the keyboard / spinner UX.
   */
  ordinal: string
  itemOidOd: string
  itemOidOs: string
  dataType: 'numeric' | 'categorical'
  unit: string
}

function blank(): Form {
  return {
    code: '',
    labelEn: '',
    labelDe: '',
    ordinal: '0',
    itemOidOd: '',
    itemOidOs: '',
    dataType: 'numeric',
    unit: '',
  }
}

function fromExisting(m: Modality): Form {
  return {
    code: m.code,
    labelEn: m.labelEn,
    labelDe: m.labelDe,
    ordinal: String(m.ordinal),
    itemOidOd: m.itemOidOd ?? '',
    itemOidOs: m.itemOidOs ?? '',
    dataType: m.dataType,
    unit: m.unit ?? '',
  }
}

const form = ref<Form>(blank())

const isEditMode = computed(() => props.existing !== undefined)

// Re-seed the form every time the dialog opens. The previous values
// (or the new existing row) drive the seed; ignore-while-closed lets
// the parent flip the `existing` prop between opens without leaking
// state across modes.
watch(
  () => [props.open, props.existing] as const,
  ([isOpen]) => {
    if (!isOpen) return
    form.value = props.existing ? fromExisting(props.existing) : blank()
  },
  { immediate: true },
)

const canSubmit = computed(() => {
  if (form.value.code.trim() === '') return false
  if (form.value.itemOidOd.trim() === '' && form.value.itemOidOs.trim() === '') return false
  if (form.value.dataType !== 'numeric' && form.value.dataType !== 'categorical') return false
  return true
})

function submit() {
  if (!canSubmit.value) return
  const od = form.value.itemOidOd.trim()
  const os = form.value.itemOidOs.trim()
  const unit = form.value.unit.trim()
  const ordinal = Number(form.value.ordinal) || 0
  if (isEditMode.value) {
    const payload: UpdateModalityRequest = {
      labelEn: form.value.labelEn.trim(),
      labelDe: form.value.labelDe.trim(),
      ordinal,
      dataType: form.value.dataType,
      ...(od !== '' ? { itemOidOd: od } : {}),
      ...(os !== '' ? { itemOidOs: os } : {}),
      ...(unit !== '' ? { unit } : {}),
    }
    emit('submit', payload)
  } else {
    const payload: CreateModalityRequest = {
      code: form.value.code.trim(),
      labelEn: form.value.labelEn.trim(),
      labelDe: form.value.labelDe.trim(),
      ordinal,
      dataType: form.value.dataType,
      ...(od !== '' ? { itemOidOd: od } : {}),
      ...(os !== '' ? { itemOidOs: os } : {}),
      ...(unit !== '' ? { unit } : {}),
    }
    emit('submit', payload)
  }
}

function cancel() {
  emit('cancel')
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="modality-edit-title"
    panel-class="max-w-2xl"
    @update:open="(v) => emit('update:open', v)"
    @close="cancel"
  >
    <template #header>
      <h2 id="modality-edit-title" class="text-lg font-semibold tracking-tight">
        {{ isEditMode ? t('modalities.dialog.titleEdit') : t('modalities.dialog.titleNew') }}
      </h2>
    </template>

    <div class="space-y-4">
      <div class="grid grid-cols-2 gap-3">
        <div>
          <FieldLabel for="modality-code" required>
            {{ t('modalities.dialog.field.code') }}
          </FieldLabel>
          <TextInput
            id="modality-code"
            v-model="form.code"
            autocomplete="off"
            spellcheck="false"
            :readonly="isEditMode"
            :disabled="isEditMode"
          />
        </div>
        <div>
          <FieldLabel for="modality-ordinal">
            {{ t('modalities.dialog.field.ordinal') }}
          </FieldLabel>
          <TextInput
            id="modality-ordinal"
            v-model="form.ordinal"
            type="number"
            autocomplete="off"
            inputmode="numeric"
          />
        </div>
        <div>
          <FieldLabel for="modality-label-en">
            {{ t('modalities.dialog.field.labelEn') }}
          </FieldLabel>
          <TextInput id="modality-label-en" v-model="form.labelEn" />
        </div>
        <div>
          <FieldLabel for="modality-label-de">
            {{ t('modalities.dialog.field.labelDe') }}
          </FieldLabel>
          <TextInput id="modality-label-de" v-model="form.labelDe" />
        </div>
        <div>
          <FieldLabel for="modality-oid-od">
            {{ t('modalities.dialog.field.itemOidOd') }}
          </FieldLabel>
          <TextInput
            id="modality-oid-od"
            v-model="form.itemOidOd"
            autocomplete="off"
            spellcheck="false"
          />
        </div>
        <div>
          <FieldLabel for="modality-oid-os">
            {{ t('modalities.dialog.field.itemOidOs') }}
          </FieldLabel>
          <TextInput
            id="modality-oid-os"
            v-model="form.itemOidOs"
            autocomplete="off"
            spellcheck="false"
          />
        </div>
        <div>
          <FieldLabel for="modality-datatype" required>
            {{ t('modalities.dialog.field.dataType') }}
          </FieldLabel>
          <SelectInput id="modality-datatype" v-model="form.dataType">
            <option value="numeric">numeric</option>
            <option value="categorical">categorical</option>
          </SelectInput>
        </div>
        <div>
          <FieldLabel for="modality-unit">
            {{ t('modalities.dialog.field.unit') }}
          </FieldLabel>
          <TextInput id="modality-unit" v-model="form.unit" autocomplete="off" />
        </div>
      </div>

      <ErrorText v-if="props.errorMessage">{{ props.errorMessage }}</ErrorText>
    </div>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="cancel"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="!canSubmit || props.isSubmitting"
          @click="submit"
          data-testid="modality-edit-submit"
        >
          {{ props.isSubmitting ? t('common.saving') : (isEditMode ? t('modalities.action.edit') : t('modalities.action.new')) }}
        </button>
      </div>
    </template>
  </Modal>
</template>
