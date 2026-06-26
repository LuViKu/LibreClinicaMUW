<script setup lang="ts">
/**
 * nAMD treat-and-extend Slice 4 (2026-06-20) — visit-to-visit
 * fluid comparison panel.
 *
 * Renders a compact strip of delta KPI tiles ("IRF 0.42 → 0.18,
 * ▼ −57%") sourced from {@code GET /retinal-jobs/{id}/compare-previous}.
 * Lives below the main KPI strip in {@code RetinalMetricsView}; hides
 * when there is no prior visit (the endpoint returns
 * {@code previousJobId=null}).
 *
 * Doesn't pull the fundus side-by-side in the MVP — that's a fast
 * follow-up. The numeric delta is the load-bearing decision input
 * for treat-and-extend, the visual is supplementary.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { comparePrevious } from '@/api/retinal'
import type { RetinalJobCompare } from '@/api/retinal'

const { t } = useI18n()

interface Props {
  jobId: number
}
const props = defineProps<Props>()

const data = ref<RetinalJobCompare | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await comparePrevious(props.jobId)
  } catch (e) {
    data.value = null
    error.value = e instanceof Error ? e.message : 'Failed to load visit comparison'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.jobId, load)

const hasPrevious = computed<boolean>(() => data.value?.previousJobId != null)

interface DeltaTile {
  key: string
  label: string
  current: number | null
  previous: number | null
  delta: number | null
  pct: number | null
}

// 2026-06-26 — the fluid task's output_payload uses `total_mm3` for
// the sum of all three biomarkers (under the nested `biomarkers` map).
// The previous key (`total_fluid_volume_mm3`) never matched anything
// on the backend, so the "Total" tile rendered "—" even on jobs with
// a real previous-visit comparison. The controller's compareToPrevious
// flattens the nested `biomarkers` block into the deltas map so the
// keys here line up 1:1 with the entries on `data.deltas`.
const METRIC_LABELS: Record<string, string> = {
  irf_mm3: 'IRF',
  srf_mm3: 'SRF',
  ped_mm3: 'PED',
  total_mm3: 'Total',
}

/**
 * Read a numeric metric from a payload that may nest fluid biomarkers
 * inside a {@code biomarkers} object. The fluid task's payload shape
 * is {@code { biomarkers: { irf_mm3, srf_mm3, ped_mm3, total_mm3 }, ... }};
 * older / future tasks may surface metrics at the top level. Look in
 * {@code biomarkers} first, fall through to the top level. Anything
 * non-numeric returns null so the tile renders an em-dash.
 */
function readMetric(payload: Record<string, unknown> | undefined, key: string): number | null {
  if (!payload) return null
  const bio = payload['biomarkers']
  if (bio != null && typeof bio === 'object') {
    const v = (bio as Record<string, unknown>)[key]
    if (typeof v === 'number') return v
  }
  const top = payload[key]
  return typeof top === 'number' ? top : null
}

const tiles = computed<DeltaTile[]>(() => {
  const d = data.value
  if (!d || d.previousJobId == null) return []
  return Object.entries(METRIC_LABELS).map(([key, label]) => {
    const cur = readMetric(d.currentMetrics as Record<string, unknown>, key)
    const prev = readMetric(d.previousMetrics as Record<string, unknown>, key)
    const delta = key in (d.deltas ?? {}) ? d.deltas[key] : null
    const pct = (prev != null && prev !== 0 && delta != null) ? (delta / prev) * 100 : null
    return { key, label, current: cur, previous: prev, delta, pct }
  })
})

function arrow(delta: number | null): string {
  if (delta == null) return '—'
  if (delta < -1e-6) return '▼'
  if (delta > 1e-6) return '▲'
  return '◆'
}

function trendClass(delta: number | null): string {
  // For nAMD fluid, DOWN (negative delta) is the clinically good direction.
  if (delta == null) return 'text-slate-500'
  if (delta < -1e-6) return 'text-emerald-700'
  if (delta > 1e-6) return 'text-rose-700'
  return 'text-slate-500'
}

function formatVol(v: number | null): string {
  if (v == null) return '—'
  return v.toFixed(3)
}

function formatPct(v: number | null): string {
  if (v == null) return ''
  return `${v >= 0 ? '+' : ''}${v.toFixed(0)}%`
}
</script>

<template>
  <section
    v-if="hasPrevious && tiles.length > 0"
    data-testid="retinal-visit-comparison"
    class="bg-white border border-slate-200 rounded-muw p-4 mb-5"
  >
    <header class="flex items-baseline justify-between mb-3">
      <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
        {{ t('retinal.compare.header') }}
      </h2>
      <span class="text-xs text-slate-500">
        {{ t('retinal.compare.daysAgo', { days: data?.daysBetween ?? 0 }) }}
      </span>
    </header>
    <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
      <div
        v-for="tile in tiles"
        :key="tile.key"
        :data-testid="`compare-${tile.key}`"
        class="border border-slate-100 rounded-md p-2.5"
      >
        <div class="text-[11px] uppercase tracking-wider text-slate-500">
          {{ tile.label }}
        </div>
        <div class="mt-0.5 text-sm font-medium tabular-nums">
          {{ formatVol(tile.previous) }}
          <span class="text-slate-400 mx-1">→</span>
          {{ formatVol(tile.current) }}
        </div>
        <div :class="['mt-0.5 text-xs tabular-nums', trendClass(tile.delta)]">
          {{ arrow(tile.delta) }} {{ formatPct(tile.pct) }}
        </div>
      </div>
    </div>
  </section>

  <section
    v-else-if="loading"
    data-testid="retinal-visit-comparison-loading"
    class="bg-white border border-slate-200 rounded-muw p-4 mb-5 text-xs text-slate-500"
  >
    {{ t('retinal.compare.loading') }}
  </section>

  <section
    v-else-if="error"
    data-testid="retinal-visit-comparison-error"
    class="rounded-muw border border-rose-200 bg-rose-50 px-4 py-3 mb-5 text-xs text-rose-800"
  >
    {{ t('retinal.compare.errorPrefix') }} {{ error }}
  </section>

  <!-- First visit (no previous): silent — the rest of RetinalMetricsView
       already communicates that today's metrics stand alone. -->
</template>
