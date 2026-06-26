<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import {
  useCrfAuthoringStore,
  allowedResponseTypesForDataType,
  type AuthoringDataType,
  type AuthoringItem,
  type AuthoringResponseType,
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
  const value = (ev.target as HTMLSelectElement).value as AuthoringDataType
  setField('dataType', value)
  // Re-clamp response type to one allowed by the new data type.
  const allowed = allowedResponseTypesForDataType(value)
  const item = selectedItem.value
  if (item && !allowed.includes(item.responseType) && allowed.length > 0) {
    setField('responseType', allowed[0]!)
  }
}

function onResponseTypeChange(ev: Event): void {
  setField('responseType', (ev.target as HTMLSelectElement).value as AuthoringResponseType)
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
          <input
            type="text"
            class="w-full text-xs border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
            :value="selectedItem.descriptionLabel"
            data-testid="crf-canvas-properties-description"
            @input="onLabelInput"
          />
        </div>

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
            <option v-for="dt in DATA_TYPES" :key="dt" :value="dt">{{ dt }}</option>
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
            <option v-for="rt in allowedResponseTypes" :key="rt" :value="rt">{{ rt }}</option>
          </select>
          <p class="mt-1 text-[10.5px] leading-snug text-slate-500">
            {{ t(`crfAuthoring.canvas.properties.responseTypeHint.${selectedItem.responseType}`) }}
          </p>
        </div>

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
