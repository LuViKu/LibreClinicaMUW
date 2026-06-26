<script setup lang="ts">
/**
 * nAMD workspace — dropdown visit picker (T&E casebooks reach 20+ visits).
 *
 * Excluded visits (typically the OTHER pane's selection) render as disabled
 * options so the operator sees the full list but can't double-pick.
 */
import { formatDate } from '@/lib/dateFormat'
import type { NamdVisit } from '../types'

interface Props {
  modelValue: string | null
  visits: NamdVisit[]
  /** Disable a visit id in the option list (typically the OTHER pane's selection). */
  excludeId?: string | null
  /** Test-id suffix — A or B. */
  variant?: 'A' | 'B'
}

const props = withDefaults(defineProps<Props>(), { excludeId: null, variant: 'A' })
const emit = defineEmits<{ 'update:modelValue': [id: string] }>()

function onChange(ev: Event): void {
  const v = (ev.target as HTMLSelectElement).value
  if (v) emit('update:modelValue', v)
}
</script>

<template>
  <div :data-testid="`namd-visit-picker-${props.variant}`" class="relative">
    <select
      :value="modelValue ?? ''"
      :data-testid="`namd-visit-select-${props.variant}`"
      class="w-full appearance-none rounded-md border border-slate-300 bg-white text-sm font-medium text-slate-700 px-3 py-2 pr-9 focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue/30 transition cursor-pointer"
      @change="onChange"
    >
      <option
        v-for="visit in visits"
        :key="visit.id"
        :value="visit.id"
        :disabled="visit.id === props.excludeId"
      >
        {{ visit.label }} · W{{ visit.week }}
        <template v-if="visit.date">— {{ formatDate(visit.date) }}</template>
      </option>
    </select>
    <span
      class="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400"
      aria-hidden="true"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="6 9 12 15 18 9" />
      </svg>
    </span>
  </div>
</template>
