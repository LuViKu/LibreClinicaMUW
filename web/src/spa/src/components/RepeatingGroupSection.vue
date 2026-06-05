<script setup lang="ts">
/**
 * Phase E.6 — repeating item group renderer.
 *
 * <p>Renders one {@code CrfItemGroup} as a table-of-rows. Each row
 * carries one input cell per item OID in the group, plus a Delete-row
 * button. Below the rows, an "Add row" button calls the store's
 * {@code addGroupRow} action; the button greys out when
 * {@code rows.length >= repeatMax}.
 *
 * <p>Per-cell input type follows the parent item's {@code dataType}.
 * The mapping mirrors the top-level section's renderer in
 * {@code CrfEntryView}: string → text, integer/real → number,
 * date → text-with-format-hint, select-one → native select.
 * select-multi + file types are NOT currently rendered inside repeating
 * groups; the playbook scopes those to top-level items first, and
 * the SPA validates by warning at runtime instead of crashing.
 *
 * <p>This widget never mutates the store directly outside its bound
 * action emits — the parent (CrfEntryView) decides whether to wire the
 * row delete behind a confirm, which makes integration tests easier.
 */

import { computed } from 'vue'
import type { CrfItem, CrfItemGroup } from '@/types/crf'

interface Props {
  group: CrfItemGroup
  /** Item OID → schema item lookup; built once by the parent. */
  itemsByOid: Record<string, CrfItem>
  /** Read-only / locked state from the parent. */
  disabled?: boolean
  /** Currently-running add/delete; greys the action buttons. */
  busy?: boolean
  /** i18n labels — parent translates so the widget stays locale-agnostic. */
  addRowLabel: string
  deleteRowLabel: string
  deleteRowConfirm: string
  repeatMaxReachedLabel: string
  emptyLabel: string
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  busy: false,
})

const emit = defineEmits<{
  (e: 'add-row'): void
  (e: 'delete-row', rowOrdinal: number): void
  (
    e: 'set-value',
    payload: { rowOrdinal: number; itemOid: string; value: unknown },
  ): void
}>()

/** Items in render order — falls back to the group's declared item OIDs
 *  so the columns stay deterministic even if the parent's lookup table
 *  doesn't have every OID (defensive — the schema should match). */
const renderItems = computed<CrfItem[]>(() =>
  props.group.itemOids
    .map((oid) => props.itemsByOid[oid])
    .filter((it): it is CrfItem => Boolean(it)),
)

const canAddRow = computed<boolean>(
  () => !props.disabled && props.group.rows.length < props.group.repeatMax,
)

function inputId(rowOrdinal: number, itemOid: string): string {
  return `g-${props.group.oid}-r${rowOrdinal}-${itemOid}`
}

function onChangeValue(
  rowOrdinal: number,
  itemOid: string,
  rawTarget: EventTarget | null,
  item: CrfItem,
): void {
  const el = rawTarget as HTMLInputElement | HTMLSelectElement | null
  if (!el) return
  let value: unknown = el.value
  if (item.dataType === 'integer' || item.dataType === 'real') {
    value = el.value === '' ? null : Number(el.value)
  } else if (el.value === '') {
    value = null
  }
  emit('set-value', { rowOrdinal, itemOid, value })
}

function onDeleteRow(rowOrdinal: number): void {
  if (props.disabled || props.busy) return
  if (typeof window !== 'undefined' && !window.confirm(props.deleteRowConfirm)) return
  emit('delete-row', rowOrdinal)
}

function rawValueFor(rowOrdinal: number, itemOid: string): string {
  const row = props.group.rows.find((r) => r.ordinal === rowOrdinal)
  const v = row?.values[itemOid]
  return v == null ? '' : String(v)
}
</script>

<template>
  <section class="bg-white border border-slate-200 rounded-muw p-5">
    <header class="mb-3 flex items-baseline justify-between">
      <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
        {{ group.label }}
      </h2>
      <span class="text-[11px] text-slate-500">
        {{ group.rows.length }} / {{ group.repeatMax }}
      </span>
    </header>

    <p v-if="group.rows.length === 0" class="text-[11px] text-slate-500 italic mb-3">
      {{ emptyLabel }}
    </p>

    <div v-else class="overflow-x-auto">
      <table class="w-full text-xs">
        <thead>
          <tr class="text-left text-slate-500 border-b border-slate-200">
            <th class="w-10 py-1.5 pr-2 font-medium">#</th>
            <th
              v-for="item in renderItems"
              :key="item.oid"
              class="py-1.5 px-2 font-medium"
            >
              {{ item.label }}<span v-if="item.required" class="text-rose-600">&nbsp;*</span>
            </th>
            <th class="w-10 py-1.5 pl-2"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in group.rows"
            :key="row.ordinal"
            class="border-b border-slate-100"
          >
            <td class="py-1.5 pr-2 text-slate-500 align-top">{{ row.ordinal }}</td>
            <td
              v-for="item in renderItems"
              :key="item.oid"
              class="py-1.5 px-2 align-top"
            >
              <template v-if="item.dataType === 'select-one' && item.options">
                <select
                  :id="inputId(row.ordinal, item.oid)"
                  :value="rawValueFor(row.ordinal, item.oid)"
                  :disabled="disabled"
                  class="w-full px-2 py-1 border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
                  @change="onChangeValue(row.ordinal, item.oid, $event.target, item)"
                >
                  <option value="">—</option>
                  <option v-for="opt in item.options" :key="opt.code" :value="opt.code">
                    {{ opt.label }}
                  </option>
                </select>
              </template>

              <template v-else-if="item.dataType === 'integer' || item.dataType === 'real'">
                <input
                  :id="inputId(row.ordinal, item.oid)"
                  type="number"
                  :value="rawValueFor(row.ordinal, item.oid)"
                  :min="item.min"
                  :max="item.max"
                  :step="item.dataType === 'integer' ? 1 : 0.1"
                  :disabled="disabled"
                  class="w-full px-2 py-1 border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
                  @input="onChangeValue(row.ordinal, item.oid, $event.target, item)"
                />
              </template>

              <template v-else-if="item.dataType === 'date'">
                <input
                  :id="inputId(row.ordinal, item.oid)"
                  type="text"
                  placeholder="YYYY-MM-DD"
                  inputmode="numeric"
                  :value="rawValueFor(row.ordinal, item.oid)"
                  :disabled="disabled"
                  class="w-full px-2 py-1 border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
                  @input="onChangeValue(row.ordinal, item.oid, $event.target, item)"
                />
              </template>

              <template v-else>
                <input
                  :id="inputId(row.ordinal, item.oid)"
                  type="text"
                  :value="rawValueFor(row.ordinal, item.oid)"
                  :disabled="disabled"
                  class="w-full px-2 py-1 border border-slate-300 rounded-md focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus"
                  @input="onChangeValue(row.ordinal, item.oid, $event.target, item)"
                />
              </template>
            </td>
            <td class="py-1.5 pl-2 align-top">
              <button
                type="button"
                class="text-[11px] text-rose-700 hover:underline disabled:text-slate-300 disabled:no-underline"
                :disabled="disabled || busy"
                :aria-label="deleteRowLabel"
                @click="onDeleteRow(row.ordinal)"
              >
                {{ deleteRowLabel }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="mt-3 flex items-center gap-3">
      <button
        type="button"
        class="text-xs px-3 py-1.5 border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700 disabled:text-slate-300 disabled:bg-slate-50/50"
        :disabled="!canAddRow || busy"
        @click="emit('add-row')"
      >
        + {{ addRowLabel }}
      </button>
      <span v-if="!canAddRow && !disabled" class="text-[11px] text-amber-700">
        {{ repeatMaxReachedLabel }}
      </span>
    </div>
  </section>
</template>
