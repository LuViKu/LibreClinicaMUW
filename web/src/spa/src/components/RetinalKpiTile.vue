<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — KPI tile for the Retinal Metrics viewer.
 *
 * One numeric stat with a label + unit; an optional subtitle slot for
 * a denominator ("of total volume") or a secondary number. The {@link
 * tone} prop drives a coloured left-border that matches the biomarker
 * palette used by {@code FundusOverlay} + {@code PerBscanTrace} so the
 * viewer's three panels read as a single visual system:
 *
 *   - irf       → cyan       (Wave 2 IRF biomarker)
 *   - srf       → amber      (Wave 2 SRF biomarker)
 *   - ped       → magenta    (Wave 2 PED biomarker)
 *   - ga        → magenta-light (geographic atrophy area)
 *   - thickness → sky        (ONL / PR thickness)
 *   - neutral   → slate      (totals, voxel volumes, confidence, etc.)
 *
 * <p>The component is presentational only — no data fetching, no
 * formatting beyond the literal slot rendering. Callers format the
 * value into the string they want shown (mm³, mm², µm, etc.).
 */
import { computed } from 'vue'

export type RetinalKpiTone =
  | 'neutral'
  | 'irf'
  | 'srf'
  | 'ped'
  | 'ga'
  | 'thickness'

interface Props {
  label: string
  value: string | number
  unit: string
  subtitle?: string
  tone?: RetinalKpiTone
}

const props = withDefaults(defineProps<Props>(), {
  tone: 'neutral',
})

const toneClasses = computed<{ border: string; label: string }>(() => {
  switch (props.tone) {
    case 'irf':
      return { border: 'border-l-cyan-400', label: 'text-cyan-700' }
    case 'srf':
      return { border: 'border-l-amber-400', label: 'text-amber-700' }
    case 'ped':
      return { border: 'border-l-fuchsia-400', label: 'text-fuchsia-700' }
    case 'ga':
      return { border: 'border-l-pink-400', label: 'text-pink-700' }
    case 'thickness':
      return { border: 'border-l-sky-400', label: 'text-sky-700' }
    case 'neutral':
    default:
      return { border: 'border-l-slate-300', label: 'text-slate-600' }
  }
})
</script>

<template>
  <div
    class="bg-white border border-slate-200 border-l-4 rounded-muw px-4 py-3"
    :class="toneClasses.border"
    data-testid="retinal-kpi-tile"
  >
    <div
      class="text-[10px] uppercase tracking-wider font-semibold"
      :class="toneClasses.label"
    >
      {{ label }}
    </div>
    <div class="flex items-baseline gap-1.5 mt-1">
      <span class="text-xl font-semibold tabular-nums text-slate-900">{{ value }}</span>
      <span class="text-xs text-slate-500">{{ unit }}</span>
    </div>
    <div v-if="subtitle" class="text-[11px] text-slate-500 mt-0.5">{{ subtitle }}</div>
  </div>
</template>
