<script setup lang="ts">
/**
 * Wave 2A — Longitudinal biomarker trends chart.
 *
 * Drops a Chart.js line chart into {@code SubjectRetinalTab} showing
 * the per-task biomarker trajectory across every completed inference
 * job for a single subject. Multi-dataset render for {@code fluid}
 * (IRF cyan / SRF amber / PED magenta / total slate); single-series
 * for {@code onl} / {@code pr} / {@code ga} (the primary metric).
 *
 * <p>X axis: {@code completedAt} (formatted as {@code dd.MM.yyyy}); Y
 * axis: primary metric value with the unit label from the trends DTO.
 *
 * <p>Chart.js + vue-chartjs are dynamic-imported the same way
 * {@code PerBscanTrace} and {@code PatientDetailModal} do, so the
 * subject-detail view doesn't carry the charting bundle unless the
 * operator scrolls into the retinal section.
 *
 * <p>Empty state: when the backend returns zero points (no completed
 * jobs for this task yet) we render a small inline empty banner via
 * the {@code retinal.trends.empty} key.
 */
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { BIOMARKER_COLORS } from '@/components/retinalPalette'

interface Props {
  subjectId: number
  task: 'fluid' | 'onl' | 'pr' | 'ga'
}
const props = defineProps<Props>()

const { t } = useI18n()

/* ----- Async Chart.js Line — mirrors PerBscanTrace registration ----- */

const Line = defineAsyncComponent(async () => {
  const [{ Line: LineComponent }, chartModule] = await Promise.all([
    import('vue-chartjs'),
    import('chart.js'),
  ])
  const {
    Chart,
    LineController,
    LineElement,
    PointElement,
    LinearScale,
    CategoryScale,
    Tooltip,
    Legend,
    Title,
    Filler,
  } = chartModule
  Chart.register(
    LineController,
    LineElement,
    PointElement,
    LinearScale,
    CategoryScale,
    Tooltip,
    Legend,
    Title,
    Filler,
  )
  return LineComponent
})

/* ----- Data fetch ------------------------------------------------------ */

/**
 * Shape returned by {@code GET /pages/api/v1/study-subjects/{id}/retinal-trends}.
 * Mirrors {@code RetinalResultsApiController.RetinalTrendsPointDto}.
 */
interface TrendsPoint {
  jobId: number
  completedAt: string | null
  /**
   * 2026-06-23 — visit date (ISO yyyy-MM-dd) sourced from
   * study_event.date_start. Used as the X axis instead of
   * completedAt so historical scans uploaded today plot at their
   * clinical date.
   */
  visitDate: string | null
  eyeLaterality: string | null
  primaryMetricValue: number | string | null
  primaryMetricUnit: string | null
  outputPayload: Record<string, unknown>
}

const points = ref<TrendsPoint[]>([])
const isLoading = ref(false)
const loadError = ref<string | null>(null)

async function load() {
  isLoading.value = true
  loadError.value = null
  try {
    const url =
      `/LibreClinica/pages/api/v1/study-subjects/${props.subjectId}` +
      `/retinal-trends?task=${encodeURIComponent(props.task)}`
    const res = await fetch(url, {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
      // 2026-06-23 — bust browser cache. The trends DTO shape
      // changed (added visitDate) and any cached single-eye response
      // would keep the chart showing collapsed-onto-today points.
      cache: 'no-store',
    })
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`)
    }
    const body = (await res.json()) as TrendsPoint[]
    points.value = Array.isArray(body) ? body : []
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
    points.value = []
  } finally {
    isLoading.value = false
  }
}

onMounted(() => {
  void load()
})

watch(
  () => [props.subjectId, props.task] as const,
  () => {
    void load()
  },
)

/* ----- Derived chart data --------------------------------------------- */

/**
 * Format an ISO timestamp as {@code dd.MM.yyyy}. Defensive against
 * blank or malformed values so the chart still renders the rest of
 * the series.
 */
function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const datePart = iso.slice(0, 10)
  const [y, m, d] = datePart.split('-')
  if (!y || !m || !d) return iso
  return `${d}.${m}.${y}`
}

function toNumber(v: number | string | null | undefined): number | null {
  if (v == null) return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  const n = Number.parseFloat(v)
  return Number.isFinite(n) ? n : null
}

interface ChartDataset {
  label: string
  data: Array<number | null>
  borderColor: string
  backgroundColor: string
  tension: number
  pointRadius: number
  pointHoverRadius: number
  spanGaps: boolean
}

interface ChartData {
  labels: string[]
  datasets: ChartDataset[]
}

const unitLabel = computed<string>(() => {
  // Pick the first non-null unit — the trends rows all share the same
  // unit for a given task, so this is the canonical axis label.
  for (const p of points.value) {
    if (p.primaryMetricUnit && p.primaryMetricUnit.trim().length > 0) {
      return p.primaryMetricUnit
    }
  }
  return ''
})

/**
 * 2026-06-23 — split the points by laterality so OD and OS get
 * independent series. Combining them was clinically wrong: the two
 * eyes can have wildly different biomarker volumes and stitching
 * them into one line produced misleading downward "trends" that
 * weren't actual progression.
 *
 * X axis is the visit date (NOT completedAt). Both eyes' points for
 * the same visit collapse onto the same X label so the per-eye lines
 * line up vertically at each visit column.
 */
function normalizeLat(raw: string | null): 'OD' | 'OS' | 'OTHER' {
  if (!raw) return 'OTHER'
  const t = raw.trim().toUpperCase()
  if (t === 'OD' || t === 'R' || t === 'RIGHT') return 'OD'
  if (t === 'OS' || t === 'L' || t === 'LEFT') return 'OS'
  return 'OTHER'
}

function xAxisIso(p: TrendsPoint): string {
  // Visit date is the clinically meaningful axis. Fall back to
  // completedAt for jobs that somehow lost their study_event binding
  // (defensive — shouldn't happen for done jobs).
  return (p.visitDate ?? p.completedAt ?? '').slice(0, 10)
}

/**
 * 2026-06-23 — build chart-data for one eye. The trends panel now
 * renders TWO charts (OD, OS) side-by-side rather than overlaying
 * both eyes on one canvas: the eyes share visit dates but their
 * biomarker volumes can differ by 2×+, so a combined chart compresses
 * the lower-volume eye into a near-flat line. Per-eye charts also
 * let the operator visually compare progression without legend
 * juggling.
 */
function buildChartDataForEye(lat: 'OD' | 'OS'): ChartData | null {
  const ofThisEye = points.value.filter((p) => normalizeLat(p.eyeLaterality) === lat)
  if (ofThisEye.length === 0) return null

  // Unique sorted visit-date labels for this eye's X axis.
  const xValues = Array.from(new Set(ofThisEye.map(xAxisIso))).filter((x) => x.length > 0)
  xValues.sort()
  if (xValues.length === 0) return null
  const labels = xValues.map((x) => formatDate(x))
  const xIndex = new Map(xValues.map((x, i) => [x, i]))

  if (props.task === 'fluid') {
    const irf: Array<number | null> = nullArr(xValues.length)
    const srf: Array<number | null> = nullArr(xValues.length)
    const ped: Array<number | null> = nullArr(xValues.length)
    const total: Array<number | null> = nullArr(xValues.length)
    for (const p of ofThisEye) {
      const idx = xIndex.get(xAxisIso(p))
      if (idx == null) continue
      const b = (p.outputPayload?.biomarkers ?? {}) as {
        irf_mm3?: number; srf_mm3?: number; ped_mm3?: number; total_mm3?: number
      }
      irf[idx]   = toNumber(b.irf_mm3 ?? null)
      srf[idx]   = toNumber(b.srf_mm3 ?? null)
      ped[idx]   = toNumber(b.ped_mm3 ?? null)
      total[idx] = toNumber(b.total_mm3 ?? null)
    }
    return {
      labels,
      datasets: [
        ds(t('retinal.kpi.irf'),   irf,   BIOMARKER_COLORS.irf),
        ds(t('retinal.kpi.srf'),   srf,   BIOMARKER_COLORS.srf),
        ds(t('retinal.kpi.ped'),   ped,   BIOMARKER_COLORS.ped),
        ds(t('retinal.kpi.total'), total, '#64748b'),
      ],
    }
  }

  // Single-series tasks (onl / pr / ga).
  const values: Array<number | null> = nullArr(xValues.length)
  for (const p of ofThisEye) {
    const idx = xIndex.get(xAxisIso(p))
    if (idx == null) continue
    values[idx] = toNumber(p.primaryMetricValue)
  }
  return {
    labels,
    datasets: [ds(labelForSingleTask(props.task), values, colorForSingleTask(props.task))],
  }
}

const chartDataOD = computed<ChartData | null>(() => buildChartDataForEye('OD'))
const chartDataOS = computed<ChartData | null>(() => buildChartDataForEye('OS'))

/**
 * Kept for backward-compatibility with BiomarkerTrendsChart.spec.ts —
 * returns the OD chart data (or OS when no OD data is present). The
 * spec's only assertion against chartData is "non-null when points
 * exist", which both eyes satisfy.
 */
const chartData = computed<ChartData | null>(
  () => chartDataOD.value ?? chartDataOS.value,
)

function nullArr(n: number): Array<number | null> {
  return Array.from({ length: n }, () => null)
}

function ds(
  label: string,
  data: Array<number | null>,
  color: string,
): ChartDataset {
  return {
    label,
    data,
    borderColor: color,
    backgroundColor: color,
    tension: 0.2,
    pointRadius: 2,
    pointHoverRadius: 5,
    spanGaps: true,
  }
}

function labelForSingleTask(task: 'onl' | 'pr' | 'ga'): string {
  if (task === 'ga') return t('retinal.kpi.gaArea')
  if (task === 'onl') return t('retinal.kpi.onlThickness')
  return t('retinal.kpi.prThickness')
}

function colorForSingleTask(task: 'onl' | 'pr' | 'ga'): string {
  if (task === 'ga') return '#ec4899'
  if (task === 'onl') return BIOMARKER_COLORS.irf
  return BIOMARKER_COLORS.srf
}

const chartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index' as const, intersect: false },
  plugins: {
    // 2026-06-23 — legend visible for fluid (4 series — IRF/SRF/PED/Σ)
    // and hidden for single-series tasks where the chart header
    // already names the metric. Each chart is single-eye now so the
    // eye discriminator is in the chart's title strip, not the
    // legend.
    legend: {
      display: props.task === 'fluid',
      position: 'bottom' as const,
      labels: { boxWidth: 10, font: { size: 10 } },
    },
    title: { display: false },
    tooltip: {
      callbacks: {
        title: (items: Array<{ label: string }>) =>
          items.length ? items[0].label : '',
      },
    },
  },
  scales: {
    x: {
      title: {
        display: true,
        text: t('retinal.trends.xAxisLabel'),
        font: { size: 10 },
      },
      ticks: { font: { size: 9 }, maxTicksLimit: 12 },
    },
    y: {
      title: {
        display: true,
        text: unitLabel.value
          ? t('retinal.trends.yAxisLabel', { unit: unitLabel.value })
          : t('retinal.trends.yAxisLabelBlank'),
        font: { size: 10 },
      },
      ticks: { font: { size: 9 } },
      beginAtZero: true,
    },
  },
}))

const eyeLabelLong = computed<Record<'OD' | 'OS', string>>(() => ({
  OD: t('retinal.header.lateralityLong.OD'),
  OS: t('retinal.header.lateralityLong.OS'),
}))

/* Test hook: lets BiomarkerTrendsChart.spec.ts read the constructed
 * chart data without mounting the lazy Line component. <script setup>
 * exposes refs as auto-unwrapped values via the component instance
 * proxy, so {@code w.vm.chartData} on the test side already yields
 * the inner ChartData (or null) — no {@code .value} hop needed. */
defineExpose({ points, chartData, chartDataOD, chartDataOS })
</script>

<template>
  <div
    data-testid="biomarker-trends-chart"
    class="bg-white border border-slate-200 rounded-muw p-3"
  >
    <div class="text-[10px] uppercase tracking-wider font-semibold text-slate-500 mb-2">
      {{ t('retinal.trends.chartHeader') }}
    </div>
    <div
      v-if="isLoading"
      class="text-xs text-slate-500 italic h-64 flex items-center justify-center"
      data-testid="biomarker-trends-loading"
    >
      {{ t('retinal.trends.loading') }}
    </div>
    <div
      v-else-if="loadError"
      class="text-xs text-rose-600 italic h-64 flex items-center justify-center"
      data-testid="biomarker-trends-error"
    >
      {{ t('retinal.trends.errorPrefix') }} {{ loadError }}
    </div>
    <div
      v-else-if="!chartDataOD && !chartDataOS"
      class="text-xs text-slate-500 italic h-64 flex items-center justify-center"
      data-testid="biomarker-trends-empty"
    >
      {{ t('retinal.trends.empty') }}
    </div>
    <!--
      2026-06-23 — two side-by-side charts (OD left / OS right), each
      filtered to one eye. Ophthalmology convention puts OD on the
      LEFT column (clinician-face-to-face mirror image), matching the
      bilateral-mask convention used elsewhere in the app. Stacks
      vertically on narrow viewports.
    -->
    <div v-else class="grid grid-cols-1 lg:grid-cols-2 gap-3">
      <div
        v-if="chartDataOD"
        class="flex flex-col"
        data-testid="biomarker-trends-chart-od"
      >
        <div class="text-[11px] font-semibold text-slate-600 mb-1">
          {{ eyeLabelLong.OD }}
        </div>
        <div class="h-64">
          <Line :data="chartDataOD" :options="chartOptions" />
        </div>
      </div>
      <div
        v-else
        class="flex items-center justify-center text-[11px] italic text-slate-400 h-72"
        data-testid="biomarker-trends-chart-od-empty"
      >
        {{ t('retinal.trends.emptyEye', { eye: eyeLabelLong.OD }) }}
      </div>
      <div
        v-if="chartDataOS"
        class="flex flex-col"
        data-testid="biomarker-trends-chart-os"
      >
        <div class="text-[11px] font-semibold text-slate-600 mb-1">
          {{ eyeLabelLong.OS }}
        </div>
        <div class="h-64">
          <Line :data="chartDataOS" :options="chartOptions" />
        </div>
      </div>
      <div
        v-else
        class="flex items-center justify-center text-[11px] italic text-slate-400 h-72"
        data-testid="biomarker-trends-chart-os-empty"
      >
        {{ t('retinal.trends.emptyEye', { eye: eyeLabelLong.OS }) }}
      </div>
    </div>
  </div>
</template>
