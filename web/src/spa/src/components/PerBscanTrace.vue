<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — Per-B-scan biomarker trace.
 *
 * Renders the per-B-scan area arrays from Wave 2 as a Chart.js line
 * chart. For the {@code fluid} task we overlay IRF / SRF / PED in the
 * shared biomarker palette; for {@code ga} we render the single
 * RPE-loss area trace. {@code onl} / {@code pr} have no per-B-scan
 * trace — the metrics viewer suppresses this component for those
 * tasks via a parent {@code v-if}.
 *
 * <p>Chart.js + vue-chartjs are dynamic-imported via
 * {@code defineAsyncComponent} (same pattern as
 * {@code PatientDetailModal}); they only ship to operators who load
 * the viewer.
 *
 * <p>Hover wiring: the parent receives a {@code hoverBscan} event
 * carrying the slice index `z` so {@code FundusOverlay} can highlight
 * the matching polyline.
 */
import { computed, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import { BIOMARKER_COLORS } from './retinalPalette'

const { t } = useI18n()

interface Props {
  payload: Record<string, unknown>
  task: 'fluid' | 'ga'
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'hoverBscan', z: number | null): void
}>()

/**
 * Async Chart.js Line component. Mirrors the registration block from
 * {@code PatientDetailModal} so the imports are deduped at the
 * bundler level (Vite hashes the dynamic import target).
 */
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

interface ChartDataset {
  label: string
  data: number[]
  borderColor: string
  backgroundColor: string
  tension: number
  pointRadius: number
  pointHoverRadius: number
}

interface ChartData {
  labels: string[]
  datasets: ChartDataset[]
}

const chartData = computed<ChartData | null>(() => {
  if (props.task === 'fluid') {
    const per = (props.payload['per_bscan_mm2'] ?? {}) as {
      irf?: number[]
      srf?: number[]
      ped?: number[]
    }
    const irf = per.irf ?? []
    const srf = per.srf ?? []
    const ped = per.ped ?? []
    const length = Math.max(irf.length, srf.length, ped.length)
    if (length === 0) return null
    const labels = Array.from({ length }, (_, i) => String(i))
    return {
      labels,
      datasets: [
        {
          label: t('retinal.kpi.irf'),
          data: irf,
          borderColor: BIOMARKER_COLORS.irf,
          backgroundColor: BIOMARKER_COLORS.irf,
          tension: 0.2,
          pointRadius: 1,
          pointHoverRadius: 4,
        },
        {
          label: t('retinal.kpi.srf'),
          data: srf,
          borderColor: BIOMARKER_COLORS.srf,
          backgroundColor: BIOMARKER_COLORS.srf,
          tension: 0.2,
          pointRadius: 1,
          pointHoverRadius: 4,
        },
        {
          label: t('retinal.kpi.ped'),
          data: ped,
          borderColor: BIOMARKER_COLORS.ped,
          backgroundColor: BIOMARKER_COLORS.ped,
          tension: 0.2,
          pointRadius: 1,
          pointHoverRadius: 4,
        },
      ],
    }
  }
  // GA — single series.
  const per = (props.payload['per_bscan_mm2'] ?? []) as number[]
  if (per.length === 0) return null
  return {
    labels: per.map((_, i) => String(i)),
    datasets: [
      {
        label: t('retinal.kpi.gaArea'),
        data: per,
        borderColor: '#ec4899',
        backgroundColor: '#ec4899',
        tension: 0.2,
        pointRadius: 1,
        pointHoverRadius: 4,
      },
    ],
  }
})

const chartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: {
    mode: 'index' as const,
    intersect: false,
  },
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
          items.length ? t('retinal.perBscan.tooltipPrefix', { label: items[0].label }) : '',
      },
    },
  },
  scales: {
    x: {
      title: { display: true, text: t('retinal.perBscan.xAxisLabel'), font: { size: 10 } },
      ticks: { font: { size: 9 }, maxTicksLimit: 10 },
    },
    y: {
      title: { display: true, text: t('retinal.perBscan.yAxisLabel'), font: { size: 10 } },
      ticks: { font: { size: 9 } },
      beginAtZero: true,
    },
  },
  // Chart.js's hover plugin lets us intercept the active element +
  // bubble the slice index to the parent so FundusOverlay can
  // highlight the matching polyline.
  onHover: (_event: unknown, elements: Array<{ index: number }>) => {
    if (elements.length === 0) {
      emit('hoverBscan', null)
      return
    }
    emit('hoverBscan', elements[0].index)
  },
}))
</script>

<template>
  <div data-testid="per-bscan-trace" class="bg-white border border-slate-200 rounded-muw p-3 h-64">
    <div class="text-[10px] uppercase tracking-wider font-semibold text-slate-500 mb-2">
      {{ t('retinal.perBscan.header') }}
    </div>
    <div v-if="!chartData" class="text-xs text-slate-500 italic h-full flex items-center justify-center">
      {{ t('retinal.perBscan.empty') }}
    </div>
    <div v-else class="h-[calc(100%-1.5rem)]">
      <Line :data="chartData" :options="chartOptions" />
    </div>
  </div>
</template>
