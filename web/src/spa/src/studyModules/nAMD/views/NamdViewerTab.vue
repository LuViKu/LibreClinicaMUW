<script setup lang="ts">
/**
 * nAMD workspace — OCT Viewer tab.
 *
 * Port of {@code ViewerTab()} from namd-app.jsx. Two-column layout:
 *   - Left (col-span-8): Cornerstone-backed {@link BscanViewer} mounted
 *     against the current visit's {@code bscan.dcm}. The design's
 *     synthesized SVG OCT was replaced with the real DICOM viewer per
 *     the plan section 4 ("don't re-implement the inner SVG B-scan").
 *   - Right (col-span-4): segmentation summary cards + a CRT row +
 *     a fluid legend.
 *
 * When the current visit has no retinal job (e.g. a baseline eye-exam
 * eCRF without an OCT acquisition) the viewer renders a polite empty
 * banner. The side cards still render — they're driven by the per-visit
 * biomarker volumes the composable surfaced.
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from '../components/primitives/Card.vue'
import Pill from '../components/primitives/Pill.vue'
import NamdSegCards from '../components/NamdSegCards.vue'
import NamdFluidLegend from '../components/NamdFluidLegend.vue'
import { I } from '../icons'
import { artifactUrl } from '@/api/retinal'
import type { NamdWorkspaceData } from '../types'

interface Props {
  data: NamdWorkspaceData
}
const props = defineProps<Props>()
const { t } = useI18n()

// BscanViewer pulls Cornerstone.js (~150 KB) — keep it lazy so the
// Overview tab doesn't ship it.
const BscanViewer = defineAsyncComponent(() => import('@/components/BscanViewer.vue'))

const currentJobId = computed(() => props.data.current?.retinalJobId ?? null)
const bscanDcmUrl = computed(() =>
  currentJobId.value != null ? artifactUrl(currentJobId.value, 'bscan.dcm') : null,
)
const nBscans = computed(() => props.data.nSlices ?? 49)

const bscanZ = ref(Math.floor(nBscans.value / 2))
watch(nBscans, (n) => {
  bscanZ.value = Math.floor(n / 2)
})
</script>

<template>
  <div data-testid="namd-viewer-tab" class="grid grid-cols-12 gap-5">
    <div class="col-span-12 lg:col-span-8 space-y-3">
      <Card :title="t('studyModules.namd.viewer.volume')">
        <template #right>
          <Pill tone="ai" :dot="false">
            <span v-html="I.spark" />
            <span class="ml-1">{{ t('studyModules.namd.viewer.aiSeg') }}</span>
          </Pill>
        </template>
        <div v-if="bscanDcmUrl" data-testid="namd-viewer-bscan-host">
          <BscanViewer
            v-model="bscanZ"
            :bscan-dcm-url="bscanDcmUrl"
            :n-bscans="nBscans"
          />
        </div>
        <div
          v-else
          data-testid="namd-viewer-empty"
          class="rounded-md bg-slate-50 px-4 py-6 text-center text-sm text-slate-500"
        >
          {{ t('studyModules.namd.viewer.empty') }}
        </div>
        <div class="mt-4 flex items-start gap-2 text-[12px] text-slate-500 bg-slate-50 rounded-lg px-3 py-2.5">
          <span class="text-muw-blue-300 mt-0.5" v-html="I.alert" />
          <span>{{ t('studyModules.namd.viewer.hint') }}</span>
        </div>
      </Card>
    </div>

    <div class="col-span-12 lg:col-span-4 space-y-5">
      <Card :title="t('studyModules.namd.viewer.segCardsHeader')">
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
      <Card :title="t('studyModules.namd.overview.legend')">
        <NamdFluidLegend />
      </Card>
    </div>
  </div>
</template>
