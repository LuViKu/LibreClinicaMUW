<script setup lang="ts">
/**
 * nAMD workspace — patient banner.
 *
 * Port of {@code PatientBanner()} from namd-app.jsx. Sub-header strip that
 * pins the patient identity, eye laterality, and three at-a-glance metrics
 * (Besuch, Letzte Injektion, Gesamt-Fluid). The activity pill on the right
 * of the title row is the headline clinical signal — patients above the
 * fluid threshold render the coral "Exsudation aktiv" pill.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { I } from '../icons'
import { totalFluid } from '../fluid'
import type { Laterality, NamdPatient, NamdVisit } from '../types'

interface Props {
  patient: NamdPatient
  current: NamdVisit | null
  prev: NamdVisit | null
  /**
   * 2026-06-24 user-feedback round — eyes the subject has at least
   * one done fluid job for. When both OD + OS are present the banner
   * renders one pill per eye; the active one (matching patient.eye)
   * is highlighted, the other is faded and clickable to switch.
   * Single-eye subjects continue to show one pill.
   */
  availableEyes?: Laterality[]
  /** Active eye — drives the highlight + reflects {@code patient.eye}. */
  selectedEye?: Laterality
}

const props = withDefaults(defineProps<Props>(), {
  availableEyes: () => [],
  selectedEye: undefined,
})
const emit = defineEmits<{
  'switch-eye': [eye: Laterality]
}>()
const { t } = useI18n()

const eyeLabelFor = (eye: Laterality) =>
  eye === 'OD' ? t('studyModules.namd.eyeOd') : t('studyModules.namd.eyeOs')

const activeEye = computed<Laterality>(() => props.selectedEye ?? props.patient.eye)

/**
 * The pills to render. When the composable didn't surface
 * availableEyes (legacy callers, mock fixtures), fall back to the
 * patient.eye single-pill shape so the banner stays well-formed.
 */
const pillEyes = computed<Laterality[]>(() => {
  if (props.availableEyes.length > 0) return props.availableEyes
  return [props.patient.eye]
})

const totalFluidLabel = computed(() => {
  if (!props.current) return '—'
  return `${Math.round(totalFluid(props.current))}`
})
</script>

<template>
  <div
    data-testid="namd-patient-banner"
    class="no-print bg-white border-b border-slate-200"
  >
    <div class="max-w-[1240px] mx-auto px-6 py-4 flex items-center gap-5">
      <div
        class="w-12 h-12 rounded-xl bg-muw-blue-50 text-muw-blue flex items-center justify-center shrink-0"
      >
        <span v-html="I.eye" />
      </div>
      <div>
        <div class="flex items-center gap-2.5">
          <h1 class="font-serif text-[22px] font-semibold text-slate-900 leading-none">
            {{ t('studyModules.namd.patientPrefix') }} {{ props.patient.id }}
          </h1>
          <!-- 2026-06-24 user-feedback round — eye-switcher pill row.
               Renders one pill per eye the subject has done fluid jobs
               for. Active pill stays full-color (teal); the OTHER eye
               (when both are enrolled) renders muted + clickable;
               clicking emits {@code switch-eye} which the workspace
               composable picks up. Monocular subjects continue to
               show a single non-interactive pill. -->
          <div class="inline-flex items-center gap-1" data-testid="namd-patient-eye-row">
            <button
              v-for="eye in pillEyes"
              :key="eye"
              type="button"
              :data-testid="`namd-patient-eye-${eye}`"
              :aria-pressed="eye === activeEye"
              :class="[
                'inline-flex items-center justify-center rounded-md text-[11px] font-bold px-2 py-0.5 transition-colors',
                eye === activeEye
                  ? 'bg-muw-teal-50 text-muw-teal-700'
                  : 'bg-slate-50 text-slate-400 hover:bg-slate-100 hover:text-slate-600 cursor-pointer',
                pillEyes.length === 1 ? 'cursor-default' : '',
              ]"
              :disabled="pillEyes.length === 1"
              @click="pillEyes.length > 1 && eye !== activeEye && emit('switch-eye', eye)"
            >{{ eye }} · {{ eyeLabelFor(eye) }}</button>
          </div>
        </div>
        <div class="text-[13px] text-slate-500 mt-1.5">
          {{ props.patient.diagnosis }}
          <template v-if="props.patient.age != null"> · {{ props.patient.age }} J.</template>
          · {{ props.patient.regimen }}
        </div>
      </div>
      <div class="ml-auto flex items-center gap-6 text-right">
        <div>
          <div class="text-[11px] text-slate-400">
            {{ t('studyModules.namd.banner.visit') }}
          </div>
          <div class="text-[15px] font-semibold text-slate-900">
            <template v-if="props.current">
              {{ props.current.label }} · W{{ props.current.week }}
            </template>
            <template v-else>—</template>
          </div>
        </div>
        <div>
          <div class="text-[11px] text-slate-400">
            {{ t('studyModules.namd.banner.lastInjection') }}
          </div>
          <div class="text-[15px] font-semibold text-slate-900">
            <template v-if="props.prev && props.prev.inj">
              {{ props.prev.label }} · {{ props.prev.inj }}
            </template>
            <template v-else>—</template>
          </div>
        </div>
        <div>
          <div class="text-[11px] text-slate-400">
            {{ t('studyModules.namd.banner.totalFluid') }}
          </div>
          <div class="text-[15px] font-semibold text-slate-900 tabular-nums">
            {{ totalFluidLabel }} nL
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
