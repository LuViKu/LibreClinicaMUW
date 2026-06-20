<script setup lang="ts">
/**
 * nAMD workspace — Report tab (A4 print-ready).
 *
 * Port of {@code ReportTab} from namd-report.jsx. Stripped-down letterhead
 * layout designed for Cmd-P / browser-print:
 *   - Letterhead (MUW name + report subtitle + report date).
 *   - Patient block (id / eye / diagnosis / regimen).
 *   - Current status (SegCards + activity summary line).
 *   - Trend chart (re-uses {@link NamdFluidTrendChart}).
 *   - Treatment-history table (per-visit row).
 *   - Signature row.
 *   - Footer (study label + LibreClinica wordmark).
 *
 * The print stylesheet hides the tab strip + header via {@code no-print}
 * already applied upstream; this view adds no extra print-only CSS.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import NamdSegCards from '../components/NamdSegCards.vue'
import NamdFluidTrendChart from '../components/NamdFluidTrendChart.vue'
import NamdActivityPill from '../components/NamdActivityPill.vue'
import { activeFluid, totalFluid } from '../fluid'
import { I } from '../icons'
import type { NamdWorkspaceData } from '../types'

interface Props {
  data: NamdWorkspaceData
}
const props = defineProps<Props>()
const { t } = useI18n()

const today = computed(() => {
  const d = new Date()
  return d.toLocaleDateString('de-AT', { year: 'numeric', month: '2-digit', day: '2-digit' })
})

const activeFluidNl = computed(() =>
  props.data.current ? Math.round(activeFluid(props.data.current)) : null,
)

function printReport() {
  window.print()
}
</script>

<template>
  <article
    data-testid="namd-report-tab"
    class="bg-white rounded-muw border border-slate-200 shadow-muw-card print:shadow-none print:border-0 max-w-[820px] mx-auto p-8 print:p-0"
  >
    <header class="flex items-start justify-between mb-6 pb-4 border-b border-slate-200">
      <div>
        <div class="font-serif text-2xl font-semibold text-muw-blue">
          {{ t('studyModules.namd.report.letterhead') }}
        </div>
        <div class="text-[12px] text-slate-500 mt-1">
          {{ t('studyModules.namd.report.subtitle') }}
        </div>
      </div>
      <div class="text-right">
        <button
          type="button"
          data-testid="namd-report-print"
          class="no-print inline-flex items-center gap-1 text-xs text-muw-blue underline"
          @click="printReport"
        >
          <span v-html="I.printer" />
          <span>{{ t('studyModules.namd.report.print') }}</span>
        </button>
        <div class="text-[11px] text-slate-400 mt-1 tabular-nums">{{ today }}</div>
      </div>
    </header>

    <section class="mb-6">
      <div class="text-[11px] uppercase tracking-wider text-slate-500 mb-2">
        {{ t('studyModules.namd.report.patient') }}
      </div>
      <div class="grid grid-cols-2 gap-3 text-sm text-slate-700">
        <div>
          <span class="text-slate-500">{{ t('studyModules.namd.report.patientId') }}:</span>
          <span class="ml-1 font-semibold">{{ props.data.patient.id }}</span>
        </div>
        <div>
          <span class="text-slate-500">{{ t('studyModules.namd.report.eye') }}:</span>
          <span class="ml-1 font-semibold">{{ props.data.patient.eye }}</span>
        </div>
        <div>
          <span class="text-slate-500">{{ t('studyModules.namd.report.diagnosis') }}:</span>
          <span class="ml-1">{{ props.data.patient.diagnosis }}</span>
        </div>
        <div>
          <span class="text-slate-500">{{ t('studyModules.namd.report.regimen') }}:</span>
          <span class="ml-1">{{ props.data.patient.regimen }}</span>
        </div>
      </div>
    </section>

    <section class="mb-6">
      <div class="flex items-baseline justify-between mb-2">
        <h2 class="text-[12px] font-semibold uppercase tracking-wider text-slate-500">
          {{ t('studyModules.namd.report.current') }}
        </h2>
        <NamdActivityPill :active-fluid-nl="activeFluidNl" />
      </div>
      <NamdSegCards :current="props.data.current" :prev="props.data.prev" />
      <div
        v-if="props.data.current"
        class="mt-3 grid grid-cols-3 gap-3 text-sm text-slate-700"
      >
        <div>
          <span class="text-slate-500">CRT:</span>
          <span class="ml-1 font-semibold tabular-nums">{{ props.data.current.crt }} µm</span>
        </div>
        <div>
          <span class="text-slate-500">BCVA:</span>
          <span class="ml-1 font-semibold tabular-nums">{{ props.data.current.bcva }} L</span>
        </div>
        <div>
          <span class="text-slate-500">Total:</span>
          <span class="ml-1 font-semibold tabular-nums">{{ totalFluid(props.data.current) }} nL</span>
        </div>
      </div>
    </section>

    <section class="mb-6">
      <h2 class="text-[12px] font-semibold uppercase tracking-wider text-slate-500 mb-2">
        {{ t('studyModules.namd.report.trend') }}
      </h2>
      <NamdFluidTrendChart :visits="props.data.visits" />
    </section>

    <section class="mb-6">
      <h2 class="text-[12px] font-semibold uppercase tracking-wider text-slate-500 mb-2">
        {{ t('studyModules.namd.report.history') }}
      </h2>
      <table class="w-full text-[12px] border-collapse">
        <thead>
          <tr class="text-left text-slate-500 border-b border-slate-200">
            <th class="py-1.5 px-2 font-medium">Visite</th>
            <th class="py-1.5 px-2 font-medium">Datum</th>
            <th class="py-1.5 px-2 font-medium text-right">IRF</th>
            <th class="py-1.5 px-2 font-medium text-right">SRF</th>
            <th class="py-1.5 px-2 font-medium text-right">PED</th>
            <th class="py-1.5 px-2 font-medium text-right">CRT</th>
            <th class="py-1.5 px-2 font-medium text-right">BCVA</th>
            <th class="py-1.5 px-2 font-medium">Injektion</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="visit in props.data.visits"
            :key="visit.id"
            class="border-b border-slate-100"
          >
            <td class="py-1.5 px-2 font-medium text-slate-700">{{ visit.label }}</td>
            <td class="py-1.5 px-2 text-slate-500 tabular-nums">{{ visit.date || '—' }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.irf }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.srf }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.ped }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.crt || '—' }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.bcva || '—' }}</td>
            <td class="py-1.5 px-2 text-slate-500">{{ visit.inj || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="mt-10 grid grid-cols-2 gap-8 text-[12px] text-slate-500">
      <div>
        <div class="border-t border-slate-300 pt-2">
          {{ t('studyModules.namd.report.signaturePhysician') }}
        </div>
      </div>
      <div>
        <div class="border-t border-slate-300 pt-2">
          {{ t('studyModules.namd.report.signatureDate') }}
        </div>
      </div>
    </section>

    <footer class="mt-8 pt-4 border-t border-slate-200 flex items-center justify-between text-[11px] text-slate-400">
      <span>{{ props.data.patient.study }}</span>
      <span>LibreClinica MUW</span>
    </footer>
  </article>
</template>
