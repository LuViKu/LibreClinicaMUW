<script setup lang="ts">
import { useI18n } from 'vue-i18n'

import TerminologyBindingEditor from './TerminologyBindingEditor.vue'
import {
  newRepeatingTableColumn,
  type AutocompleteBinding,
  type RepeatingTableColumn,
  type RepeatingTableColumnType,
  type RepeatingTableSpec,
} from '@/stores/crfAuthoring'

/**
 * #26 (2026-08-12) — repeating-table authoring editor. The operator
 * defines columns (label + type), and any text column can opt into
 * terminology autocomplete with a property→column fill map. Emits a fresh
 * {@link RepeatingTableSpec} on every edit; never mutates the prop.
 */
interface Props {
  spec: RepeatingTableSpec
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:spec': [spec: RepeatingTableSpec] }>()

const { t } = useI18n()

const COLUMN_TYPES: ReadonlyArray<{ value: RepeatingTableColumnType; labelKey: string }> = [
  { value: 'text', labelKey: 'crfAuthoring.canvas.table.columnType.text' },
  { value: 'number', labelKey: 'crfAuthoring.canvas.table.columnType.number' },
  { value: 'date', labelKey: 'crfAuthoring.canvas.table.columnType.date' },
  { value: 'laterality', labelKey: 'crfAuthoring.canvas.table.columnType.laterality' },
]

function commit(next: Partial<RepeatingTableSpec>): void {
  emit('update:spec', { ...props.spec, ...next })
}

function updateColumns(columns: RepeatingTableColumn[]): void {
  commit({ columns })
}

function setColumn(i: number, patch: Partial<RepeatingTableColumn>): void {
  updateColumns(props.spec.columns.map((c, idx) => (idx === i ? { ...c, ...patch } : c)))
}

function onLabel(i: number, value: string): void {
  setColumn(i, { label: value })
}

function onType(i: number, value: string): void {
  const type = value as RepeatingTableColumnType
  // A non-text column can't host autocomplete — drop the binding on switch.
  const patch: Partial<RepeatingTableColumn> = { type }
  if (type !== 'text') patch.autocomplete = undefined
  setColumn(i, patch)
}

function onBinding(i: number, binding: AutocompleteBinding | undefined): void {
  setColumn(i, { autocomplete: binding })
}

function addColumn(): void {
  updateColumns([...props.spec.columns, newRepeatingTableColumn('')])
}

function removeColumn(i: number): void {
  if (props.spec.columns.length <= 1) return
  const removedKey = props.spec.columns[i]!.key
  // Prune any fill rule that targeted the removed column so no binding
  // points at a dangling key.
  const columns = props.spec.columns
    .filter((_, idx) => idx !== i)
    .map((c) =>
      c.autocomplete
        ? { ...c, autocomplete: { ...c.autocomplete, fills: c.autocomplete.fills.filter((f) => f.toKey !== removedKey) } }
        : c,
    )
  updateColumns(columns)
}

function onMinRows(value: string): void {
  const n = Math.max(0, Number(value) || 0)
  commit({ minRows: n })
}

function onMaxRows(value: string): void {
  const n = Math.max(1, Number(value) || 1)
  commit({ maxRows: n })
}

/** Sibling columns a fill rule on column {@code i} may target (all but itself). */
function targetsFor(i: number): { key: string; label: string }[] {
  return props.spec.columns
    .filter((_, idx) => idx !== i)
    .map((c) => ({ key: c.key, label: c.label }))
}
</script>

<template>
  <div class="space-y-3" data-testid="crf-canvas-table-editor">
    <div class="flex items-center gap-2">
      <label class="text-[10.5px] font-semibold text-slate-600">{{ t('crfAuthoring.canvas.table.minRows') }}</label>
      <input
        type="number"
        min="0"
        class="w-16 text-xs border-slate-200 rounded px-2 py-1"
        :value="spec.minRows"
        data-testid="crf-canvas-table-minRows"
        @input="onMinRows(($event.target as HTMLInputElement).value)"
      />
      <label class="text-[10.5px] font-semibold text-slate-600 ml-2">{{ t('crfAuthoring.canvas.table.maxRows') }}</label>
      <input
        type="number"
        min="1"
        class="w-16 text-xs border-slate-200 rounded px-2 py-1"
        :value="spec.maxRows"
        data-testid="crf-canvas-table-maxRows"
        @input="onMaxRows(($event.target as HTMLInputElement).value)"
      />
    </div>

    <div
      v-for="(col, i) in spec.columns"
      :key="col.key"
      class="border border-slate-200 rounded p-2 space-y-2 bg-white"
      :data-testid="`crf-canvas-table-column-${i}`"
    >
      <div class="flex items-center gap-1">
        <input
          type="text"
          class="flex-1 min-w-0 text-xs border-slate-200 rounded px-2 py-1"
          :placeholder="t('crfAuthoring.canvas.table.columnLabelPlaceholder')"
          :value="col.label"
          :data-testid="`crf-canvas-table-column-label-${i}`"
          @input="onLabel(i, ($event.target as HTMLInputElement).value)"
        />
        <button
          type="button"
          class="shrink-0 text-slate-400 hover:text-rose-600 text-xs px-1 disabled:opacity-30"
          :disabled="spec.columns.length <= 1"
          :aria-label="t('crfAuthoring.canvas.table.removeColumn')"
          :data-testid="`crf-canvas-table-remove-column-${i}`"
          @click="removeColumn(i)"
        >✕</button>
      </div>
      <select
        class="w-full text-xs border-slate-200 rounded px-2 py-1"
        :value="col.type"
        :data-testid="`crf-canvas-table-column-type-${i}`"
        @change="onType(i, ($event.target as HTMLSelectElement).value)"
      >
        <option v-for="ct in COLUMN_TYPES" :key="ct.value" :value="ct.value">{{ t(ct.labelKey) }}</option>
      </select>

      <TerminologyBindingEditor
        v-if="col.type === 'text'"
        :binding="col.autocomplete"
        :targets="targetsFor(i)"
        :id-prefix="`crf-canvas-table-column-${i}`"
        @update:binding="(b: AutocompleteBinding | undefined) => onBinding(i, b)"
      />
    </div>

    <button
      type="button"
      class="w-full text-[11px] text-muw-blue border border-dashed border-muw-blue/40 rounded px-2 py-1.5 hover:bg-muw-blue/5"
      data-testid="crf-canvas-table-add-column"
      @click="addColumn"
    >＋ {{ t('crfAuthoring.canvas.table.addColumn') }}</button>
  </div>
</template>
