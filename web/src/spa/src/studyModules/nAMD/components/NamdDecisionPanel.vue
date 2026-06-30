<script setup lang="ts">
/**
 * nAMD workspace — decision panel.
 *
 * Captures the physician's treatment decision per visit and persists
 * it through {@code POST /pages/api/v1/eventCrfs/{id}/items}. New
 * fields beyond the prior client-side-only v1:
 *
 * <ul>
 *   <li><b>rationaleCode</b> — single-select radio with arm-specific
 *     presets. Required when (study arm AND chosen does not match the
 *     AI rec) OR (control arm).</li>
 *   <li><b>rationaleOther</b> — sibling free-text field, shown when
 *     {@code rationaleCode === 'OTHER'}; required in that branch.</li>
 *   <li><b>decisionDate</b> — optional clinical date for retrospective
 *     backfill per the
 *     {@code project_retrospective_data_phase} memory. Defaults to
 *     today.</li>
 * </ul>
 *
 * <p>On confirm the panel:
 *   1. Validates the rationale-required matrix.
 *   2. Snapshots the AI recommendation (rec + interval + triggers) as
 *      a JSON string into {@code I_NAMD_AI_REC_SNAPSHOT}.
 *   3. Computes {@code agreedWithAi} = action matches the rec's
 *      direction (TREAT for SHORTEN/KEEP; OBSERVE for EXTEND-only-
 *      at-no-injection visits) AND the interval matches the rec's.
 *   4. POSTs all of action / drug / interval / rationale / date /
 *      AI snapshot / agreed in one batch. The backend
 *      {@code EventCrfsApiController} emits one summary
 *      {@code TREATMENT_DECISION_RECORDED} audit_log_event row.
 *
 * <p>The success view shows what was recorded + a "Korrektur"
 * affordance that re-opens the form pre-populated with the saved
 * values for amendments. Backend allows updates until the event_crf
 * is signed/locked.
 */

import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { I } from '../icons'
import type { NamdAiRecommendation, NamdSubjectArm } from '../types'

const props = defineProps<{
  /**
   * 2026-06-30 — event_crf id that backs this visit's NAMD_VISIT
   * CRF row. When null the panel goes into preview-only mode (e.g.
   * the static design fixture) and the confirm button is disabled.
   */
  eventCrfId: number | null
  /** Cohort assignment — drives the rationale-preset choice. */
  subjectArm: NamdSubjectArm
  /** AI rec on screen at decision-time. Null on first visit (no rec card). */
  aiRec: NamdAiRecommendation | null
}>()

const emit = defineEmits<{
  saved: [decision: SavedDecision]
}>()

interface SavedDecision {
  action: Action
  drug: Drug | null
  intervalWeeks: number | null
  rationaleCode: RationaleCode | null
  rationaleOther: string
  decisionDate: string
  agreedWithAi: boolean | null
}

const { t } = useI18n()

type Action = 'TREAT' | 'OBSERVE'
type Drug = 'BEVACIZUMAB' | 'AFLIBERCEPT' | 'FARICIMAB'
type RationaleCode =
  | 'CLINICAL_JUDGMENT'
  | 'STABLE_NO_TREAT'
  | 'ACTIVE_DISEASE_TREAT'
  | 'CLINICAL_WORSENING'
  | 'PATIENT_PREFERENCE'
  | 'COMORBIDITY'
  | 'LOGISTICS_COMPLIANCE'
  | 'SAFETY_CONCERN'
  | 'OTHER'

const action = ref<Action | null>(null)
const drug = ref<Drug | null>(null)
const intervalWeeks = ref<number | null>(null)
const rationaleCode = ref<RationaleCode | null>(null)
const rationaleOther = ref('')
// 2026-06-30 — defaults to today (ISO yyyy-MM-dd). Allows pre-dating
// per the retrospective-data-phase memory.
const decisionDate = ref<string>(new Date().toISOString().slice(0, 10))
const confirmed = ref(false)
const saving = ref(false)
const saveError = ref<string | null>(null)

const DRUG_CHOICES: Drug[] = ['BEVACIZUMAB', 'AFLIBERCEPT', 'FARICIMAB']
const INTERVAL_CHOICES: (number | null)[] = [4, 6, 8, 10, 12, 16, null]

const STUDY_OVERRIDE_PRESET: readonly RationaleCode[] = [
  'CLINICAL_JUDGMENT',
  'PATIENT_PREFERENCE',
  'COMORBIDITY',
  'LOGISTICS_COMPLIANCE',
  'SAFETY_CONCERN',
  'OTHER',
] as const
const CONTROL_PRESET: readonly RationaleCode[] = [
  'STABLE_NO_TREAT',
  'ACTIVE_DISEASE_TREAT',
  'CLINICAL_WORSENING',
  'PATIENT_PREFERENCE',
  'LOGISTICS_COMPLIANCE',
  'OTHER',
] as const

/**
 * Did the doctor's action+interval match the AI rec?
 * SHORTEN/KEEP imply TREAT. EXTEND is consistent with TREAT too (at a
 * longer interval) — only OBSERVE is "extension via no injection".
 * Returns null on first visit (no AI rec).
 */
const agreedWithAi = computed<boolean | null>(() => {
  if (!props.aiRec) return null
  const a = action.value
  if (a == null) return null
  if (intervalWeeks.value == null) return null
  return intervalWeeks.value === props.aiRec.intervalWeeks
})

/**
 * Rationale required matrix:
 *   - Study arm: required when the chosen decision doesn't match
 *     {@code aiRec.intervalWeeks}.
 *   - Control arm: always required.
 *   - First visit (no rec): not required on study arm; still required
 *     on control arm.
 *   - Unassigned cohort (subjectArm=null): treat as control to keep
 *     the audit trail honest.
 */
const rationaleRequired = computed<boolean>(() => {
  if (props.subjectArm === 'study') {
    if (props.aiRec == null) return false
    return agreedWithAi.value === false
  }
  return true
})

const presetOptions = computed<readonly RationaleCode[]>(() => {
  if (props.subjectArm === 'study') return STUDY_OVERRIDE_PRESET
  return CONTROL_PRESET
})

const canConfirm = computed<boolean>(() => {
  if (props.eventCrfId == null) return false
  if (action.value == null) return false
  if (action.value === 'TREAT' && drug.value == null) return false
  if (rationaleRequired.value) {
    if (rationaleCode.value == null) return false
    if (rationaleCode.value === 'OTHER' && rationaleOther.value.trim() === '') return false
  }
  return true
})

function pickAction(next: Action) {
  action.value = next
  if (next === 'OBSERVE') {
    drug.value = null
    if (intervalWeeks.value == null) intervalWeeks.value = 12
  }
}
function pickDrug(next: Drug) { drug.value = next }
function pickInterval(weeks: number | null) { intervalWeeks.value = weeks }
function pickRationale(code: RationaleCode) {
  rationaleCode.value = code
  if (code !== 'OTHER') rationaleOther.value = ''
}

async function postDecision(values: Record<string, string>): Promise<void> {
  const url = `/pages/api/v1/eventCrfs/${props.eventCrfId}/items`
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ values }),
  })
  if (!res.ok) {
    let msg = `HTTP ${res.status}`
    try {
      const payload = await res.json() as { message?: string }
      if (payload.message) msg = payload.message
    } catch {
      /* non-JSON error body — keep status */
    }
    throw new Error(msg)
  }
}

async function confirm() {
  if (!canConfirm.value || saving.value) return
  saving.value = true
  saveError.value = null
  try {
    const snapshot = props.aiRec ? JSON.stringify({
      rec: props.aiRec.rec,
      intervalWeeks: props.aiRec.intervalWeeks,
      rationale: props.aiRec.rationale,
      triggersFired: props.aiRec.triggersFired,
    }) : ''
    const values: Record<string, string> = {
      I_NAMD_DECISION_ACTION: action.value as Action,
      I_NAMD_DECISION_INTERVAL_WEEKS: intervalWeeks.value == null ? '' : String(intervalWeeks.value),
      I_NAMD_DECISION_DATE: decisionDate.value,
      I_NAMD_AI_REC_SNAPSHOT: snapshot,
    }
    if (drug.value) values.I_NAMD_DECISION_DRUG = drug.value
    if (rationaleCode.value) {
      values.I_NAMD_DECISION_RATIONALE_CODE = rationaleCode.value
      if (rationaleCode.value === 'OTHER') {
        values.I_NAMD_DECISION_RATIONALE_OTHER = rationaleOther.value.trim()
      }
    }
    if (agreedWithAi.value != null) {
      values.I_NAMD_AI_AGREED = String(agreedWithAi.value)
    }
    await postDecision(values)
    confirmed.value = true
    emit('saved', {
      action: action.value as Action,
      drug: drug.value,
      intervalWeeks: intervalWeeks.value,
      rationaleCode: rationaleCode.value,
      rationaleOther: rationaleOther.value,
      decisionDate: decisionDate.value,
      agreedWithAi: agreedWithAi.value,
    })
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Speichern fehlgeschlagen'
  } finally {
    saving.value = false
  }
}

function reset() {
  confirmed.value = false
  saveError.value = null
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
          :class="action === 'TREAT'
            ? 'border-muw-blue bg-muw-blue text-white'
            : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'"
          @click="pickAction('TREAT')"
        >
          {{ t('studyModules.namd.decision.treat') }}
        </button>
        <button
          type="button"
          data-testid="namd-decision-action-OBSERVE"
          :aria-pressed="action === 'OBSERVE'"
          class="px-3 py-2 rounded-md border text-sm font-medium transition"
          :class="action === 'OBSERVE'
            ? 'border-muw-blue bg-muw-blue text-white'
            : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'"
          @click="pickAction('OBSERVE')"
        >
          {{ t('studyModules.namd.decision.observe') }}
        </button>
      </div>

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
            :class="drug === choice
              ? 'border-muw-blue bg-muw-blue-50 text-muw-blue-700'
              : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'"
            @click="pickDrug(choice)"
          >
            {{ t('studyModules.namd.decision.drugs.' + choice) }}
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
            :class="intervalWeeks === choice
              ? 'border-muw-blue bg-muw-blue-50 text-muw-blue-700'
              : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'"
            @click="pickInterval(choice)"
          >
            {{ choice == null ? '—' : `${choice} W` }}
          </button>
        </div>
      </div>

      <!-- 2026-06-30 — clinical date for retrospective backfill. -->
      <div class="mb-3">
        <label class="block text-[11px] uppercase tracking-wider text-slate-500 mb-1.5">
          {{ t('studyModules.namd.decision.dateLabel') }}
        </label>
        <input
          v-model="decisionDate"
          type="date"
          data-testid="namd-decision-date"
          class="w-full px-2.5 py-1.5 rounded-md border border-slate-200 text-sm text-slate-700 focus:outline-none focus:ring-2 focus:ring-muw-blue focus:border-muw-blue"
        />
      </div>

      <!-- 2026-06-30 — rationale code (radio) + free-text "OTHER". -->
      <div v-if="rationaleRequired" class="mb-3" data-testid="namd-decision-rationale-block">
        <div class="text-[11px] uppercase tracking-wider text-slate-500 mb-1.5">
          {{ t('studyModules.namd.decision.rationaleLabel') }}
          <span class="text-rose-500 ml-1">*</span>
        </div>
        <div class="flex flex-col gap-1">
          <label
            v-for="code in presetOptions"
            :key="code"
            class="flex items-start gap-2 text-[13px] text-slate-700 cursor-pointer"
            :data-testid="`namd-decision-rationale-${code}`"
          >
            <input
              type="radio"
              :value="code"
              :checked="rationaleCode === code"
              name="namd-decision-rationale"
              class="mt-0.5"
              @change="pickRationale(code)"
            />
            <span>{{ t('studyModules.namd.decision.rationaleCodes.' + code) }}</span>
          </label>
        </div>
        <textarea
          v-if="rationaleCode === 'OTHER'"
          v-model="rationaleOther"
          data-testid="namd-decision-rationale-other"
          rows="2"
          maxlength="500"
          :placeholder="t('studyModules.namd.decision.rationaleOtherPlaceholder')"
          class="mt-2 w-full px-2.5 py-1.5 rounded-md border border-slate-200 text-sm text-slate-700 focus:outline-none focus:ring-2 focus:ring-muw-blue focus:border-muw-blue"
        />
      </div>

      <div
        v-if="saveError"
        data-testid="namd-decision-error"
        class="mb-3 rounded-md bg-rose-50 text-rose-700 px-3 py-2 text-xs"
      >{{ saveError }}</div>

      <button
        type="button"
        data-testid="namd-decision-confirm"
        :disabled="!canConfirm || saving"
        class="w-full px-3 py-2 rounded-md text-sm font-semibold transition bg-muw-blue text-white hover:bg-muw-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
        @click="confirm"
      >
        {{ saving
          ? t('studyModules.namd.decision.saving')
          : t('studyModules.namd.decision.confirm') }}
      </button>
    </template>
  </section>
</template>
