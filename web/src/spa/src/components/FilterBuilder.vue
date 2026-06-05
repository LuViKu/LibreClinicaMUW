<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldLabel from '@/components/FieldLabel.vue'
import SelectInput from '@/components/SelectInput.vue'
import TextInput from '@/components/TextInput.vue'
import {
  type DatasetFilterDto,
  type FilterItemDto,
  type FilterOperator,
  type FilterTestResult,
  LIST_OPERATORS,
  UNARY_OPERATORS,
  operatorsForDataType,
} from '@/types/export'

/**
 * Phase E.6 Data Export — Phase 3 (filters) authoring UI.
 *
 * Builds a list of {@link DatasetFilterDto} predicates over the items
 * the operator picked in Phase 2's tree picker. Per-row UI:
 *
 *   item selector (autocomplete over selectedItems)
 *   ── operator selector (filtered to type-appropriate operators)
 *   ── value input(s) (type-appropriate widget per row)
 *   ── remove button
 *
 * Plus:
 *
 *   "Add filter" button at the foot of the row list.
 *   Inline preview "{matching} of {total} subjects · {matching} of {total} CRFs"
 *
 * The preview is provided by the parent — the FilterBuilder fires a
 * {@code preview-requested} event whenever the model mutates so the
 * parent (the wizard) can debounce-call the {@code :test-filter}
 * endpoint via the datasets store. This keeps the builder presentational
 * + makes it cheap to test (no Pinia, no network).
 *
 * Accessibility:
 *  - Each row's controls are labelled via {@code FieldLabel} so SR
 *    announces "Item, Operator, Value" before the editable cells.
 *  - Remove button carries an aria-label so SR users know which row
 *    they're deleting.
 *  - The preview pane is rendered in an aria-live polite region so the
 *    count updates announce without interrupting the operator's typing.
 */

interface Props {
  /**
   * The items the operator already picked in the wizard's tree
   * picker. The FilterBuilder is presentational — it doesn't pick
   * items, it only authors predicates over them.
   */
  selectedItems: FilterItemDto[]
  /** v-model:modelValue — the in-progress filter list. */
  modelValue: DatasetFilterDto[]
  /**
   * Preview result the wizard hands in after calling
   * {@code datasets.testFilter()}. {@code null} means no preview is
   * available yet (no filters or the call hasn't returned).
   */
  preview?: FilterTestResult | null
  /** True while the preview probe is in flight. */
  previewLoading?: boolean
  /** Optional error to display under the preview pane. */
  previewError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  preview: null,
  previewLoading: false,
  previewError: null,
})

const emit = defineEmits<{
  'update:modelValue': [value: DatasetFilterDto[]]
  'preview-requested': [filters: DatasetFilterDto[]]
}>()

const { t } = useI18n()

/**
 * Mutable copy of {@code modelValue} the row controls bind against.
 * We mirror back via {@code update:modelValue} after each mutation so
 * the parent's reactive draft updates.
 */
const rows = ref<DatasetFilterDto[]>(cloneRows(props.modelValue))

watch(
  () => props.modelValue,
  (next) => {
    if (!sameRows(rows.value, next)) {
      rows.value = cloneRows(next)
    }
  },
  { deep: true },
)

function cloneRows(src: DatasetFilterDto[]): DatasetFilterDto[] {
  return src.map((r) => ({
    itemOid: r.itemOid,
    operator: r.operator,
    value: r.value ?? null,
    values: r.values ? [...r.values] : undefined,
  }))
}

function sameRows(a: DatasetFilterDto[], b: DatasetFilterDto[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    const x = a[i]
    const y = b[i]
    if (x.itemOid !== y.itemOid) return false
    if (x.operator !== y.operator) return false
    if ((x.value ?? null) !== (y.value ?? null)) return false
    const xv = x.values ?? []
    const yv = y.values ?? []
    if (xv.length !== yv.length) return false
    for (let j = 0; j < xv.length; j++) if (xv[j] !== yv[j]) return false
  }
  return true
}

const itemsByOid = computed(() => {
  const map = new Map<string, FilterItemDto>()
  for (const item of props.selectedItems) map.set(item.oid, item)
  return map
})

function lookupItem(oid: string): FilterItemDto | undefined {
  return itemsByOid.value.get(oid)
}

function defaultRow(): DatasetFilterDto {
  // Default to the first selected item + an equality predicate so the
  // operator selector + value input render immediately when the
  // operator clicks "Add filter".
  const first = props.selectedItems[0]
  return {
    itemOid: first?.oid ?? '',
    operator: '=' as FilterOperator,
    value: '',
    values: undefined,
  }
}

function emitUpdate(): void {
  emit('update:modelValue', cloneRows(rows.value))
  emit('preview-requested', cloneRows(rows.value))
}

function addRow(): void {
  rows.value = [...rows.value, defaultRow()]
  emitUpdate()
}

function removeRow(index: number): void {
  rows.value = rows.value.filter((_, i) => i !== index)
  emitUpdate()
}

function onItemChange(index: number, oid: string): void {
  const row = rows.value[index]
  const newItem = lookupItem(oid)
  const previousItem = lookupItem(row.itemOid)
  rows.value[index] = {
    ...row,
    itemOid: oid,
    // If the new item's type doesn't support the current operator,
    // reset to '=' so the SPA + the backend agree the row is valid.
    operator: newItem
      && previousItem
      && newItem.dataType !== previousItem.dataType
      && !operatorsForDataType(newItem.dataType).includes(row.operator)
      ? '='
      : row.operator,
  }
  emitUpdate()
}

function onOperatorChange(index: number, op: string): void {
  const row = rows.value[index]
  const next: DatasetFilterDto = {
    ...row,
    operator: op as FilterOperator,
  }
  // Reset value/values when the row shape changes so we don't carry
  // a stale scalar into a list-op or vice versa.
  if (UNARY_OPERATORS.includes(next.operator)) {
    next.value = null
    next.values = undefined
  } else if (LIST_OPERATORS.includes(next.operator)) {
    next.value = null
    next.values = next.values ?? []
  } else if (next.operator === 'between') {
    next.value = null
    next.values = [
      next.values?.[0] ?? '',
      next.values?.[1] ?? '',
    ]
  } else {
    next.value = next.value ?? ''
    next.values = undefined
  }
  rows.value[index] = next
  emitUpdate()
}

function onValueChange(index: number, value: string): void {
  rows.value[index] = { ...rows.value[index], value }
  emitUpdate()
}

function onListValuesChange(index: number, raw: string): void {
  // Comma-separated; trim + drop empty entries so the backend sees a
  // non-empty list (it 400s on an empty `in` list).
  const values = raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
  rows.value[index] = { ...rows.value[index], values, value: null }
  emitUpdate()
}

function onBetweenLowChange(index: number, low: string): void {
  const row = rows.value[index]
  const hi = row.values?.[1] ?? ''
  rows.value[index] = { ...row, values: [low, hi], value: null }
  emitUpdate()
}

function onBetweenHighChange(index: number, high: string): void {
  const row = rows.value[index]
  const lo = row.values?.[0] ?? ''
  rows.value[index] = { ...row, values: [lo, high], value: null }
  emitUpdate()
}

function operatorsFor(row: DatasetFilterDto): FilterOperator[] {
  const item = lookupItem(row.itemOid)
  if (!item) return ['=', '!=', 'in', 'is-null', 'not-null']
  return operatorsForDataType(item.dataType)
}

function valueInputType(row: DatasetFilterDto): 'text' | 'date' | 'number' {
  const item = lookupItem(row.itemOid)
  if (!item) return 'text'
  const t = item.dataType.toLowerCase()
  if (t === 'date' || t === 'partial_date') return 'date'
  if (t === 'integer' || t === 'floating') return 'number'
  return 'text'
}
</script>

<template>
  <div class="space-y-3">
    <div
      v-for="(row, index) in rows"
      :key="index"
      class="grid grid-cols-12 gap-2 items-end p-3 rounded-md border border-slate-200 bg-slate-50/40"
      :data-row-index="index"
      :data-testid="`filter-row-${index}`"
    >
      <!-- Item picker -->
      <div class="col-span-4">
        <FieldLabel :for="`filter-item-${index}`">{{ t('filters.itemLabel') }}</FieldLabel>
        <SelectInput
          :id="`filter-item-${index}`"
          :model-value="row.itemOid"
          @update:model-value="(v) => onItemChange(index, String(v))"
        >
          <option v-for="item in selectedItems" :key="item.oid" :value="item.oid">
            {{ item.label || item.oid }}
          </option>
        </SelectInput>
      </div>

      <!-- Operator picker -->
      <div class="col-span-3">
        <FieldLabel :for="`filter-op-${index}`">{{ t('filters.operatorLabel') }}</FieldLabel>
        <SelectInput
          :id="`filter-op-${index}`"
          :model-value="row.operator"
          @update:model-value="(v) => onOperatorChange(index, String(v))"
        >
          <option v-for="op in operatorsFor(row)" :key="op" :value="op">
            {{ t(`filters.operators.${op}`) }}
          </option>
        </SelectInput>
      </div>

      <!-- Value input(s) — shape depends on the operator -->
      <div class="col-span-4">
        <template v-if="UNARY_OPERATORS.includes(row.operator)">
          <div class="text-xs text-slate-500 italic pt-5">
            {{ t('filters.noValueRequired') }}
          </div>
        </template>
        <template v-else-if="LIST_OPERATORS.includes(row.operator)">
          <FieldLabel :for="`filter-values-${index}`">{{ t('filters.valuesLabel') }}</FieldLabel>
          <TextInput
            :id="`filter-values-${index}`"
            :model-value="(row.values ?? []).join(', ')"
            :placeholder="t('filters.valuesPlaceholder')"
            @update:model-value="(v) => onListValuesChange(index, v)"
          />
        </template>
        <template v-else-if="row.operator === 'between'">
          <FieldLabel :for="`filter-low-${index}`">{{ t('filters.betweenLabel') }}</FieldLabel>
          <div class="flex items-center gap-2">
            <TextInput
              :id="`filter-low-${index}`"
              :type="valueInputType(row)"
              :model-value="row.values?.[0] ?? ''"
              @update:model-value="(v) => onBetweenLowChange(index, v)"
            />
            <span class="text-xs text-slate-500">{{ t('filters.betweenAnd') }}</span>
            <TextInput
              :id="`filter-high-${index}`"
              :type="valueInputType(row)"
              :model-value="row.values?.[1] ?? ''"
              @update:model-value="(v) => onBetweenHighChange(index, v)"
            />
          </div>
        </template>
        <template v-else>
          <FieldLabel :for="`filter-value-${index}`">{{ t('filters.valueLabel') }}</FieldLabel>
          <TextInput
            :id="`filter-value-${index}`"
            :type="valueInputType(row)"
            :model-value="row.value ?? ''"
            @update:model-value="(v) => onValueChange(index, v)"
          />
        </template>
      </div>

      <!-- Remove -->
      <div class="col-span-1 flex justify-end">
        <button
          type="button"
          class="px-2 py-2 text-rose-600 hover:bg-rose-50 rounded-md muw-focus"
          :aria-label="t('filters.removeRowAria', { n: index + 1 })"
          :data-testid="`filter-remove-${index}`"
          @click="removeRow(index)"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <polyline points="3 6 5 6 21 6" />
            <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
          </svg>
        </button>
      </div>
    </div>

    <div v-if="rows.length === 0" class="text-xs text-slate-500 italic">
      {{ t('filters.emptyHint') }}
    </div>

    <div class="flex justify-between items-center">
      <button
        type="button"
        class="px-3 py-1.5 text-sm rounded-md border border-muw-blue text-muw-blue hover:bg-muw-blue-50 muw-focus"
        data-testid="filter-add"
        :disabled="selectedItems.length === 0"
        @click="addRow"
      >
        {{ t('filters.addRow') }}
      </button>

      <div
        v-if="rows.length > 0"
        class="text-xs text-slate-600"
        aria-live="polite"
        data-testid="filter-preview"
      >
        <template v-if="previewLoading">{{ t('filters.previewLoading') }}</template>
        <template v-else-if="previewError">
          <span class="text-rose-600">{{ previewError }}</span>
        </template>
        <template v-else-if="preview">
          {{
            t('filters.previewLine', {
              matchingSubjects: preview.matchingSubjects,
              totalSubjects: preview.totalSubjects,
              matchingCrfs: preview.matchingCrfs,
              totalCrfs: preview.totalCrfs,
            })
          }}
        </template>
      </div>
    </div>
  </div>
</template>
