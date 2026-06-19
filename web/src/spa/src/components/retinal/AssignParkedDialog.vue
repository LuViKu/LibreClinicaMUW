<script setup lang="ts">
/**
 * 2026-06-19 — AssignParkedDialog.
 *
 * Two-step wizard the parked-scans admin view uses to bind one
 * {@code retinal_inference_job} (status='parked', event_crf_id IS NULL)
 * to a concrete event_crf:
 *
 *   1. Pick patient → composes {@code PatientSearchModal} from the
 *      OCT-portal, seeded with the parked row's parsed PatientId so
 *      the operator usually just confirms.
 *   2. Pick visit   → composes {@code VisitPickerModal} from the
 *      OCT-portal, scoped to the picked subject's events.
 *
 * On the second-step pick this component emits {@code bind} with
 * {@code eventCrfId}; the parent calls
 * {@code retinalParkedStore.bind(jobId, eventCrfId)}. Both nested
 * modals already exist and carry their own loading / empty / error
 * states — we don't duplicate them here.
 *
 * <p>Strings come from {@code retinalParked.assign.*} — no hard-coded
 * German in the picker prose, only in step copy.
 */
import { computed, ref, watch } from 'vue'

import PatientSearchModal from '@/components/octportal/PatientSearchModal.vue'
import VisitPickerModal from '@/components/octportal/VisitPickerModal.vue'

import type { StudySubjectSearchHit } from '@/api/retinal'

interface Props {
  /** Whether the dialog flow is active. The parent controls the lifecycle
   *  via the open prop and the close emit. */
  open: boolean
  /** The {@code retinal_inference_job.job_id} being assigned. Used only
   *  for forwarding back to the parent on bind; never rendered here. */
  jobId: number
  /** Parsed PatientId from the parked row's audit metadata. Pre-fills
   *  the patient-search step so the operator typically only confirms. */
  initialPatientId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** Operator finished both steps; parent should PATCH the bind. */
  (e: 'bind', payload: { jobId: number; eventCrfId: number }): void
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

function onSubjectPicked(subject: StudySubjectSearchHit): void {
  pickedSubject.value = subject
  step.value = 'visit'
}

function onEventPicked(payload: {
  eventCrfId: number
  definitionLabel: string
  dateStart: string
}): void {
  if (payload.eventCrfId <= 0) {
    // The visit picker emits -1 when no started CRF exists for the
    // picked event. Surface the failure so the operator knows why the
    // bind didn't take — silent close was the 2026-06-18 smoke bug.
    emit('no-event-crf')
    return
  }
  emit('bind', { jobId: props.jobId, eventCrfId: payload.eventCrfId })
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
