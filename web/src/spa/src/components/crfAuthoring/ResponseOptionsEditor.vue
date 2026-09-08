<script setup lang="ts">
/**
 * Response-options editor for the CRF builder's properties rail.
 *
 * <p>Fills the gap that made the canvas unable to author any choice
 * item: {@code PropertiesRail} let the operator pick
 * {@code single-select} but offered no way to supply a single option,
 * so {@code item.responseSet} stayed null and the backend rejected the
 * save with "Response type 'single-select' requires at least one
 * option". A working editor existed ({@code ResponseSetPicker.vue} via
 * {@code ItemEditor.vue}) but both were orphaned when the legacy
 * authoring wizard was deleted.
 *
 * <p><b>Why not reuse {@code ResponseSetPicker} directly:</b> it is
 * sized for a full-width editor pane (two {@code px-3 py-2} inputs in a
 * 3-column grid) inside a {@code w-72} rail; its primary CTA is a
 * catalog POST rather than "give this item four options"; and it copies
 * props into local {@code draftOptions} exactly once, never re-syncing.
 * That last one is fatal here — the rail mounts ONE instance for the
 * whole canvas and the selection changes on every click, so the
 * operator would edit item B while looking at item A's rows.
 *
 * <p>This component is therefore <b>fully controlled</b>: it holds no
 * row state at all, deriving everything from {@code modelValue} and
 * emitting a complete replacement set on every edit. Selection changes
 * are then free.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type {
  AuthoringDataType,
  AuthoringResponseSet,
  AuthoringResponseType,
  InlineResponseSet,
  ResponseSetCatalogEntry,
  ResponseSetOption,
} from '@/stores/crfAuthoring'

interface Props {
  modelValue: AuthoringResponseSet
  responseType: AuthoringResponseType
  /** Drives numeric auto-values — see {@link autoValueFor}. */
  dataType: AuthoringDataType
  /** {@code store.responseSetCatalog}; powers the copy-in picker. */
  catalog?: ResponseSetCatalogEntry[]
  idPrefix?: string
}

const props = withDefaults(defineProps<Props>(), {
  catalog: () => [],
  idPrefix: 'crf-canvas-properties-options',
})

const emit = defineEmits<{
  'update:modelValue': [value: AuthoringResponseSet]
}>()

const { t } = useI18n()

const inline = computed<InlineResponseSet | null>(() =>
  props.modelValue != null && !('ref' in props.modelValue) ? props.modelValue : null,
)
const rows = computed<ResponseSetOption[]>(() => inline.value?.options ?? [])
const isRef = computed(() => props.modelValue != null && 'ref' in props.modelValue)

/** At least one row carries something worth sending. */
const hasUsableOption = computed(() =>
  rows.value.some((o) => o.text.trim() !== '' || o.value.trim() !== ''),
)

/** Catalog entries whose response type matches the item's. */
const catalogMatches = computed<ResponseSetCatalogEntry[]>(() =>
  (props.catalog ?? []).filter(
    (e) => String(e.responseType) === props.responseType && e.options.length > 0,
  ),
)

function cloneRows(): ResponseSetOption[] {
  return rows.value.map((o) => ({ ...o }))
}

function emitRows(next: ResponseSetOption[], label = inline.value?.label ?? ''): void {
  emit('update:modelValue', { type: props.responseType, label, options: next })
}

/**
 * Derive the stored wire value from the display text.
 *
 * <p>Numeric data types get a 1-based ordinal because
 * {@code CrfJsonValidator} requires every option value on an INT/REAL
 * item to parse as a number. Everything else gets a slugified upper-snake
 * token, which is what the seeded institutional CRFs use.
 */
function autoValueFor(text: string, index: number): string {
  if (props.dataType === 'INT' || props.dataType === 'REAL') return String(index + 1)
  return text
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
}

function onAddOption(): void {
  emitRows([...cloneRows(), { text: '', value: '' }])
}

function onRemoveOption(index: number): void {
  // Mirrors ResponseSetPicker: never leave a choice set with zero rows.
  if (rows.value.length <= 1) return
  emitRows(cloneRows().filter((_, i) => i !== index))
}

/**
 * Auto-fill the value while the operator hasn't taken it over — the same
 * "sticky override" contract the rail already uses for name → OID. Once
 * they type their own value it is never overwritten.
 */
function onOptionText(index: number, text: string): void {
  const next = cloneRows()
  const row = next[index]
  if (!row) return
  const previousAuto = autoValueFor(row.text, index)
  row.text = text
  if (row.value.trim() === '' || row.value === previousAuto) {
    row.value = autoValueFor(text, index)
  }
  emitRows(next)
}

function onOptionValue(index: number, value: string): void {
  const next = cloneRows()
  const row = next[index]
  if (!row) return
  row.value = value
  emitRows(next)
}

function onLabelInput(label: string): void {
  emitRows(cloneRows(), label)
}

/**
 * Copy a catalog entry's options in rather than linking to it. The
 * backend adapter does not implement {@code ResponseSet.ref} — a
 * {@code {ref:{label}}} payload silently degrades to a plain text field
 * — and a linked set would also be invisible/uneditable in the rail.
 * Copying keeps one editable representation and still dedupes into a
 * single {@code response_set} row when two items share a label.
 */
function onCopyFromCatalog(event: Event): void {
  const label = (event.target as HTMLSelectElement).value
  if (!label) return
  const entry = catalogMatches.value.find((e) => e.label === label)
  if (!entry) return
  emitRows(
    entry.options.map((o) => ({ ...o })),
    entry.label,
  )
}

/** Materialise a legacy/forked {@code ref} set so the rail can edit it. */
function onDetachRef(): void {
  if (!isRef.value) return
  const label = (props.modelValue as { ref: { label: string } }).ref.label
  const entry = (props.catalog ?? []).find((e) => e.label === label)
  emitRows(
    (entry?.options ?? [{ text: '', value: '' }]).map((o) => ({ ...o })),
    label,
  )
}
</script>

<template>
  <div :data-testid="`${idPrefix}-root`">
    <label class="block text-[11px] font-semibold text-slate-700 mb-0.5">
      {{ t('crfAuthoring.responseSetPicker.options') }}
    </label>

    <!-- Catalog-linked set: not editable in place. Offer a one-click
         conversion to inline options instead of a dead end. -->
    <template v-if="isRef">
      <p class="text-[10.5px] leading-snug text-slate-500">
        {{ t('crfAuthoring.canvas.properties.options.refNotice') }}
      </p>
      <button
        type="button"
        class="mt-1 text-[11px] text-muw-blue hover:underline focus:outline-none focus:ring-1 focus:ring-muw-blue/40 rounded"
        :data-testid="`${idPrefix}-detach`"
        @click="onDetachRef"
      >
        {{ t('crfAuthoring.canvas.properties.options.detach') }}
      </button>
    </template>

    <template v-else>
      <div class="grid grid-cols-[1fr_1fr_auto] gap-1 mb-0.5">
        <span class="text-[10px] font-medium text-slate-500">
          {{ t('crfAuthoring.responseSetPicker.optionText') }}
        </span>
        <span class="text-[10px] font-medium text-slate-500">
          {{ t('crfAuthoring.responseSetPicker.optionValue') }}
        </span>
        <span class="w-5"></span>
      </div>

      <div
        v-for="(opt, i) in rows"
        :key="`opt-${i}`"
        class="grid grid-cols-[1fr_1fr_auto] gap-1 mb-1"
        :data-testid="`${idPrefix}-row`"
      >
        <input
          type="text"
          class="w-full text-[11px] border-slate-200 rounded px-1.5 py-0.5 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
          :value="opt.text"
          :data-testid="`${idPrefix}-text-${i}`"
          @input="onOptionText(i, ($event.target as HTMLInputElement).value)"
        />
        <input
          type="text"
          class="w-full text-[11px] font-mono border-slate-200 rounded px-1.5 py-0.5 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
          :value="opt.value"
          :data-testid="`${idPrefix}-value-${i}`"
          @input="onOptionValue(i, ($event.target as HTMLInputElement).value)"
        />
        <button
          type="button"
          class="w-5 text-[13px] leading-none text-slate-400 hover:text-muw-coral-700 disabled:opacity-30 disabled:hover:text-slate-400 focus:outline-none focus:ring-1 focus:ring-muw-blue/40 rounded"
          :disabled="rows.length <= 1"
          :aria-label="t('common.remove')"
          :data-testid="`${idPrefix}-remove-${i}`"
          @click="onRemoveOption(i)"
        >
          ×
        </button>
      </div>

      <button
        type="button"
        class="text-[11px] text-muw-blue hover:underline focus:outline-none focus:ring-1 focus:ring-muw-blue/40 rounded"
        :data-testid="`${idPrefix}-add`"
        @click="onAddOption"
      >
        {{ t('crfAuthoring.responseSetPicker.addOption') }}
      </button>

      <p
        v-if="!hasUsableOption"
        class="mt-1 text-[10.5px] leading-snug text-amber-700"
        :data-testid="`${idPrefix}-warning`"
      >
        {{ t('crfAuthoring.responseSetPicker.optionsRequired') }}
      </p>

      <div v-if="catalogMatches.length > 0" class="mt-1.5">
        <select
          class="w-full text-[11px] border-slate-200 rounded px-1.5 py-0.5 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
          value=""
          :data-testid="`${idPrefix}-catalog`"
          @change="onCopyFromCatalog"
        >
          <option value="">{{ t('crfAuthoring.canvas.properties.options.fromCatalog') }}</option>
          <option v-for="entry in catalogMatches" :key="entry.label" :value="entry.label">
            {{ entry.label }} ({{ entry.options.length }})
          </option>
        </select>
      </div>

      <div class="mt-1.5">
        <label class="block text-[10px] font-medium text-slate-500 mb-0.5">
          {{ t('crfAuthoring.responseSetPicker.label') }}
        </label>
        <input
          type="text"
          class="w-full text-[11px] font-mono border-slate-200 rounded px-1.5 py-0.5 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
          :value="inline?.label ?? ''"
          :data-testid="`${idPrefix}-label`"
          @input="onLabelInput(($event.target as HTMLInputElement).value)"
        />
        <p class="text-[10px] text-slate-500 mt-0.5">
          {{ t('crfAuthoring.responseSetPicker.labelHelper') }}
        </p>
      </div>
    </template>
  </div>
</template>
