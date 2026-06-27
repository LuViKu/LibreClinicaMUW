<script setup lang="ts">
/**
 * nAMD workspace — compare delta bar.
 *
 * Port of {@code CompareDeltaBar} from namd-compare.jsx. Five-cell strip
 * showing the per-metric delta between the {@code A} and {@code B} visits:
 *   - IRF / SRF / PED / CRT (badUp: rising is bad → coral)
 *   - BCVA letters (goodUp: rising is good → teal on rise)
 *
 * Mounted as the masthead of the Compare tab's two-pane viewer.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DeltaChip from './primitives/DeltaChip.vue'
import type { NamdVisit } from '../types'

interface Props {
  a: NamdVisit | null
  b: NamdVisit | null
}

const props = defineProps<Props>()
const { t } = useI18n()

interface Cell {
  key: 'IRF' | 'SRF' | 'PED' | 'CST' | 'BCVA'
  unit: string
  direction: 'badUp' | 'goodUp'
  a: number | null
  b: number | null
  delta: number | null
}

const cells = computed<Cell[]>(() => {
  const A = props.a
  const B = props.b
  function pair(get: (v: NamdVisit) => number): { a: number | null; b: number | null; delta: number | null } {
    if (!A || !B) return { a: A ? get(A) : null, b: B ? get(B) : null, delta: null }
    const av = get(A)
    const bv = get(B)
    return { a: av, b: bv, delta: bv - av }
  }
  return [
    { key: 'IRF', unit: ' nL', direction: 'badUp', ...pair((v) => v.irf) },
    { key: 'SRF', unit: ' nL', direction: 'badUp', ...pair((v) => v.srf) },
    { key: 'PED', unit: ' nL', direction: 'badUp', ...pair((v) => v.ped) },
    { key: 'CST', unit: ' µm', direction: 'badUp', ...pair((v) => v.crt) },
    { key: 'BCVA', unit: ' L', direction: 'goodUp', ...pair((v) => v.bcva) },
  ]
})
</script>

<template>
  <div
    data-testid="namd-compare-delta-bar"
    class="grid grid-cols-5 gap-2"
  >
    <div
      v-for="cell in cells"
      :key="cell.key"
      :data-testid="`namd-compare-delta-${cell.key}`"
      class="rounded-md border border-slate-100 bg-white p-2.5"
    >
      <div class="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
        {{ cell.key === 'BCVA' ? t('studyModules.namd.compare.bcva') : cell.key }}
      </div>
      <div class="mt-1 flex items-baseline justify-between">
        <span class="text-[13px] font-medium tabular-nums text-slate-700">
          <template v-if="cell.a != null && cell.b != null">
            {{ cell.a }} <span class="text-slate-400">→</span> {{ cell.b }}
          </template>
          <template v-else>—</template>
        </span>
        <DeltaChip :value="cell.delta" :direction="cell.direction" :unit="cell.unit" />
      </div>
    </div>
  </div>
</template>
