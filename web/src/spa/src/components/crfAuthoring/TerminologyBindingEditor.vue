<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { AutocompleteBinding, AutocompleteFill, TerminologySystem } from '@/stores/crfAuthoring'

/**
 * #26 (2026-08-12) — editor for a text field's opt-in terminology
 * autocomplete binding. Reusable for a flat item and for a repeating-table
 * column; the caller supplies the list of sibling fields a fill rule can
 * target ({@link targets}) so the same UI serves both.
 *
 * <p>Emits a fresh {@link AutocompleteBinding} (or {@code undefined} to
 * disable) — never mutates the prop, so the parent stays the source of
 * truth and undo/reorder behaviours hold.
 */
interface TargetOption { key: string; label: string }

interface Props {
  binding: AutocompleteBinding | undefined
  /** Sibling fields a fill rule may write into (excluding this field). */
  targets: TargetOption[]
  idPrefix: string
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:binding': [binding: AutocompleteBinding | undefined] }>()

const { t } = useI18n()

/** Known catalogues. Loose enough to add more without touching this list. */
const SYSTEMS: ReadonlyArray<{ value: TerminologySystem; labelKey: string }> = [
  { value: 'icd10gm', labelKey: 'crfAuthoring.canvas.terminology.system.icd10gm' },
  { value: 'medication', labelKey: 'crfAuthoring.canvas.terminology.system.medication' },
]

/**
 * The flat properties each catalogue exposes on a search hit (mirrors the
 * backend {@code TerminologyIngestService} profile). Surfaced in the editor
 * so the operator can SEE + PICK a fill-map source field instead of guessing
 * the name. ICD-10-GM diagnoses carry no auto-fillable satellite fields.
 */
const PROPERTIES_BY_SYSTEM: Record<string, string[]> = {
  medication: ['strength', 'unit', 'form', 'atc', 'substance', 'name'],
  icd10gm: [],
}
const availableProps = computed<string[]>(() =>
  props.binding ? (PROPERTIES_BY_SYSTEM[props.binding.system] ?? []) : [],
)

const enabled = computed(() => props.binding != null)

function onToggle(ev: Event): void {
  const on = (ev.target as HTMLInputElement).checked
  emit('update:binding', on ? { system: 'icd10gm', fills: [] } : undefined)
}

function onSystem(ev: Event): void {
  if (!props.binding) return
  emit('update:binding', { ...props.binding, system: (ev.target as HTMLSelectElement).value })
}

function updateFills(fills: AutocompleteFill[]): void {
  if (!props.binding) return
  emit('update:binding', { ...props.binding, fills })
}

function addFill(): void {
  if (!props.binding) return
  const firstTarget = props.targets[0]?.key ?? ''
  updateFills([...props.binding.fills, { fromProperty: '', toKey: firstTarget }])
}

function setFillProperty(i: number, value: string): void {
  if (!props.binding) return
  updateFills(props.binding.fills.map((f, idx) => (idx === i ? { ...f, fromProperty: value } : f)))
}

function setFillTarget(i: number, value: string): void {
  if (!props.binding) return
  updateFills(props.binding.fills.map((f, idx) => (idx === i ? { ...f, toKey: value } : f)))
}

function removeFill(i: number): void {
  if (!props.binding) return
  updateFills(props.binding.fills.filter((_, idx) => idx !== i))
}
</script>

<template>
  <div class="space-y-2" :data-testid="`${idPrefix}-terminology`">
    <label class="flex items-center gap-2 text-[11px] text-slate-700">
      <input
        type="checkbox"
        class="rounded border-slate-300"
        :checked="enabled"
        :data-testid="`${idPrefix}-terminology-toggle`"
        @change="onToggle"
      />
      <span>{{ t('crfAuthoring.canvas.terminology.enable') }}</span>
    </label>

    <template v-if="binding">
      <div>
        <label class="block text-[10.5px] font-semibold text-slate-600 mb-0.5">
          {{ t('crfAuthoring.canvas.terminology.sourceLabel') }}
        </label>
        <select
          class="w-full text-xs border-slate-200 rounded px-2 py-1 focus:border-muw-blue focus:outline-none focus:ring-1 focus:ring-muw-blue/40"
          :value="binding.system"
          :data-testid="`${idPrefix}-terminology-system`"
          @change="onSystem"
        >
          <option v-for="s in SYSTEMS" :key="s.value" :value="s.value">{{ t(s.labelKey) }}</option>
        </select>
        <!-- Discoverability: which fields a hit from this source actually
             carries, so the operator isn't guessing the fill property. -->
        <p class="text-[10px] text-slate-500 mt-1 leading-snug" :data-testid="`${idPrefix}-terminology-available`">
          <template v-if="availableProps.length > 0">
            {{ t('crfAuthoring.canvas.terminology.availableProps') }}
            <span class="font-mono text-slate-600">{{ availableProps.join(', ') }}</span>
          </template>
          <template v-else>{{ t('crfAuthoring.canvas.terminology.noProps') }}</template>
        </p>
      </div>

      <!-- Fill rules — copy a picked concept's property into a sibling
           field. Only shown when the source exposes properties (medication);
           ICD-10-GM has none, so the rule UI is hidden there. -->
      <div v-if="targets.length > 0 && availableProps.length > 0">
        <div class="flex items-center justify-between mb-1">
          <span class="text-[10.5px] font-semibold text-slate-600">
            {{ t('crfAuthoring.canvas.terminology.fillLabel') }}
          </span>
          <button
            type="button"
            class="text-[10px] text-muw-blue hover:underline"
            :data-testid="`${idPrefix}-terminology-add-fill`"
            @click="addFill"
          >{{ t('crfAuthoring.canvas.terminology.addFill') }}</button>
        </div>
        <p class="text-[10px] text-slate-500 mb-1.5 leading-snug">
          {{ t('crfAuthoring.canvas.terminology.fillHint') }}
        </p>
        <div
          v-for="(fill, i) in binding.fills"
          :key="i"
          class="flex items-center gap-1 mb-1"
          :data-testid="`${idPrefix}-terminology-fill-${i}`"
        >
          <select
            class="flex-1 min-w-0 text-[11px] font-mono border-slate-200 rounded px-1 py-1"
            :value="fill.fromProperty"
            @change="setFillProperty(i, ($event.target as HTMLSelectElement).value)"
          >
            <option value="" disabled>{{ t('crfAuthoring.canvas.terminology.propertyPlaceholder') }}</option>
            <option v-for="p in availableProps" :key="p" :value="p">{{ p }}</option>
          </select>
          <span class="text-slate-400 text-[11px]" aria-hidden="true">→</span>
          <select
            class="flex-1 min-w-0 text-[11px] border-slate-200 rounded px-1 py-1"
            :value="fill.toKey"
            @change="setFillTarget(i, ($event.target as HTMLSelectElement).value)"
          >
            <option v-for="tg in targets" :key="tg.key" :value="tg.key">{{ tg.label || tg.key }}</option>
          </select>
          <button
            type="button"
            class="shrink-0 text-slate-400 hover:text-rose-600 text-xs px-1"
            :aria-label="t('crfAuthoring.canvas.terminology.removeFill')"
            @click="removeFill(i)"
          >✕</button>
        </div>
      </div>
    </template>
  </div>
</template>
