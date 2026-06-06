<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'
import ResponseSetPicker from '@/components/ResponseSetPicker.vue'

import {
  responseTypeRequiresOptions,
  useCrfAuthoringStore,
  type AuthoringDataType,
  type AuthoringItem,
  type AuthoringResponseSet,
  type AuthoringResponseType,
  type AuthoringSection,
  type ResponseSetCatalogEntry,
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

const showResponseSetPicker = computed<boolean>(() =>
  responseTypeRequiresOptions(props.item.responseType),
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

const responseTypeOptions: Array<{ value: AuthoringResponseType; labelKey: string }> = [
  { value: 'text', labelKey: 'crfAuthoring.responseType.text' },
  { value: 'textarea', labelKey: 'crfAuthoring.responseType.textarea' },
  { value: 'radio', labelKey: 'crfAuthoring.responseType.radio' },
  { value: 'single-select', labelKey: 'crfAuthoring.responseType.single-select' },
  { value: 'multi-select', labelKey: 'crfAuthoring.responseType.multi-select' },
  { value: 'checkbox', labelKey: 'crfAuthoring.responseType.checkbox' },
  { value: 'file', labelKey: 'crfAuthoring.responseType.file' },
]

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

function onResponseSetUpdate(next: AuthoringResponseSet): void {
  props.item.responseSet = next
}
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
        >
          <option v-for="opt in responseTypeOptions" :key="opt.value" :value="opt.value">
            {{ t(opt.labelKey) }}
          </option>
        </SelectInput>
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

    <div class="text-right">
      <button
        type="button"
        class="text-[11px] text-rose-600 hover:underline"
        @click="emit('remove')"
      >{{ t('common.remove') }}</button>
    </div>
  </li>
</template>
