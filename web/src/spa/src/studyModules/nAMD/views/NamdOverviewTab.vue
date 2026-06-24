<script setup lang="ts">
/**
 * nAMD workspace — Overview tab.
 *
 * Two-column layout:
 *   - Left (col-span-8): segmentation cards + fluid trend chart with
 *     hover-driven multi-metric tooltip.
 *   - Right (col-span-4): decision panel that surfaces the AI hint and
 *     captures the operator's Treat / Observe + interval choice.
 *
 * The Overview is the workspace's entry surface — must paint without
 * waiting for the Compare or Viewer bundles to load. Stays Chart.js-free
 * deliberately (see {@link NamdFluidTrendChart}'s rationale comment).
 */
import { useI18n } from 'vue-i18n'
import Card from '../components/primitives/Card.vue'
import NamdSegCards from '../components/NamdSegCards.vue'
import NamdFluidTrendChart from '../components/NamdFluidTrendChart.vue'
import NamdDecisionPanel from '../components/NamdDecisionPanel.vue'
import NamdFluidLegend from '../components/NamdFluidLegend.vue'
import type { NamdWorkspaceData } from '../types'

interface Props {
  data: NamdWorkspaceData
}
const props = defineProps<Props>()
const { t } = useI18n()
</script>

<template>
  <div data-testid="namd-overview-tab" class="grid grid-cols-12 gap-5">
    <div class="col-span-12 lg:col-span-8 space-y-5">
      <Card :title="t('studyModules.namd.overview.current')">
        <NamdSegCards :current="props.data.current" :prev="props.data.prev" />
        <div class="mt-3 rounded-md bg-slate-50 px-3.5 py-2.5 flex items-center justify-between">
          <span class="text-[12px] text-slate-500">
            {{ t('studyModules.namd.overview.crt') }}
          </span>
          <span class="text-[15px] font-semibold text-slate-900 tabular-nums">
            <template v-if="props.data.current">{{ props.data.current.crt }} µm</template>
            <template v-else>—</template>
          </span>
        </div>
      </Card>

      <Card :title="t('studyModules.namd.overview.trend')">
        <NamdFluidTrendChart :visits="props.data.visits" />
      </Card>
    </div>

    <div class="col-span-12 lg:col-span-4 space-y-5">
      <NamdDecisionPanel />
      <Card :title="t('studyModules.namd.overview.legend')">
        <NamdFluidLegend />
      </Card>
    </div>
  </div>
</template>
