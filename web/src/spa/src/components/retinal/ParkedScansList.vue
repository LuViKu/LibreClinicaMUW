<script setup lang="ts">
/**
 * Wave 2B (retinal followups) — ParkedScansList.
 *
 * Lists every {@code parked} retinal_inference_job for a single study
 * subject + lets the operator bind each one to a concrete event_crf
 * via Wave 1B's {@code PATCH /pages/api/v1/retinal-jobs/{jobId}/bind}.
 *
 * <p>Embedded in Wave 2A's {@code SubjectRetinalTab.vue} via a named
 * slot:
 *
 * <pre>
 *   &lt;SubjectRetinalTab :subject-id="subjectId"&gt;
 *     &lt;template #parked&gt;
 *       &lt;ParkedScansList :study-subject-id="subjectId" /&gt;
 *     &lt;/template&gt;
 *   &lt;/SubjectRetinalTab&gt;
 * </pre>
 *
 * <p>If Wave 2A's SubjectRetinalTab does not exist yet at integration
 * time, the component renders standalone and the main session adds
 * the integration during the harmonize commit.
 *
 * <p>Filter strategy: the backend endpoint
 * {@code GET /pages/api/v1/study-subjects/{id}/retinal-jobs} does NOT
 * accept a status filter, so we fetch the full list + filter
 * client-side to {@code status === 'parked'}. The list is short by
 * definition (parked = "operator hasn't bound it yet") so the cost is
 * negligible.
 *
 * <p>Strings come from {@code retinal.parked.*} in the Wave 1C i18n
 * registry — no hard-coded German.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import VisitPickerModal, {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  type PickedEvent as _VisitPickerPickedEvent,
} from '@/components/octportal/VisitPickerModal.vue'
import { ApiError, ApiNetworkError } from '@/api/client'
import {
  bindParkedJob,
  listSubjectJobs,
  type RetinalJobSummary,
} from '@/api/retinal'

interface Props {
  studySubjectId: number
  /** Subject LABEL for the visit picker; defaults to the numeric id
   *  string when the parent (e.g. Wave 2A's SubjectRetinalTab) hasn't
   *  threaded the label through yet. The events endpoint accepts the
   *  numeric id-as-string as a fallback for un-labelled subjects. */
  subjectLabel?: string
}

const props = withDefaults(defineProps<Props>(), {
  subjectLabel: '',
})

const { t } = useI18n()

const jobs = ref<RetinalJobSummary[]>([])
const isLoading = ref(false)
const errorMessage = ref<string | null>(null)
const toastMessage = ref<string | null>(null)

/** Job currently driving the visit picker. */
const bindTargetJobId = ref<number | null>(null)
/** Per-job in-flight flag for the PATCH bind. */
const bindingId = ref<number | null>(null)

/** Subject label for the visit picker. Falls back to the numeric id
 *  as a string so the modal still has something to pass to
 *  /api/v1/events?subjectId=… until the parent threads the label. */
const effectiveLabel = computed<string>(() => {
  if (props.subjectLabel.length > 0) return props.subjectLabel
  return String(props.studySubjectId)
})

const parkedJobs = computed<RetinalJobSummary[]>(() =>
  jobs.value.filter((j) => j.status === 'parked'),
)
const hasParked = computed(() => parkedJobs.value.length > 0)

async function load(): Promise<void> {
  errorMessage.value = null
  isLoading.value = true
  try {
    jobs.value = await listSubjectJobs(props.studySubjectId)
  } catch (e) {
    if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      errorMessage.value = body?.message ?? t('retinal.parked.loadError')
    } else if (e instanceof ApiNetworkError) {
      errorMessage.value = t('retinal.parked.loadError')
    } else {
      errorMessage.value = e instanceof Error ? e.message : t('retinal.parked.loadError')
    }
    jobs.value = []
  } finally {
    isLoading.value = false
  }
}

function openBind(jobId: number): void {
  bindTargetJobId.value = jobId
}

function onBindModalClose(): void {
  bindTargetJobId.value = null
}

/** Visit picker emitted a pick — fire the PATCH bind for the in-flight
 *  job, then refresh the list. Handles 409 (already bound by another
 *  session) as a non-error refresh. */
async function onEventPicked(payload: {
  studyEventId: number
  eventCrfId: number | null
  definitionLabel: string
  dateStart: string
}): Promise<void> {
  const jobId = bindTargetJobId.value
  bindTargetJobId.value = null
  if (jobId == null) return
  // 2026-06-23 — parked-bind still requires an open event_crf (the
  // bulk-bind backend writes event_crf_id). Surface the no-CRF
  // outcome so the operator knows to start data entry first.
  if (payload.eventCrfId == null || payload.eventCrfId <= 0) {
    errorMessage.value = t('retinal.parked.bindError')
    return
  }
  bindingId.value = jobId
  errorMessage.value = null
  toastMessage.value = null
  // Optimistic — remove the row immediately so the operator sees the
  // bind take effect without waiting for the refresh.
  const snapshot = jobs.value
  jobs.value = jobs.value.filter((j) => j.jobId !== jobId)
  try {
    await bindParkedJob(jobId, { eventCrfId: payload.eventCrfId })
    toastMessage.value = t('retinal.parked.bindSuccess')
  } catch (e) {
    // 409 = bound by another session in the meantime — refresh + show
    // the conflict toast instead of an error.
    if (e instanceof ApiError && e.status === 409) {
      toastMessage.value = t('retinal.parked.bindConflict')
    } else {
      // Restore the optimistic removal so the operator can retry.
      jobs.value = snapshot
      if (e instanceof ApiError) {
        const body = e.body as { message?: string } | null
        errorMessage.value = body?.message ?? t('retinal.parked.bindError')
      } else if (e instanceof ApiNetworkError) {
        errorMessage.value = t('retinal.parked.bindError')
      } else {
        errorMessage.value = e instanceof Error ? e.message : t('retinal.parked.bindError')
      }
    }
  } finally {
    bindingId.value = null
    // Refresh after any outcome — covers both happy path (jobs that
    // didn't make the optimistic cut) and the 409 conflict path where
    // the server's truth differs from our local snapshot.
    await load()
  }
}

onMounted(() => {
  void load()
})

watch(
  () => props.studySubjectId,
  () => {
    void load()
  },
)
</script>

<template>
  <section data-testid="parked-scans-list" class="border border-slate-200 rounded-md bg-white p-4">
    <header class="flex items-baseline justify-between gap-3 mb-3">
      <div>
        <h3 class="text-[13px] font-semibold text-slate-900">{{ t('retinal.parked.title') }}</h3>
        <p class="text-[11.5px] text-slate-500 mt-0.5">{{ t('retinal.parked.subtitle') }}</p>
      </div>
    </header>

    <div v-if="toastMessage" data-testid="parked-scans-toast" class="text-[12px] text-muw-teal-700 bg-muw-teal-50 border border-muw-teal-200 rounded px-2 py-1.5 mb-2">
      {{ toastMessage }}
    </div>

    <div v-if="errorMessage" data-testid="parked-scans-error" class="text-[12px] text-rose-700 bg-rose-50 border border-rose-200 rounded px-2 py-1.5 mb-2">
      {{ errorMessage }}
    </div>

    <div v-if="isLoading" data-testid="parked-scans-loading" class="text-[12px] text-slate-500">
      {{ t('retinal.parked.loading') }}
    </div>

    <div v-else-if="!hasParked" data-testid="parked-scans-empty" class="text-[12px] text-slate-500">
      {{ t('retinal.parked.empty') }}
    </div>

    <table v-else class="w-full text-[12px]" data-testid="parked-scans-table">
      <thead class="text-left text-slate-500 border-b border-slate-200">
        <tr>
          <th class="py-1.5 font-medium">{{ t('retinal.parked.colTask') }}</th>
          <th class="py-1.5 font-medium">{{ t('retinal.parked.colEye') }}</th>
          <th class="py-1.5 font-medium">{{ t('retinal.parked.colEnqueued') }}</th>
          <th class="py-1.5 font-medium text-right">{{ t('retinal.parked.colAction') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="job in parkedJobs"
          :key="job.jobId"
          :data-testid="`parked-scans-row-${job.jobId}`"
          class="border-b border-slate-100 last:border-b-0"
        >
          <td class="py-1.5">{{ job.task }}</td>
          <td class="py-1.5">{{ job.laterality }}</td>
          <td class="py-1.5">{{ job.completedAt ?? '—' }}</td>
          <td class="py-1.5 text-right">
            <button
              type="button"
              :data-testid="`parked-scans-bind-${job.jobId}`"
              class="px-2.5 py-1 text-[12px] font-medium border border-slate-200 rounded bg-white hover:bg-slate-50 text-slate-700 disabled:opacity-60 disabled:cursor-wait"
              :disabled="bindingId === job.jobId"
              @click="openBind(job.jobId)"
            >{{ t('retinal.parked.bindAction') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    <VisitPickerModal
      v-if="bindTargetJobId !== null"
      :open="bindTargetJobId !== null"
      :study-subject-id="props.studySubjectId"
      :subject-label="effectiveLabel"
      @event-picked="onEventPicked"
      @close="onBindModalClose"
    />
  </section>
</template>
