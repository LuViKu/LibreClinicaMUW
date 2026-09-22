<script setup lang="ts">
/**
 * DR-029 — pick the visit once, for every file that follows.
 *
 * Three ways in, because in a clinic all three happen: today's scheduled
 * visits (served only when the institution has enabled the list — the
 * backend answers 404 otherwise and the list simply is not there), a
 * patient search followed by the visit picker, or a typed label that the
 * backend resolves for the date given. Whichever way, the result is one
 * batch visit on the store, applied to every reviewable row; a row can still
 * be pointed elsewhere by hand afterwards.
 *
 * The typed label doubles as the hint for files that carry none (photos,
 * DICOM exports whose patient module is not consulted), so a label typed
 * before the drop travels with every row.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import PatientSearchModal from '@/components/octportal/PatientSearchModal.vue'
import VisitPickerModal from '@/components/octportal/VisitPickerModal.vue'
import { listVisitsForDay, resolveRows, type DayVisit, type ResolveScanResult, type UploadMode } from '@/api/uploadWorkbench'
import { useUploadWorkbenchStore } from '@/stores/uploadWorkbench'
import type { StudySubjectSearchHit } from '@/api/retinal'

const props = defineProps<{ mode: UploadMode }>()
const { t } = useI18n()
const store = useUploadWorkbenchStore()

/** null = the list is not available; [] = available, nothing today. */
const visits = ref<DayVisit[] | null>(null)
const loadingVisits = ref(false)
const filter = ref('')

const patientId = ref('')
const date = ref('')
const resolveState = ref<ResolveScanResult['state'] | null>(null)
const resolved = ref<ResolveScanResult | null>(null)

const searchOpen = ref(false)
const pickerSubject = ref<{ id: number; label: string; studyName: string; studyId: number } | null>(null)

async function loadVisits(): Promise<void> {
  loadingVisits.value = true
  try {
    visits.value = await listVisitsForDay(props.mode, date.value || undefined)
  } catch {
    visits.value = null
  } finally {
    loadingVisits.value = false
  }
}

onMounted(loadVisits)
watch(date, () => {
  store.setDateHint(date.value)
  void loadVisits()
})

const listAvailable = computed(() => visits.value !== null)
const filtered = computed(() => {
  const all = visits.value ?? []
  const q = filter.value.trim().toLowerCase()
  if (!q) return all
  return all.filter((v) =>
    v.subjectLabel.toLowerCase().includes(q)
    || v.eventLabel.toLowerCase().includes(q)
    || v.studyName.toLowerCase().includes(q))
})

function chooseVisit(v: DayVisit): void {
  store.setBatchVisit({
    studyEventId: v.studyEventId,
    eventCrfId: null,
    studySubjectId: v.studySubjectId,
    subjectLabel: v.subjectLabel,
    eventLabel: v.eventLabel,
    studyName: v.studyName,
    studyId: null,
    dateStart: date.value || null,
  })
  patientId.value = v.subjectLabel
  store.setPatientIdHint(v.subjectLabel)
  resolveState.value = null
}

function clearVisit(): void {
  store.setBatchVisit(null)
}

/** The typed label: checked against the register, and kept as the hint for label-less files. */
async function onPatientBlur(): Promise<void> {
  const pid = patientId.value.trim()
  store.setPatientIdHint(pid)
  resolveState.value = null
  resolved.value = null
  if (!pid) return
  try {
    const r = await resolveRows(props.mode, [{ patientId: pid, scanDate: date.value || null, laterality: null }])
    const first = r.scans[0]
    if (first) {
      resolved.value = first
      resolveState.value = first.state
    }
  } catch {
    resolveState.value = null // best-effort: the upload never waits on this
  }
  await store.applyPatientIdHint()
}

const suggestedEvent = computed(() => {
  const r = resolved.value
  if (!r || r.state !== 'suggested' || r.candidates.length !== 1) return null
  return r.candidates[0].matchingEvent
})

const resolvedLabel = computed(() => resolved.value?.candidates[0]?.subjectLabel ?? '')
const resolvedStudy = computed(() => resolved.value?.candidates[0]?.studyName ?? '')

function acceptSuggestedVisit(): void {
  const r = resolved.value
  const ev = suggestedEvent.value
  if (!r || !ev) return
  const c = r.candidates[0]
  store.setBatchVisit({
    studyEventId: ev.studyEventId,
    eventCrfId: ev.eventCrfId,
    studySubjectId: c.studySubjectId,
    subjectLabel: c.subjectLabel,
    eventLabel: ev.definitionLabel,
    studyName: c.studyName,
    studyId: c.studyId,
    dateStart: ev.dateStart,
  })
}

function onSubjectPicked(subject: StudySubjectSearchHit): void {
  searchOpen.value = false
  pickerSubject.value = { id: subject.studySubjectId, label: subject.label, studyName: subject.studyName, studyId: subject.studyId }
}

function onEventPicked(payload: { studyEventId: number; eventCrfId: number | null; definitionLabel: string; dateStart: string }): void {
  const subject = pickerSubject.value
  pickerSubject.value = null
  if (!subject) return
  store.setBatchVisit({
    studyEventId: payload.studyEventId,
    eventCrfId: payload.eventCrfId,
    studySubjectId: subject.id,
    subjectLabel: subject.label,
    eventLabel: payload.definitionLabel,
    studyName: subject.studyName,
    studyId: subject.studyId,
    dateStart: payload.dateStart,
  })
  patientId.value = subject.label
  store.setPatientIdHint(subject.label)
}
</script>

<template>
  <section class="bg-white rounded-2xl ring-1 ring-slate-200 p-4 sm:p-5 mb-5" data-testid="batch-visit" :aria-label="t('uploadPortal.batch.title')">
    <div class="flex items-start justify-between gap-3 mb-3">
      <div>
        <h2 class="text-[15px] font-semibold text-slate-800">{{ t('uploadPortal.batch.title') }}</h2>
        <p class="text-[12.5px] text-slate-500 mt-0.5">{{ t('uploadPortal.batch.hint') }}</p>
      </div>
      <button
        v-if="!store.batchVisit"
        type="button"
        class="shrink-0 inline-flex items-center gap-1.5 px-3 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 min-h-8"
        data-testid="batch-search-patient"
        @click="searchOpen = true"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
          <circle cx="11" cy="11" r="8" />
          <path d="m21 21-4.3-4.3" />
        </svg>
        {{ t('uploadPortal.batch.searchPatient') }}
      </button>
    </div>

    <div
      v-if="store.batchVisit"
      class="flex items-center justify-between gap-3 rounded-lg ring-1 ring-muw-teal-200 bg-muw-teal-50 px-3 py-2.5"
      data-testid="batch-visit-chosen"
    >
      <div>
        <div class="text-[14px] font-medium text-slate-800">{{ store.batchVisit.subjectLabel }}</div>
        <div class="text-[12px] text-muw-teal-700">{{ store.batchVisit.eventLabel }} · {{ store.batchVisit.studyName }}</div>
      </div>
      <button
        type="button"
        class="text-[12px] text-slate-500 hover:text-slate-700 underline min-h-8 px-1"
        data-testid="batch-visit-clear"
        @click="clearVisit"
      >{{ t('uploadPortal.batch.clear') }}</button>
    </div>

    <div v-else class="grid gap-4 md:grid-cols-2">
      <div v-if="listAvailable" data-testid="todays-visits">
        <label class="block text-[13px] font-medium text-slate-600 mb-1" for="up-visit-filter">{{ t('uploadPortal.batch.visitsLabel') }}</label>
        <input
          id="up-visit-filter"
          v-model="filter"
          type="text"
          inputmode="text"
          autocomplete="off"
          :placeholder="t('uploadPortal.batch.visitsFilterPlaceholder')"
          class="w-full px-3 py-2.5 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
        />
        <p v-if="loadingVisits" class="mt-2 text-[12px] text-slate-500">{{ t('uploadPortal.batch.visitsLoading') }}</p>
        <p v-else-if="filtered.length === 0" class="mt-2 text-[12px] text-slate-500" data-testid="visit-empty">{{ t('uploadPortal.batch.visitsNone') }}</p>
        <ul v-else class="mt-2 max-h-56 overflow-y-auto rounded-lg ring-1 ring-slate-200 divide-y divide-slate-100">
          <li v-for="v in filtered" :key="v.studyEventId">
            <button
              type="button"
              class="w-full text-left px-3 py-2.5 hover:bg-slate-50 focus:bg-slate-50 focus:outline-none"
              data-testid="visit-row"
              @click="chooseVisit(v)"
            >
              <span class="block text-[14px] font-medium text-slate-800">{{ v.subjectLabel }}</span>
              <span class="block text-[12px] text-slate-500">{{ v.eventLabel }} · {{ v.studyName }}<template v-if="v.time"> · {{ v.time }}</template></span>
            </button>
          </li>
        </ul>
      </div>

      <div class="space-y-3">
        <div>
          <label class="block text-[13px] font-medium text-slate-600 mb-1" for="up-patient">{{ t('uploadPortal.batch.patientIdLabel') }}</label>
          <input
            id="up-patient"
            v-model="patientId"
            type="text"
            inputmode="text"
            autocomplete="off"
            :placeholder="t('uploadPortal.batch.patientIdPlaceholder')"
            class="w-full px-3 py-2.5 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
            data-testid="patient-id-input"
            @blur="onPatientBlur"
          />
          <p v-if="resolveState === 'suggested' || resolveState === 'novisit'" class="mt-1.5 text-[12px] text-muw-teal-700" data-testid="resolve-found">
            {{ t('uploadPortal.batch.patientFound', { label: resolvedLabel, study: resolvedStudy }) }}
          </p>
          <button
            v-if="suggestedEvent"
            type="button"
            class="mt-1.5 block text-[12px] font-medium text-muw-blue hover:text-muw-blue-700 underline min-h-8"
            data-testid="accept-suggested-visit"
            @click="acceptSuggestedVisit"
          >{{ t('uploadPortal.batch.attachToVisit', { visit: suggestedEvent.definitionLabel }) }}</button>
          <p v-else-if="resolveState === 'ambiguous'" class="mt-1.5 text-[12px] text-amber-700">{{ t('uploadPortal.batch.patientAmbiguous') }}</p>
          <p v-else-if="resolveState === 'nopatient'" class="mt-1.5 text-[12px] text-slate-500">{{ t('uploadPortal.batch.patientNotFound') }}</p>
        </div>
        <div>
          <label class="block text-[13px] font-medium text-slate-600 mb-1" for="up-date">{{ t('uploadPortal.batch.dateLabel') }}</label>
          <input
            id="up-date"
            v-model="date"
            type="date"
            class="w-full px-3 py-2.5 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
            data-testid="date-input"
          />
        </div>
      </div>
    </div>

    <PatientSearchModal
      :open="searchOpen"
      :initial-query="patientId"
      :public-context="props.mode === 'public'"
      @subject-picked="onSubjectPicked"
      @close="searchOpen = false"
    />
    <VisitPickerModal
      v-if="pickerSubject"
      :open="pickerSubject !== null"
      :study-subject-id="pickerSubject.id"
      :subject-label="pickerSubject.label"
      :public-context="props.mode === 'public'"
      @event-picked="onEventPicked"
      @close="pickerSubject = null"
    />
  </section>
</template>
