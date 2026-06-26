<script setup lang="ts">
/**
 * nAMD workspace — segmentation cards (IRF / SRF / PED).
 *
 * Three side-by-side cards summarising the current visit's fluid
 * biomarker volumes — each shows the current value, a swatch, and a
 * delta chip referencing the previous visit. Used by Overview tab
 * + Viewer tab side panel.
 *
 * Rising delta is bad (coral) for fluid biomarkers; the
 * {@link DeltaChip} primitive enforces the {@code badUp} direction.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import FluidDot from './primitives/FluidDot.vue'
import DeltaChip from './primitives/DeltaChip.vue'
import type { FluidKey } from '../fluid'
import type { NamdVisit } from '../types'

interface Props {
  current: NamdVisit | null
  prev: NamdVisit | null
}

const props = defineProps<Props>()
const { t } = useI18n()

const KEYS: FluidKey[] = ['IRF', 'SRF', 'PED']
const ACCESSORS: Record<FluidKey, (v: NamdVisit) => number> = {
  IRF: (v) => v.irf,
  SRF: (v) => v.srf,
  PED: (v) => v.ped,
}

const rows = computed(() =>
  KEYS.map((k) => {
    const cur = props.current ? ACCESSORS[k](props.current) : null
    const prevValue = props.prev ? ACCESSORS[k](props.prev) : null
    const delta = cur != null && prevValue != null ? cur - prevValue : null
    return { k, cur, prev: prevValue, delta }
  }),
)
</script>

<template>
  <div
    data-testid="namd-seg-cards"
    class="grid grid-cols-3 gap-2.5"
  >
    <div
      v-for="row in rows"
      :key="row.k"
      :data-testid="`namd-seg-card-${row.k}`"
      class="rounded-md border border-slate-100 bg-slate-50 p-2.5"
    >
      <div class="flex items-center gap-1.5">
        <FluidDot :k="row.k" :size="10" />
        <span class="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          {{ row.k }}
        </span>
      </div>
      <!-- Number + unit (nL) share the same baseline; delta chip floats right. -->
      <div class="mt-1 flex items-baseline justify-between gap-2">
        <span class="inline-flex items-baseline gap-1 tabular-nums">
          <span class="text-[20px] font-semibold leading-none text-slate-900">
            <template v-if="row.cur != null">{{ row.cur }}</template>
            <template v-else>—</template>
          </span>
          <span class="text-[11px] font-medium text-slate-500 uppercase tracking-wider">
            nL
          </span>
        </span>
        <DeltaChip :value="row.delta" direction="badUp" :unit="' nL'" />
      </div>
      <!-- 2026-06-23 — break-words + leading-tight keeps the long
           German labels (e.g. "Pigmentepithelabhebung") inside the
           card. Without the wrap rule, the 25-char term overran the
           card right edge on the OCT-Viewer side panel. -->
      <div class="mt-0.5 text-[11px] text-slate-400 leading-tight break-words">
        {{ t(`studyModules.namd.fluid.${row.k}.long`) }}
      </div>
    </div>
  </div>
</template>
