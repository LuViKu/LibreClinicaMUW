<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import TerminologyAutocomplete, { type TermPick } from '@/components/TerminologyAutocomplete.vue'
import { LATERALITY_OPTIONS, type RepeatingTableSpec } from '@/stores/crfAuthoring'

/**
 * #26 (2026-08-12) — repeating-table renderer (preview + live entry).
 *
 * Renders an operator-defined table: one column per {@link RepeatingTableSpec.columns}
 * entry, the clinician adds/removes rows. A text column may opt into
 * terminology autocomplete; picking a suggestion fans the concept's
 * properties into sibling cells of the SAME row via the column's fill map
 * (the 2026-08-12 explicit property→field design).
 *
 * <p>Model value is an array of rows, each a {@code Record<columnKey, string>}.
 * Kept deliberately dumb: reads {@code modelValue}, emits a fresh array on
 * every edit; the caller funnels it into its store (preview or entry).
 */
type Row = Record<string, string>

interface Props {
  spec: RepeatingTableSpec
  idPrefix: string
  modelValue: Row[] | null | undefined
  disabled?: boolean
}
const props = withDefaults(defineProps<Props>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [rows: Row[]] }>()

const { t } = useI18n()

const rows = computed<Row[]>(() => {
  const v = props.modelValue
  if (Array.isArray(v) && v.length > 0) return v
  // Seed the operator-declared minimum so the table never renders empty.
  const min = Math.max(props.spec.minRows ?? 0, 0)
  return Array.from({ length: Math.max(min, 1) }, () => blankRow())
})

function blankRow(): Row {
  const r: Row = {}
  for (const c of props.spec.columns) r[c.key] = ''
  return r
}

function commit(next: Row[]): void {
  emit('update:modelValue', next)
}

function setCell(rowIndex: number, key: string, value: string): void {
  const next = rows.value.map((r, i) => (i === rowIndex ? { ...r, [key]: value } : { ...r }))
  // Hand-editing a diagnosis field invalidates the code we remembered from the
  // last pick (see onPick) — otherwise a stale eye code would keep laterality on.
  const col = props.spec.columns.find((c) => c.key === key)
  if (col?.autocomplete?.system === 'icd10gm') delete next[rowIndex]![dxCodeKey(key)]
  commit(next)
}

/**
 * Distribute a picked concept across the row: the picked cell gets the
 * "CODE — Display" value, and each fill rule copies a concept property
 * into its target sibling cell (same row) when the property is present.
 */
function onPick(rowIndex: number, columnKey: string, pick: TermPick): void {
  const col = props.spec.columns.find((c) => c.key === columnKey)
  const next = rows.value.map((r, i) => (i === rowIndex ? { ...r } : { ...r }))
  const row = next[rowIndex]!
  row[columnKey] = pick.value
  for (const fill of col?.autocomplete?.fills ?? []) {
    const v = pick.properties[fill.fromProperty]
    if (v != null && v !== '') row[fill.toKey] = v
  }
  // Eye-diagnosis gating. Remember the picked concept's ICD code in a hidden
  // per-row slot so laterality can be gated even without a visible ICD-Code
  // column, then clear laterality cells if the diagnosis is NOT an eye code.
  if (col?.autocomplete?.system === 'icd10gm') {
    row[dxCodeKey(columnKey)] = pick.code
    if (!isEyeIcdCode(pick.code)) {
      for (const c of props.spec.columns) if (c.type === 'laterality') row[c.key] = ''
    }
  }
  commit(next)
}

/** Hidden per-row key remembering a diagnosis column's last picked ICD code.
 *  Never a real column key (those are `col_N`); preview-only — persistence
 *  walks spec.columns, so these never leak into a payload. */
function dxCodeKey(columnKey: string): string {
  return `__dxcode__${columnKey}`
}

/**
 * ICD-10 chapter VII "Diseases of the eye and adnexa" = H00–H59 (H60+ is the
 * ear, so a leading "H" alone is not enough). Matches "H40", "H40.0", …
 */
function isEyeIcdCode(code: string | undefined | null): boolean {
  return /^H[0-5]\d/i.test((code ?? '').trim())
}

/** Shape of any ICD-10 code (letter + two digits): H40, E11, H66, I10, … —
 *  used to tell "a coded, non-eye diagnosis" apart from free text. */
function isIcdCodeLike(v: string): boolean {
  return /^[A-Z]\d\d/i.test(v.trim())
}

/** Does this table carry a diagnosis (ICD-10) autocomplete column? */
const hasDiagnosisColumn = computed(() =>
  props.spec.columns.some((c) => c.type === 'text' && c.autocomplete?.system === 'icd10gm'),
)

/**
 * Is a laterality cell active for this row? On a non-diagnosis table (e.g. a
 * per-eye medication) always — a topical drug still needs OD/OS. On a diagnosis
 * table, gate on the ICD code, which may come from a filled ICD-Code column OR
 * the code we remembered when the diagnosis was picked (so the column is
 * optional):
 *   - an eye code (H00–H59) anywhere → active;
 *   - a coded but non-eye diagnosis → inactive;
 *   - no code at all (diagnosis typed freehand, never picked) → active once
 *     some diagnosis text is entered, since we can't classify it — better to
 *     offer laterality than to block it.
 */
function lateralityActive(row: Row): boolean {
  if (props.disabled) return false
  if (!hasDiagnosisColumn.value) return true
  const values = Object.values(row).map((v) => String(v ?? '').trim())
  if (values.some(isEyeIcdCode)) return true
  if (values.some(isIcdCodeLike)) return false
  return props.spec.columns.some(
    (c) => c.autocomplete?.system === 'icd10gm' && (row[c.key] ?? '').trim() !== '',
  )
}

const canAddRow = computed(() => {
  const max = props.spec.maxRows ?? 0
  return max <= 0 || rows.value.length < max
})
function canRemoveRow(): boolean {
  return rows.value.length > Math.max(props.spec.minRows ?? 0, 1)
}

function addRow(): void {
  if (!canAddRow.value || props.disabled) return
  commit([...rows.value.map((r) => ({ ...r })), blankRow()])
}
function removeRow(rowIndex: number): void {
  if (!canRemoveRow() || props.disabled) return
  commit(rows.value.filter((_, i) => i !== rowIndex).map((r) => ({ ...r })))
}

function cellId(rowIndex: number, key: string): string {
  return `${props.idPrefix}-r${rowIndex}-${key}`
}
</script>

<template>
  <div class="overflow-x-auto" data-testid="repeating-table">
    <table class="w-full text-sm border-separate border-spacing-0">
      <thead>
        <tr>
          <th
            v-for="col in spec.columns"
            :key="col.key"
            class="text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500 px-2 py-1.5 border-b border-slate-200"
          >
            {{ col.label || t('crfAuthoring.canvas.table.untitledColumn') }}
          </th>
          <th class="w-10 border-b border-slate-200" aria-hidden="true"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, rowIndex) in rows" :key="rowIndex" data-testid="repeating-table-row">
          <td v-for="col in spec.columns" :key="col.key" class="px-2 py-1.5 align-top">
            <TerminologyAutocomplete
              v-if="col.type === 'text' && col.autocomplete"
              :id="cellId(rowIndex, col.key)"
              :model-value="row[col.key] ?? ''"
              :system="col.autocomplete.system"
              :disabled="disabled"
              @update:model-value="(v: string) => setCell(rowIndex, col.key, v)"
              @pick="(p: TermPick) => onPick(rowIndex, col.key, p)"
            />
            <select
              v-else-if="col.type === 'laterality'"
              :id="cellId(rowIndex, col.key)"
              :value="lateralityActive(row) ? (row[col.key] ?? '') : ''"
              :disabled="!lateralityActive(row)"
              :title="lateralityActive(row) ? undefined : t('crfAuthoring.canvas.table.lateralityEyeOnly')"
              class="w-full px-2.5 py-1.5 border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus disabled:bg-slate-100 disabled:text-slate-400 disabled:cursor-not-allowed"
              @change="setCell(rowIndex, col.key, ($event.target as HTMLSelectElement).value)"
            >
              <option value="">—</option>
              <option v-for="o in LATERALITY_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
            <input
              v-else
              :id="cellId(rowIndex, col.key)"
              :value="row[col.key] ?? ''"
              :type="col.type === 'number' ? 'number' : col.type === 'date' ? 'date' : 'text'"
              :disabled="disabled"
              class="w-full px-2.5 py-1.5 border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
              @input="setCell(rowIndex, col.key, ($event.target as HTMLInputElement).value)"
            />
          </td>
          <td class="px-1 py-1.5 align-top text-center">
            <button
              type="button"
              class="text-slate-400 hover:text-rose-600 disabled:opacity-30 disabled:hover:text-slate-400"
              :disabled="disabled || !canRemoveRow()"
              :aria-label="t('crfAuthoring.canvas.table.removeRow')"
              data-testid="repeating-table-remove-row"
              @click="removeRow(rowIndex)"
            >✕</button>
          </td>
        </tr>
      </tbody>
    </table>
    <button
      type="button"
      class="mt-2 inline-flex items-center gap-1.5 px-2.5 py-1.5 text-xs text-muw-blue border border-dashed border-muw-blue/40 rounded-md hover:bg-muw-blue/5 disabled:opacity-40"
      :disabled="disabled || !canAddRow"
      data-testid="repeating-table-add-row"
      @click="addRow"
    >
      <span aria-hidden="true">＋</span>
      {{ t('crfAuthoring.canvas.table.addRow') }}
    </button>
  </div>
</template>
