<script setup lang="ts">
/**
 * DR-025 — two-step bind wizard for the fundus-image reconciliation inbox.
 *
 * Clones the retinal AssignParkedDialog, reusing the OCT-portal
 * PatientSearchModal (subject typeahead → /study-subjects/search) and
 * VisitPickerModal (event/visit picker, auth two-hop). On the visit pick it
 * emits `bind` with the chosen subject + event; unlike the parked flow it
 * ALSO accepts a planned visit with no started event_crf (eventCrfId=null) —
 * the backend binds the image at the event level in that case.
 */
import { computed, ref, watch } from 'vue'

import PatientSearchModal from '@/components/octportal/PatientSearchModal.vue'
import VisitPickerModal, { type PickedEvent } from '@/components/octportal/VisitPickerModal.vue'
import type { StudySubjectSearchHit } from '@/api/retinal'

interface Props {
  open: boolean
  imageIngestId: number
  /** Pre-fills the subject search from the row's PatientID hint. */
  initialPatientId: string
}
const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'bind', payload: {
    imageIngestId: number
    studySubjectId: number
    studyEventId: number | null
    eventCrfId: number | null
  }): void
  (e: 'close'): void
}>()

const step = ref<'patient' | 'visit'>('patient')
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

function onEventPicked(evt: PickedEvent): void {
  if (!pickedSubject.value) return
  emit('bind', {
    imageIngestId: props.imageIngestId,
    studySubjectId: pickedSubject.value.studySubjectId,
    studyEventId: evt.studyEventId,
    eventCrfId: evt.eventCrfId,
  })
}

function onPatientClose(): void {
  emit('close')
}
function onVisitClose(): void {
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
    @close="onPatientClose"
  />
  <VisitPickerModal
    v-if="pickedSubject"
    :open="visitStepOpen"
    :study-subject-id="pickedSubject.studySubjectId"
    :subject-label="pickedSubject.label"
    @event-picked="onEventPicked"
    @close="onVisitClose"
  />
</template>
