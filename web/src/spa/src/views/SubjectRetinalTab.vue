<script setup lang="ts">
/**
 * Wave 2A — Self-contained "Retinal-Verlauf" section embedded into
 * {@link SubjectDetailView}. Not a route — the parent view mounts this
 * inline below the events table when the subject has at least one
 * retinal_inference_job.
 *
 * Contents:
 *   1. Optional {@code parked} slot — Wave 2B's {@code ParkedScansList}
 *      drops in here. Wave 2A leaves the slot empty.
 *   2. A task selector dropdown (fluid / onl / pr / ga).
 *   3. {@link BiomarkerTrendsChart} bound to subject + task.
 *   4. The historical jobs table for this subject (re-uses the
 *      existing per-subject jobs endpoint).
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

import BiomarkerTrendsChart from '@/components/retinal/BiomarkerTrendsChart.vue'
import StatusPill from '@/components/StatusPill.vue'
import DenseTable from '@/components/DenseTable.vue'
import { listSubjectJobs } from '@/api/retinal'
import type { RetinalJobSummary } from '@/api/retinal'

interface Props {
  /** Numeric study_subject_id — the trends + jobs endpoints take this. */
  subjectId: number
  /** Study-subject label (e.g. EIAMD150) — builds the per-subject job
   *  deep link /subjects/{label}/jobs/{n}. */
  subjectLabel: string
}
const props = defineProps<Props>()

/**
 * 2026-06-26 — link to a job by its stable per-subject number when the
 * backend supplied one; fall back to the canonical by-id route otherwise
 * (e.g. older payloads without subjectSeq).
 */
function jobLink(row: RetinalJobSummary): string {
  return row.subjectSeq != null
    ? `/subjects/${encodeURIComponent(props.subjectLabel)}/jobs/${row.subjectSeq}`
    : `/retinal-jobs/${row.jobId}`
}

const { t } = useI18n()

type TrendTask = 'fluid' | 'onl' | 'pr' | 'ga'
const TASKS: TrendTask[] = ['fluid', 'onl', 'pr', 'ga']

const task = ref<TrendTask>('fluid')

const jobs = ref<RetinalJobSummary[]>([])
const isLoadingJobs = ref(false)
const jobsError = ref<string | null>(null)

async function loadJobs() {
  isLoadingJobs.value = true
  jobsError.value = null
  try {
    jobs.value = await listSubjectJobs(props.subjectId)
  } catch (e) {
    jobsError.value = e instanceof Error ? e.message : String(e)
    jobs.value = []
  } finally {
    isLoadingJobs.value = false
  }
}

onMounted(() => {
  void loadJobs()
})

watch(
  () => props.subjectId,
  () => {
    void loadJobs()
  },
)

const hasJobs = computed(() => jobs.value.length > 0)

/* ----- Display helpers (mirrored from RetinalMetricsView) ----- */

/** Scan acquisition date (ISO yyyy-MM-dd from the .e2e header) → DD-MM-YYYY. */
function formatAcqDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = iso.slice(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(d) ? d.split('-').reverse().join('-') : d
}

function statusVariant(status: string): 'success' | 'info' | 'warning' | 'danger' | 'neutral' {
  if (status === 'done' || status === 'succeeded') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'segmenting' || status === 'queued' || status === 'remote_pending') return 'info'
  if (status === 'parked') return 'warning'
  return 'neutral'
}

function formatPrimary(job: RetinalJobSummary): string {
  if (!job.primaryMetric || job.primaryMetric.value == null) return '—'
  const v = job.primaryMetric.value
  const unit = job.primaryMetric.unit ?? ''
  return unit ? `${v} ${unit}` : String(v)
}

/* ── 2026-06-26 user-feedback round — sortable columns ── */

/**
 * Sort key. Each column header cycles between asc / desc on the same
 * column, or sets a new column with the default direction below.
 * Default = acquisitionDate desc (most recent scan first), which
 * matches operator expectations from the timeline view.
 */
type SortKey = 'job' | 'acquired' | 'task' | 'eye' | 'status' | 'metric'
type SortDir = 'asc' | 'desc'
interface SortState { key: SortKey; dir: SortDir }
const DEFAULT_DIR: Record<SortKey, SortDir> = {
  job: 'desc',
  acquired: 'desc',
  task: 'asc',
  eye: 'asc',
  status: 'asc',
  metric: 'desc',
}
const sort = ref<SortState>({ key: 'acquired', dir: 'desc' })

function toggleSort(key: SortKey): void {
  if (sort.value.key === key) {
    sort.value = { key, dir: sort.value.dir === 'asc' ? 'desc' : 'asc' }
  } else {
    sort.value = { key, dir: DEFAULT_DIR[key] }
  }
}

/** Comparator value per column. Returns a tuple (primary, secondary)
 *  so equal primary values fall back to a stable secondary (jobId)
 *  for deterministic ordering on ties. */
function sortValue(row: RetinalJobSummary, key: SortKey): [number | string, number] {
  const sec = row.jobId
  switch (key) {
    case 'job':
      return [row.subjectSeq ?? row.jobId, sec]
    case 'acquired':
      // Null acquisition dates sort to the END regardless of direction
      // by mapping to the sentinel '' which compares LOW; the direction
      // multiplier in compareTuple flips that. Operators consistently
      // want "no data" rows at the bottom — having them mid-list
      // when sorted ASC is more confusing than convenient.
      return [row.acquisitionDate ?? '', sec]
    case 'task':
      return [row.task ?? '', sec]
    case 'eye':
      return [row.laterality ?? '', sec]
    case 'status':
      return [row.status ?? '', sec]
    case 'metric':
      return [row.primaryMetric?.value ?? Number.NEGATIVE_INFINITY, sec]
  }
}

const sortedJobs = computed<RetinalJobSummary[]>(() => {
  const arr = jobs.value.slice()
  const { key, dir } = sort.value
  const mul = dir === 'asc' ? 1 : -1
  arr.sort((a, b) => {
    const [av, asec] = sortValue(a, key)
    const [bv, bsec] = sortValue(b, key)
    // Acquisition-date sentinel handling — keep nulls always at the bottom
    // regardless of dir, since "no data" rows mid-list are confusing.
    if (key === 'acquired') {
      const aMissing = av === ''
      const bMissing = bv === ''
      if (aMissing && !bMissing) return 1
      if (!aMissing && bMissing) return -1
    }
    if (av < bv) return -1 * mul
    if (av > bv) return 1 * mul
    return asec - bsec
  })
  return arr
})

/** Arrow glyph for the column header (▲ asc, ▼ desc, dimmed for inactive). */
function sortArrow(key: SortKey): string {
  if (sort.value.key !== key) return ''
  return sort.value.dir === 'asc' ? '▲' : '▼'
}

/** Sortable column definitions for the template v-for. The template
 *  can't carry TS type casts (Vue's SFC compiler rejects `as`), so
 *  declare the typed array here once and iterate it below. */
const SORTABLE_COLS: { key: SortKey; label: string }[] = [
  { key: 'job', label: 'colJob' },
  { key: 'acquired', label: 'colAcquired' },
  { key: 'task', label: 'colTask' },
  { key: 'eye', label: 'colEye' },
  { key: 'status', label: 'colStatus' },
  { key: 'metric', label: 'colPrimaryMetric' },
]
</script>

<template>
  <section data-testid="subject-retinal-tab" class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5">
    <div class="px-5 py-3 border-b border-slate-200">
      <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
        {{ t('retinal.subject.sectionTitle') }}
      </h2>
      <p class="text-xs text-slate-500 mt-1">{{ t('retinal.subject.sectionSubtitle') }}</p>
    </div>

    <!-- Wave 2B's ParkedScansList drops in here -->
    <div data-testid="subject-retinal-tab-parked" class="px-5 pt-4">
      <slot name="parked" />
    </div>

    <div class="px-5 py-4">
      <label class="flex items-center gap-2 text-xs text-slate-700 mb-3">
        <span class="font-medium">{{ t('retinal.trends.taskLabel') }}:</span>
        <select
          v-model="task"
          data-testid="subject-retinal-tab-task-select"
          class="px-3 py-1.5 rounded-md text-sm border border-slate-300 bg-white focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100 muw-focus appearance-none cursor-pointer pr-8"
        >
          <option v-for="tk in TASKS" :key="tk" :value="tk">
            {{ t(`retinal.trends.taskLabels.${tk}`) }}
          </option>
        </select>
      </label>

      <BiomarkerTrendsChart :subject-id="subjectId" :task="task" />
    </div>

    <div class="border-t border-slate-200">
      <div class="px-5 py-3 border-b border-slate-200">
        <h3 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
          {{ t('retinal.trends.historyHeader') }}
        </h3>
      </div>
      <p
        v-if="isLoadingJobs"
        class="px-5 py-3 text-xs text-slate-500 italic"
        data-testid="subject-retinal-tab-history-loading"
      >
        {{ t('retinal.trends.loading') }}
      </p>
      <p
        v-else-if="jobsError"
        class="px-5 py-3 text-xs text-rose-600"
        data-testid="subject-retinal-tab-history-error"
      >
        {{ t('retinal.trends.errorPrefix') }} {{ jobsError }}
      </p>
      <p
        v-else-if="!hasJobs"
        class="px-5 py-3 text-xs text-slate-500 italic"
        data-testid="subject-retinal-tab-history-empty"
      >
        {{ t('retinal.trends.history.empty') }}
      </p>
      <DenseTable v-else :bordered="false">
        <template #header>
          <!-- 2026-06-26 — sortable column headers. Click cycles
               asc/desc on the active column; clicking another
               column resets it to that column's default direction
               (see DEFAULT_DIR above). The arrow lives in a fixed-
               width span so the layout doesn't shift when sorting. -->
          <tr class="border-b border-slate-200">
            <th
              v-for="col in SORTABLE_COLS"
              :key="col.key"
              scope="col"
              class="px-5 py-2 font-medium select-none cursor-pointer hover:text-muw-blue"
              :data-testid="`subject-retinal-tab-sort-${col.key}`"
              :aria-sort="sort.key === col.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'"
              @click="toggleSort(col.key)"
            >
              <span class="inline-flex items-center gap-1">
                <span>{{ t(`retinal.trends.history.${col.label}`) }}</span>
                <span class="w-2 text-[10px]" :class="sort.key === col.key ? 'text-muw-blue' : 'text-slate-300'">{{ sortArrow(col.key) }}</span>
              </span>
            </th>
            <th scope="col" class="px-5 py-2 font-medium w-20 text-right"></th>
          </tr>
        </template>
        <tr v-for="row in sortedJobs" :key="row.jobId" data-testid="subject-retinal-tab-history-row">
          <td class="px-5 py-2.5 font-mono text-xs">#{{ row.subjectSeq ?? row.jobId }}</td>
          <td class="px-5 py-2.5 font-mono text-xs text-slate-600">{{ formatAcqDate(row.acquisitionDate) }}</td>
          <td class="px-5 py-2.5 text-xs uppercase">{{ row.task }}</td>
          <td class="px-5 py-2.5 text-xs">{{ row.laterality }}</td>
          <td class="px-5 py-2.5 text-xs">
            <StatusPill :variant="statusVariant(row.status)">{{ row.status }}</StatusPill>
          </td>
          <td class="px-5 py-2.5 text-xs tabular-nums font-mono">{{ formatPrimary(row) }}</td>
          <td class="px-5 py-2.5 text-right text-xs">
            <RouterLink
              :to="jobLink(row)"
              class="text-muw-blue hover:underline"
            >
              {{ t('retinal.trends.history.viewLink') }}
            </RouterLink>
          </td>
        </tr>
      </DenseTable>
    </div>
  </section>
</template>
