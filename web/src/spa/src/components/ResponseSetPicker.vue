<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'

import {
  useCrfAuthoringStore,
  type AuthoringResponseSet,
  type AuthoringResponseType,
  type ResponseSetCatalogEntry,
  type ResponseSetOption,
} from '@/stores/crfAuthoring'

/**
 * Phase E.6 Milestone B — response-set picker.
 *
 * <p>Combines a catalog dropdown over {@link
 * ResponseSetCatalogEntry} with an inline "create new" branch. On
 * Create the dialog posts to {@code POST /api/v1/response-sets} via the
 * store and selects the resulting (virtual) entry. The catalog is
 * read-side derived (DR-020) — the picker just surfaces it.
 *
 * <p>Inline-edit branch: once the operator picks "Create new", the
 * options[] are mutated locally and saved on the v-modeled
 * {@link AuthoringResponseSet}. The store re-emits the chosen entry as
 * a `{ ref: { label } }` link the moment a catalog entry is picked
 * (saves serialising the same options twice on the wire).
 */

interface Props {
  /** Current value — null when no response set is bound yet. */
  modelValue: AuthoringResponseSet
  /** The response type the parent ItemEditor settled on — drives the inline shape. */
  responseType: AuthoringResponseType
  /** Catalog entries (`GET /api/v1/response-sets`). */
  available: ResponseSetCatalogEntry[]
  /** Stable test id prefix. */
  idPrefix: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:modelValue': [value: AuthoringResponseSet]
}>()

const { t } = useI18n()
const store = useCrfAuthoringStore()

/**
 * Picker mode — operator either picks a catalog entry or types a new
 * one inline. The picker starts in catalog mode unless the model is
 * already an inline definition, in which case it opens in inline mode.
 */
type PickerMode = 'catalog' | 'create'
const pickerMode = ref<PickerMode>(initialMode())

function initialMode(): PickerMode {
  if (props.modelValue && !('ref' in props.modelValue) && props.modelValue.options.length > 0) {
    return 'create'
  }
  return 'catalog'
}

/** Catalog entries filtered to the response type the item expects. */
const eligibleCatalog = computed<ResponseSetCatalogEntry[]>(() =>
  props.available.filter((e) => e.responseType === props.responseType),
)

/** Currently-selected catalog label (only meaningful in catalog mode). */
const selectedLabel = ref<string>(
  props.modelValue && 'ref' in props.modelValue ? props.modelValue.ref.label : '',
)

watch(
  () => props.modelValue,
  (next) => {
    if (next && 'ref' in next) {
      selectedLabel.value = next.ref.label
      pickerMode.value = 'catalog'
    } else if (next && 'options' in next) {
      pickerMode.value = 'create'
    }
  },
)

function onCatalogPick(label: string): void {
  selectedLabel.value = label
  if (label === '') {
    emit('update:modelValue', null)
    return
  }
  emit('update:modelValue', { ref: { label } })
}

/* ----------------------------------------------------------------- */
/* Inline-create branch                                              */
/* ----------------------------------------------------------------- */

const draftLabel = ref<string>(initialDraftLabel())
const draftOptions = ref<ResponseSetOption[]>(initialDraftOptions())
const isSavingCatalog = ref<boolean>(false)
const inlineError = ref<string | null>(null)

function initialDraftLabel(): string {
  if (props.modelValue && !('ref' in props.modelValue)) return props.modelValue.label
  return ''
}
function initialDraftOptions(): ResponseSetOption[] {
  if (props.modelValue && !('ref' in props.modelValue) && props.modelValue.options.length > 0) {
    return props.modelValue.options.map((o) => ({ ...o }))
  }
  return [{ text: '', value: '' }, { text: '', value: '' }]
}

function startCreate(): void {
  pickerMode.value = 'create'
  selectedLabel.value = ''
  if (draftOptions.value.length === 0) {
    draftOptions.value = [{ text: '', value: '' }, { text: '', value: '' }]
  }
  // Bind the inline set immediately so the wizard's draft reflects
  // the operator's intent even before they hit Save.
  emit('update:modelValue', {
    type: props.responseType,
    label: draftLabel.value,
    options: draftOptions.value.map((o) => ({ ...o })),
  })
}

function cancelCreate(): void {
  pickerMode.value = 'catalog'
  emit('update:modelValue', null)
}

function addOption(): void {
  draftOptions.value.push({ text: '', value: '' })
  syncDraftToModel()
}

function removeOption(idx: number): void {
  if (draftOptions.value.length <= 1) return
  draftOptions.value.splice(idx, 1)
  syncDraftToModel()
}

function syncDraftToModel(): void {
  emit('update:modelValue', {
    type: props.responseType,
    label: draftLabel.value,
    options: draftOptions.value.map((o) => ({ ...o })),
  })
}

async function saveDraftToCatalog(): Promise<void> {
  inlineError.value = null
  if (draftLabel.value.trim() === '') {
    inlineError.value = t('crfAuthoring.responseSetPicker.labelRequired')
    return
  }
  if (draftOptions.value.length === 0) {
    inlineError.value = t('crfAuthoring.responseSetPicker.optionsRequired')
    return
  }
  isSavingCatalog.value = true
  try {
    const entry = await store.createCatalogEntry({
      label: draftLabel.value.trim(),
      responseType: props.responseType,
      options: draftOptions.value,
    })
    if (entry) {
      // Switch to ref mode now that the catalog has the entry; the
      // backend re-materialises the options at version-create time.
      selectedLabel.value = entry.label
      pickerMode.value = 'catalog'
      emit('update:modelValue', { ref: { label: entry.label } })
    } else {
      inlineError.value = store.error ?? t('crfAuthoring.responseSetPicker.saveFailed')
    }
  } finally {
    isSavingCatalog.value = false
  }
}
</script>

<template>
  <div
    class="space-y-2"
    :data-testid="`${idPrefix}-root`"
  >
    <FieldLabel :for="`${idPrefix}-select`">
      {{ t('crfAuthoring.responseSetPicker.title') }}
    </FieldLabel>

    <!-- Catalog mode -->
    <div v-if="pickerMode === 'catalog'" class="space-y-2">
      <SelectInput
        :id="`${idPrefix}-select`"
        :model-value="selectedLabel"
        @update:model-value="onCatalogPick"
      >
        <option value="">{{ t('crfAuthoring.responseSetPicker.placeholder') }}</option>
        <option
          v-for="entry in eligibleCatalog"
          :key="`${entry.responseType}::${entry.label}`"
          :value="entry.label"
        >
          {{ entry.label }} ({{ entry.usageCount }}×)
        </option>
      </SelectInput>
      <button
        type="button"
        class="text-xs text-muw-blue hover:underline"
        :data-testid="`${idPrefix}-create-trigger`"
        @click="startCreate"
      >{{ t('crfAuthoring.responseSetPicker.createNew') }}</button>
    </div>

    <!-- Inline-create mode -->
    <div
      v-else
      class="space-y-2 rounded-md border border-slate-200 bg-white p-3"
      :data-testid="`${idPrefix}-inline-editor`"
    >
      <div>
        <FieldLabel :for="`${idPrefix}-label`" required>
          {{ t('crfAuthoring.responseSetPicker.label') }}
        </FieldLabel>
        <TextInput
          :id="`${idPrefix}-label`"
          v-model="draftLabel"
          placeholder="yes_no"
          @update:model-value="syncDraftToModel"
        />
        <HelperText>{{ t('crfAuthoring.responseSetPicker.labelHelper') }}</HelperText>
      </div>

      <div>
        <div class="text-xs font-semibold text-slate-700 mb-1">
          {{ t('crfAuthoring.responseSetPicker.options') }}
        </div>
        <ul class="space-y-1.5">
          <li
            v-for="(opt, oi) in draftOptions"
            :key="`opt-${oi}`"
            class="grid grid-cols-[1fr_1fr_auto] gap-2 items-center"
            :data-testid="`${idPrefix}-option-row`"
          >
            <TextInput
              :id="`${idPrefix}-opt-text-${oi}`"
              v-model="opt.text"
              :placeholder="t('crfAuthoring.responseSetPicker.optionText')"
              @update:model-value="syncDraftToModel"
            />
            <TextInput
              :id="`${idPrefix}-opt-value-${oi}`"
              v-model="opt.value"
              :placeholder="t('crfAuthoring.responseSetPicker.optionValue')"
              @update:model-value="syncDraftToModel"
            />
            <button
              type="button"
              class="text-[11px] text-rose-600 hover:underline"
              :disabled="draftOptions.length <= 1"
              @click="removeOption(oi)"
            >{{ t('common.remove') }}</button>
          </li>
        </ul>
        <button
          type="button"
          class="text-xs text-muw-blue hover:underline mt-2"
          :data-testid="`${idPrefix}-add-option`"
          @click="addOption"
        >{{ t('crfAuthoring.responseSetPicker.addOption') }}</button>
      </div>

      <p v-if="inlineError" class="text-[11px] text-rose-600">{{ inlineError }}</p>

      <div class="flex items-center justify-end gap-2 pt-1">
        <button
          type="button"
          class="px-3 py-1 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          @click="cancelCreate"
        >{{ t('crfAuthoring.responseSetPicker.cancel') }}</button>
        <button
          type="button"
          class="px-3 py-1 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="isSavingCatalog"
          :data-testid="`${idPrefix}-save`"
          @click="saveDraftToCatalog"
        >{{ isSavingCatalog ? t('common.saving') : t('crfAuthoring.responseSetPicker.save') }}</button>
      </div>
    </div>
  </div>
</template>
