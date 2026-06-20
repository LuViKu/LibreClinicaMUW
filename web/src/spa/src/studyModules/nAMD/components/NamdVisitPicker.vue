<script setup lang="ts">
/**
 * nAMD workspace — visit picker.
 *
 * Port of {@code VisitPicker} from namd-compare.jsx. Renders a horizontal
 * strip of visit chips; the active chip drives the v-model. Used by
 * Compare tab to pick the {@code A} and {@code B} visit panes.
 */
import type { NamdVisit } from '../types'

interface Props {
  modelValue: string | null
  visits: NamdVisit[]
  /** Disable / hide a visit id (typically the OTHER pane's selection). */
  excludeId?: string | null
  /** Test-id suffix — A or B. */
  variant?: 'A' | 'B'
}

const props = withDefaults(defineProps<Props>(), { excludeId: null, variant: 'A' })
const emit = defineEmits<{ 'update:modelValue': [id: string] }>()
</script>

<template>
  <div
    :data-testid="`namd-visit-picker-${props.variant}`"
    class="flex flex-wrap gap-1.5"
  >
    <button
      v-for="visit in visits"
      :key="visit.id"
      type="button"
      :data-testid="`namd-visit-chip-${props.variant}-${visit.id}`"
      :aria-pressed="modelValue === visit.id"
      :disabled="visit.id === props.excludeId"
      class="px-2.5 py-1 rounded-md border text-xs font-medium transition"
      :class="
        modelValue === visit.id
          ? 'border-muw-blue bg-muw-blue text-white'
          : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed'
      "
      @click="emit('update:modelValue', visit.id)"
    >
      {{ visit.label }} · W{{ visit.week }}
    </button>
  </div>
</template>
