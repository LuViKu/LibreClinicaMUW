<script setup lang="ts">
/**
 * DR-029 — the combined uploader, the same on both of its pages.
 *
 * One drop zone for OCT exports, DICOM files and photos; one visit pick for
 * the batch; one review list where every row is filed, changed by hand,
 * sent without a visit, or taken back within a minute. The public page and
 * the staff page wrap this with their own chrome and pass the mode; the
 * store does the rest.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import PatientSearchModal from '@/components/octportal/PatientSearchModal.vue'
import StudyPickerModal from '@/components/octportal/StudyPickerModal.vue'
import VisitPickerModal from '@/components/octportal/VisitPickerModal.vue'
import BatchVisitPanel from './BatchVisitPanel.vue'
import UploadDropzone from './UploadDropzone.vue'
import UploadRow from './UploadRow.vue'
import UploadSummaryBar from './UploadSummaryBar.vue'
import { useUploadWorkbenchStore } from '@/stores/uploadWorkbench'
import type { ResolveCandidate, UploadMode } from '@/api/uploadWorkbench'
import type { StudySubjectSearchHit } from '@/api/retinal'

const props = defineProps<{ mode: UploadMode }>()
const { t } = useI18n()
const store = useUploadWorkbenchStore()

onMounted(() => {
  store.reset()
  store.setMode(props.mode)
})
onBeforeUnmount(() => store.reset())

const searchTargetRowId = ref<string | null>(null)
const visitTargetRowId = ref<string | null>(null)
const studyPickTargetRowId = ref<string | null>(null)

const searchInitialQuery = computed(() => {
  const id = searchTargetRowId.value
  if (id == null) return ''
  return store.rows.find((r) => r.rowId === id)?.patientId ?? ''
})

const visitTargetSubject = computed<{ id: number; label: string } | null>(() => {
  const rid = visitTargetRowId.value
  if (rid == null) return null
  const row = store.rows.find((r) => r.rowId === rid)
  if (!row?.selectedCandidate) return null
  return { id: row.selectedCandidate.studySubjectId, label: row.selectedCandidate.subjectLabel }
})

const studyPickContext = computed<{ candidates: ResolveCandidate[]; patientId: string } | null>(() => {
  const rid = studyPickTargetRowId.value
  if (rid == null) return null
  const row = store.rows.find((r) => r.rowId === rid)
  if (!row?.candidates || row.candidates.length === 0) return null
  return { candidates: row.candidates, patientId: row.patientId }
})

const hasRows = computed(() => store.rows.length > 0)

function onFilesAdded(files: File[]): void {
  void store.addFiles(files)
}

function onSubjectPicked(subject: StudySubjectSearchHit): void {
  const rid = searchTargetRowId.value
  searchTargetRowId.value = null
  if (rid == null) return
  void store.assignFromSearch(rid, subject)
}

function onEventPicked(payload: { studyEventId: number; eventCrfId: number | null; definitionLabel: string; dateStart: string }): void {
  const rid = visitTargetRowId.value
  visitTargetRowId.value = null
  if (rid == null) return
  store.setManualVisit(rid, payload.studyEventId, payload.eventCrfId, payload.definitionLabel, payload.dateStart)
}

function onStudyPicked(candidate: ResolveCandidate): void {
  const rid = studyPickTargetRowId.value
  studyPickTargetRowId.value = null
  if (rid == null) return
  store.pickStudyCandidate(rid, candidate)
}
</script>

<template>
  <div data-testid="upload-workbench" :data-mode="props.mode">
    <BatchVisitPanel :mode="props.mode" />

    <template v-if="!hasRows">
      <UploadDropzone mode="hero" @files-added="onFilesAdded" />
    </template>

    <template v-else>
      <UploadDropzone mode="slim" @files-added="onFilesAdded" />
      <UploadSummaryBar :rows="store.rows" @confirm-all="store.confirmAll()" />
      <div class="bg-white rounded-b-2xl ring-1 ring-slate-200 ring-t-0 overflow-hidden" data-testid="upload-rows">
        <div class="hidden md:flex items-center gap-4 px-5 py-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-slate-400" aria-hidden="true">
          <div class="w-[260px] shrink-0">{{ t('uploadPortal.columns.file') }}</div>
          <div class="w-[110px] shrink-0">{{ t('uploadPortal.columns.patientId') }}</div>
          <div class="w-[110px] shrink-0">{{ t('uploadPortal.columns.date') }}</div>
          <div class="w-[92px] shrink-0">{{ t('uploadPortal.columns.eye') }}</div>
          <div class="flex-1">{{ t('uploadPortal.columns.assignment') }}</div>
        </div>
        <UploadRow
          v-for="row in store.rows"
          :key="row.rowId"
          :row="row"
          @confirm="(id) => store.confirm(id)"
          @undo="(id) => store.undo(id)"
          @pick-visit="(id) => (visitTargetRowId = id)"
          @pick-study="(id) => (studyPickTargetRowId = id)"
          @park="(id) => store.park(id)"
          @search-patient="(id) => (searchTargetRowId = id)"
          @dismiss="(id) => store.dismiss(id)"
        />
      </div>
    </template>

    <PatientSearchModal
      :open="searchTargetRowId !== null"
      :initial-query="searchInitialQuery"
      :public-context="props.mode === 'public'"
      @subject-picked="onSubjectPicked"
      @close="searchTargetRowId = null"
    />
    <VisitPickerModal
      v-if="visitTargetSubject"
      :open="visitTargetRowId !== null"
      :study-subject-id="visitTargetSubject.id"
      :subject-label="visitTargetSubject.label"
      :public-context="props.mode === 'public'"
      @event-picked="onEventPicked"
      @close="visitTargetRowId = null"
    />
    <StudyPickerModal
      v-if="studyPickContext"
      :open="studyPickTargetRowId !== null"
      :candidates="studyPickContext.candidates"
      :patient-id="studyPickContext.patientId"
      @study-picked="onStudyPicked"
      @close="studyPickTargetRowId = null"
    />
  </div>
</template>
