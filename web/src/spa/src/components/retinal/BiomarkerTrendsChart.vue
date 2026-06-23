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
  borderDash: number[]
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

const chartData = computed<ChartData | null>(() => {
  if (points.value.length === 0) return null

  // Unique sorted visit-date labels (X axis).
  const xValues = Array.from(new Set(points.value.map(xAxisIso))).filter((x) => x.length > 0)
  xValues.sort()
  const labels = xValues.map((x) => formatDate(x))
  const xIndex = new Map(xValues.map((x, i) => [x, i]))

  // Per-eye buckets keyed by (laterality × biomarker) — populated
  // sparsely so a visit that only scanned OD leaves OS as a gap.
  const lats: Array<'OD' | 'OS'> = ['OD', 'OS']

  if (props.task === 'fluid') {
    const series: Record<'OD' | 'OS', {
      irf: Array<number | null>
      srf: Array<number | null>
      ped: Array<number | null>
      total: Array<number | null>
    }> = {
      OD: { irf: nullArr(xValues.length), srf: nullArr(xValues.length), ped: nullArr(xValues.length), total: nullArr(xValues.length) },
      OS: { irf: nullArr(xValues.length), srf: nullArr(xValues.length), ped: nullArr(xValues.length), total: nullArr(xValues.length) },
    }
    for (const p of points.value) {
      const lat = normalizeLat(p.eyeLaterality)
      if (lat === 'OTHER') continue
      const idx = xIndex.get(xAxisIso(p))
      if (idx == null) continue
      const b = (p.outputPayload?.biomarkers ?? {}) as {
        irf_mm3?: number; srf_mm3?: number; ped_mm3?: number; total_mm3?: number
      }
      series[lat].irf[idx]   = toNumber(b.irf_mm3 ?? null)
      series[lat].srf[idx]   = toNumber(b.srf_mm3 ?? null)
      series[lat].ped[idx]   = toNumber(b.ped_mm3 ?? null)
      series[lat].total[idx] = toNumber(b.total_mm3 ?? null)
    }
    const datasets: ChartDataset[] = []
    for (const lat of lats) {
      const s = series[lat]
      // Skip the eye entirely when no points fell on it — keeps the
      // legend tight for single-eye subjects.
      const has = s.irf.some((v) => v != null) || s.srf.some((v) => v != null) || s.ped.some((v) => v != null) || s.total.some((v) => v != null)
      if (!has) continue
      datasets.push(ds(`${t('retinal.kpi.irf')} · ${lat}`,   s.irf,   BIOMARKER_COLORS.irf,   lat))
      datasets.push(ds(`${t('retinal.kpi.srf')} · ${lat}`,   s.srf,   BIOMARKER_COLORS.srf,   lat))
      datasets.push(ds(`${t('retinal.kpi.ped')} · ${lat}`,   s.ped,   BIOMARKER_COLORS.ped,   lat))
      datasets.push(ds(`${t('retinal.kpi.total')} · ${lat}`, s.total, '#64748b',              lat))
    }
    return { labels, datasets }
  }

  // Single-series tasks (onl / pr / ga) — still split per eye.
  const label = labelForSingleTask(props.task)
  const color = colorForSingleTask(props.task)
  const buckets: Record<'OD' | 'OS', Array<number | null>> = {
    OD: nullArr(xValues.length),
    OS: nullArr(xValues.length),
  }
  for (const p of points.value) {
    const lat = normalizeLat(p.eyeLaterality)
    if (lat === 'OTHER') continue
    const idx = xIndex.get(xAxisIso(p))
    if (idx == null) continue
    buckets[lat][idx] = toNumber(p.primaryMetricValue)
  }
  const datasets: ChartDataset[] = []
  for (const lat of lats) {
    if (buckets[lat].every((v) => v == null)) continue
    datasets.push(ds(`${label} · ${lat}`, buckets[lat], color, lat))
  }
  return { labels, datasets }
})

function nullArr(n: number): Array<number | null> {
  return Array.from({ length: n }, () => null)
}

function ds(
  label: string,
  data: Array<number | null>,
  color: string,
  /**
   * 2026-06-23 — laterality discriminator; dashes the OS lines so the
   * eye is distinguishable on grayscale prints / colour-blind users
   * even before the legend label is read. OD keeps the solid stroke.
   */
  lat: 'OD' | 'OS' = 'OD',
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
    borderDash: lat === 'OS' ? [5, 4] : [],
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
      // 2026-06-23 — always show; single-task charts now carry up to
      // two series (OD + OS) so a legend is needed to disambiguate.
      display: true,
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
