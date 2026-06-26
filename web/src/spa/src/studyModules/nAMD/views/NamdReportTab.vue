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
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import NamdSegCards from '../components/NamdSegCards.vue'
import NamdFluidTrendChart from '../components/NamdFluidTrendChart.vue'
import NamdReportScan from '../components/NamdReportScan.vue'
import { totalFluid } from '../fluid'
import { I } from '../icons'
import { useRetinalJobStore } from '@/stores/retinalJob'
import type { NamdWorkspaceData, NamdVisit } from '../types'

interface Props {
  data: NamdWorkspaceData
}
const props = defineProps<Props>()
const { t } = useI18n()
const store = useRetinalJobStore()

const today = computed(() => {
  const d = new Date()
  return d.toLocaleDateString('de-AT', { year: 'numeric', month: '2-digit', day: '2-digit' })
})

const nSlices = computed(() => props.data.nSlices ?? 49)

/* ── 2026-06-26 user-feedback round — top-2 dynamic B-scans block ── */

/**
 * Pull the per-B-scan fluid trace ({@code per_bscan_mm2}) out of a
 * fluid payload. Returns null when the payload isn't fluid-shaped
 * or the trace arrays are missing. Used to compute the
 * within-visit per-slice fluid totals.
 */
function perBscanMm2(payload: unknown): { irf: number[]; srf: number[]; ped: number[] } | null {
  if (!payload || typeof payload !== 'object') return null
  const per = (payload as { per_bscan_mm2?: unknown }).per_bscan_mm2
  if (!per || typeof per !== 'object') return null
  const { irf, srf, ped } = per as { irf?: unknown; srf?: unknown; ped?: unknown }
  if (!Array.isArray(irf) || !Array.isArray(srf) || !Array.isArray(ped)) return null
  return { irf: irf as number[], srf: srf as number[], ped: ped as number[] }
}

/** Per-slice sum of (irf + srf + ped) in mm². */
function totalPerSlice(t: { irf: number[]; srf: number[]; ped: number[] }): number[] {
  const n = Math.min(t.irf.length, t.srf.length, t.ped.length)
  const out = new Array<number>(n)
  for (let i = 0; i < n; i++) out[i] = (t.irf[i] ?? 0) + (t.srf[i] ?? 0) + (t.ped[i] ?? 0)
  return out
}

interface DynamicBscan {
  visit: NamdVisit
  slice: number
  deltaMm2: number
}

/**
 * Ensure both the current + prior visit jobs are loaded so we can
 * read their per_bscan_mm2 arrays. The store caches detail by
 * job id so a return-trip from another tab is free.
 */
async function ensureLoaded(): Promise<void> {
  const ids = [props.data.current?.retinalJobId, props.data.prev?.retinalJobId].filter((x): x is number => x != null)
  await Promise.all(ids.map((id) => (store.jobs[id] == null ? store.loadJob(id) : Promise.resolve(null))))
}

onMounted(() => { void ensureLoaded() })
watch(() => [props.data.current?.retinalJobId, props.data.prev?.retinalJobId] as const, () => { void ensureLoaded() })

/**
 * The two B-scans with the BIGGEST absolute fluid Δ vs the prior
 * visit. Iterate the per_bscan_mm2 traces of (current, prev),
 * compute the per-slice (current_total − prev_total) in mm², and
 * pick the top two by absolute value. Returns null when there's
 * no prior visit OR either job lacks the per-B-scan trace.
 */
const dynamicBscans = computed<DynamicBscan[] | null>(() => {
  const cur = props.data.current
  const prv = props.data.prev
  if (!cur || !prv || cur.id === prv.id) return null
  const curJob = cur.retinalJobId != null ? store.jobs[cur.retinalJobId] : null
  const prvJob = prv.retinalJobId != null ? store.jobs[prv.retinalJobId] : null
  if (!curJob || !prvJob) return null
  const curTrace = perBscanMm2(curJob.outputPayload)
  const prvTrace = perBscanMm2(prvJob.outputPayload)
  if (!curTrace || !prvTrace) return null
  const curTotals = totalPerSlice(curTrace)
  const prvTotals = totalPerSlice(prvTrace)
  const n = Math.min(curTotals.length, prvTotals.length)
  if (n < 2) return null
  // Pair each slice index with its Δ so we can sort by |Δ| then
  // emit the two winners in slice-index order so the printed page
  // reads superior → inferior.
  const deltas = new Array<{ z: number; d: number }>(n)
  for (let z = 0; z < n; z++) deltas[z] = { z, d: curTotals[z]! - prvTotals[z]! }
  deltas.sort((a, b) => Math.abs(b.d) - Math.abs(a.d))
  const top = deltas.slice(0, 2).sort((a, b) => a.z - b.z)
  // Filter out the all-zero-Δ corner case (both visits dry at every
  // B-scan) — surfacing two arbitrary "Δ = 0" scans would be
  // misleading on a clinical report.
  if (top.every((entry) => Math.abs(entry.d) < 1e-6)) return null
  return top.map(({ z, d }) => ({ visit: cur, slice: z, deltaMm2: d }))
})

/** Caption for a dynamic-B-scan tile: "Anstieg +12.4 nL · B-Scan 23/49". */
function dynamicCaption(entry: DynamicBscan): string {
  // mm² → nL (1 µm slice depth assumed) is a rough proxy; the
  // detailed numbers are on the trend chart. The report caption
  // just needs the direction + a rough magnitude. Use the raw mm²
  // value × 1000 to land in the µL range and round to one decimal.
  const dLitres = entry.deltaMm2 * 1000
  const sign = dLitres >= 0 ? '+' : ''
  const dir = entry.deltaMm2 >= 0
    ? t('studyModules.namd.report2.dynamicRise')
    : t('studyModules.namd.report2.dynamicFall')
  return t('studyModules.namd.report2.dynamicCaption', {
    dir,
    sign,
    value: dLitres.toFixed(1),
    z: entry.slice + 1,
    n: nSlices.value,
  })
}

/**
 * 2026-06-23 — baseline visit fallback. Retained for the legacy
 * "OCT · Baseline vs. aktueller" block which paints when there's
 * no prior visit (so per-B-scan deltas can't be computed) and
 * gives the report a second OCT page regardless.
 */
const baselineVisit = computed(() => props.data.visits[0] ?? null)

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
      <h2 class="text-[12px] font-semibold uppercase tracking-wider text-slate-500 mb-2">
        {{ t('studyModules.namd.report.current') }}
      </h2>
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
          <span
            v-if="props.data.current.bcvaRaw"
            class="ml-1 text-slate-400 text-xs"
          >· {{ props.data.current.bcvaRaw }}</span>
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

    <!-- 2026-06-26 user-feedback round — top-2 dynamic B-scans.
         Replaces the previous "Baseline vs. aktueller Besuch" block
         for visits with a prior reference: the two B-scans whose
         per-slice fluid total (irf+srf+ped) changed the most vs the
         previous visit's same-index B-scan. Sorted by absolute |Δ|;
         emitted in superior→inferior slice order so the printed
         page reads top-to-bottom. The caption surfaces direction +
         magnitude + slice index. Falls back to the legacy
         baseline-vs-current block when (a) there's no prior visit
         or (b) the payloads lack per_bscan_mm2 traces. Page-break-
         before keeps the two scans + captions together on a second
         sheet. -->
    <section
      v-if="dynamicBscans && dynamicBscans.length > 0"
      class="mb-6 print:break-before-page"
      data-testid="namd-report-dynamic-block"
    >
      <h2 class="text-[12px] font-semibold uppercase tracking-wider text-slate-500 mb-3">
        {{ t('studyModules.namd.report2.dynamicHeader') }}
      </h2>
      <div class="grid grid-cols-2 gap-4">
        <NamdReportScan
          v-for="entry in dynamicBscans"
          :key="`${entry.visit.id}-${entry.slice}`"
          :visit="entry.visit"
          :n-slices="nSlices"
          :slice="entry.slice"
          :caption="dynamicCaption(entry)"
        />
      </div>
    </section>
    <section
      v-else-if="baselineVisit && props.data.current && baselineVisit.id !== props.data.current.id"
      class="mb-6 print:break-before-page"
      data-testid="namd-report-oct-block"
    >
      <h2 class="text-[12px] font-semibold uppercase tracking-wider text-slate-500 mb-3">
        {{ t('studyModules.namd.report.octBaselineVsCurrent') }}
      </h2>
      <div class="grid grid-cols-2 gap-4">
        <NamdReportScan :visit="baselineVisit" :n-slices="nSlices" />
        <NamdReportScan :visit="props.data.current" :n-slices="nSlices" />
      </div>
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
            :class="visit.dateMismatch ? 'bg-amber-50/50' : ''"
          >
            <td class="py-1.5 px-2 font-medium text-slate-700">{{ visit.label }}</td>
            <!-- 2026-06-23 user-feedback round — flag when the planned
                 visit date doesn't line up with the .e2e acquisition
                 date (> DATE_MISMATCH_DAYS apart). The displayed date is
                 the acquisition date (per the composable's priority);
                 the tooltip surfaces both raw dates so the operator
                 can see which one to correct. -->
            <td
              class="py-1.5 px-2 text-slate-500 tabular-nums"
              :class="visit.dateMismatch ? 'text-amber-700 font-medium' : ''"
              :title="visit.dateMismatch
                ? t('studyModules.namd.report.dateMismatchTitle', {
                    visit: visit.visitDate ?? '—',
                    acquired: visit.acquisitionDate ?? '—',
                  })
                : ''"
            >
              <span v-if="visit.dateMismatch" class="inline-block mr-1" aria-hidden="true">⚠</span>{{ visit.date || '—' }}
            </td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.irf }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.srf }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.ped }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">{{ visit.crt || '—' }}</td>
            <td class="py-1.5 px-2 text-right tabular-nums">
              <span>{{ visit.bcva || '—' }}</span>
              <span v-if="visit.bcvaRaw" class="block text-[10px] text-slate-400">
                {{ visit.bcvaRaw }}
              </span>
            </td>
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
