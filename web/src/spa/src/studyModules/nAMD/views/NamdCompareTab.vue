<script setup lang="ts">
/**
 * nAMD workspace — Compare tab.
 *
 * Two visit panes side-by-side. Each pane has:
 *   - A visit picker chip strip ({@link NamdVisitPicker}).
 *   - A summary line (label / week / total fluid).
 *
 * Above the panes sits {@link NamdCompareDeltaBar} — five cells showing
 * per-metric delta + direction. Below the panes the Slice 4 endpoint
 * ({@link RetinalVisitComparison}) renders against the {@code B} pane's
 * retinal job, when the visit has one.
 *
 * Defaults to A = previous, B = current — the operator's most common
 * starting question is "what changed at today's visit?".
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from '../components/primitives/Card.vue'
import NamdVisitPicker from '../components/NamdVisitPicker.vue'
import NamdCompareDeltaBar from '../components/NamdCompareDeltaBar.vue'
import type { NamdWorkspaceData } from '../types'

interface Props {
  data: NamdWorkspaceData
}
const props = defineProps<Props>()
const { t } = useI18n()

const RetinalVisitComparison = defineAsyncComponent(
  () => import('@/components/RetinalVisitComparison.vue'),
)

const aId = ref<string | null>(props.data.prev?.id ?? null)
const bId = ref<string | null>(props.data.current?.id ?? null)

watch(
  () => props.data,
  (next) => {
    aId.value = next.prev?.id ?? aId.value
    bId.value = next.current?.id ?? bId.value
  },
)

const aVisit = computed(() => props.data.visits.find((v) => v.id === aId.value) ?? null)
const bVisit = computed(() => props.data.visits.find((v) => v.id === bId.value) ?? null)
const bJobId = computed(() => bVisit.value?.retinalJobId ?? null)
</script>

<template>
  <div data-testid="namd-compare-tab" class="space-y-5">
    <Card :title="t('studyModules.namd.compare.delta')">
      <NamdCompareDeltaBar :a="aVisit" :b="bVisit" />
    </Card>

    <div class="grid grid-cols-12 gap-5">
      <Card :title="t('studyModules.namd.compare.paneA')" class="col-span-12 lg:col-span-6">
        <NamdVisitPicker
          v-model="aId as unknown as string"
          :visits="props.data.visits"
          :exclude-id="bId"
          variant="A"
        />
        <div v-if="aVisit" class="mt-4 space-y-1">
          <div class="flex items-baseline justify-between">
            <span class="text-[13px] font-semibold text-slate-700">
              {{ aVisit.label }}
              <span class="text-slate-400 font-normal"> · W{{ aVisit.week }}</span>
            </span>
            <span class="text-[11px] uppercase tracking-wider text-slate-400">
              {{ t('studyModules.namd.banner.totalFluid') }}
            </span>
          </div>
          <div class="text-[15px] font-semibold tabular-nums text-slate-900">
            {{ aVisit.irf + aVisit.srf + aVisit.ped }}
            <span class="text-[11px] font-medium uppercase tracking-wider text-slate-500 ml-1">nL</span>
          </div>
        </div>
      </Card>
      <Card :title="t('studyModules.namd.compare.paneB')" class="col-span-12 lg:col-span-6">
        <NamdVisitPicker
          v-model="bId as unknown as string"
          :visits="props.data.visits"
          :exclude-id="aId"
          variant="B"
        />
        <div v-if="bVisit" class="mt-4 space-y-1">
          <div class="flex items-baseline justify-between">
            <span class="text-[13px] font-semibold text-slate-700">
              {{ bVisit.label }}
              <span class="text-slate-400 font-normal"> · W{{ bVisit.week }}</span>
            </span>
            <span class="text-[11px] uppercase tracking-wider text-slate-400">
              {{ t('studyModules.namd.banner.totalFluid') }}
            </span>
          </div>
          <div class="text-[15px] font-semibold tabular-nums text-slate-900">
            {{ bVisit.irf + bVisit.srf + bVisit.ped }}
            <span class="text-[11px] font-medium uppercase tracking-wider text-slate-500 ml-1">nL</span>
          </div>
        </div>
      </Card>
    </div>

    <RetinalVisitComparison v-if="bJobId != null" :job-id="bJobId" />
  </div>
</template>
