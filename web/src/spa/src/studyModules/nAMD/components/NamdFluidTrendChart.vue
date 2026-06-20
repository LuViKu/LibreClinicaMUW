<script setup lang="ts">
/**
 * nAMD workspace — fluid trend chart.
 *
 * Hand-rolled SVG (760 × 250) showing per-visit fluid biomarker volumes
 * as three stacked polygon traces (IRF / SRF / PED) plus a thin BCVA
 * strip below. Mirrors the design's {@code FluidTrendChart} from
 * namd-overview.jsx — DOM structure preserved 1:1 so the design's
 * CSS hooks keep working.
 *
 * <p>Interaction: hovering a column highlights its index + shows a
 * tooltip with the four metrics (IRF / SRF / PED / CRT / BCVA). Hover
 * is purely client-side, no router / store interaction.
 *
 * <p>Chosen over vue-chartjs because:
 *   1. The design's polygon shapes are bespoke (filled area, not
 *      stacked bar) and don't map onto a Chart.js dataset cleanly.
 *   2. Avoids adding a per-tab Chart.js dependency footprint when the
 *      Compare tab + ETDRS sub-tables already pull it in.
 *   3. Vitest can assert against the rendered {@code <polygon>}/{@code <rect>}
 *      directly without a JSDOM canvas shim.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { FLUID } from '../fluid'
import type { NamdVisit } from '../types'

interface Props {
  visits: NamdVisit[]
}

const props = defineProps<Props>()
const { t } = useI18n()

// Layout — match the design's 760 × 250 viewport. The chart drawing
// area inset leaves room for the BCVA strip below.
const W = 760
const H = 250
const PAD_L = 36
const PAD_R = 12
const PAD_T = 14
const FLUID_H = 170
const BCVA_TOP = PAD_T + FLUID_H + 12
const BCVA_H = 40

const hoverIdx = ref<number | null>(null)

const maxFluid = computed(() => {
  let m = 0
  for (const v of props.visits) m = Math.max(m, v.irf + v.srf + v.ped, 50)
  return Math.ceil(m / 10) * 10
})

const maxBcva = computed(() => {
  let m = 0
  for (const v of props.visits) m = Math.max(m, v.bcva)
  return Math.max(60, Math.ceil(m / 5) * 5)
})

function xAt(i: number): number {
  const n = props.visits.length
  if (n <= 1) return PAD_L
  const inner = W - PAD_L - PAD_R
  return PAD_L + (i / (n - 1)) * inner
}

function fluidY(value: number): number {
  return PAD_T + FLUID_H - (value / maxFluid.value) * FLUID_H
}

function bcvaY(value: number): number {
  return BCVA_TOP + BCVA_H - (value / maxBcva.value) * BCVA_H
}

interface Series {
  key: 'IRF' | 'SRF' | 'PED'
  poly: string
  color: string
}

const series = computed<Series[]>(() => {
  const xs = props.visits.map((_, i) => xAt(i))
  const baseY = PAD_T + FLUID_H
  return (['IRF', 'SRF', 'PED'] as const).map((k) => {
    const ys = props.visits.map((v) =>
      fluidY(k === 'IRF' ? v.irf : k === 'SRF' ? v.srf : v.ped),
    )
    const top = xs.map((x, i) => `${x},${ys[i]}`).join(' ')
    const bot = `${xs[xs.length - 1]},${baseY} ${xs[0]},${baseY}`
    return { key: k, poly: `${top} ${bot}`, color: FLUID[k].color }
  })
})

const bcvaPoly = computed(() => {
  if (props.visits.length === 0) return ''
  return props.visits.map((v, i) => `${xAt(i)},${bcvaY(v.bcva)}`).join(' ')
})

const yTicks = computed(() => {
  const m = maxFluid.value
  return [0, m / 2, m].map((value) => ({ value, y: fluidY(value) }))
})

function onHover(idx: number | null) {
  hoverIdx.value = idx
}

const hovered = computed(() => (hoverIdx.value == null ? null : props.visits[hoverIdx.value] ?? null))
</script>

<template>
  <figure
    data-testid="namd-fluid-trend-chart"
    class="relative"
    role="img"
    :aria-label="t('studyModules.namd.trend.aria')"
  >
    <svg
      :viewBox="`0 0 ${W} ${H}`"
      class="w-full h-auto block"
      data-testid="namd-fluid-trend-svg"
      preserveAspectRatio="none"
    >
      <!-- y-axis grid + labels -->
      <g class="text-slate-400" font-size="10">
        <line
          v-for="tick in yTicks"
          :key="`gy-${tick.value}`"
          :x1="PAD_L"
          :x2="W - PAD_R"
          :y1="tick.y"
          :y2="tick.y"
          stroke="#e2e8f0"
        />
        <text
          v-for="tick in yTicks"
          :key="`yl-${tick.value}`"
          :x="PAD_L - 6"
          :y="tick.y + 3"
          text-anchor="end"
          fill="#94a3b8"
        >{{ Math.round(tick.value) }}</text>
      </g>

      <!-- Stacked fluid polygons -->
      <polygon
        v-for="s in series"
        :key="s.key"
        :data-testid="`namd-trend-poly-${s.key}`"
        :points="s.poly"
        :fill="s.color"
        fill-opacity="0.18"
        :stroke="s.color"
        stroke-width="1.5"
      />

      <!-- BCVA strip -->
      <g>
        <rect
          :x="PAD_L"
          :y="BCVA_TOP"
          :width="W - PAD_L - PAD_R"
          :height="BCVA_H"
          fill="#f8fafc"
        />
        <polyline
          data-testid="namd-trend-bcva"
          :points="bcvaPoly"
          fill="none"
          stroke="#0f766e"
          stroke-width="1.5"
        />
        <text
          :x="PAD_L"
          :y="BCVA_TOP - 4"
          font-size="10"
          fill="#64748b"
        >BCVA · ETDRS Letters</text>
      </g>

      <!-- Hover columns + dots -->
      <g>
        <rect
          v-for="(v, i) in visits"
          :key="`hover-${v.id}`"
          :data-testid="`namd-trend-col-${i}`"
          :x="xAt(i) - 18"
          :y="PAD_T"
          width="36"
          :height="H - PAD_T"
          fill="transparent"
          @mouseenter="onHover(i)"
          @mouseleave="onHover(null)"
        />
        <line
          v-if="hoverIdx != null"
          :x1="xAt(hoverIdx)"
          :x2="xAt(hoverIdx)"
          :y1="PAD_T"
          :y2="H - 4"
          stroke="#94a3b8"
          stroke-dasharray="3 3"
          stroke-width="1"
        />
      </g>
    </svg>

    <!-- Tooltip — absolutely positioned over the chart -->
    <div
      v-if="hovered"
      data-testid="namd-trend-tooltip"
      class="absolute top-2 left-1/2 -translate-x-1/2 rounded-md bg-slate-900 text-white text-[11px] px-3 py-2 shadow-md pointer-events-none"
    >
      <div class="font-semibold mb-1">{{ hovered.label }} · W{{ hovered.week }}</div>
      <div class="grid grid-cols-2 gap-x-3 gap-y-0.5 tabular-nums">
        <span class="text-cyan-300">IRF</span><span>{{ hovered.irf }} nL</span>
        <span class="text-amber-300">SRF</span><span>{{ hovered.srf }} nL</span>
        <span class="text-purple-300">PED</span><span>{{ hovered.ped }} nL</span>
        <span class="text-emerald-300">CRT</span><span>{{ hovered.crt }} µm</span>
        <span class="text-teal-300">BCVA</span><span>{{ hovered.bcva }} L</span>
      </div>
    </div>
  </figure>
</template>
