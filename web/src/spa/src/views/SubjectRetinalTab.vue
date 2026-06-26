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

    <!-- Task selector + chart -->
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

    <!-- Historical jobs table -->
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
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-5 py-2 font-medium">{{ t('retinal.trends.history.colJob') }}</th>
            <th scope="col" class="px-5 py-2 font-medium">{{ t('retinal.trends.history.colAcquired') }}</th>
            <th scope="col" class="px-5 py-2 font-medium">{{ t('retinal.trends.history.colTask') }}</th>
            <th scope="col" class="px-5 py-2 font-medium">{{ t('retinal.trends.history.colEye') }}</th>
            <th scope="col" class="px-5 py-2 font-medium">{{ t('retinal.trends.history.colStatus') }}</th>
            <th scope="col" class="px-5 py-2 font-medium">{{ t('retinal.trends.history.colPrimaryMetric') }}</th>
            <th scope="col" class="px-5 py-2 font-medium w-20 text-right"></th>
          </tr>
        </template>
        <tr v-for="row in jobs" :key="row.jobId" data-testid="subject-retinal-tab-history-row">
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
