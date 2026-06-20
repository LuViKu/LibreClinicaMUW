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
import { activeFluid, totalFluid } from '../fluid'
import NamdActivityPill from './NamdActivityPill.vue'
import type { NamdPatient, NamdVisit } from '../types'

interface Props {
  patient: NamdPatient
  current: NamdVisit | null
  prev: NamdVisit | null
}

const props = defineProps<Props>()
const { t } = useI18n()

const eyeLabel = computed(() =>
  props.patient.eye === 'OD'
    ? t('studyModules.namd.eyeOd')
    : t('studyModules.namd.eyeOs'),
)

const activeFluidNl = computed(() =>
  props.current ? Math.round(activeFluid(props.current)) : null,
)

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
          <span
            class="inline-flex items-center justify-center rounded-md bg-muw-teal-50 text-muw-teal-700 text-[11px] font-bold px-2 py-0.5"
            data-testid="namd-patient-eye"
          >{{ props.patient.eye }} · {{ eyeLabel }}</span>
          <NamdActivityPill :active-fluid-nl="activeFluidNl" />
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
