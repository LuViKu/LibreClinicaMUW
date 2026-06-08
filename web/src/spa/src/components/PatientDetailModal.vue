<script setup lang="ts">
/**
 * Phase E.6 — Patient Detail modal.
 *
 * Surfaces the cross-study eye timeline + measurement charts for a
 * single patient inside a modal that overlays the patient list. The
 * modal is in-component state (NOT URL-backed); closing returns the
 * operator to the list with their scroll position intact.
 *
 * Two sections:
 *  1. Studienverlauf pro Auge — a horizontal pill chain per eye showing
 *     the enrolment that introduced the eye to the cohort + every
 *     transition the eye has gone through. Clicking any pill switches
 *     the active study and routes to that subject's matrix detail.
 *  2. Messungen über Zeit — modality picker driving per-modality
 *     Chart.js line charts (numeric) or stepped categorical-pill rows.
 *
 * Chart.js is dynamic-imported so the patient list itself doesn't carry
 * the (~80 KB gzipped) charting library — only operators who open a
 * detail pay for it.
 */
import { computed, onMounted, ref, watch, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import Modal from './Modal.vue'
import StatusPill from './StatusPill.vue'
import { useAuthStore } from '@/stores/auth'
import { usePatientsOverviewStore } from '@/stores/patientsOverview'
import type {
  EyeTransitionEvent,
  MeasurementSeries,
  Modality,
  PatientDetail,
  PatientEnrolment,
} from '@/types/patient'

interface Props {
  open: boolean
  /** Subject id of the patient whose detail is being shown. */
  subjectId: number | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  'update:open': [value: boolean]
}>()

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const store = usePatientsOverviewStore()

/**
 * Lazy-load the vue-chartjs `Line` component so the (vendored) Chart.js
 * runtime + adapter chunk only ship to operators who open this modal.
 * The async resolver registers the required Chart.js controllers /
 * scales on first call and returns the `<Line>` component re-export.
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
    TimeScale,
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
    TimeScale,
    Tooltip,
    Legend,
    Title,
    Filler,
  )
  return LineComponent
})

const labelledById = 'patient-detail-modal-heading'

const detail = computed<PatientDetail | null>(() => store.detail)
const isLoading = computed(() => store.isLoadingDetail)
const error = computed(() => store.detailError)

/** Modalities the operator has enabled in the picker. Numeric ones are
 *  on by default; categorical ones default off because they need much
 *  more screen real-estate to be useful. */
const enabledModalities = ref<Set<string>>(new Set())

watch(
  () => detail.value?.subjectId,
  async (id) => {
    if (id == null) return
    await store.loadModalities()
    // Default-on every numeric modality so first-paint shows charts
    // without any picker interaction. Categorical modalities stay off
    // — operators can opt them in.
    const next = new Set<string>()
    for (const m of store.modalities) {
      if (m.dataType === 'numeric') next.add(m.code)
    }
    enabledModalities.value = next
    // Kick off series fetches for every numeric modality × eye.
    void hydrateSeries(id)
  },
  { immediate: true },
)

watch(enabledModalities, (codes) => {
  const id = detail.value?.subjectId
  if (id == null) return
  void hydrateSeriesForCodes(id, codes)
})

async function hydrateSeries(subjectId: number): Promise<void> {
  const codes = enabledModalities.value
  await hydrateSeriesForCodes(subjectId, codes)
}

async function hydrateSeriesForCodes(subjectId: number, codes: Set<string>): Promise<void> {
  const eyes: Array<'OD' | 'OS'> = ['OD', 'OS']
  const tasks: Array<Promise<unknown>> = []
  for (const code of codes) {
    for (const eye of eyes) {
      tasks.push(store.loadSeries(subjectId, code, eye))
    }
  }
  await Promise.allSettled(tasks)
}

function close(): void {
  emit('close')
  emit('update:open', false)
}

/* -------------------- Eye timeline derivation -------------------- */

interface TimelinePill {
  studyOid: string
  studyName: string
  label: string
  /** ISO instant of the transition; null on the start pill. */
  eventAt: string | null
  kind: 'enrolment' | 'transition'
  current: boolean
}

interface EyeTimeline {
  eye: 'OD' | 'OS'
  pills: TimelinePill[]
}

const eyeTimelines = computed<EyeTimeline[]>(() => {
  const d = detail.value
  if (!d) return []
  return (['OD', 'OS'] as const).map((eye) => buildEyeTimeline(d, eye))
})

function buildEyeTimeline(d: PatientDetail, eye: 'OD' | 'OS'): EyeTimeline {
  // Enrolments that include this eye on enrolment day (OD/OS exact or OU).
  const enrolmentsForEye = d.enrolments
    .filter((e) => eyeMatches(e.studyEye, eye))
    .slice()
    .sort((a, b) => compareIso(a.enrolledOn, b.enrolledOn))

  const transitionsForEye = d.eyeTransitions
    .filter((tx) => tx.eye === eye)
    .slice()
    .sort((a, b) => compareIso(a.eventAt, b.eventAt))

  // Start pill = earliest enrolment that introduced the eye. If the
  // patient has had the eye transitioned, the start pill is the
  // earliest *originating* enrolment (the from-side of the earliest
  // transition); the cohort then moves to the to-side via the
  // transition pill. If there are no transitions we just show the
  // start pill plus any subsequent enrolments.
  const pills: TimelinePill[] = []
  if (enrolmentsForEye.length > 0) {
    const start = enrolmentsForEye[0]
    pills.push({
      studyOid: start.studyOid,
      studyName: start.studyName,
      label: start.label,
      eventAt: null,
      kind: 'enrolment',
      current: false,
    })
  } else if (transitionsForEye.length > 0) {
    // Edge case: enrolment list pruned but a transition referenced
    // the from-side. Synthesize a start pill from the first
    // transition's fromStudy snapshot.
    const first = transitionsForEye[0]
    pills.push({
      studyOid: first.fromStudyOid,
      studyName: first.fromStudyName,
      label: first.fromLabel,
      eventAt: null,
      kind: 'enrolment',
      current: false,
    })
  }
  for (const tx of transitionsForEye) {
    pills.push({
      studyOid: tx.toStudyOid,
      studyName: tx.toStudyName,
      label: tx.toLabel,
      eventAt: tx.eventAt,
      kind: 'transition',
      current: false,
    })
  }

  // Mark the eye's current location — the latest study still listed in
  // enrolments where the study_eye covers this eye. Falls back to the
  // last transition's to-side when the latest enrolment can't be
  // identified.
  const currentEnrolment = pickCurrentEnrolment(enrolmentsForEye, transitionsForEye)
  if (currentEnrolment) {
    const idx = pills.findIndex((p) => p.studyOid === currentEnrolment.studyOid && p.label === currentEnrolment.label)
    if (idx >= 0) {
      pills[idx].current = true
    } else if (pills.length > 0) {
      pills[pills.length - 1].current = true
    }
  } else if (pills.length > 0) {
    pills[pills.length - 1].current = true
  }

  return { eye, pills }
}

function eyeMatches(scope: string | null, eye: 'OD' | 'OS'): boolean {
  if (!scope) return false
  return scope === eye || scope === 'OU'
}

function compareIso(a: string | null, b: string | null): number {
  if (a == null && b == null) return 0
  if (a == null) return -1
  if (b == null) return 1
  return a < b ? -1 : a > b ? 1 : 0
}

function pickCurrentEnrolment(
  enrolmentsForEye: PatientEnrolment[],
  transitionsForEye: EyeTransitionEvent[],
): PatientEnrolment | null {
  if (transitionsForEye.length > 0) {
    const last = transitionsForEye[transitionsForEye.length - 1]
    const match = enrolmentsForEye.find((e) => e.studyOid === last.toStudyOid && e.label === last.toLabel)
    if (match) return match
  }
  return enrolmentsForEye[enrolmentsForEye.length - 1] ?? null
}

/* -------------------- Pill click → switch study + route -------------------- */

async function onPillClick(p: TimelinePill): Promise<void> {
  try {
    await auth.pickStudy(p.studyOid)
    close()
    await router.push(`/subjects/${p.label}`)
  } catch {
    // pickStudy surfaces its own error banner via the auth store;
    // swallow here to keep the modal from crashing.
  }
}

/* -------------------- Date formatting -------------------- */

function formatDate(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const dd = String(d.getUTCDate()).padStart(2, '0')
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0')
  const yyyy = d.getUTCFullYear()
  return `${dd}.${mm}.${yyyy}`
}

/* -------------------- Modality picker -------------------- */

const numericModalities = computed<Modality[]>(() =>
  store.modalities.filter((m) => m.dataType === 'numeric'),
)
const categoricalModalities = computed<Modality[]>(() =>
  store.modalities.filter((m) => m.dataType === 'categorical'),
)

function toggleModality(code: string, on: boolean): void {
  const next = new Set(enabledModalities.value)
  if (on) next.add(code)
  else next.delete(code)
  enabledModalities.value = next
}

/* -------------------- Chart data assembly -------------------- */

interface ChartDataset {
  label: string
  data: number[]
  borderColor: string
  backgroundColor: string
  tension: number
  pointBackgroundColor: string[]
  spanGaps: boolean
}

interface ChartCanvas {
  modality: Modality
  data: {
    labels: string[]
    datasets: ChartDataset[]
  }
  options: Record<string, unknown>
  hasPoints: boolean
}

const numericCanvases = computed<ChartCanvas[]>(() => {
  const subjectId = detail.value?.subjectId
  if (subjectId == null) return []
  return numericModalities.value
    .filter((m) => enabledModalities.value.has(m.code))
    .map((m) => buildNumericCanvas(subjectId, m))
})

function buildNumericCanvas(subjectId: number, modality: Modality): ChartCanvas {
  const seriesOd = store.seriesByKey.get(store.seriesKey(subjectId, modality.code, 'OD'))
  const seriesOs = store.seriesByKey.get(store.seriesKey(subjectId, modality.code, 'OS'))
  const allDates = new Set<string>()
  for (const s of [seriesOd, seriesOs]) {
    if (!s) continue
    for (const p of s.series) if (p.numericValue !== null) allDates.add(p.date)
  }
  const labels = Array.from(allDates).sort()
  const datasets: ChartDataset[] = []
  if (seriesOd) datasets.push(numericDataset('OD', '#1d4ed8', seriesOd, labels))
  if (seriesOs) datasets.push(numericDataset('OS', '#be123c', seriesOs, labels))
  return {
    modality,
    data: { labels, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: true, position: 'bottom' as const },
        title: { display: false },
      },
      scales: {
        x: { type: 'category' as const, title: { display: true, text: 'Datum' } },
        y: {
          beginAtZero: false,
          title: { display: !!modality.unit, text: modality.unit ?? '' },
        },
      },
    },
    hasPoints: datasets.some((d) => d.data.some((v) => v !== null && !Number.isNaN(v))),
  }
}

function numericDataset(
  label: 'OD' | 'OS',
  baseColor: string,
  series: MeasurementSeries,
  labels: string[],
): ChartDataset {
  const byDate = new Map<string, { y: number; studyOid: string }>()
  for (const p of series.series) {
    if (p.numericValue === null) continue
    byDate.set(p.date, { y: p.numericValue, studyOid: p.studyOid })
  }
  // Parallel y[] against the shared `labels` axis. Missing dates are
  // mapped to NaN so Chart.js with spanGaps still connects across them
  // without inventing a zero data point.
  const data: number[] = labels.map((d) => byDate.get(d)?.y ?? Number.NaN)
  const pointBackgroundColor: string[] = labels.map((d) => {
    const e = byDate.get(d)
    return e ? studyColor(e.studyOid) : 'transparent'
  })
  return {
    label,
    data,
    borderColor: baseColor,
    backgroundColor: baseColor + '33',
    tension: 0.2,
    pointBackgroundColor,
    spanGaps: true,
  }
}

/**
 * Deterministic color picker — same `studyOid` always maps to the same
 * palette slot so the operator can mentally key off the dot color.
 */
const STUDY_PALETTE = [
  '#1d4ed8', // blue
  '#0e7490', // cyan
  '#be123c', // rose
  '#a16207', // amber
  '#15803d', // green
  '#6b21a8', // purple
  '#c2410c', // orange
]

function studyColor(oid: string): string {
  let hash = 0
  for (let i = 0; i < oid.length; i++) hash = (hash * 31 + oid.charCodeAt(i)) >>> 0
  return STUDY_PALETTE[hash % STUDY_PALETTE.length]
}

/* -------------------- Categorical row data -------------------- */

interface CategoricalCell {
  date: string
  value: string
  studyOid: string
  studyName: string
}

interface CategoricalRow {
  eye: 'OD' | 'OS'
  cells: CategoricalCell[]
}

interface CategoricalCanvas {
  modality: Modality
  rows: CategoricalRow[]
  hasCells: boolean
}

const categoricalCanvases = computed<CategoricalCanvas[]>(() => {
  const subjectId = detail.value?.subjectId
  if (subjectId == null) return []
  return categoricalModalities.value
    .filter((m) => enabledModalities.value.has(m.code))
    .map((m) => buildCategoricalCanvas(subjectId, m))
})

function buildCategoricalCanvas(subjectId: number, modality: Modality): CategoricalCanvas {
  const rows: CategoricalRow[] = (['OD', 'OS'] as const).map((eye) => {
    const series = store.seriesByKey.get(store.seriesKey(subjectId, modality.code, eye))
    const cells: CategoricalCell[] = (series?.series ?? [])
      .slice()
      .sort((a, b) => compareIso(a.date, b.date))
      .map((p) => ({ date: p.date, value: p.value, studyOid: p.studyOid, studyName: p.studyName }))
    return { eye, cells }
  })
  const hasCells = rows.some((r) => r.cells.length > 0)
  return { modality, rows, hasCells }
}

/* -------------------- Lifecycle: load detail on open -------------------- */

onMounted(() => {
  // Defer to the parent — the patient list view triggers loadDetail
  // before mounting the modal. We just react to detail.value changes.
})

watch(
  () => props.open,
  (isOpen) => {
    if (!isOpen) {
      // Defer reset so the leave transition isn't disrupted by null
      // detail. (Modal's transition is 150ms.)
      setTimeout(() => store.resetDetail(), 200)
    }
  },
)
</script>

<template>
  <Modal
    :open="props.open"
    :labelled-by="labelledById"
    panel-class="max-w-4xl"
    @close="close"
    @update:open="(v) => emit('update:open', v)"
  >
    <template #header>
      <div>
        <h2 :id="labelledById" class="text-lg font-semibold text-slate-900">
          {{ t('patientDetail.heading') }}<span v-if="detail"> #{{ detail.subjectId }}</span>
        </h2>
        <div v-if="detail" class="text-xs text-slate-500 mt-0.5">
          <span class="mr-3">{{ detail.gender }}</span>
          <span v-if="detail.yearOfBirth">{{ detail.yearOfBirth }}</span>
          <span v-if="detail.uniqueIdentifier" class="ml-3 font-mono">{{ detail.uniqueIdentifier }}</span>
        </div>
      </div>
    </template>

    <div v-if="isLoading" class="py-6 text-center text-slate-500 italic">
      {{ t('common.loading') }}
    </div>

    <div v-else-if="error" class="py-6 text-center text-rose-700">
      {{ error }}
    </div>

    <div v-else-if="detail" class="space-y-6">
      <!-- ============== Section 1 — Eye timelines ============== -->
      <section data-testid="eye-timelines" aria-labelledby="patient-detail-timelines-heading">
        <h3 id="patient-detail-timelines-heading" class="text-sm font-semibold text-slate-800 mb-3">
          {{ t('patientDetail.timeline.heading') }}
        </h3>

        <div v-if="eyeTimelines.every((t) => t.pills.length === 0)" class="text-xs text-slate-500 italic">
          {{ t('patientDetail.emptyTimeline') }}
        </div>

        <div v-for="row in eyeTimelines" :key="row.eye" class="mb-3 last:mb-0">
          <div class="flex items-center gap-3">
            <span class="inline-flex items-center justify-center w-9 h-6 rounded bg-muw-blue-50 text-muw-blue text-xs font-mono font-semibold border border-muw-blue-100">
              {{ row.eye }}
            </span>
            <div
              v-if="row.pills.length === 0"
              class="text-xs text-slate-400 italic"
            >
              —
            </div>
            <ol
              v-else
              class="flex items-center gap-2 flex-wrap"
              :data-testid="`timeline-${row.eye}`"
            >
              <li
                v-for="(pill, idx) in row.pills"
                :key="`${pill.studyOid}-${pill.label}-${idx}`"
                class="flex items-center gap-2"
              >
                <button
                  type="button"
                  class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs border bg-white hover:bg-slate-50 transition-colors"
                  :class="pill.current
                    ? 'border-muw-blue-300 text-muw-blue-900 bg-muw-blue-50'
                    : 'border-slate-200 text-slate-700'"
                  :data-testid="`pill-${row.eye}-${idx}`"
                  @click="onPillClick(pill)"
                >
                  <span class="font-medium">{{ pill.studyName }}</span>
                  <span class="text-slate-400 text-[10px]">·</span>
                  <span class="text-slate-500 text-[10px] font-mono">{{ pill.label }}</span>
                  <span class="text-slate-400 text-[10px]">
                    {{ pill.eventAt ? formatDate(pill.eventAt) : t('patientDetail.timeline.enrolmentPill') }}
                  </span>
                  <StatusPill
                    v-if="pill.current"
                    variant="success"
                    compact
                  >
                    {{ t('patientDetail.timeline.currentBadge') }}
                  </StatusPill>
                </button>
                <span
                  v-if="idx < row.pills.length - 1"
                  class="text-slate-300"
                  aria-hidden="true"
                >→</span>
              </li>
            </ol>
          </div>
        </div>
      </section>

      <!-- ============== Section 2 — Measurements ============== -->
      <section data-testid="measurements" aria-labelledby="patient-detail-charts-heading">
        <h3 id="patient-detail-charts-heading" class="text-sm font-semibold text-slate-800 mb-3">
          {{ t('patientDetail.charts.title') }}
        </h3>

        <div
          class="flex flex-wrap items-center gap-x-4 gap-y-2 mb-4 text-xs"
          data-testid="modality-picker"
        >
          <span class="text-slate-500">{{ t('patientDetail.charts.modalityPicker') }}</span>
          <label
            v-for="m in store.modalities"
            :key="m.code"
            class="inline-flex items-center gap-1.5 text-slate-700 cursor-pointer"
          >
            <input
              type="checkbox"
              class="rounded text-muw-blue"
              :data-testid="`modality-toggle-${m.code}`"
              :checked="enabledModalities.has(m.code)"
              @change="(e) => toggleModality(m.code, (e.target as HTMLInputElement).checked)"
            />
            <span>{{ m.labelDe }}</span>
            <span v-if="m.unit" class="text-slate-400 text-[10px]">{{ m.unit }}</span>
          </label>
        </div>

        <div v-if="numericCanvases.length === 0 && categoricalCanvases.length === 0" class="text-xs text-slate-500 italic py-4">
          {{ t('patientDetail.charts.empty') }}
        </div>

        <!-- Numeric charts -->
        <div
          v-for="canvas in numericCanvases"
          :key="`numeric-${canvas.modality.code}`"
          class="mb-5"
          :data-testid="`chart-${canvas.modality.code}`"
        >
          <div class="text-xs font-medium text-slate-700 mb-2">
            {{ canvas.modality.labelDe }}
            <span v-if="canvas.modality.unit" class="text-slate-400 font-mono ml-1.5">{{ canvas.modality.unit }}</span>
          </div>
          <div v-if="!canvas.hasPoints" class="text-xs text-slate-400 italic py-2">
            {{ t('patientDetail.charts.empty') }}
          </div>
          <div v-else class="h-64 border border-slate-200 rounded-md bg-white p-2">
            <Line :data="canvas.data" :options="canvas.options" />
          </div>
        </div>

        <!-- Categorical stepped pill rows -->
        <div
          v-for="canvas in categoricalCanvases"
          :key="`categorical-${canvas.modality.code}`"
          class="mb-5"
          :data-testid="`categorical-${canvas.modality.code}`"
        >
          <div class="text-xs font-medium text-slate-700 mb-2">
            {{ canvas.modality.labelDe }}
          </div>
          <div v-if="!canvas.hasCells" class="text-xs text-slate-400 italic py-2">
            {{ t('patientDetail.charts.empty') }}
          </div>
          <template v-else>
            <div
              v-for="row in canvas.rows"
              :key="`${canvas.modality.code}-${row.eye}`"
              class="flex items-center gap-2 mb-1"
              :data-testid="`categorical-row-${canvas.modality.code}-${row.eye}`"
            >
              <span class="inline-flex items-center justify-center w-9 h-6 rounded bg-muw-blue-50 text-muw-blue text-xs font-mono font-semibold border border-muw-blue-100">
                {{ row.eye }}
              </span>
              <div v-if="row.cells.length === 0" class="text-xs text-slate-400 italic">—</div>
              <ol v-else class="flex items-center gap-1 flex-wrap">
                <li
                  v-for="(cell, idx) in row.cells"
                  :key="`${cell.date}-${idx}`"
                  class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[11px] border border-slate-200 bg-white"
                  :title="`${cell.date} · ${cell.studyName}`"
                >
                  <span
                    class="w-1.5 h-1.5 rounded-full"
                    :style="{ backgroundColor: studyColor(cell.studyOid) }"
                    aria-hidden="true"
                  ></span>
                  <span>{{ cell.value }}</span>
                </li>
              </ol>
            </div>
          </template>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="ml-auto">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
          @click="close"
        >
          {{ t('patientDetail.close') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
