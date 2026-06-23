<script setup lang="ts">
/**
 * nAMD report — single static B-scan rendition.
 *
 * Mounts a {@link BscanViewer} at the foveal (median) slice with
 * {@code staticFrame=true} + {@code showSegmentation=true}. The
 * viewer's own slider + header + scroll handlers are suppressed
 * via the staticFrame prop so the canvas paints once at the
 * chosen slice and survives a print preview ({@code Cmd-P})
 * without any animation or layout shift.
 *
 * <p>Per the design's "OCT · Baseline vs. aktueller Besuch"
 * block: a small "{visitLabel} · {date}" caption sits above the
 * scan with an activity pill on the right; the
 * "Foveale B-Scan-Ebene · KI-Segmentierung eingeblendet" line
 * runs below.
 */
import { computed, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import Pill from './primitives/Pill.vue'
import { artifactUrl } from '@/api/retinal'
import { activeFluid, ACTIVITY_THRESHOLD_NL } from '../fluid'
import type { NamdVisit } from '../types'

interface Props {
  visit: NamdVisit
  nSlices: number
}
const props = defineProps<Props>()
const { t } = useI18n()

const BscanViewer = defineAsyncComponent(() => import('@/components/BscanViewer.vue'))

const bscanDcmUrl = computed(() =>
  props.visit.retinalJobId != null ? artifactUrl(props.visit.retinalJobId, 'bscan.dcm') : null,
)

const fovealSlice = computed(() => Math.floor(props.nSlices / 2))
const activeTone = computed(() =>
  activeFluid(props.visit) > ACTIVITY_THRESHOLD_NL ? 'active' : 'dry',
)

const dateFormatter =
  typeof Intl !== 'undefined'
    ? new Intl.DateTimeFormat('de-AT', { day: '2-digit', month: 'short', year: 'numeric' })
    : null
function fmtDate(iso: string): string {
  if (!iso || !dateFormatter) return iso
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return dateFormatter.format(d)
}
</script>

<template>
  <div data-testid="namd-report-scan" class="break-inside-avoid">
    <div class="flex items-center justify-between mb-1.5 gap-2">
      <span class="text-[12px] font-semibold text-slate-700">
        {{ visit.label }} · {{ fmtDate(visit.date) }}
      </span>
      <Pill :tone="activeTone" :dot="false">
        {{
          activeTone === 'active'
            ? t('studyModules.namd.activityActive')
            : t('studyModules.namd.activityDry')
        }}
      </Pill>
    </div>
    <div class="rounded-lg overflow-hidden bg-black ring-1 ring-slate-300">
      <div v-if="bscanDcmUrl" class="aspect-[16/9]">
        <BscanViewer
          :bscan-dcm-url="bscanDcmUrl"
          :n-bscans="nSlices"
          :model-value="fovealSlice"
          :job-id="visit.retinalJobId"
          :static-frame="true"
          :show-segmentation="true"
        />
      </div>
      <div
        v-else
        class="aspect-[16/9] flex items-center justify-center text-white/40 text-xs"
      >
        {{ t('studyModules.namd.viewer.empty') }}
      </div>
    </div>
    <div class="text-[10.5px] text-slate-400 mt-1">
      {{ t('studyModules.namd.report.octCaption') }}
    </div>
  </div>
</template>
