<script setup lang="ts">
/**
 * nAMD workspace — OCT Viewer tab.
 *
 * Layout-only shell that delegates the whole scan presentation to
 * {@link NamdScanFrame}. The frame owns the corner overlays, the
 * KI-Maske toggle, the activity heatmap, the slider + en-face
 * thumbnail; this tab only adds the right-hand segmentation
 * summary cards.
 *
 * When the current visit has no retinal job the frame renders a
 * polite empty banner (still inside the design's black scan box)
 * so the column widths don't shift.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from '../components/primitives/Card.vue'
import NamdScanFrame from '../components/NamdScanFrame.vue'
import NamdSegCards from '../components/NamdSegCards.vue'
import NamdFluidLegend from '../components/NamdFluidLegend.vue'
import type { NamdWorkspaceData } from '../types'

interface Props {
  data: NamdWorkspaceData
}
const props = defineProps<Props>()
const { t } = useI18n()

const nSlices = computed(() => props.data.nSlices ?? 49)
const slice = ref(Math.floor(nSlices.value / 2))
const mask = ref(true)

watch(
  () => props.data.current?.id,
  () => {
    // New visit selected — reset the slice to the centre so the
    // operator lands on the foveal frame.
    slice.value = Math.floor(nSlices.value / 2)
  },
)
</script>

<template>
  <div data-testid="namd-viewer-tab" class="grid grid-cols-12 gap-5">
    <div class="col-span-12 lg:col-span-8 space-y-3">
      <Card :title="t('studyModules.namd.viewer.volume')">
        <NamdScanFrame
          v-if="props.data.current"
          :visit="props.data.current"
          :eye="props.data.patient.eye"
          :n-slices="nSlices"
          :slice="slice"
          :mask="mask"
          id-base="viewer"
          @update:slice="(z) => (slice = z)"
          @update:mask="(m) => (mask = m)"
        />
        <div
          v-else
          data-testid="namd-viewer-empty"
          class="rounded-md bg-slate-50 px-4 py-6 text-center text-sm text-slate-500"
        >
          {{ t('studyModules.namd.viewer.empty') }}
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
