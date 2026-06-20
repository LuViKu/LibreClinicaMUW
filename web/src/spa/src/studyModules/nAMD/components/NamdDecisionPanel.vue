<script setup lang="ts">
/**
 * nAMD workspace — decision panel.
 *
 * Port of {@code DecisionPanel} from namd-overview.jsx. Three-step
 * decision capture:
 *   1. Pick an action — "Behandeln" or "Beobachten".
 *   2. Pick an interval — 4 / 6 / 8 / 10 / 12 / 16 weeks (or None).
 *   3. Confirm — transitions to the "Entscheidung dokumentiert" success
 *      view + emits {@code confirm}.
 *
 * For v1 the panel is purely client-side — no backend write yet. The
 * planned wire-up (event-scheduling endpoint) is captured in the plan's
 * §10 deferred backlog. The success view's "Zurück" affordance resets
 * the panel to its initial state so the operator can correct a misclick.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Pill from './primitives/Pill.vue'
import { I } from '../icons'
import type { NamdAiRecommendation } from '../types'

interface Props {
  ai?: NamdAiRecommendation | null
}
const props = defineProps<Props>()
const emit = defineEmits<{
  confirm: [decision: { action: 'TREAT' | 'OBSERVE'; intervalWeeks: number | null }]
}>()

const { t } = useI18n()

type Action = 'TREAT' | 'OBSERVE'
const action = ref<Action | null>(null)
const intervalWeeks = ref<number | null>(null)
const confirmed = ref(false)

const INTERVAL_CHOICES: (number | null)[] = [4, 6, 8, 10, 12, 16, null]

const canConfirm = computed(() => action.value != null && intervalWeeks.value !== undefined && action.value != null)

// Seed the panel with the AI suggestion when it arrives, but only if the
// operator hasn't already picked an action — preserve manual choices.
watch(
  () => props.ai,
  (ai) => {
    if (!ai || action.value != null) return
    if (ai.rec === 'TREAT' || ai.rec === 'SHORTEN') action.value = 'TREAT'
    else action.value = 'OBSERVE'
    intervalWeeks.value = ai.intervalWeeks
  },
  { immediate: true },
)

function pickAction(next: Action) {
  action.value = next
  // Treat sub-paths reset to AI's suggested interval; observe resets to 12.
  if (next === 'TREAT' && props.ai) intervalWeeks.value = props.ai.intervalWeeks
  if (next === 'OBSERVE') intervalWeeks.value = 12
}

function pickInterval(weeks: number | null) {
  intervalWeeks.value = weeks
}

function confirm() {
  if (!canConfirm.value) return
  confirmed.value = true
  emit('confirm', {
    action: action.value as Action,
    intervalWeeks: intervalWeeks.value,
  })
}

function reset() {
  confirmed.value = false
  // Keep the previously selected action/interval — the operator may want
  // to refine, not start over.
}
</script>

<template>
  <section
    data-testid="namd-decision-panel"
    class="bg-white rounded-muw shadow-muw-card border border-slate-100 p-4"
  >
    <header class="flex items-baseline justify-between mb-3">
      <h3 class="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
        {{ t('studyModules.namd.decision.header') }}
      </h3>
      <Pill v-if="props.ai" tone="ai" :dot="false">
        <span v-html="I.spark" />
        <span class="ml-1">{{ t('studyModules.namd.decision.aiSuggests', { rec: props.ai.rec }) }}</span>
      </Pill>
    </header>

    <div
      v-if="confirmed"
      data-testid="namd-decision-success"
      class="rounded-md bg-muw-teal-50 text-muw-teal-700 px-4 py-3 flex items-center gap-2"
    >
      <span v-html="I.check" />
      <div class="text-sm font-semibold">
        {{ t('studyModules.namd.decision.confirmed') }}
      </div>
      <button
        type="button"
        data-testid="namd-decision-reset"
        class="ml-auto text-xs text-muw-teal-700 underline"
        @click="reset"
      >
        {{ t('studyModules.namd.decision.back') }}
      </button>
    </div>

    <template v-else>
      <div class="grid grid-cols-2 gap-2 mb-3">
        <button
          type="button"
          data-testid="namd-decision-action-TREAT"
          :aria-pressed="action === 'TREAT'"
          class="px-3 py-2 rounded-md border text-sm font-medium transition"
          :class="
            action === 'TREAT'
              ? 'border-muw-blue bg-muw-blue text-white'
              : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
          "
          @click="pickAction('TREAT')"
        >
          {{ t('studyModules.namd.decision.treat') }}
        </button>
        <button
          type="button"
          data-testid="namd-decision-action-OBSERVE"
          :aria-pressed="action === 'OBSERVE'"
          class="px-3 py-2 rounded-md border text-sm font-medium transition"
          :class="
            action === 'OBSERVE'
              ? 'border-muw-blue bg-muw-blue text-white'
              : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
          "
          @click="pickAction('OBSERVE')"
        >
          {{ t('studyModules.namd.decision.observe') }}
        </button>
      </div>

      <div class="mb-3">
        <div class="text-[11px] uppercase tracking-wider text-slate-500 mb-1.5">
          {{ t('studyModules.namd.decision.interval') }}
        </div>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="choice in INTERVAL_CHOICES"
            :key="choice ?? 'none'"
            type="button"
            :data-testid="`namd-decision-interval-${choice ?? 'none'}`"
            :aria-pressed="intervalWeeks === choice"
            class="px-2.5 py-1 rounded-md border text-xs font-medium transition"
            :class="
              intervalWeeks === choice
                ? 'border-muw-blue bg-muw-blue-50 text-muw-blue-700'
                : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            "
            @click="pickInterval(choice)"
          >
            {{ choice == null ? '—' : `${choice} W` }}
          </button>
        </div>
      </div>

      <div v-if="props.ai" class="text-[12px] text-slate-500 mb-3">
        {{ props.ai.rationale }}
      </div>

      <button
        type="button"
        data-testid="namd-decision-confirm"
        :disabled="!canConfirm"
        class="w-full px-3 py-2 rounded-md text-sm font-semibold transition bg-muw-blue text-white hover:bg-muw-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
        @click="confirm"
      >
        {{ t('studyModules.namd.decision.confirm') }}
      </button>
    </template>
  </section>
</template>
