<script setup lang="ts">
/**
 * nAMD workspace — fluid legend.
 *
 * Three rows mapping each biomarker swatch to its long-form clinical
 * name. Used by Overview tab + OCT-Viewer side panel.
 */
import { useI18n } from 'vue-i18n'
import FluidDot from './primitives/FluidDot.vue'
import { type FluidKey } from '../fluid'

const KEYS: FluidKey[] = ['IRF', 'SRF', 'PED']
const { t } = useI18n()
</script>

<template>
  <div data-testid="namd-fluid-legend" class="space-y-2.5">
    <div
      v-for="k in KEYS"
      :key="k"
      class="flex items-center gap-2.5"
      :data-testid="`namd-fluid-legend-row-${k}`"
    >
      <FluidDot :k="k" :size="14" />
      <div>
        <div class="text-[13px] font-semibold text-slate-800">{{ k }}</div>
        <div class="text-[11px] text-slate-400">
          {{ t(`studyModules.namd.fluid.${k}.long`) }}
        </div>
      </div>
    </div>
  </div>
</template>
