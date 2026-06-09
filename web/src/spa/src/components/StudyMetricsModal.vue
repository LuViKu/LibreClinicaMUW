<script setup lang="ts">
/**
 * Phase E.6 — Studien-Statistik modal.
 *
 * Surfaces a small aggregate dashboard for the active study, computed
 * client-side from the already-loaded {@link useSubjectsStore.rows}:
 *
 *   - **Gender donut** — counts subjects by `gender`.
 *   - **Age bars** — bucketed by `yearOfBirth` against today.
 *   - **Follow-up bars** — months between earliest enrolment and the
 *     last event's `dateStart` per subject.
 *
 * Chart "animations" mirror the muw-libreclinica study-overview mockup:
 * a CSS `is-anim` class toggles transform scale on each bar / donut
 * segment to grow them from zero on modal open. No Chart.js needed —
 * the modal stays inside the entry bundle and there's no cost added
 * to the lazy chart chunk patients-overview ships.
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { useSubjectsStore } from '@/stores/subjects'

interface Props {
  open: boolean
}
const props = defineProps<Props>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const subjects = useSubjectsStore()

/* ---------- aggregate computeds ---------- */

interface Slice {
  label: string
  value: number
}

const totalCount = computed(() => subjects.rows.length)

const sexBuckets = computed<Slice[]>(() => {
  const f = subjects.rows.filter((s) => (s.gender ?? '').toUpperCase() === 'F').length
  const m = subjects.rows.filter((s) => (s.gender ?? '').toUpperCase() === 'M').length
  const other = subjects.rows.length - f - m
  const out: Slice[] = [
    { label: t('studyMetrics.sex.female'), value: f },
    { label: t('studyMetrics.sex.male'), value: m },
  ]
  if (other > 0) out.push({ label: t('studyMetrics.sex.other'), value: other })
  return out
})

const ageBuckets = computed<Slice[]>(() => {
  const currentYear = new Date().getFullYear()
  const bands: Slice[] = [
    { label: '50-59', value: 0 },
    { label: '60-69', value: 0 },
    { label: '70-79', value: 0 },
    { label: '80-89', value: 0 },
    { label: '90+', value: 0 },
  ]
  for (const s of subjects.rows) {
    const yob = s.yearOfBirth ?? null
    if (yob == null) continue
    const age = currentYear - yob
    if (age >= 90) bands[4].value++
    else if (age >= 80) bands[3].value++
    else if (age >= 70) bands[2].value++
    else if (age >= 60) bands[1].value++
    else if (age >= 50) bands[0].value++
  }
  return bands
})

const ages = computed<number[]>(() => {
  const currentYear = new Date().getFullYear()
  return subjects.rows
    .map((s) => (s.yearOfBirth == null ? null : currentYear - s.yearOfBirth))
    .filter((v): v is number => v != null)
    .sort((a, b) => a - b)
})

const medianAge = computed<number | null>(() => {
  const a = ages.value
  if (a.length === 0) return null
  return a.length % 2
    ? a[Math.floor(a.length / 2)]
    : Math.round((a[a.length / 2 - 1] + a[a.length / 2]) / 2)
})

/* Follow-up: months between subject.enrolledOn and the latest event
 * dateStart on each row. Subjects without any started events
 * collapse to zero and don't contribute to median/range. */
const followUps = computed<number[]>(() => {
  const out: number[] = []
  for (const s of subjects.rows) {
    if (!s.enrolledOn) continue
    const start = Date.parse(s.enrolledOn)
    if (Number.isNaN(start)) continue
    let latest = 0
    for (const e of s.events ?? []) {
      const cellLatest = (e as { dateStart?: string | null }).dateStart
      if (!cellLatest) continue
      const ts = Date.parse(cellLatest)
      if (!Number.isNaN(ts) && ts > latest) latest = ts
    }
    if (latest <= 0) continue
    const months = (latest - start) / (1000 * 60 * 60 * 24 * 30.44)
    out.push(Math.max(0, months))
  }
  return out.sort((a, b) => a - b)
})

const followUpBuckets = computed<Slice[]>(() => {
  const bands: Slice[] = [
    { label: '0-6', value: 0 },
    { label: '6-12', value: 0 },
    { label: '12-18', value: 0 },
    { label: '18-24', value: 0 },
    { label: '24+', value: 0 },
  ]
  for (const m of followUps.value) {
    if (m >= 24) bands[4].value++
    else if (m >= 18) bands[3].value++
    else if (m >= 12) bands[2].value++
    else if (m >= 6) bands[1].value++
    else bands[0].value++
  }
  return bands
})

const medianFollowUp = computed<number | null>(() => {
  const a = followUps.value
  if (a.length === 0) return null
  const median = a.length % 2
    ? a[Math.floor(a.length / 2)]
    : (a[a.length / 2 - 1] + a[a.length / 2]) / 2
  return Math.round(median * 10) / 10
})

const followUpRange = computed<string>(() => {
  const a = followUps.value
  if (a.length === 0) return '—'
  const lo = Math.round(a[0])
  const hi = Math.round(a[a.length - 1])
  return `${lo}–${hi}`
})

const sexTotal = computed(() => sexBuckets.value.reduce((s, b) => s + b.value, 0))

/* ---------- donut geometry ---------- */

const SEX_COLORS = ['#2f8e91', '#111d4e', '#94a3b8'] // teal / blue / slate
const ageMax = computed(() => Math.max(1, ...ageBuckets.value.map((b) => b.value)))
const fuMax = computed(() => Math.max(1, ...followUpBuckets.value.map((b) => b.value)))

interface DonutSegment {
  pct: number
  rotation: number
  color: string
}
const donutSegments = computed<DonutSegment[]>(() => {
  const total = sexTotal.value
  if (total === 0) return []
  let cum = 0
  return sexBuckets.value
    .filter((b) => b.value > 0)
    .map((b, i) => {
      const pct = (b.value / total) * 100
      const rotation = (cum / 100) * 360 - 90
      cum += pct
      return { pct, rotation, color: SEX_COLORS[i] ?? '#94a3b8' }
    })
})

/* ---------- animation control ---------- */

const animationActive = ref(false)

watch(
  () => props.open,
  async (next) => {
    if (!next) {
      animationActive.value = false
      return
    }
    animationActive.value = false
    await nextTick()
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        animationActive.value = true
      })
    })
  },
  { immediate: true },
)

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) {
    e.preventDefault()
    emit('close')
  }
}

if (typeof document !== 'undefined') {
  watch(
    () => props.open,
    (next, prev) => {
      if (next && !prev) document.addEventListener('keydown', onKeydown)
      else if (!next && prev) document.removeEventListener('keydown', onKeydown)
    },
    { immediate: true },
  )
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-40 flex items-start justify-center p-6 pt-12 bg-slate-900/40 overflow-y-auto"
      role="dialog"
      aria-modal="true"
      aria-labelledby="study-metrics-title"
      data-testid="study-metrics-modal"
      @click.self="emit('close')"
    >
      <div
        class="w-full max-w-3xl my-auto bg-white rounded-2xl shadow-2xl ring-1 ring-slate-200 overflow-hidden max-h-[calc(100vh-3rem)] flex flex-col"
        :class="animationActive ? 'is-anim' : ''"
      >
        <div class="flex items-center justify-between px-6 py-4 border-b border-slate-200 shrink-0">
          <h2
            id="study-metrics-title"
            class="text-base font-semibold tracking-tight text-slate-900"
          >
            {{ t('studyMetrics.title') }}
          </h2>
          <button
            type="button"
            class="p-2 -mr-2 rounded-md text-slate-400 hover:text-slate-700 hover:bg-slate-100"
            :aria-label="t('studyMetrics.close')"
            data-testid="study-metrics-close"
            @click="emit('close')"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
              <line x1="18" x2="6" y1="6" y2="18" />
              <line x1="6" x2="18" y1="6" y2="18" />
            </svg>
          </button>
        </div>

        <div class="px-6 py-5 overflow-y-auto">
          <!-- KPI strip -->
          <div class="grid grid-cols-2 md:grid-cols-3 gap-3 mb-5">
            <div class="rounded-xl bg-slate-50 ring-1 ring-slate-200 px-4 py-3">
              <div class="text-[22px] font-semibold text-muw-blue chart-num">{{ totalCount }}</div>
              <div class="text-[11px] text-slate-500 mt-0.5">{{ t('studyMetrics.kpi.subjects') }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 ring-1 ring-slate-200 px-4 py-3">
              <div class="text-[22px] font-semibold text-muw-blue chart-num">{{ medianAge ?? '—' }}</div>
              <div class="text-[11px] text-slate-500 mt-0.5">{{ t('studyMetrics.kpi.medianAge') }}</div>
            </div>
            <div class="rounded-xl bg-slate-50 ring-1 ring-slate-200 px-4 py-3">
              <div class="text-[22px] font-semibold text-muw-blue chart-num">{{ medianFollowUp ?? '—' }}</div>
              <div class="text-[11px] text-slate-500 mt-0.5">{{ t('studyMetrics.kpi.medianFollowUp') }}</div>
            </div>
          </div>

          <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <!-- Age bars -->
            <div class="rounded-xl ring-1 ring-slate-200 p-4">
              <div class="flex items-baseline justify-between mb-3">
                <div class="text-[13px] font-semibold text-slate-800">{{ t('studyMetrics.age.title') }}</div>
                <div class="text-[11px] text-slate-400">{{ t('studyMetrics.kpi.subjectsShort', { n: totalCount }) }}</div>
              </div>
              <div class="flex items-end gap-1.5" style="height: 140px;">
                <div
                  v-for="(band, i) in ageBuckets"
                  :key="band.label"
                  class="flex-1 flex flex-col items-center justify-end h-full"
                >
                  <div
                    class="chart-num text-[10px] font-semibold text-slate-500 mb-1"
                    :style="{ transitionDelay: `${0.3 + i * 0.06}s` }"
                  >{{ band.value }}</div>
                  <div
                    class="w-full rounded-t-[3px] bar-fill bg-muw-blue"
                    :style="{
                      height: `${Math.max(3, Math.round((band.value / ageMax) * 110))}px`,
                      transitionDelay: `${i * 0.06}s`,
                    }"
                  ></div>
                </div>
              </div>
              <div class="flex gap-1.5 mt-1.5">
                <div
                  v-for="band in ageBuckets"
                  :key="`l-${band.label}`"
                  class="flex-1 text-center text-[10px] text-slate-400"
                >{{ band.label }}</div>
              </div>
            </div>

            <!-- Sex donut -->
            <div class="rounded-xl ring-1 ring-slate-200 p-4">
              <div class="text-[13px] font-semibold text-slate-800 mb-3">{{ t('studyMetrics.sex.title') }}</div>
              <div v-if="sexTotal === 0" class="text-xs text-slate-400 italic py-4">
                {{ t('studyMetrics.empty') }}
              </div>
              <div v-else class="flex items-center gap-5">
                <svg width="120" height="120" viewBox="0 0 120 120" class="shrink-0">
                  <circle cx="60" cy="60" r="49" fill="none" stroke="#eef0f4" stroke-width="22" />
                  <circle
                    v-for="(seg, i) in donutSegments"
                    :key="i"
                    class="donut-seg"
                    cx="60"
                    cy="60"
                    r="49"
                    fill="none"
                    :stroke="seg.color"
                    stroke-width="22"
                    pathLength="100"
                    :stroke-dasharray="animationActive ? `${seg.pct.toFixed(3)} 100` : '0 100'"
                    :style="{
                      transform: `rotate(${seg.rotation}deg)`,
                      transformOrigin: '60px 60px',
                      transitionDelay: `${i * 0.12}s`,
                    }"
                  />
                </svg>
                <ul class="flex-1 space-y-2.5 text-[13px]">
                  <li
                    v-for="(band, i) in sexBuckets"
                    :key="band.label"
                    class="flex items-center justify-between"
                  >
                    <span class="inline-flex items-center gap-2 text-slate-600">
                      <span class="w-2.5 h-2.5 rounded-sm" :style="{ background: SEX_COLORS[i] ?? '#94a3b8' }"></span>
                      {{ band.label }}
                    </span>
                    <span class="text-slate-800 font-medium tabular-nums">
                      {{ band.value }}
                      <span class="text-slate-400 font-normal">
                        · {{ sexTotal > 0 ? Math.round((band.value / sexTotal) * 100) : 0 }}%
                      </span>
                    </span>
                  </li>
                </ul>
              </div>
            </div>

            <!-- Follow-up bars -->
            <div class="rounded-xl ring-1 ring-slate-200 p-4 md:col-span-2">
              <div class="flex items-baseline justify-between mb-3">
                <div class="text-[13px] font-semibold text-slate-800">{{ t('studyMetrics.followUp.title') }}</div>
                <div class="text-[11px] text-slate-400">
                  {{ t('studyMetrics.followUp.context', {
                    median: medianFollowUp ?? '—',
                    range: followUpRange,
                  }) }}
                </div>
              </div>
              <ul class="space-y-1.5">
                <li
                  v-for="(band, i) in followUpBuckets"
                  :key="band.label"
                  class="flex items-center gap-2"
                >
                  <span class="text-[10px] text-slate-400 text-right shrink-0 w-12">{{ band.label }}</span>
                  <span class="flex-1 h-3 rounded-full bg-slate-100 overflow-hidden">
                    <span
                      class="block h-full rounded-full hbar-fill bg-muw-teal-700"
                      :style="{
                        width: `${Math.max(4, (band.value / fuMax) * 100)}%`,
                        transitionDelay: `${i * 0.06}s`,
                      }"
                    ></span>
                  </span>
                  <span
                    class="chart-num text-[10px] font-semibold text-slate-500 w-4 text-right"
                    :style="{ transitionDelay: `${0.3 + i * 0.06}s` }"
                  >{{ band.value }}</span>
                </li>
              </ul>
            </div>
          </div>
        </div>

        <div class="px-6 py-3 border-t border-slate-200 bg-slate-50/60 text-[11px] text-slate-400">
          {{ t('studyMetrics.footer', { n: totalCount }) }}
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* CSS animation primitives lifted from the study-overview mockup:
 * grow bars from zero on .is-anim, fade in counters. Respects
 * prefers-reduced-motion via media query. */
.bar-fill {
  transform: scaleY(0);
  transform-origin: bottom;
  transition: transform 0.72s cubic-bezier(0.22, 1, 0.36, 1);
}
.is-anim .bar-fill {
  transform: scaleY(1);
}
.hbar-fill {
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.72s cubic-bezier(0.22, 1, 0.36, 1);
}
.is-anim .hbar-fill {
  transform: scaleX(1);
}
.donut-seg {
  transition: stroke-dasharray 0.85s cubic-bezier(0.22, 1, 0.36, 1);
}
.chart-num {
  opacity: 0;
  transition: opacity 0.4s ease 0.35s;
}
.is-anim .chart-num {
  opacity: 1;
}
@media (prefers-reduced-motion: reduce) {
  .bar-fill,
  .hbar-fill,
  .donut-seg,
  .chart-num {
    transition: none !important;
  }
}
</style>
