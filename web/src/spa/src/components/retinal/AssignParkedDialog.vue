<script setup lang="ts">
/**
 * 2026-06-19 — AssignParkedDialog.
 *
 * Two-step wizard the parked-scans admin view uses to bind one OR many
 * {@code retinal_inference_job} rows (status='parked', event_crf_id IS
 * NULL) to a concrete event_crf:
 *
 *   1. Pick patient → composes {@code PatientSearchModal} from the
 *      OCT-portal, seeded with the parked row's parsed PatientId so
 *      the operator usually just confirms.
 *   2. Pick visit   → composes {@code VisitPickerModal} from the
 *      OCT-portal, scoped to the picked subject's events.
 *
 * On the second-step pick this component emits {@code bind} with
 * {@code jobIds: number[]} + {@code eventCrfId}; the parent calls
 * {@code retinalParkedStore.bulkBind(jobIds, eventCrfId)} when the
 * batch has more than one entry, or {@code bind(jobId, eventCrfId)}
 * for the legacy single-row case. Both nested modals already exist
 * and carry their own loading / empty / error states — we don't
 * duplicate them here.
 *
 * <p>2026-06-20 B2 (bulk-bind): the {@code jobIds} prop accepts an
 * array so the bulk-toolbar callsite can ship the whole selection
 * through one wizard. A small "Sie weisen N Scans dem nächsten Besuch
 * zu" summary is rendered when N > 1; the single-row case keeps its
 * original layout (no summary). The emit shape is always an array, so
 * the parent's handler stays uniform.
 *
 * <p>Strings come from {@code retinalParked.assign.*} and
 * {@code retinalParked.bulkBind.*} — no hard-coded German in the
 * picker prose, only in the bulk summary.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import PatientSearchModal from '@/components/octportal/PatientSearchModal.vue'
import VisitPickerModal from '@/components/octportal/VisitPickerModal.vue'

import type { StudySubjectSearchHit } from '@/api/retinal'

interface Props {
  /** Whether the dialog flow is active. The parent controls the lifecycle
   *  via the open prop and the close emit. */
  open: boolean
  /**
   * The {@code retinal_inference_job.job_id}s being assigned. Single-bind
   * passes a one-element array; bulk-bind passes the operator's full
   * selection. The dialog itself never renders jobIds (only the count),
   * but forwards them verbatim to the parent on bind.
   */
  jobIds: number[]
  /** Parsed PatientId from the parked row's audit metadata. Pre-fills
   *  the patient-search step so the operator typically only confirms.
   *  In bulk mode the parent picks the first row's PatientId — the
   *  operator may need to clear if the upload session spans patients,
   *  but the common case is one upload session = one patient. */
  initialPatientId: string
}

const props = defineProps<Props>()

const { t } = useI18n()

const emit = defineEmits<{
  /**
   * Operator finished both steps; parent should call the bulk-bind
   * endpoint with every {@code jobId}. The emit shape is always an
   * array — single-bind callers see a length-1 array.
   */
  (e: 'bind', payload: { jobIds: number[]; eventCrfId: number }): void
  /** Operator cancelled at either step. */
  (e: 'close'): void
  /**
   * Picked event has no started event_crf — surfaced to the parent so
   * the operator sees an actionable toast instead of a silently-closing
   * dialog. The 2026-06-18 smoke uncovered this gap: parked rows seemed
   * to "stick" because the bind PATCH never fired.
   */
  (e: 'no-event-crf'): void
}>()

/** Two discrete steps:
 *  - {@code patient} — PatientSearchModal mounted, awaiting pick
 *  - {@code visit}   — VisitPickerModal mounted, awaiting pick
 *  Once the operator picks a subject in step 1, we advance to step 2;
 *  cancelling step 2 walks back to step 1 (in case the operator picked
 *  the wrong patient). */
const step = ref<'patient' | 'visit'>('patient')

/** The subject picked in step 1. Drives the VisitPickerModal mount. */
const pickedSubject = ref<StudySubjectSearchHit | null>(null)

watch(
  () => props.open,
  (next) => {
    if (next) {
      step.value = 'patient'
      pickedSubject.value = null
    }
  },
)

/** True when the operator is binding more than one row through the
 *  bulk toolbar. Drives the in-dialog summary banner. */
const isBulk = computed(() => props.jobIds.length > 1)

/** Localised in-dialog summary copy — only rendered in bulk mode. */
const bulkSummary = computed(() =>
  t('retinalParked.bulkBind.bulkConfirm', { count: props.jobIds.length }),
)

function onSubjectPicked(subject: StudySubjectSearchHit): void {
  pickedSubject.value = subject
  step.value = 'visit'
}

function onEventPicked(payload: {
  studyEventId: number
  eventCrfId: number | null
  definitionLabel: string
  dateStart: string
}): void {
  // 2026-06-23 — assign-parked still requires a real event_crf (the
  // bulk-bind backend writes to event_crf_id; planned-visit binding
  // for the parked-recovery path is a separate future change). When
  // the picker emits a planned visit (eventCrfId === null), surface
  // the no-CRF outcome so the operator knows to start data entry on
  // that visit first.
  if (payload.eventCrfId == null || payload.eventCrfId <= 0) {
    emit('no-event-crf')
    return
  }
  emit('bind', { jobIds: props.jobIds, eventCrfId: payload.eventCrfId })
}

function onPatientStepClose(): void {
  emit('close')
}
function onVisitStepClose(): void {
  // Walk back to step 1 so the operator can correct a wrong patient
  // pick without losing the dialog. Cancel-all-the-way is reached by
  // closing the patient modal that re-mounts here.
  step.value = 'patient'
  pickedSubject.value = null
}

const patientStepOpen = computed(() => props.open && step.value === 'patient')
const visitStepOpen = computed(
  () => props.open && step.value === 'visit' && pickedSubject.value != null,
)
</script>

<template>
  <!--
    Bulk-summary banner — only rendered when binding > 1 row. The
    PatientSearchModal / VisitPickerModal already teleport to body, so
    this small overlay is rendered at the top of the document body too
    via teleport so it sits above the modal backdrops at z-50.
  -->
  <Teleport v-if="isBulk && open" to="body">
    <div
      class="fixed top-3 left-1/2 -translate-x-1/2 z-[60] bg-sky-50 border border-sky-200 text-sky-900 rounded-md px-4 py-2 text-[13px] shadow-md"
      data-testid="assign-parked-bulk-summary"
    >
      {{ bulkSummary }}
    </div>
  </Teleport>

  <PatientSearchModal
    :open="patientStepOpen"
    :initial-query="props.initialPatientId"
    @subject-picked="onSubjectPicked"
    @close="onPatientStepClose"
  />
  <VisitPickerModal
    v-if="pickedSubject"
    :open="visitStepOpen"
    :study-subject-id="pickedSubject.studySubjectId"
    :subject-label="pickedSubject.label"
    @event-picked="onEventPicked"
    @close="onVisitStepClose"
  />
</template>
