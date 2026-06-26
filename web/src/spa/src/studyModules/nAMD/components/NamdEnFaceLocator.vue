<script setup lang="ts">
/**
 * nAMD workspace — en-face locator thumbnail.
 *
 * 64px (default) box showing the fundus PNG with a thin horizontal
 * line indicating the current B-scan's Y position. Used by
 * {@link NamdScanFrame} as the right-hand companion to the volume
 * slider. The design's reference fakes this with a synthesized
 * SVG ellipse; we feed it the real fundus artifact for the visit's
 * retinal job.
 *
 * Falls back to a neutral slate placeholder if no jobId is bound
 * (e.g. a baseline eCRF with no OCT) so the layout doesn't shift.
 */
import { computed } from 'vue'
import { artifactUrl } from '@/api/retinal'

interface Props {
  jobId: number | null
  slice: number
  nSlices: number
  size?: number
}

const props = withDefaults(defineProps<Props>(), { size: 64 })

const fundusUrl = computed(() =>
  props.jobId == null ? null : artifactUrl(props.jobId, 'fundus.png'),
)

const linePct = computed(() => {
  if (props.nSlices <= 1) return 50
  return (props.slice / (props.nSlices - 1)) * 100
})
</script>

<template>
  <div
    data-testid="namd-enface-locator"
    class="shrink-0 text-center"
  >
    <div
      class="relative rounded-lg overflow-hidden ring-1 ring-slate-200 bg-slate-900"
      :style="`width:${size}px;height:${size}px`"
    >
      <img
        v-if="fundusUrl"
        :src="fundusUrl"
        alt=""
        class="absolute inset-0 w-full h-full object-cover"
      />
      <div
        v-else
        class="absolute inset-0 bg-gradient-to-br from-slate-700 to-slate-900"
      />
      <div
        data-testid="namd-enface-line"
        class="absolute left-0 right-0 h-[2px] bg-muw-coral-400 shadow-[0_0_0_1px_rgba(0,0,0,0.45)]"
        :style="`top:${linePct}%`"
      />
    </div>
    <div class="text-[9px] text-slate-400 mt-1">En-face</div>
  </div>
</template>
