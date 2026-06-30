<script setup lang="ts">
/**
 * nAMD workspace — AI recommendation card.
 *
 * Renders the {@link NamdAiRecommendation} produced by
 * {@link useNamdAiRecommendation} as a headline pill
 * (SHORTEN / KEEP / EXTEND) plus the explanatory list of every fired
 * trigger. Each trigger row uses a coloured dot keyed off the
 * verdict bucket:
 *
 * <ul>
 *   <li>SHORTEN → rot — drove the rec to "shorten interval".</li>
 *   <li>KEEP → amber — drove the rec to "hold interval".</li>
 *   <li>EXTEND → grün — eligibility condition for "extend interval".</li>
 * </ul>
 *
 * <p>The card is study-arm-only — the parent gates its mount on
 * {@code useStudyArm().aiVisible}. Control-arm visits never see this.
 *
 * <p>First-visit fall-through: when the rec is null the card hides;
 * the parent tab shows the loading-phase copy ("Loading-Phase —
 * monatliche Injektion") instead.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { NamdAiRecommendation, NamdTriggerHit } from '../types'

const props = defineProps<{
  rec: NamdAiRecommendation | null
}>()

const { t } = useI18n()

const bucketColour: Record<NamdTriggerHit['bucket'], string> = {
  SHORTEN: 'bg-rose-500',
  KEEP: 'bg-amber-500',
  EXTEND: 'bg-emerald-500',
}

const headlineColour = computed(() => {
  if (!props.rec) return 'bg-slate-200 text-slate-700'
  switch (props.rec.rec) {
    case 'SHORTEN': return 'bg-rose-100 text-rose-800 ring-rose-300'
    case 'KEEP':    return 'bg-amber-100 text-amber-800 ring-amber-300'
    case 'EXTEND':  return 'bg-emerald-100 text-emerald-800 ring-emerald-300'
  }
})

const triggers = computed<NamdTriggerHit[]>(() => props.rec?.triggersFired ?? [])

/** Localised one-line phrasing for a single trigger. */
function triggerLabel(t: NamdTriggerHit): string {
  return useI18nLabel(t)
}
function useI18nLabel(h: NamdTriggerHit): string {
  // i18n keys mirror the trigger key 1:1 under retinal.namd.triggers.*
  const base = `studyModules.namd.recommendation.triggers.${h.key}`
  // Pass measured value + threshold so the localised phrasing can
  // include them (e.g. "IRF +24 nL über Schwelle 20 nL").
  return t(base, {
    value: h.value != null ? Math.round(h.value) : '',
    threshold: h.threshold != null ? Math.round(h.threshold) : '',
  })
}
</script>

<template>
  <div
    v-if="rec"
    data-testid="namd-recommendation-card"
    class="rounded-2xl ring-1 ring-slate-200 bg-white p-5 shadow-sm"
  >
    <div class="flex items-start gap-3">
      <span
        :class="['inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[12px] font-semibold ring-1', headlineColour]"
      >
        <span class="w-2 h-2 rounded-full bg-current opacity-70" />
        {{ t('studyModules.namd.recommendation.headline.' + rec.rec) }}
      </span>
      <span class="ml-auto text-[12px] text-slate-500 tabular-nums">
        {{ t('studyModules.namd.recommendation.intervalProposed', { weeks: rec.intervalWeeks }) }}
      </span>
    </div>
    <p class="mt-3 text-[13.5px] text-slate-700 leading-relaxed">{{ rec.rationale }}</p>

    <div v-if="triggers.length > 0" class="mt-4 border-t border-slate-100 pt-3">
      <div class="text-[11px] uppercase tracking-[0.1em] text-slate-400 font-semibold mb-2">
        {{ t('studyModules.namd.recommendation.firedTriggers') }}
      </div>
      <ul class="space-y-1.5">
        <li
          v-for="(trig, i) in triggers"
          :key="`trig-${i}-${trig.key}`"
          :data-testid="`namd-trigger-${trig.key}`"
          class="flex items-start gap-2 text-[13px] text-slate-700"
        >
          <span :class="['inline-block w-1.5 h-1.5 rounded-full mt-1.5 shrink-0', bucketColour[trig.bucket]]" />
          <span>{{ triggerLabel(trig) }}</span>
        </li>
      </ul>
    </div>

    <p class="mt-4 text-[11px] text-slate-400 leading-snug">
      {{ t('studyModules.namd.recommendation.disclaimer') }}
    </p>
  </div>
</template>
