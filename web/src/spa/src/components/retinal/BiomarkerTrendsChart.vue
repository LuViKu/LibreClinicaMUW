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

const chartData = computed<ChartData | null>(() => {
  if (points.value.length === 0) return null
  const labels = points.value.map((p) => formatDate(p.completedAt))

  if (props.task === 'fluid') {
    const irf: Array<number | null> = []
    const srf: Array<number | null> = []
    const ped: Array<number | null> = []
    const total: Array<number | null> = []
    for (const p of points.value) {
      const b = (p.outputPayload?.biomarkers ?? {}) as {
        irf_mm3?: number
        srf_mm3?: number
        ped_mm3?: number
        total_mm3?: number
      }
      irf.push(toNumber(b.irf_mm3 ?? null))
      srf.push(toNumber(b.srf_mm3 ?? null))
      ped.push(toNumber(b.ped_mm3 ?? null))
      total.push(toNumber(b.total_mm3 ?? null))
    }
    return {
      labels,
      datasets: [
        ds(t('retinal.kpi.irf'), irf, BIOMARKER_COLORS.irf),
        ds(t('retinal.kpi.srf'), srf, BIOMARKER_COLORS.srf),
        ds(t('retinal.kpi.ped'), ped, BIOMARKER_COLORS.ped),
        // Slate-500 — keeps the total visually distinct from the three
        // biomarker hues; matches the FundusOverlay neutral stroke.
        ds(t('retinal.kpi.total'), total, '#64748b'),
      ],
    }
  }

  // Single-series tasks — onl / pr / ga.
  const values = points.value.map((p) => toNumber(p.primaryMetricValue))
  const label = labelForSingleTask(props.task)
  const color = colorForSingleTask(props.task)
  return {
    labels,
    datasets: [ds(label, values, color)],
  }
})

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

/* Test hook: lets BiomarkerTrendsChart.spec.ts read the constructed
 * chart data without mounting the lazy Line component. <script setup>
 * exposes refs as auto-unwrapped values via the component instance
 * proxy, so {@code w.vm.chartData} on the test side already yields
 * the inner ChartData (or null) — no {@code .value} hop needed. */
defineExpose({ points, chartData })
</script>

<template>
  <div data-testid="biomarker-trends-chart" class="bg-white border border-slate-200 rounded-muw p-3 h-72">
    <div class="text-[10px] uppercase tracking-wider font-semibold text-slate-500 mb-2">
      {{ t('retinal.trends.chartHeader') }}
    </div>
    <div
      v-if="isLoading"
      class="text-xs text-slate-500 italic h-full flex items-center justify-center"
      data-testid="biomarker-trends-loading"
    >
      {{ t('retinal.trends.loading') }}
    </div>
    <div
      v-else-if="loadError"
      class="text-xs text-rose-600 italic h-full flex items-center justify-center"
      data-testid="biomarker-trends-error"
    >
      {{ t('retinal.trends.errorPrefix') }} {{ loadError }}
    </div>
    <div
      v-else-if="!chartData"
      class="text-xs text-slate-500 italic h-full flex items-center justify-center"
      data-testid="biomarker-trends-empty"
    >
      {{ t('retinal.trends.empty') }}
    </div>
    <div v-else class="h-[calc(100%-1.5rem)]">
      <Line :data="chartData" :options="chartOptions" />
    </div>
  </div>
</template>
