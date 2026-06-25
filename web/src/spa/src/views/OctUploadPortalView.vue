<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — OCT-Upload-Portal view.
 *
 * Public, unauthenticated SPA page mounted at `/app/oct-upload`. The
 * institutional reverse proxy is the only access gate; both the router
 * guard ({@code meta.public = true}) and the backend
 * {@code PublicOctUploadController} match the same access model.
 *
 * Three artboards driven by one component:
 *  - {@code ready}    — empty queue, hero dropzone
 *  - {@code parsing}  — at least one row is reading its header
 *  - {@code review}   — every row has parsed; SummaryBar + ReviewQueue
 *
 * Backed by the {@link useOctPortalStore} Pinia store. The view stays
 * declarative — every operator action emits up to here and routes to
 * the store; the store owns the API client wiring.
 *
 * <h3>Strings</h3>
 *
 * German UI text is hard-coded inline per the plan's "no de.json for
 * v1" choice — the portal is unilaterally German for MUW operators.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import PublicTopBar from '@/components/octportal/PublicTopBar.vue'
import PublicFooter from '@/components/octportal/PublicFooter.vue'
import E2eDropzone from '@/components/octportal/E2eDropzone.vue'
import ParseQueue from '@/components/octportal/ParseQueue.vue'
import ReviewQueue from '@/components/octportal/ReviewQueue.vue'
import SummaryBar from '@/components/octportal/SummaryBar.vue'
import PatientSearchModal from '@/components/octportal/PatientSearchModal.vue'
import VisitPickerModal from '@/components/octportal/VisitPickerModal.vue'
import StudyPickerModal from '@/components/octportal/StudyPickerModal.vue'

import { useOctPortalStore } from '@/stores/octPortal'
import type { ResolveCandidate } from '@/api/octPortal'
import type { StudySubjectSearchHit } from '@/api/retinal'

const { t } = useI18n()
const store = useOctPortalStore()

/** Row currently driving a modal — {@code null} when no modal is open. */
const searchTargetRowId = ref<string | null>(null)
const visitTargetRowId = ref<string | null>(null)
const studyPickTargetRowId = ref<string | null>(null)

/** Initial query for the search modal — seeded from the row's parsed
 *  PatientId so the operator doesn't have to retype the scan's id. */
const searchInitialQuery = computed<string>(() => {
  const id = searchTargetRowId.value
  if (id == null) return ''
  const row = store.rows.find((r) => r.rowId === id)
  return row?.scan?.patientId ?? ''
})

/** Subject + label for the visit-picker — the modal needs the LABEL
 *  to call /api/v1/events?subjectId=<label>, and the numeric id to
 *  let the store match the row on pick. */
const visitTargetSubject = computed<{ id: number; label: string } | null>(() => {
  const rid = visitTargetRowId.value
  if (rid == null) return null
  const row = store.rows.find((r) => r.rowId === rid)
  if (!row?.selectedCandidate) return null
  return {
    id: row.selectedCandidate.studySubjectId,
    label: row.selectedCandidate.subjectLabel,
  }
})

/** Candidates + parsed PatientId for the study-picker — driven by an
 *  ambiguous row whose /resolve call returned more than one cohort.
 *  Null when no row is driving the modal. */
const studyPickContext = computed<{
  candidates: ResolveCandidate[]
  patientId: string
} | null>(() => {
  const rid = studyPickTargetRowId.value
  if (rid == null) return null
  const row = store.rows.find((r) => r.rowId === rid)
  if (!row?.candidates || row.candidates.length === 0) return null
  return {
    candidates: row.candidates,
    patientId: row.scan?.patientId ?? '',
  }
})

/** Three-state visual machine derived from store.rows. */
const screen = computed<'ready' | 'parsing' | 'review'>(() => {
  if (store.rows.length === 0) return 'ready'
  if (store.isParsing) return 'parsing'
  return 'review'
})

function onFilesAdded(files: File[]): void {
  // Fire and forget — the store flips rows into the `parsing` state
  // synchronously and the rest runs as promises.
  void store.addFiles(files)
}

function onConfirm(rowId: string): void {
  void store.confirm(rowId)
}

function onConfirmAll(): void {
  void store.confirmAll()
}

function onPark(rowId: string): void {
  void store.park(rowId)
}

function onUndo(rowId: string): void {
  void store.undo(rowId)
}

function onDismiss(rowId: string): void {
  store.dismiss(rowId)
}

/**
 * Wave 2B — "Visite wählen" / "Patient suchen" land their respective
 * modals. The wiring keeps the row interactive throughout:
 *  - Patient suchen → PatientSearchModal → store.assignFromSearch on
 *    pick → row re-resolves against the chosen subject.
 *  - Visite wählen → VisitPickerModal → store.setManualVisit on pick
 *    → row flips to {@code suggested} with the picked event.
 */
function onPickVisit(rowId: string): void {
  visitTargetRowId.value = rowId
}
function onSearchPatient(rowId: string): void {
  searchTargetRowId.value = rowId
}

/**
 * Wave 2C follow-up (2026-06-19) — operator clicked "Studie wählen"
 * on an ambiguous row. Open StudyPickerModal against the row's
 * {@code candidates} so the operator can pick the cohort.
 */
function onPickStudy(rowId: string): void {
  studyPickTargetRowId.value = rowId
}
function onStudyPicked(candidate: ResolveCandidate): void {
  const rid = studyPickTargetRowId.value
  studyPickTargetRowId.value = null
  if (rid == null) return
  store.pickStudyCandidate(rid, candidate)
}
function onStudyPickerClose(): void {
  studyPickTargetRowId.value = null
}

function onSubjectPicked(subject: StudySubjectSearchHit): void {
  const rid = searchTargetRowId.value
  searchTargetRowId.value = null
  if (rid == null) return
  void store.assignFromSearch(rid, subject)
}

function onEventPicked(payload: {
  studyEventId: number
  eventCrfId: number | null
  definitionLabel: string
  dateStart: string
}): void {
  const rid = visitTargetRowId.value
  visitTargetRowId.value = null
  if (rid == null) return
  store.setManualVisit(
    rid,
    payload.studyEventId,
    payload.eventCrfId,
    payload.definitionLabel,
    payload.dateStart,
  )
}

function onPatientSearchClose(): void {
  searchTargetRowId.value = null
}
function onVisitPickerClose(): void {
  visitTargetRowId.value = null
}
</script>

<template>
  <div class="flex flex-col bg-slate-50 min-h-screen" data-testid="oct-upload-portal">
    <PublicTopBar />
    <div class="flex-1 min-h-0">
      <div class="mx-auto max-w-[1248px] px-6 md:px-10 py-9">
        <!-- ============================ Page head ============================ -->
        <div class="flex items-end justify-between gap-4 mb-6">
          <div>
            <div class="text-[11px] font-semibold uppercase tracking-[0.14em] text-muw-coral-700 mb-1.5">{{ t('octPortal.head.eyebrow') }}</div>
            <h1 class="muw-display text-[27px] leading-tight font-semibold tracking-tight text-slate-900">{{ t('octPortal.head.title') }}</h1>
            <p class="text-[13.5px] text-slate-500 mt-2 max-w-[660px] leading-relaxed">
              {{ t('octPortal.head.leadIntro') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.studyWord') }}</span> {{ t('octPortal.head.leadAnd') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.visitWord') }}</span> {{ t('octPortal.head.leadDetermine') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.patientIdWord') }}</span> {{ t('octPortal.head.leadAnd') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.scanDateWord') }}</span> {{ t('octPortal.head.leadFromHeader') }}
            </p>
          </div>
          <div v-if="screen === 'parsing'" class="inline-flex items-center gap-2 text-[13px] text-slate-500 mb-1">
            <span class="text-muw-blue inline-block">
              <svg class="muw-portal-spin" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">
                <path d="M21 12a9 9 0 1 1-6.2-8.5" opacity="0.9" />
              </svg>
            </span>
            {{ t('octPortal.head.parsingStatus') }}
          </div>
        </div>

        <!-- ============================ READY artboard ============================ -->
        <template v-if="screen === 'ready'">
          <E2eDropzone mode="hero" @files-added="onFilesAdded" />
          <div class="flex items-start gap-2.5 mt-5 text-[12.5px] text-slate-500">
            <span class="text-muw-blue-300 mt-0.5">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
                <path d="M10.3 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.7 3.86a2 2 0 0 0-3.42 0Z" />
                <path d="M12 9v4M12 17h.01" />
              </svg>
            </span>
            <p class="max-w-[720px] leading-relaxed">
              {{ t('octPortal.hero.noLoginPrefix') }} <span class="font-medium text-slate-700">.e2e</span>{{ t('octPortal.hero.noLoginHeader') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.patientIdWord') }}</span>, <span class="font-medium text-slate-700">{{ t('octPortal.head.scanDateWord') }}</span> {{ t('octPortal.head.leadAnd') }} <span class="font-medium text-slate-700">{{ t('octPortal.hero.noLoginEye') }}</span> {{ t('octPortal.hero.noLoginSuffix') }}
            </p>
          </div>
        </template>

        <!-- ============================ PARSING artboard ============================ -->
        <template v-else-if="screen === 'parsing'">
          <E2eDropzone mode="slim" @files-added="onFilesAdded" />
          <ParseQueue :rows="store.rows" />
        </template>

        <!-- ============================ REVIEW artboard ============================ -->
        <template v-else>
          <E2eDropzone mode="slim" @files-added="onFilesAdded" />
          <SummaryBar :rows="store.rows" @confirm-all="onConfirmAll" />
          <ReviewQueue
            :rows="store.rows"
            @confirm="onConfirm"
            @undo="onUndo"
            @pick-visit="onPickVisit"
            @pick-study="onPickStudy"
            @park="onPark"
            @search-patient="onSearchPatient"
            @dismiss="onDismiss"
          />
        </template>
      </div>
    </div>
    <PublicFooter />

    <!-- ============================ Wave 2B modals ============================ -->
    <PatientSearchModal
      :open="searchTargetRowId !== null"
      :initial-query="searchInitialQuery"
      @subject-picked="onSubjectPicked"
      @close="onPatientSearchClose"
    />
    <VisitPickerModal
      v-if="visitTargetSubject"
      :open="visitTargetRowId !== null"
      :study-subject-id="visitTargetSubject.id"
      :subject-label="visitTargetSubject.label"
      :public-context="true"
      @event-picked="onEventPicked"
      @close="onVisitPickerClose"
    />
    <StudyPickerModal
      v-if="studyPickContext"
      :open="studyPickTargetRowId !== null"
      :candidates="studyPickContext.candidates"
      :patient-id="studyPickContext.patientId"
      @study-picked="onStudyPicked"
      @close="onStudyPickerClose"
    />
  </div>
</template>
