<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — Retinal results panel for the Subject Detail
 * view + (in passing) anywhere else that needs a compact list of
 * retinal-inference jobs.
 *
 * Calls {@code listSubjectJobs} (or {@code listEventCrfJobs} when
 * given an event-CRF id) through the {@link useRetinalJobStore}; each
 * row links into the {@code /retinal-jobs/:jobId} viewer.
 *
 * <p>The component is intentionally tiny — it's a section embedded
 * inside SubjectDetailView, not a full view. The viewer route does
 * the heavy lifting.
 */
import { computed, onMounted, watch } from 'vue'
import { RouterLink } from 'vue-router'

import DenseTable from './DenseTable.vue'
import StatusPill from './StatusPill.vue'
import { useRetinalJobStore } from '@/stores/retinalJob'
import type { RetinalJobSummary, RetinalJobStatus } from '@/api/retinal'

interface Props {
  /** Either a subject id OR an event-CRF id MUST be supplied. */
  studySubjectId?: number | null
  eventCrfId?: number | null
}

const props = defineProps<Props>()
const store = useRetinalJobStore()

const isSubjectScope = computed(() => props.studySubjectId != null)

const jobs = computed<RetinalJobSummary[]>(() => {
  if (isSubjectScope.value && props.studySubjectId != null) {
    return store.subjectJobs[props.studySubjectId] ?? []
  }
  if (!isSubjectScope.value && props.eventCrfId != null) {
    return store.eventCrfJobs[props.eventCrfId] ?? []
  }
  return []
})

const isLoading = computed<boolean>(() => {
  if (isSubjectScope.value && props.studySubjectId != null) {
    return !!store.subjectLoading[props.studySubjectId]
  }
  if (!isSubjectScope.value && props.eventCrfId != null) {
    return !!store.eventCrfLoading[props.eventCrfId]
  }
  return false
})

async function refresh() {
  if (props.studySubjectId != null) {
    await store.loadSubjectJobs(props.studySubjectId)
  } else if (props.eventCrfId != null) {
    await store.loadEventCrfJobs(props.eventCrfId)
  }
}

onMounted(() => {
  void refresh()
})

watch(
  () => [props.studySubjectId, props.eventCrfId] as const,
  () => {
    void refresh()
  },
)

function statusVariant(status: RetinalJobStatus): 'success' | 'info' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'succeeded':
      return 'success'
    case 'failed':
      return 'danger'
    case 'queued':
      return 'neutral'
    case 'preprocessing':
    case 'segmenting':
      return 'info'
    default:
      return 'neutral'
  }
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return '—'
  // Display only the date portion to keep the table compact; the
  // full ISO timestamp stays accessible on the per-job detail view.
  return iso.slice(0, 10)
}

function formatPrimaryMetric(job: RetinalJobSummary): string {
  if (job.primaryMetric == null) return '—'
  const v = job.primaryMetric.value
  // 3 sig figs is enough for clinical KPIs at this density; the
  // metrics view shows the full precision.
  return `${Number(v).toPrecision(3)} ${job.primaryMetric.unit}`
}
</script>

<template>
  <section
    class="bg-white border border-slate-200 rounded-muw overflow-clip mb-5"
    data-testid="retinal-results-tab"
  >
    <div class="px-5 py-3 border-b border-slate-200 flex items-center justify-between">
      <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
        Retinal results
      </h2>
      <span class="text-xs text-slate-500">{{ jobs.length }} job(s)</span>
    </div>

    <p
      v-if="isLoading && jobs.length === 0"
      class="px-5 py-6 text-xs text-slate-500 italic"
      data-testid="retinal-results-loading"
    >
      Loading…
    </p>

    <p
      v-else-if="jobs.length === 0"
      class="px-5 py-6 text-xs text-slate-500 italic"
      data-testid="retinal-results-empty"
    >
      No retinal inference jobs yet.
    </p>

    <DenseTable v-else :bordered="false">
      <template #header>
        <tr class="border-b border-slate-200">
          <th scope="col" class="px-5 py-2 font-medium w-24">Task</th>
          <th scope="col" class="px-5 py-2 font-medium w-20">Eye</th>
          <th scope="col" class="px-5 py-2 font-medium w-32">Status</th>
          <th scope="col" class="px-5 py-2 font-medium">Primary metric</th>
          <th scope="col" class="px-5 py-2 font-medium w-32">Model</th>
          <th scope="col" class="px-5 py-2 font-medium w-28">Completed</th>
          <th scope="col" class="px-5 py-2 font-medium w-28 text-right"></th>
        </tr>
      </template>
      <tr v-for="job in jobs" :key="job.jobId" data-testid="retinal-results-row">
        <td class="px-5 py-2.5 font-medium uppercase text-xs">{{ job.task }}</td>
        <td class="px-5 py-2.5 font-mono text-xs">{{ job.laterality }}</td>
        <td class="px-5 py-2.5">
          <StatusPill :variant="statusVariant(job.status)">{{ job.status }}</StatusPill>
        </td>
        <td class="px-5 py-2.5 text-xs tabular-nums">{{ formatPrimaryMetric(job) }}</td>
        <td class="px-5 py-2.5 text-xs font-mono text-slate-600">{{ job.modelVersion ?? '—' }}</td>
        <td class="px-5 py-2.5 text-xs font-mono text-slate-600">{{ formatTimestamp(job.completedAt) }}</td>
        <td class="px-5 py-2.5 text-right text-xs">
          <RouterLink
            :to="`/retinal-jobs/${job.jobId}`"
            class="text-muw-blue hover:underline"
            data-testid="retinal-results-view-link"
          >
            View metrics
          </RouterLink>
        </td>
      </tr>
    </DenseTable>
  </section>
</template>
