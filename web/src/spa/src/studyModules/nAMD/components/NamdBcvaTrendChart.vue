<script setup lang="ts">
/**
 * nAMD workspace — BCVA-over-time chart.
 *
 * 2026-06-30 — focused complement to the larger {@link NamdFluidTrendChart}.
 * Plots one line: BCVA letters per visit. Injection markers
 * (small navy triangles) line up along the baseline at every visit
 * where a drug was administered. Visible on BOTH arms — BCVA is a
 * fundamental clinical metric and the chart doubles as the
 * control-arm primary trend (where the fluid plot is hidden).
 *
 * <h2>Geometry</h2>
 *
 * <p>Pure SVG; same drawing idiom as {@link NamdFluidTrendChart} —
 * 760 × 220 viewBox with a 250 × 130 plot area + a 40-px baseline
 * strip for the injection markers. Y-range is adaptive: clamped to
 * [{@link MIN_LETTERS}, {@link MAX_LETTERS}] but expanded to cover
 * the actual data range with {@link Y_PAD_LETTERS} of headroom either
 * side so a five-letter shift doesn't get visually crushed.
 *
 * <p>Visits with {@code bcva === 0} are treated as MISSING — the
 * polyline breaks at that point so the chart doesn't dive to zero
 * (the BCVA chart for legacy event-CRFs where no BCVA was captured
 * shouldn't read "patient went blind").
 */

import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { NamdVisit } from '../types'

interface Props {
  visits: NamdVisit[]
}
const props = defineProps<Props>()
const { t } = useI18n()

// ── Geometry ───────────────────────────────────────────────────────
const W = 760
const PL = 60  // plot-area left padding (room for y-axis tick labels)
const PR = 16  // plot-area right padding
const PT = 14  // plot-area top padding (room for y-axis upper tick)
const PH = 150 // plot-area height
const BH = 40  // baseline strip height (injection markers + x-axis ticks)
const H = PT + PH + BH

const MIN_LETTERS = 50
const MAX_LETTERS = 90
const Y_PAD_LETTERS = 3

const present = computed<NamdVisit[]>(() =>
  props.visits.filter((v) => v.bcva > 0),
)

const yRange = computed<{ lo: number; hi: number }>(() => {
  if (present.value.length === 0) {
    return { lo: MIN_LETTERS, hi: MAX_LETTERS }
  }
  const vals = present.value.map((v) => v.bcva)
  const lo = Math.max(MIN_LETTERS, Math.min(...vals) - Y_PAD_LETTERS)
  const hi = Math.min(MAX_LETTERS, Math.max(...vals) + Y_PAD_LETTERS)
  // Defensive: a zero-width band stretches to a sensible 10-letter band.
  if (hi - lo < 10) {
    const mid = (lo + hi) / 2
    return { lo: Math.max(MIN_LETTERS, mid - 5), hi: Math.min(MAX_LETTERS, mid + 5) }
  }
  return { lo, hi }
})

function yBcva(value: number): number {
  const { lo, hi } = yRange.value
  const clamped = Math.max(lo, Math.min(hi, value))
  return PT + (1 - (clamped - lo) / (hi - lo)) * PH
}

const n = computed(() => props.visits.length)
const innerW = computed(() => W - PL - PR)
function xAt(idx: number): number {
  if (n.value <= 1) return PL + innerW.value / 2
  return PL + (idx / (n.value - 1)) * innerW.value
}

interface BcvaPoint {
  v: NamdVisit
  idx: number
  x: number
  y: number
}

const points = computed<BcvaPoint[]>(() =>
  props.visits
    .map((v, idx) => ({ v, idx, x: xAt(idx), y: yBcva(v.bcva) }))
    .filter((p) => p.v.bcva > 0),
)

/**
 * SVG path string for the BCVA line. Each contiguous run of present
 * visits emits one M…L…L… subpath; a gap in the visits array
 * (bcva=0) terminates the run + restarts on the next present visit.
 */
const linePath = computed<string>(() => {
  if (points.value.length === 0) return ''
  let d = ''
  let prevIdx = -2 // sentinel — guarantees the first point opens with M
  for (const p of points.value) {
    if (p.idx !== prevIdx + 1) {
      d += `M ${p.x.toFixed(2)} ${p.y.toFixed(2)} `
    } else {
      d += `L ${p.x.toFixed(2)} ${p.y.toFixed(2)} `
    }
    prevIdx = p.idx
  }
  return d.trim()
})

const yTicks = computed<number[]>(() => {
  const { lo, hi } = yRange.value
  const step = hi - lo >= 30 ? 10 : 5
  const out: number[] = []
  const start = Math.ceil(lo / step) * step
  for (let v = start; v <= hi; v += step) out.push(v)
  return out
})

const injectionVisits = computed<NamdVisit[]>(() =>
  props.visits.filter((v) => v.inj && v.inj.trim() !== ''),
)

function injectionTooltip(v: NamdVisit): string {
  return `${v.label} · ${v.date || '—'} · ${v.inj}`
}
function pointTooltip(p: BcvaPoint): string {
  const inj = p.v.inj ? ` · ${p.v.inj}` : ''
  return `${p.v.label} · ${p.v.date || '—'} · ${p.v.bcva} L${inj}`
}
</script>

<template>
  <div data-testid="namd-bcva-trend-chart" class="space-y-2">
    <div class="flex items-center gap-3 text-[11px] text-slate-500">
      <span class="inline-flex items-center gap-1.5">
        <svg viewBox="0 0 14 6" class="w-4 h-2">
          <line x1="0" x2="14" y1="3" y2="3" stroke="#0284c7" stroke-width="2" />
        </svg>
        BCVA (Buchst.)
      </span>
      <span class="inline-flex items-center gap-1.5">
        <svg viewBox="0 0 10 10" class="w-3 h-3">
          <polygon points="5,0 10,10 0,10" fill="#111d4e" />
        </svg>
        {{ t('studyModules.namd.trend.injectionLegend') }}
      </span>
    </div>
    <svg
      role="img"
      :aria-label="t('studyModules.namd.bcvaTrend.aria')"
      :viewBox="`0 0 ${W} ${H}`"
      class="w-full h-auto"
    >
      <!-- Y grid + tick labels -->
      <g>
        <line
          v-for="(t, i) in yTicks"
          :key="`grid-${i}`"
          :x1="PL"
          :x2="W - PR"
          :y1="yBcva(t)"
          :y2="yBcva(t)"
          stroke="#e2e8f0"
          stroke-width="1"
        />
        <text
          v-for="(t, i) in yTicks"
          :key="`ticklbl-${i}`"
          :x="PL - 8"
          :y="yBcva(t) + 4"
          text-anchor="end"
          font-size="11"
          fill="#64748b"
        >{{ t }}</text>
        <!-- y-axis title -->
        <text
          :x="14"
          :y="PT + PH / 2"
          text-anchor="middle"
          font-size="10"
          fill="#94a3b8"
          :transform="`rotate(-90 14 ${PT + PH / 2})`"
        >Buchstaben</text>
      </g>

      <!-- BCVA line -->
      <path
        :d="linePath"
        fill="none"
        stroke="#0284c7"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <g>
        <g v-for="(p, i) in points" :key="`pt-${i}`">
          <circle
            :cx="p.x"
            :cy="p.y"
            r="3.5"
            fill="#fff"
            stroke="#0284c7"
            stroke-width="1.5"
          />
          <title>{{ pointTooltip(p) }}</title>
        </g>
      </g>

      <!-- Baseline + visit-label strip -->
      <line
        :x1="PL"
        :x2="W - PR"
        :y1="PT + PH"
        :y2="PT + PH"
        stroke="#94a3b8"
        stroke-width="1"
      />
      <g>
        <text
          v-for="(v, i) in props.visits"
          :key="`xlbl-${i}`"
          :x="xAt(i)"
          :y="PT + PH + 14"
          text-anchor="middle"
          font-size="10"
          fill="#64748b"
        >{{ v.label }}</text>
      </g>

      <!-- Injection markers -->
      <g>
        <g v-for="(v, i) in injectionVisits" :key="`inj-${i}`">
          <polygon
            :points="`${xAt(props.visits.indexOf(v))},${PT + PH + 20} ${xAt(props.visits.indexOf(v)) + 4},${PT + PH + 28} ${xAt(props.visits.indexOf(v)) - 4},${PT + PH + 28}`"
            fill="#111d4e"
          />
          <title>{{ injectionTooltip(v) }}</title>
        </g>
      </g>
    </svg>
  </div>
</template>
