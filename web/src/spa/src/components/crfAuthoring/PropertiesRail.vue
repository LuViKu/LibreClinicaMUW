<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import ResponseOptionsEditor from './ResponseOptionsEditor.vue'
import RepeatingTableEditor from './RepeatingTableEditor.vue'
import TerminologyBindingEditor from './TerminologyBindingEditor.vue'

import {
  useCrfAuthoringStore,
  allowedResponseTypesForDataType,
  dataTypeIsBoolean,
  responseTypeRequiresOptions,
  type AuthoringDataType,
  type AuthoringItem,
  type AutocompleteBinding,
  type AuthoringResponseSet,
  type AuthoringResponseType,
  type RepeatingTableSpec,
  type ShowWhenComparator,
} from '@/stores/crfAuthoring'

/**
 * App-feedback Wave 2 (2026-06-19) — right properties rail.
 *
 * <p>When an item is selected (store.selectedItemUid !== null) the rail
 * surfaces the per-item editor: label / OID / data type / response type
 * / validation / show-when rule. Every field change dispatches
 * {@code store.setItemField(s, i, field, value)} via a coordinate
 * lookup; the rail stores no local state so reorder + undo behaviours
 * stay coherent.
 *
 * <p>When nothing is selected the rail shows an empty-state hint.
 *
 * <p>Show-when editor: per the existing store schema, a rule is a single
 * binary comparison ({@code sourceItemOid}, {@code comparator},
 * {@code literal}). The source-item dropdown lists earlier items only —
 * forward references would break evaluation order at runtime.
 */

const { t } = useI18n()
const store = useCrfAuthoringStore()

const DATA_TYPES: ReadonlyArray<AuthoringDataType> = [
  'ST', 'INT', 'REAL', 'DATE', 'PDATE', 'BL', 'TRISTATE_REASON', 'FILE',
]

/**
 * Localised labels for the two type dropdowns. The rail used to render
 * the raw union codes ({@code ST}, {@code single-select}) even though
 * fully-translated key blocks already existed — they were only ever
 * consumed by the orphaned {@code ItemEditor.vue}. Static maps rather
 * than a template literal so the keys stay greppable.
 */
const DATA_TYPE_LABEL_KEYS: Record<AuthoringDataType, string> = {
  ST: 'crfAuthoring.dataType.ST',
  INT: 'crfAuthoring.dataType.INT',
  REAL: 'crfAuthoring.dataType.REAL',
  DATE: 'crfAuthoring.dataType.DATE',
  PDATE: 'crfAuthoring.dataType.PDATE',
  FILE: 'crfAuthoring.dataType.FILE',
  BL: 'crfAuthoring.dataType.BL',
  TRISTATE_REASON: 'crfAuthoring.dataType.TRISTATE_REASON',
}

const RESPONSE_TYPE_LABEL_KEYS: Record<AuthoringResponseType, string> = {
  text: 'crfAuthoring.responseType.text',
  textarea: 'crfAuthoring.responseType.textarea',
  radio: 'crfAuthoring.responseType.radio',
  'single-select': 'crfAuthoring.responseType.single-select',
  'multi-select': 'crfAuthoring.responseType.multi-select',
  checkbox: 'crfAuthoring.responseType.checkbox',
  file: 'crfAuthoring.responseType.file',
}

const COMPARATORS: ReadonlyArray<{ value: ShowWhenComparator; labelKey: string }> = [
  { value: '==', labelKey: 'crfAuthoring.showWhen.comparator.eq' },
  { value: '!=', labelKey: 'crfAuthoring.showWhen.comparator.ne' },
  { value: '>', labelKey: 'crfAuthoring.showWhen.comparator.gt' },
  { value: '<', labelKey: 'crfAuthoring.showWhen.comparator.lt' },
  { value: '>=', labelKey: 'crfAuthoring.showWhen.comparator.gte' },
  { value: '<=', labelKey: 'crfAuthoring.showWhen.comparator.lte' },
]

interface ItemCoord {
  sectionIndex: number
  itemIndex: number
  section: { items: AuthoringItem[] }
  item: AuthoringItem
}

const selectedCoord = computed<ItemCoord | null>(() => {
  const uid = store.selectedItemUid
  if (!uid) return null
  const sections = store.draft.sections
  for (let s = 0; s < sections.length; s++) {
    const section = sections[s]!
    const idx = section.items.findIndex((it) => it.uid === uid)
    if (idx >= 0) {
      return { sectionIndex: s, itemIndex: idx, section, item: section.items[idx]! }
    }
  }
  return null
})

const selectedItem = computed<AuthoringItem | null>(() => selectedCoord.value?.item ?? null)

const allowedResponseTypes = computed<ReadonlyArray<AuthoringResponseType>>(() => {
  const it = selectedItem.value
  if (!it) return []
  return allowedResponseTypesForDataType(it.dataType)
})

/**
 * Whether to surface the response-options editor. Choice response types
 * need options; BL synthesises its own fixed Yes/No at serialise time.
 */
const showOptionsEditor = computed<boolean>(() => {
  const it = selectedItem.value
  if (!it) return false
  if (dataTypeIsBoolean(it.dataType)) return false
  return responseTypeRequiresOptions(it.responseType)
})

/**
 * Earlier items the operator can reference in a show-when rule.
 * "Earlier" = items declared before this one in the same section
 * (linear evaluation order, matches the wizard's existing helper).
 */
const earlierItems = computed<AuthoringItem[]>(() => {
  const coord = selectedCoord.value
  if (!coord) return []
  return coord.section.items.slice(0, coord.itemIndex).filter((it) => it.oid.trim() !== '')
})

function setField<K extends keyof AuthoringItem>(field: K, value: AuthoringItem[K]): void {
  const coord = selectedCoord.value
  if (!coord) return
  store.setItemField(coord.sectionIndex, coord.itemIndex, field, value)
}

function onNameInput(ev: Event): void {
  const value = (ev.target as HTMLInputElement).value
  setField('name', value)
  // Mirror the existing wizard's auto-suggest behaviour: when the OID
  // is blank, derive from the name. Operators can override afterwards.
  const item = selectedItem.value
  if (item && item.oid.trim() === '') {
    setField('oid', store.suggestOid(value))
  }
}

function onOidInput(ev: Event): void {
  setField('oid', (ev.target as HTMLInputElement).value)
}

function onLabelInput(ev: Event): void {
  setField('descriptionLabel', (ev.target as HTMLInputElement).value)
}

function onUnitsInput(ev: Event): void {
  setField('units', (ev.target as HTMLInputElement).value)
}

function onDefaultValueInput(ev: Event): void {
  setField('defaultValue', (ev.target as HTMLInputElement).value)
}

function onRequiredInput(ev: Event): void {
  setField('required', (ev.target as HTMLInputElement).checked)
}

function onDataTypeChange(ev: Event): void {
  // The store's reconcileItemResponseShape re-clamps the response type
  // and seeds/clears the response set — no component-side clamping, so
  // this path and the palette-drop path cannot diverge.
  setField('dataType', (ev.target as HTMLSelectElement).value as AuthoringDataType)
}

function onResponseTypeChange(ev: Event): void {
  setField('responseType', (ev.target as HTMLSelectElement).value as AuthoringResponseType)
}

function onResponseSetUpdate(next: AuthoringResponseSet): void {
  const coord = selectedCoord.value
  if (!coord) return
  store.setItemResponseSet(coord.sectionIndex, coord.itemIndex, next)
}

function onValidationRegexInput(ev: Event): void {
  const item = selectedItem.value
  if (!item) return
  setField('validation', {
    ...item.validation,
    regexp: (ev.target as HTMLInputElement).value,
  })
}

function onValidationMessageInput(ev: Event): void {
  const item = selectedItem.value
  if (!item) return
  setField('validation', {
    ...item.validation,
    errorMessage: (ev.target as HTMLInputElement).value,
  })
}

function onToggleShowWhen(ev: Event): void {
  const enabled = (ev.target as HTMLInputElement).checked
  const item = selectedItem.value
  if (!item) return
  if (enabled) {
    const first = earlierItems.value[0]
    setField('showWhen', {
      sourceItemOid: first?.oid ?? '',
      comparator: '==',
      literal: '',
    })
  } else {
    setField('showWhen', undefined)
  }
}

function onShowWhenSource(ev: Event): void {
  const value = (ev.target as HTMLSelectElement).value
  const item = selectedItem.value
  if (!item || !item.showWhen) return
  setField('showWhen', { ...item.showWhen, sourceItemOid: value })
}

function onShowWhenComparator(ev: Event): void {
  const value = (ev.target as HTMLSelectElement).value as ShowWhenComparator
  const item = selectedItem.value
  if (!item || !item.showWhen) return
  setField('showWhen', { ...item.showWhen, comparator: value })
}

function onShowWhenLiteral(ev: Event): void {
  const value = (ev.target as HTMLInputElement).value
  const item = selectedItem.value
  if (!item || !item.showWhen) return
  setField('showWhen', { ...item.showWhen, literal: value })
}

function onClearSelection(): void {
  store.selectItem(null)
}

/* ------------------- #26 terminology + repeating table ------------------- */

/** True for a table item — its scalar type fields are inert, so hide them. */
const isTableItem = computed<boolean>(() => selectedItem.value?.table != null)

/**
 * Flat text items that can host autocomplete. Only ST/text makes sense —
 * a coded free-text field. Not shown for table items (columns carry their
 * own bindings) or non-text data types.
 */
const canBindFlatAutocomplete = computed<boolean>(() => {
  const it = selectedItem.value
  return it != null && !it.table && it.dataType === 'ST'
})

/** Sibling items a flat fill rule may target — same section, excluding self. */
const flatFillTargets = computed<{ key: string; label: string }[]>(() => {
  const coord = selectedCoord.value
  if (!coord) return []
  return coord.section.items
    .filter((it) => it.uid !== coord.item.uid && it.oid.trim() !== '')
    .map((it) => ({ key: it.oid.trim(), label: it.name.trim() || it.oid.trim() }))
})

function onFlatBinding(binding: AutocompleteBinding | undefined): void {
  setField('autocomplete', binding)
}

function onTableUpdate(spec: RepeatingTableSpec): void {
  setField('table', spec)
}
</script>

<template>
  <aside
    class="w-72 shrink-0 border-l border-slate-200 bg-slate-50/40 overflow-y-auto"
    data-testid="crf-canvas-properties-rail"
  >
    <div class="p-3">
      <div class="flex items-center justify-between mb-3">
        <h3 class="text-xs font-semibold uppercase tracking-wider text-slate-600">
          {{ t('crfAuthoring.canvas.properties.title') }}
        </h3>
        <button
          v-if="selectedItem"
          type="button"
          class="text-[10px] text-slate-500 hover:text-slate-800 underline"
          data-testid="crf-canvas-properties-clear"
          @click="onClearSelection"
        >
          {{ t('crfAuthoring.canvas.properties.clear') }}
        </button>
      </div>

      <div
        v-if="!selectedItem"
        class="text-center text-[11px] italic text-slate-500 px-2 py-8 border border-dashed border-slate-300 rounded-md"
        data-testid="crf-canvas-properties-empty"
      >
        {{ t('crfAuthoring.canvas.properties.noSelection') }}
      </div>

      <div v-else class="space-y-3" data-testid="crf-canvas-properties-form">
        <div>
          <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
            {{ t('crfAuthoring.canvas.properties.labelField') }}
          </label>
          <input
            type="text"
            class="w-full text-xs border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.name"
            data-testid="crf-canvas-properties-name"
            @input="onNameInput"
          />
        </div>

        <div>
          <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
            {{ t('crfAuthoring.canvas.properties.oidField') }}
          </label>
          <input
            type="text"
            class="w-full text-xs font-mono border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.oid"
            data-testid="crf-canvas-properties-oid"
            @input="onOidInput"
          />
          <p class="text-[10px] text-slate-500 mt-0.5">
            {{ t('crfAuthoring.canvas.properties.oidHelper') }}
          </p>
        </div>

        <div>
          <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
            {{ t('crfAuthoring.canvas.properties.descriptionField') }}
          </label>
          <!-- Multi-line + vertically resizable (drag the lower-right grip) so
               a long description can break across lines. resize-y shows the
               native resize handle as the affordance. -->
          <textarea
            rows="2"
            class="w-full text-xs border-slate-200 rounded px-2 py-1 resize-y min-h-[2.25rem] focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.descriptionLabel"
            data-testid="crf-canvas-properties-description"
            @input="onLabelInput"
          ></textarea>
        </div>

        <!-- #26 — scalar type fields are inert for a repeating-table item;
             the table editor below owns its column definitions instead. -->
        <template v-if="!isTableItem">
        <div>
          <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
            {{ t('crfAuthoring.canvas.properties.dataTypeField') }}
          </label>
          <select
            class="w-full text-xs border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.dataType"
            data-testid="crf-canvas-properties-dataType"
            @change="onDataTypeChange"
          >
            <option v-for="dt in DATA_TYPES" :key="dt" :value="dt">
              {{ t(DATA_TYPE_LABEL_KEYS[dt]) }}
            </option>
          </select>
          <p class="mt-1 text-[10.5px] leading-snug text-slate-500">
            {{ t(`crfAuthoring.canvas.properties.dataTypeHint.${selectedItem.dataType}`) }}
          </p>
        </div>

        <div>
          <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
            {{ t('crfAuthoring.canvas.properties.responseTypeField') }}
          </label>
          <select
            class="w-full text-xs border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.responseType"
            :disabled="allowedResponseTypes.length <= 1"
            data-testid="crf-canvas-properties-responseType"
            @change="onResponseTypeChange"
          >
            <option v-for="rt in allowedResponseTypes" :key="rt" :value="rt">
              {{ t(RESPONSE_TYPE_LABEL_KEYS[rt]) }}
            </option>
          </select>
          <p class="mt-1 text-[10.5px] leading-snug text-slate-500">
            {{ t(`crfAuthoring.canvas.properties.responseTypeHint.${selectedItem.responseType}`) }}
          </p>
        </div>

        <!-- Response options — the editor the canvas never had. Keyed on
             the item uid so switching selection remounts with fresh rows
             rather than showing the previously-selected item's options. -->
        <div v-if="showOptionsEditor" data-testid="crf-canvas-properties-options-host">
          <ResponseOptionsEditor
            :key="selectedItem.uid"
            :model-value="selectedItem.responseSet"
            :response-type="selectedItem.responseType"
            :data-type="selectedItem.dataType"
            :catalog="store.responseSetCatalog"
            @update:model-value="onResponseSetUpdate"
          />
        </div>
        <p
          v-else-if="selectedItem.dataType === 'BL'"
          class="text-[10.5px] leading-snug text-slate-500"
          data-testid="crf-canvas-properties-options-bl-hint"
        >
          {{ t('crfAuthoring.dataType.BLHelper') }}
        </p>

        <div>
          <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
            {{ t('crfAuthoring.canvas.properties.unitsField') }}
          </label>
          <input
            type="text"
            class="w-full text-xs border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.units"
            data-testid="crf-canvas-properties-units"
            @input="onUnitsInput"
          />
        </div>

        <div>
          <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
            {{ t('crfAuthoring.canvas.properties.defaultField') }}
          </label>
          <input
            type="text"
            class="w-full text-xs border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.defaultValue"
            data-testid="crf-canvas-properties-default"
            @input="onDefaultValueInput"
          />
        </div>
        </template>

        <!-- #26 — repeating-table column editor (operator-defined columns
             + per-text-column terminology autocomplete). -->
        <details
          v-if="isTableItem && selectedItem.table"
          class="border border-slate-200 rounded p-2"
          open
          data-testid="crf-canvas-properties-table-section"
        >
          <summary class="text-[11px] font-semibold text-slate-700 cursor-pointer">
            {{ t('crfAuthoring.canvas.table.section') }}
          </summary>
          <div class="mt-2">
            <RepeatingTableEditor :spec="selectedItem.table" @update:spec="onTableUpdate" />
          </div>
        </details>

        <!-- #26 — flat text field terminology autocomplete opt-in. -->
        <details
          v-if="canBindFlatAutocomplete"
          class="border border-slate-200 rounded p-2"
          :open="!!selectedItem.autocomplete"
          data-testid="crf-canvas-properties-autocomplete-section"
        >
          <summary class="text-[11px] font-semibold text-slate-700 cursor-pointer">
            {{ t('crfAuthoring.canvas.terminology.section') }}
          </summary>
          <div class="mt-2">
            <TerminologyBindingEditor
              :binding="selectedItem.autocomplete"
              :targets="flatFillTargets"
              id-prefix="crf-canvas-properties-flat"
              @update:binding="onFlatBinding"
            />
          </div>
        </details>

        <div>
          <label class="flex items-center gap-2 text-[11px] text-slate-700">
            <input
              type="checkbox"
              class="rounded border-slate-300"
              :checked="selectedItem.required"
              data-testid="crf-canvas-properties-required"
              @change="onRequiredInput"
            />
            <span>{{ t('crfAuthoring.canvas.properties.requiredField') }}</span>
          </label>
        </div>

        <details class="border border-slate-200 rounded p-2">
          <summary class="text-[11px] font-semibold text-slate-700 cursor-pointer">
            {{ t('crfAuthoring.canvas.properties.validationSection') }}
          </summary>
          <div class="mt-2 space-y-2">
            <input
              type="text"
              class="w-full text-xs font-mono border-slate-200 rounded px-2 py-1"
              :placeholder="t('crfAuthoring.canvas.properties.validationRegex')"
              :value="selectedItem.validation.regexp"
              data-testid="crf-canvas-properties-validation-regex"
              @input="onValidationRegexInput"
            />
            <input
              type="text"
              class="w-full text-xs border-slate-200 rounded px-2 py-1"
              :placeholder="t('crfAuthoring.canvas.properties.validationMessage')"
              :value="selectedItem.validation.errorMessage"
              data-testid="crf-canvas-properties-validation-message"
              @input="onValidationMessageInput"
            />
          </div>
        </details>

        <details class="border border-slate-200 rounded p-2" :open="!!selectedItem.showWhen">
          <summary class="text-[11px] font-semibold text-slate-700 cursor-pointer">
            {{ t('crfAuthoring.canvas.properties.showWhenSection') }}
          </summary>
          <div class="mt-2 space-y-2">
            <label class="flex items-center gap-2 text-[11px] text-slate-700">
              <input
                type="checkbox"
                class="rounded border-slate-300"
                :checked="!!selectedItem.showWhen"
                data-testid="crf-canvas-properties-showWhen-toggle"
                @change="onToggleShowWhen"
              />
              <span>{{ t('crfAuthoring.canvas.properties.showWhenToggle') }}</span>
            </label>
            <template v-if="selectedItem.showWhen">
              <select
                class="w-full text-xs border-slate-200 rounded px-2 py-1"
                :value="selectedItem.showWhen.sourceItemOid"
                data-testid="crf-canvas-properties-showWhen-source"
                @change="onShowWhenSource"
              >
                <option value="" disabled>
                  {{ t('crfAuthoring.canvas.properties.showWhenSourcePlaceholder') }}
                </option>
                <option v-for="ei in earlierItems" :key="ei.uid" :value="ei.oid">
                  {{ ei.oid }}
                </option>
              </select>
              <select
                class="w-full text-xs border-slate-200 rounded px-2 py-1"
                :value="selectedItem.showWhen.comparator"
                data-testid="crf-canvas-properties-showWhen-comparator"
                @change="onShowWhenComparator"
              >
                <option v-for="c in COMPARATORS" :key="c.value" :value="c.value">
                  {{ t(c.labelKey) }}
                </option>
              </select>
              <input
                type="text"
                class="w-full text-xs border-slate-200 rounded px-2 py-1"
                :placeholder="t('crfAuthoring.canvas.properties.showWhenLiteralPlaceholder')"
                :value="selectedItem.showWhen.literal"
                data-testid="crf-canvas-properties-showWhen-literal"
                @input="onShowWhenLiteral"
              />
            </template>
          </div>
        </details>
      </div>
    </div>
  </aside>
</template>
