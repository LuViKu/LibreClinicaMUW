<script setup lang="ts">
/**
 * nAMD workspace — fluid-over-time chart.
 *
 * Pure-SVG port of {@code FluidTrendChart} from namd-overview.jsx —
 * 760 × (250 + 70) drawing, no Chart.js. Three stacked polygons
 * (PED bottom → SRF → IRF top) on a left fluid axis [0..560 nL],
 * a CRT polyline on the right axis [250..500 µm], teal injection
 * markers along the baseline, and a sky-blue BCVA strip below.
 *
 * Hover anywhere over a visit's column reveals a tooltip with the
 * five metrics (IRF / SRF / PED / CRT / BCVA) and the formatted
 * visit date. The current visit's X label is rendered in coral.
 *
 * On first paint the stacked-area + CRT + BCVA layers are revealed
 * by a clip-path width transition (~1.1s); a {@code prefers-reduced-motion}
 * media query skips the animation. Per the design's "no .png for
 * plotting" rule this draws every pixel inline.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { FLUID } from '../fluid'
import type { NamdVisit } from '../types'

interface Props {
  visits: NamdVisit[]
}

const props = defineProps<Props>()
const { t } = useI18n()

const W = 760
const H = 250
const PL = 46
const PR = 44
const PT = 16
const PB = 28
const BH = 70 // BCVA strip height
const BPT = 8 // BCVA strip top padding

const hoverIdx = ref<number | null>(null)
const mounted = ref(false)

onMounted(() => {
  // Mirror the design's 60ms-then-reveal pattern. A reduced-motion
  // preference skips the transition by jumping straight to mounted.
  const reduce = typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduce) {
    mounted.value = true
    return
  }
  const t = setTimeout(() => { mounted.value = true }, 60)
  return () => clearTimeout(t)
})

const maxWeek = computed(() => {
  if (props.visits.length === 0) return 1
  return Math.max(1, props.visits[props.visits.length - 1]!.week)
})

function xAt(week: number): number {
  return PL + (week / maxWeek.value) * (W - PL - PR)
}

const FLUID_MAX = 560
function yFluid(value: number): number {
  return PT + (1 - value / FLUID_MAX) * (H - PT - PB)
}

const CRT_MIN = 250
const CRT_MAX = 500
function yCrt(value: number): number {
  return PT + (1 - (value - CRT_MIN) / (CRT_MAX - CRT_MIN)) * (H - PT - PB)
}

const BCVA_MIN = 58
const BCVA_MAX = 84
function yBcva(value: number): number {
  return H + BPT + (1 - (value - BCVA_MIN) / (BCVA_MAX - BCVA_MIN)) * (BH - BPT - 14)
}

interface Layer {
  key: 'IRF' | 'SRF' | 'PED'
  color: string
  points: string
}

const gridVals = [0, 140, 280, 420, 560]
const crtTicks = [250, 375, 500]

const layers = computed<Layer[]>(() => {
  // Build stacked polygons: PED bottom → SRF → IRF top so an
  // increase in subretinal fluid pushes the IRF cap upward
  // visibly.
  let baseline = props.visits.map(() => 0)
  const order: Array<{ key: Layer['key']; field: 'irf' | 'srf' | 'ped' }> = [
    { key: 'PED', field: 'ped' },
    { key: 'SRF', field: 'srf' },
    { key: 'IRF', field: 'irf' },
  ]
  return order.map(({ key, field }) => {
    const lower = baseline.slice()
    const upper = props.visits.map((v, i) => lower[i]! + v[field])
    baseline = upper
    const top = props.visits.map(
      (v, i) => `${xAt(v.week).toFixed(1)},${yFluid(upper[i]!).toFixed(1)}`,
    )
    const bot = props.visits
      .map((v, i) => `${xAt(v.week).toFixed(1)},${yFluid(lower[i]!).toFixed(1)}`)
      .reverse()
    return { key, color: FLUID[key].color, points: [...top, ...bot].join(' ') }
  })
})

const crtPath = computed(() => {
  if (props.visits.length === 0) return ''
  return props.visits
    .map((v, i) => `${i === 0 ? 'M' : 'L'} ${xAt(v.week).toFixed(1)} ${yCrt(v.crt).toFixed(1)}`)
    .join(' ')
})

const bcvaPath = computed(() => {
  if (props.visits.length === 0) return ''
  return props.visits
    .map((v, i) => `${i === 0 ? 'M' : 'L'} ${xAt(v.week).toFixed(1)} ${yBcva(v.bcva).toFixed(1)}`)
    .join(' ')
})

const lastBcva = computed(() => {
  if (props.visits.length === 0) return null
  return props.visits[props.visits.length - 1]!.bcva
})

const currentIdx = computed(() => props.visits.length - 1)

const injectionVisits = computed(() =>
  props.visits
    .map((v, i) => ({ v, i }))
    .filter(({ v }) => v.inj && v.inj.length > 0),
)

const revealWidth = computed(() =>
  mounted.value ? W - PL - PR : 0,
)

const hovered = computed(() =>
  hoverIdx.value == null ? null : (props.visits[hoverIdx.value] ?? null),
)

const hoveredXPct = computed(() => {
  if (hovered.value == null) return 0
  return (xAt(hovered.value.week) / W) * 100
})

const reduceMotion = computed(() =>
  typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches,
)

function onHover(i: number | null): void {
  hoverIdx.value = i
}

const dateFormatter = computed(() => {
  if (typeof Intl === 'undefined') return null
  try {
    return new Intl.DateTimeFormat('de-AT', { day: '2-digit', month: 'short', year: 'numeric' })
  } catch {
    return null
  }
})

function fmtDate(iso: string): string {
  if (!iso) return ''
  if (!dateFormatter.value) return iso
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return dateFormatter.value.format(d)
}
</script>

<template>
  <figure
    data-testid="namd-fluid-trend-chart"
    class="relative"
    role="img"
    :aria-label="t('studyModules.namd.trend.aria')"
  >
    <svg
      :viewBox="`0 0 ${W} ${H + BH + 6}`"
      width="100%"
      style="overflow: visible"
      data-testid="namd-fluid-trend-svg"
    >
      <!-- Gridlines + left axis (fluid, nL).
           2026-06-23 user-feedback round — dotted gridlines per the
           design's dotted-grid aesthetic. -->
      <g>
        <line
          v-for="g in gridVals"
          :key="`gy-${g}`"
          :x1="PL"
          :x2="W - PR"
          :y1="yFluid(g)"
          :y2="yFluid(g)"
          stroke="#cbd5e1"
          stroke-width="1"
          stroke-dasharray="1 4"
          stroke-linecap="round"
        />
        <text
          v-for="g in gridVals"
          :key="`gl-${g}`"
          :x="PL - 8"
          :y="yFluid(g) + 3"
          text-anchor="end"
          font-size="9.5"
          fill="#aab2c2"
        >{{ g }}</text>
        <text
          :x="PL - 8"
          :y="yFluid(560) - 8"
          text-anchor="end"
          font-size="9"
          fill="#8b94a7"
          font-weight="600"
        >nL</text>
      </g>

      <!-- Right axis (CRT, µm) -->
      <g>
        <text
          v-for="c in crtTicks"
          :key="`cl-${c}`"
          :x="W - PR + 8"
          :y="yCrt(c) + 3"
          text-anchor="start"
          font-size="9.5"
          fill="#aab2c2"
        >{{ c }}</text>
        <text
          :x="W - PR + 8"
          :y="yCrt(500) - 8"
          text-anchor="start"
          font-size="9"
          fill="#8b94a7"
          font-weight="600"
        >µm</text>
      </g>

      <!-- Reveal clip — the stacked fluid + CRT + BCVA layers
           grow from the left over 1.1s on first mount. The
           clip is at the SVG element level so the polygons,
           CRT line, CRT dots, and BCVA strip all reveal in
           lockstep. -->
      <clipPath id="namd-trend-reveal">
        <rect
          :x="PL"
          y="0"
          :width="revealWidth"
          :height="H + BH"
          :style="reduceMotion ? '' : 'transition: width 1.1s cubic-bezier(.22,1,.36,1)'"
        />
      </clipPath>

      <g clip-path="url(#namd-trend-reveal)">
        <polygon
          v-for="l in layers"
          :key="`poly-${l.key}`"
          :data-testid="`namd-trend-poly-${l.key}`"
          :points="l.points"
          :fill="l.color"
          fill-opacity="0.82"
        />
        <path
          :d="crtPath"
          fill="none"
          stroke="#111d4e"
          stroke-width="2"
          data-testid="namd-trend-crt"
        />
        <circle
          v-for="(v, i) in visits"
          :key="`crt-dot-${i}`"
          :cx="xAt(v.week)"
          :cy="yCrt(v.crt)"
          r="2.6"
          fill="#fff"
          stroke="#111d4e"
          stroke-width="1.6"
        />
      </g>

      <!-- Injection markers — teal droplet + dashed guide. -->
      <g>
        <g
          v-for="({ v, i }) in injectionVisits"
          :key="`inj-${i}`"
          :transform="`translate(${xAt(v.week)}, ${H - PB + 14})`"
          data-testid="namd-trend-injection"
        >
          <line
            x1="0"
            :y1="-(H - PB + 14 - PT)"
            x2="0"
            y2="0"
            stroke="#cdd4e0"
            stroke-width="1"
            stroke-dasharray="2 3"
            :opacity="mounted ? 0.7 : 0"
            :style="reduceMotion ? '' : 'transition: opacity .6s ease .5s'"
          />
          <circle cx="0" cy="0" r="6.5" fill="#e4f2ef" stroke="#2f8e91" stroke-width="1" />
          <path
            d="M0 -3.2 C 2.4 -0.6 2.6 1 0 2.6 C -2.6 1 -2.4 -0.6 0 -3.2 Z"
            fill="#2f8e91"
          />
        </g>
      </g>

      <!-- X labels — visit id under each tick. Trailing/current
           visit takes the design's coral accent + bold weight. -->
      <g>
        <text
          v-for="(v, i) in visits"
          :key="`xl-${v.id}`"
          :x="xAt(v.week)"
          :y="H - 2"
          text-anchor="middle"
          font-size="9.5"
          :fill="i === currentIdx ? '#b04a30' : '#8b94a7'"
          :font-weight="i === currentIdx ? 700 : 400"
        >{{ v.label }}</text>
      </g>

      <!-- Hover columns — generous 28px width per visit, so the
           operator can land on a tick without precise aim. -->
      <g>
        <rect
          v-for="(v, i) in visits"
          :key="`hover-${v.id}`"
          :data-testid="`namd-trend-col-${i}`"
          :x="xAt(v.week) - 14"
          :y="PT"
          width="28"
          :height="H - PT - PB"
          fill="transparent"
          style="cursor: pointer"
          @mouseenter="onHover(i)"
          @mouseleave="onHover(null)"
        />
        <line
          v-if="hoverIdx != null"
          :x1="xAt(visits[hoverIdx]!.week)"
          :y1="PT"
          :x2="xAt(visits[hoverIdx]!.week)"
          :y2="H - PB"
          stroke="#111d4e"
          stroke-width="1"
          opacity="0.25"
        />
      </g>

      <!-- BCVA strip — sky-blue polyline + dots, axis title left,
           trailing-value annotation right. -->
      <g>
        <text
          :x="PL - 8"
          :y="yBcva(82) - 4"
          text-anchor="end"
          font-size="9"
          fill="#8b94a7"
          font-weight="600"
        >BCVA</text>
        <line
          :x1="PL"
          :y1="yBcva(BCVA_MIN) + 6"
          :x2="W - PR"
          :y2="yBcva(BCVA_MIN) + 6"
          stroke="#cbd5e1"
          stroke-dasharray="1 4"
          stroke-linecap="round"
        />
        <path
          :d="bcvaPath"
          fill="none"
          stroke="#5fb4e5"
          stroke-width="2"
          clip-path="url(#namd-trend-reveal)"
          data-testid="namd-trend-bcva"
        />
        <circle
          v-for="(v, i) in visits"
          :key="`bcva-dot-${i}`"
          :cx="xAt(v.week)"
          :cy="yBcva(v.bcva)"
          r="2.4"
          fill="#5fb4e5"
          clip-path="url(#namd-trend-reveal)"
        />
        <text
          v-if="lastBcva != null"
          :x="W - PR"
          :y="yBcva(lastBcva) - 7"
          text-anchor="end"
          font-size="9.5"
          fill="#1d6c98"
          font-weight="600"
        >{{ lastBcva }} {{ t('studyModules.namd.trend.bcvaSuffix') }}</text>
      </g>
    </svg>

    <!-- Hover tooltip — anchored to the column's X with all five
         metrics + the formatted visit date. -->
    <div
      v-if="hovered"
      data-testid="namd-trend-tooltip"
      class="absolute -top-1 bg-white rounded-lg shadow-lg ring-1 ring-slate-200 px-3 py-2 text-[11px] pointer-events-none z-10"
      :style="`left: ${hoveredXPct}%; transform: translate(-50%,-100%)`"
    >
      <div class="font-semibold text-slate-800 mb-1 whitespace-nowrap">
        {{ hovered.label }} · {{ fmtDate(hovered.date) }}
      </div>
      <div class="grid grid-cols-2 gap-x-3 gap-y-0.5 tabular-nums">
        <span class="inline-flex items-center gap-1.5">
          <span class="w-2 h-2 rounded-full" :style="`background:${FLUID.IRF.color}`" />IRF
        </span>
        <span class="text-right font-medium">{{ hovered.irf }} nL</span>
        <span class="inline-flex items-center gap-1.5">
          <span class="w-2 h-2 rounded-full" :style="`background:${FLUID.SRF.color}`" />SRF
        </span>
        <span class="text-right font-medium">{{ hovered.srf }} nL</span>
        <span class="inline-flex items-center gap-1.5">
          <span class="w-2 h-2 rounded-full" :style="`background:${FLUID.PED.color}`" />PED
        </span>
        <span class="text-right font-medium">{{ hovered.ped }} nL</span>
        <span class="text-slate-500">CRT</span>
        <span class="text-right font-medium">{{ hovered.crt }} µm</span>
        <span class="text-slate-500">BCVA</span>
        <span class="text-right font-medium">{{ hovered.bcva }}</span>
      </div>
    </div>
  </figure>
</template>
