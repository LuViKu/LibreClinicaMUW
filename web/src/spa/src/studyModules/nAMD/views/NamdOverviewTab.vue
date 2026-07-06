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
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from '../components/primitives/Card.vue'
import DeltaChip from '../components/primitives/DeltaChip.vue'
import NamdSegCards from '../components/NamdSegCards.vue'
import NamdFluidTrendChart from '../components/NamdFluidTrendChart.vue'
import NamdBcvaTrendChart from '../components/NamdBcvaTrendChart.vue'
import NamdDecisionPanel from '../components/NamdDecisionPanel.vue'
import NamdRecommendationCard from '../components/NamdRecommendationCard.vue'
import NamdClinicalFlagsCard from '../components/NamdClinicalFlagsCard.vue'
import { useNamdAiRecommendation } from '../composables/useNamdAiRecommendation'
import { useStudyArm } from '../composables/useStudyArm'
import type { NamdWorkspaceData } from '../types'

interface Props {
  data: NamdWorkspaceData
  /** Currently-viewed eye — needed by the clinical-flags card to write the correct per-eye items. */
  selectedEye: 'OD' | 'OS'
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'refresh'): void
}>()
const { t } = useI18n()

// 2026-06-30 — cohort gate for the AI-fluid panels (seg cards, trend
// chart, recommendation card). Control-arm visits hide all of them;
// the OCT viewer + decision panel still render so the operator can
// capture a treatment decision either way.
const currentRef = computed(() => props.data.current)
const prevRef = computed(() => props.data.prev)
const armRef = computed(() => props.data.subjectArm)
const { aiVisible } = useStudyArm(armRef)
const recommendation = useNamdAiRecommendation({ current: currentRef, prev: prevRef })

/**
 * 2026-06-26 user-feedback round — surface the CRT delta-vs-prev
 * next to the current value. CRT was the only metric on the Overview
 * tab that DIDN'T show its delta even though the prior-visit data is
 * already in props.data.prev. Null + zero are both treated as "no
 * prior data" so a first-visit case renders the neutral grey chip.
 *
 * Direction is {@code badUp} — a rising CRT is clinically bad in the
 * nAMD context (thickening = re-accumulating fluid). Matches the
 * fluid-biomarker chips in {@link NamdSegCards}.
 */
const crtDelta = computed<number | null>(() => {
  const cur = props.data.current?.crt
  const prev = props.data.prev?.crt
  if (cur == null || prev == null) return null
  if (cur === 0 || prev === 0) return null
  return cur - prev
})
</script>

<template>
  <div data-testid="namd-overview-tab" class="grid grid-cols-12 gap-5">
    <div class="col-span-12 lg:col-span-8 space-y-5">
      <!-- AI fluid quantification — study-arm only. Control-arm hides
           the seg-cards + CRT chip + trend chart entirely. -->
      <Card v-if="aiVisible" :title="t('studyModules.namd.overview.current')">
        <NamdSegCards :current="props.data.current" :prev="props.data.prev" />
        <div class="mt-3 rounded-md bg-slate-50 px-3.5 py-2.5 flex items-center justify-between gap-3">
          <span class="text-[12px] text-slate-500">
            {{ t('studyModules.namd.overview.crt') }}
          </span>
          <span class="flex items-center gap-2">
            <DeltaChip
              v-if="crtDelta != null"
              :value="crtDelta"
              direction="badUp"
              :unit="' µm'"
              data-testid="namd-overview-crt-delta"
              :aria-label="t('studyModules.namd.overview2.crtDeltaAria')"
            />
            <span class="text-[15px] font-semibold text-slate-900 tabular-nums" data-testid="namd-overview-crt-value">
              <template v-if="props.data.current">{{ props.data.current.crt }} µm</template>
              <template v-else>—</template>
            </span>
          </span>
        </div>
      </Card>

      <Card v-if="aiVisible" :title="t('studyModules.namd.overview.trend')">
        <NamdFluidTrendChart :visits="props.data.visits" />
      </Card>

      <!-- 2026-06-30 — Standalone BCVA-only trend. Control-arm ONLY —
           the study-arm's NamdFluidTrendChart above already includes
           a BCVA strip along the bottom, so surfacing this chart there
           duplicates the information. Renders only when at least one
           visit carries a non-zero BCVA reading. -->
      <Card
        v-if="!aiVisible && props.data.visits.some((v) => v.bcva > 0)"
        :title="t('studyModules.namd.overview.bcvaTrend')"
      >
        <NamdBcvaTrendChart :visits="props.data.visits" />
      </Card>

      <!-- 2026-06-30 — Control-arm clinical fallback. The AI panels
           are hidden on the control arm + on the defensive
           "unassigned subject" path; without this block the entire
           left column would be empty. Surface BCVA + the per-eye
           clinical flags + an arm-unassigned warning so the operator
           has something to read while authoring the decision. -->
      <Card v-if="!aiVisible && props.data.current" :title="t('studyModules.namd.overview.clinical')">
        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3 text-sm text-slate-700">
          <div>
            <div class="text-[11px] uppercase tracking-wider text-slate-500 mb-1">BCVA</div>
            <div class="text-[16px] font-semibold tabular-nums">
              <template v-if="props.data.current.bcva > 0">{{ props.data.current.bcva }} L</template>
              <template v-else>—</template>
            </div>
            <div v-if="props.data.current.bcvaRaw" class="text-[11px] text-slate-400 mt-0.5">
              {{ props.data.current.bcvaRaw }}
            </div>
          </div>
          <div>
            <div class="text-[11px] uppercase tracking-wider text-slate-500 mb-1">
              {{ t('studyModules.namd.overview.clinicalHemorrhage') }}
            </div>
            <div
              class="text-[14px] font-semibold"
              :class="props.data.current.hemorrhage ? 'text-rose-700' : 'text-slate-500'"
            >
              {{ props.data.current.hemorrhage
                ? t('studyModules.namd.overview.clinicalFlagYes')
                : t('studyModules.namd.overview.clinicalFlagNo') }}
            </div>
          </div>
          <div>
            <div class="text-[11px] uppercase tracking-wider text-slate-500 mb-1">
              {{ t('studyModules.namd.overview.clinicalBcvaAttr') }}
            </div>
            <div
              class="text-[14px] font-semibold"
              :class="props.data.current.bcvaAttributableToNamd ? 'text-rose-700' : 'text-slate-500'"
            >
              {{ props.data.current.bcvaAttributableToNamd
                ? t('studyModules.namd.overview.clinicalFlagYes')
                : t('studyModules.namd.overview.clinicalFlagNo') }}
            </div>
          </div>
        </div>
        <div
          v-if="props.data.subjectArm == null"
          class="mt-3 rounded-md bg-amber-50 text-amber-800 px-3 py-2 text-[12px]"
        >
          {{ t('studyModules.namd.overview.armUnassigned') }}
        </div>
      </Card>

    </div>

    <div class="col-span-12 lg:col-span-4 space-y-5">
      <!-- 2026-07-06 — Clinical-flags entry. Sits above the rec card so
           the physician can toggle hemorrhage / BCVA-attribution before
           reading the recommendation (both feed the rule engine as
           SHORTEN triggers). Rendered for BOTH arms — control-arm
           clinicians still need to record these observations for the
           protocol. -->
      <NamdClinicalFlagsCard
        v-if="props.data.current"
        :event-crf-id="props.data.current.eventCrfId ?? null"
        :eye="props.selectedEye"
        :hemorrhage="props.data.current.hemorrhage"
        :bcva-attributable-to-namd="props.data.current.bcvaAttributableToNamd"
        @saved="emit('refresh')"
      />
      <!-- AI recommendation card — study-arm only. -->
      <NamdRecommendationCard v-if="aiVisible" :rec="recommendation" />
      <NamdDecisionPanel
        :event-crf-id="props.data.current?.eventCrfId ?? null"
        :subject-arm="props.data.subjectArm"
        :ai-rec="aiVisible ? recommendation : null"
      />
    </div>
  </div>
</template>
