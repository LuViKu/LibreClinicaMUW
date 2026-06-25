<script setup lang="ts">
/**
 * nAMD workspace — decision panel.
 *
 * Captures the physician's treatment decision per visit:
 *   1. Pick an action — "Behandeln" or "Beobachten".
 *   2. Treat path → pick the anti-VEGF drug
 *      ({@code Bevacizumab} / {@code Aflibercept} / {@code Faricimab}).
 *   3. Pick an interval — 4 / 6 / 8 / 10 / 12 / 16 weeks (or None).
 *   4. Confirm — flips to the success view + emits {@code confirm}.
 *
 * <p>2026-06-23 user-feedback round — the KI recommendation pill,
 * AI rationale line and AI-seeded defaults are gone. The panel is
 * fully physician-driven; AI cues live in the side cards above.
 *
 * <p>For v1 the panel is client-side only — no backend write yet.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { I } from '../icons'

const emit = defineEmits<{
  confirm: [decision: { action: 'TREAT' | 'OBSERVE'; drug: Drug | null; intervalWeeks: number | null }]
}>()

const { t } = useI18n()

type Action = 'TREAT' | 'OBSERVE'
type Drug = 'Bevacizumab' | 'Aflibercept' | 'Faricimab'

const action = ref<Action | null>(null)
const drug = ref<Drug | null>(null)
const intervalWeeks = ref<number | null>(null)
const confirmed = ref(false)

const DRUG_CHOICES: Drug[] = ['Bevacizumab', 'Aflibercept', 'Faricimab']
const INTERVAL_CHOICES: (number | null)[] = [4, 6, 8, 10, 12, 16, null]

const canConfirm = computed(() => {
  if (action.value == null) return false
  if (action.value === 'TREAT' && drug.value == null) return false
  return true
})

function pickAction(next: Action) {
  action.value = next
  if (next === 'OBSERVE') {
    drug.value = null
    if (intervalWeeks.value == null) intervalWeeks.value = 12
  }
}

function pickDrug(next: Drug) {
  drug.value = next
}

function pickInterval(weeks: number | null) {
  intervalWeeks.value = weeks
}

function confirm() {
  if (!canConfirm.value) return
  confirmed.value = true
  emit('confirm', {
    action: action.value as Action,
    drug: drug.value,
    intervalWeeks: intervalWeeks.value,
  })
}

function reset() {
  confirmed.value = false
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

      <!-- Drug picker — only on the treat path. Per 2026-06-23
           feedback the anti-VEGF list is fixed to Bevacizumab,
           Aflibercept, Faricimab. -->
      <div v-if="action === 'TREAT'" class="mb-3">
        <div class="text-[11px] uppercase tracking-wider text-slate-500 mb-1.5">
          {{ t('studyModules.namd.decision.drug') }}
        </div>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="choice in DRUG_CHOICES"
            :key="choice"
            type="button"
            :data-testid="`namd-decision-drug-${choice}`"
            :aria-pressed="drug === choice"
            class="px-2.5 py-1 rounded-md border text-xs font-medium transition"
            :class="
              drug === choice
                ? 'border-muw-blue bg-muw-blue-50 text-muw-blue-700'
                : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
            "
            @click="pickDrug(choice)"
          >
            {{ choice }}
          </button>
        </div>
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
