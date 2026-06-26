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

/**
 * 2026-06-26 user-feedback round — ETDRS-ring region filter.
 *
 * <p>The chart's polygons read fluid biomarker values for one of the
 * three central rings the inference pipeline emits:
 *   - {@code c1} — central 1 mm (foveal core, ~0.79 mm² area)
 *   - {@code c3} — central 3 mm (foveal + parafoveal, ~7.07 mm² area)
 *   - {@code c6} — central 6 mm (full ETDRS grid, ~28.27 mm² area)
 *
 * <p>Default is {@code c6} (the widest ring + the legacy behaviour —
 * before this iteration the chart always plotted c6 values via
 * {@link NamdVisit.irf}/{@code srf}/{@code ped}). The dropdown lives
 * in the chart header next to the inline legend.
 */
type Region = 'c1' | 'c3' | 'c6'
const region = ref<Region>('c6')

/**
 * Visits with {@link NamdVisit.irf}/{@code srf}/{@code ped} swapped
 * to the selected region's values (when {@link NamdVisit.fluidByRegion}
 * is present). All downstream computeds (layers, fluidMax, tooltip)
 * read off this shape so the swap is transparent. For legacy
 * payloads with no {@link NamdVisit.fluidByRegion} we keep the flat
 * c6 values regardless of the selector — the data simply doesn't
 * carry the smaller-ring breakdown.
 */
const regionVisits = computed<NamdVisit[]>(() =>
  props.visits.map((v) => {
    const r = v.fluidByRegion
    if (!r) return v
    const slice = r[region.value]
    return { ...v, irf: Math.round(slice.irf), srf: Math.round(slice.srf), ped: Math.round(slice.ped) }
  }),
)

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
  if (regionVisits.value.length === 0) return 1
  return Math.max(1, regionVisits.value[regionVisits.value.length - 1]!.week)
})

function xAt(week: number): number {
  return PL + (week / maxWeek.value) * (W - PL - PR)
}

/**
 * 2026-06-24 user-feedback round — fluid Y-axis is dynamic.
 *
 * <p>The hardcoded 560 nL ceiling was a leftover from the design
 * mock (which uses an exsudative-AMD timeline that fits the
 * 0..560 band). Real datasets sit much lower — a "Trocken" patient
 * may never exceed 30 nL, and the chart collapsed to a sliver near
 * y=0 with most of the canvas empty. Compute the ceiling per
 * dataset:
 *
 * <ul>
 *   <li>Cap = max stack height (irf + srf + ped) across visits</li>
 *   <li>Rounded UP to a "nice" tick step — 10, 25, 50, 100, 200,
 *       500, 1000 — whichever lands the ceiling at exactly four
 *       evenly-spaced grid lines.</li>
 *   <li>Minimum 40 so a 0-fluid timeline still has a readable
 *       axis (otherwise the polygons collapse onto y=baseline).</li>
 * </ul>
 *
 * <p>The downstream {@code gridVals} computed re-derives the four
 * tick labels (0, ¼, ½, ¾, max) off the same ceiling.
 */
const fluidMax = computed(() => {
  let peak = 0
  for (const v of regionVisits.value) {
    const stack = (v.irf ?? 0) + (v.srf ?? 0) + (v.ped ?? 0)
    if (stack > peak) peak = stack
  }
  if (peak <= 40) return 40
  // Pick a "nice" step so the ceiling lands on a round number with
  // four evenly-spaced gridlines below it.
  const candidates = [50, 100, 200, 400, 500, 1000, 2000, 5000]
  for (const step of candidates) {
    if (peak <= step * 4) return step * 4
  }
  // Beyond 20 000 nL — uncommon; round to the next 5 000.
  return Math.ceil(peak / 5000) * 5000
})

function yFluid(value: number): number {
  return PT + (1 - value / fluidMax.value) * (H - PT - PB)
}

/**
 * 2026-06-25 — dynamic CRT axis. The previous hardcoded [250..500]
 * band fit only the design-mock exsudative-AMD timeline. Real cohorts
 * span a much wider clinical envelope:
 *   * severe atrophy        ~150 µm
 *   * normal foveal CRT     ~250 µm
 *   * moderate IRF edema    ~400 µm
 *   * dense subretinal fluid ~600+ µm
 * Hardcoding to [250..500] meant atrophy datasets dove off the bottom
 * and severe-edema datasets ran out the top. Compute the visible band
 * from the actual data + a clinical-baseline anchor (always include
 * 250 µm so the normal-CRT line stays a familiar reference).
 *
 * Mirrors {@link fluidMax}'s "nice number" rounding so the axis ticks
 * land on values an operator can read.
 */
const crtRange = computed<{ min: number; max: number }>(() => {
  const values = regionVisits.value.map((v) => v.crt).filter((c) => c > 0)
  if (values.length === 0) {
    // No CRT data yet — keep the design-mock band so the empty chart
    // still has a familiar axis.
    return { min: 250, max: 500 }
  }
  let min = Math.min(...values)
  let max = Math.max(...values)
  // Always include the 250 µm clinical-baseline anchor in the visible
  // band so cross-cohort visual comparison stays grounded.
  min = Math.min(min, 250)
  // 8 % padding above and below so the polyline doesn't graze the
  // axis edge.
  const span = Math.max(50, max - min)
  min = min - span * 0.08
  max = max + span * 0.08
  // Round to multiples of 25 µm so ticks read cleanly. Min rounds
  // DOWN (never above the lowest datum), max rounds UP.
  const STEP = 25
  min = Math.floor(min / STEP) * STEP
  max = Math.ceil(max / STEP) * STEP
  // Guarantee a minimum band of 100 µm so a flat trace doesn't
  // collapse onto a single hairline.
  if (max - min < 100) max = min + 100
  // Never let min go negative.
  if (min < 0) min = 0
  return { min, max }
})
function yCrt(value: number): number {
  const { min, max } = crtRange.value
  // Clamp into the visible axis range so a present-but-out-of-range
  // datum lands at the chart edge instead of plotting below the frame.
  const v = Math.max(min, Math.min(max, value))
  return PT + (1 - (v - min) / (max - min)) * (H - PT - PB)
}

const BCVA_MIN = 58
const BCVA_MAX = 84
function yBcva(value: number): number {
  const v = Math.max(BCVA_MIN, Math.min(BCVA_MAX, value))
  return H + BPT + (1 - (v - BCVA_MIN) / (BCVA_MAX - BCVA_MIN)) * (BH - BPT - 14)
}

interface Layer {
  key: 'IRF' | 'SRF' | 'PED'
  color: string
  points: string
}

/**
 * 2026-06-24 — five evenly-spaced gridline values derived from the
 * dynamic {@link fluidMax}. Replaces the static
 * {@code [0, 140, 280, 420, 560]} ladder so the chart adapts to the
 * dataset's actual scale.
 */
const gridVals = computed<number[]>(() => {
  const m = fluidMax.value
  return [0, m * 0.25, m * 0.5, m * 0.75, m]
})
const crtTicks = computed<number[]>(() => {
  const { min, max } = crtRange.value
  // Three ticks: bottom, midpoint, top. Round the midpoint to the
  // same 25-µm step the range uses.
  const mid = Math.round((min + max) / 2 / 25) * 25
  return [min, mid, max]
})

const layers = computed<Layer[]>(() => {
  // Build stacked polygons: PED bottom → SRF → IRF top so an
  // increase in subretinal fluid pushes the IRF cap upward
  // visibly.
  let baseline = regionVisits.value.map(() => 0)
  const order: Array<{ key: Layer['key']; field: 'irf' | 'srf' | 'ped' }> = [
    { key: 'PED', field: 'ped' },
    { key: 'SRF', field: 'srf' },
    { key: 'IRF', field: 'irf' },
  ]
  return order.map(({ key, field }) => {
    const lower = baseline.slice()
    const upper = regionVisits.value.map((v, i) => lower[i]! + v[field])
    baseline = upper
    const top = regionVisits.value.map(
      (v, i) => `${xAt(v.week).toFixed(1)},${yFluid(upper[i]!).toFixed(1)}`,
    )
    const bot = regionVisits.value
      .map((v, i) => `${xAt(v.week).toFixed(1)},${yFluid(lower[i]!).toFixed(1)}`)
      .reverse()
    return { key, color: FLUID[key].color, points: [...top, ...bot].join(' ') }
  })
})

/**
 * 2026-06-25 — break the polyline at visits with no data. The
 * NamdVisit type uses {@code 0} as a sentinel for "no CRT row" /
 * "no BCVA row"; a literal 0 µm or 0 letters is clinically
 * meaningless and would otherwise drag the line far below the
 * chart frame (the visible axis range starts at 250 µm / 58
 * letters). Treat 0 as "missing" and start a new sub-path at the
 * next valid datum so gaps render as breaks instead of off-frame
 * dives.
 */
function buildBrokenPath(
  values: ReadonlyArray<{ x: number; y: number; present: boolean }>,
): string {
  const segments: string[] = []
  let needsMove = true
  for (const p of values) {
    if (!p.present) {
      needsMove = true
      continue
    }
    segments.push(
      `${needsMove ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`,
    )
    needsMove = false
  }
  return segments.join(' ')
}

const crtPath = computed(() => {
  if (regionVisits.value.length === 0) return ''
  return buildBrokenPath(
    regionVisits.value.map((v) => ({
      x: xAt(v.week),
      y: yCrt(v.crt),
      present: v.crt > 0,
    })),
  )
})

const bcvaPath = computed(() => {
  if (regionVisits.value.length === 0) return ''
  return buildBrokenPath(
    regionVisits.value.map((v) => ({
      x: xAt(v.week),
      y: yBcva(v.bcva),
      present: v.bcva > 0,
    })),
  )
})

const lastBcva = computed(() => {
  if (regionVisits.value.length === 0) return null
  return regionVisits.value[regionVisits.value.length - 1]!.bcva
})

const currentIdx = computed(() => regionVisits.value.length - 1)

const injectionVisits = computed(() =>
  regionVisits.value
    .map((v, i) => ({ v, i }))
    .filter(({ v }) => v.inj && v.inj.length > 0),
)

const revealWidth = computed(() =>
  mounted.value ? W - PL - PR : 0,
)

const hovered = computed(() =>
  hoverIdx.value == null ? null : (regionVisits.value[hoverIdx.value] ?? null),
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
    <!-- 2026-06-26 user-feedback round — inline header.
         Replaces the standalone right-column Legend Card with a chip
         strip in the chart's own title bar:
           IRF · SRF · PED · CRT line · Injektion marker
         plus a region dropdown (Zentral 1 mm / 3 mm / 6 mm). The
         Overview tab reclaims the freed right-column height for the
         Decision panel. The strip stays compact on narrow widths by
         flex-wrapping; the dropdown anchors right. -->
    <header
      data-testid="namd-fluid-trend-header"
      class="flex items-center flex-wrap gap-x-3 gap-y-1 mb-2 text-[11px] text-slate-600"
    >
      <span
        v-for="k in (['IRF', 'SRF', 'PED'] as const)"
        :key="`legend-${k}`"
        class="inline-flex items-center gap-1.5"
        :data-testid="`namd-fluid-legend-chip-${k}`"
      >
        <span class="w-2.5 h-2.5 rounded-full" :style="`background:${FLUID[k].color}`" />
        <span class="font-semibold tracking-tight">{{ k }}</span>
      </span>
      <span class="inline-flex items-center gap-1.5" data-testid="namd-fluid-legend-chip-CRT">
        <svg viewBox="0 0 14 6" class="w-4 h-2"><line x1="0" x2="14" y1="3" y2="3" stroke="#111d4e" stroke-width="2" /></svg>
        <span class="font-semibold tracking-tight">CRT</span>
      </span>
      <span class="inline-flex items-center gap-1.5" data-testid="namd-fluid-legend-chip-INJ">
        <svg viewBox="0 0 14 14" class="w-3.5 h-3.5">
          <circle cx="7" cy="7" r="5.5" fill="#e4f2ef" stroke="#2f8e91" stroke-width="1" />
          <path d="M7 3.8 C 9.4 6.4 9.6 8 7 9.6 C 4.4 8 4.6 6.4 7 3.8 Z" fill="#2f8e91" />
        </svg>
        <span class="font-semibold tracking-tight">{{ t('studyModules.namd.trend.injectionLegend') }}</span>
      </span>
      <span class="ml-auto inline-flex items-center gap-1.5">
        <label
          for="namd-fluid-region"
          class="text-[10.5px] uppercase tracking-wider text-slate-500"
        >{{ t('studyModules.namd.trend.region.label') }}</label>
        <select
          id="namd-fluid-region"
          v-model="region"
          data-testid="namd-fluid-region-select"
          class="rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[11px] font-semibold text-slate-700 focus:outline-none focus:ring-2 focus:ring-muw-teal-300"
        >
          <option value="c1">{{ t('studyModules.namd.trend.region.c1') }}</option>
          <option value="c3">{{ t('studyModules.namd.trend.region.c3') }}</option>
          <option value="c6">{{ t('studyModules.namd.trend.region.c6') }}</option>
        </select>
      </span>
    </header>
    <svg
      :viewBox="`0 0 ${W} ${H + BH + 6}`"
      width="100%"
      style="overflow: visible"
      data-testid="namd-fluid-trend-svg"
    >
      <!-- Gridlines + left axis (fluid, nL).
           2026-06-24 user-feedback round — horizontal gridlines are
           SOLID (continuous) so the eye can read the fluid value
           horizontally across the chart; vertical guides at each
           visit's X tick are dotted (rendered below alongside the
           visit X labels) so the visit-time axis stays subordinate. -->
      <g>
        <line
          v-for="g in gridVals"
          :key="`gy-${g}`"
          :x1="PL"
          :x2="W - PR"
          :y1="yFluid(g)"
          :y2="yFluid(g)"
          stroke="#e2e8f0"
          stroke-width="1"
        />
        <text
          v-for="g in gridVals"
          :key="`gl-${g}`"
          :x="PL - 8"
          :y="yFluid(g) + 3"
          text-anchor="end"
          font-size="9.5"
          fill="#aab2c2"
        >{{ Math.round(g) }}</text>
        <text
          :x="PL - 8"
          :y="yFluid(fluidMax) - 8"
          text-anchor="end"
          font-size="9"
          fill="#8b94a7"
          font-weight="600"
        >nL</text>
      </g>

      <!-- 2026-06-24 user-feedback round — dotted vertical guides at
           each visit's X tick. Drawn BEFORE the stacked polygons +
           CRT line + BCVA strip so the data layers paint over them.
           Span from the top of the fluid panel through the bottom of
           the BCVA strip so the operator can read across the whole
           chart for any visit. -->
      <g>
        <line
          v-for="v in visits"
          :key="`gx-${v.id}`"
          :x1="xAt(v.week)"
          :x2="xAt(v.week)"
          :y1="PT"
          :y2="H + BH"
          stroke="#cbd5e1"
          stroke-width="1"
          stroke-dasharray="1 4"
          stroke-linecap="round"
        />
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
          v-show="v.crt > 0"
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
          stroke="#e2e8f0"
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
          v-show="v.bcva > 0"
          :key="`bcva-dot-${i}`"
          :cx="xAt(v.week)"
          :cy="yBcva(v.bcva)"
          r="2.4"
          fill="#5fb4e5"
          clip-path="url(#namd-trend-reveal)"
        />
        <text
          v-if="lastBcva != null && lastBcva > 0"
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
        <span class="text-right font-medium">
          {{ hovered.bcva }} L<span
            v-if="hovered.bcvaRaw"
            class="text-slate-400 text-[10px] ml-1"
          >· {{ hovered.bcvaRaw }}</span>
        </span>
      </div>
    </div>
  </figure>
</template>
