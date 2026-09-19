<script setup lang="ts">
/**
 * DR-029 — assignment + action cells for one upload row.
 *
 * The OCT portal's Assignment component against the combined store's row:
 * the same sub-states (suggested, committed, novisit, nopatient, ambiguous,
 * duplicate, error, committing) and the same actions, with one difference
 * in wording — a row without a visit is not "parked" for an image or a DICOM
 * file, it is uploaded to the inbox, and the button says so.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import EyeBadge from '@/components/octportal/EyeBadge.vue'
import PortalStatusPill from '@/components/octportal/PortalStatusPill.vue'
import StudyChip from '@/components/octportal/StudyChip.vue'
import { useUploadWorkbenchStore, type UploadRow } from '@/stores/uploadWorkbench'
import { formatDate } from '@/lib/dateFormat'

const store = useUploadWorkbenchStore()
const { t } = useI18n()

interface Props {
  row: UploadRow
}

const props = defineProps<Props>()
const emit = defineEmits<{
  confirm: [rowId: string]
  undo: [rowId: string]
  'pick-visit': [rowId: string]
  'pick-study': [rowId: string]
  park: [rowId: string]
  'search-patient': [rowId: string]
  dismiss: [rowId: string]
}>()

const candidate = computed(() => props.row.selectedCandidate)
const studyName = computed(() => candidate.value?.studyName ?? '')
const subjectLabel = computed(() => candidate.value?.subjectLabel ?? props.row.patientId ?? '')
const eventLabel = computed(() => props.row.selectedEvent?.definitionLabel ?? '')
const isOct = computed(() => props.row.kind === 'e2e')

const eventWhen = computed(() => {
  const ev = props.row.selectedEvent
  if (!ev || !ev.dateStart) return ''
  const today = new Date()
  const todayIso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  if (ev.dateStart === todayIso) return t('octPortal.assignment.todayScheduled')
  return formatDate(ev.dateStart)
})

const dateLabel = computed(() => (props.row.date ? formatDate(props.row.date) : ''))

const uploadPercentLabel = computed(() => `${Math.round(store.uploadPct.get(props.row.rowId) ?? 0)} %`)

/** Error rows carry a key from the store or a message from the backend. */
const errorText = computed(() => {
  const e = props.row.error
  if (!e) return t('octPortal.assignment.processError')
  const keyed: Record<string, string> = {
    unsupported: t('uploadPortal.row.unsupported'),
    noVolumes: t('uploadPortal.row.noVolumes'),
    headerError: t('uploadPortal.row.headerError'),
    resolveFailed: t('uploadPortal.row.resolveFailed'),
    uploadFailed: t('uploadPortal.row.uploadFailed'),
    undoFailed: t('uploadPortal.row.undoFailed'),
    invalidVisit: t('uploadPortal.row.invalidVisit'),
    noSubject: t('uploadPortal.row.noSubject'),
  }
  return keyed[e] ?? e
})

const withoutVisitLabel = computed(() =>
  isOct.value ? t('octPortal.actions.parkLater') : t('uploadPortal.actions.uploadWithoutVisit'),
)
</script>

<template>
  <div class="flex items-center gap-4 flex-1 min-w-0">
    <div class="flex-1 min-w-0">
      <template v-if="props.row.state === 'suggested'">
        <div class="flex items-center gap-2 mb-1.5 flex-wrap">
          <StudyChip v-if="studyName" :name="studyName" />
          <span class="inline-flex items-center gap-1.5 text-[12px] font-medium text-slate-700">
            <span class="opacity-60">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <circle cx="12" cy="8" r="4" />
                <path d="M4 21a8 8 0 0 1 16 0" />
              </svg>
            </span>{{ subjectLabel }}
          </span>
          <PortalStatusPill tone="ok">{{ t('octPortal.assignment.patientFound') }}</PortalStatusPill>
        </div>
        <div class="inline-flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50/70 pl-2.5 pr-2 py-1.5">
          <span class="text-amber-600">
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <rect width="18" height="18" x="3" y="4" rx="2" />
              <path d="M16 2v4M8 2v4M3 10h18" />
            </svg>
          </span>
          <span class="text-[12px] text-slate-700">
            <span class="font-semibold">{{ eventLabel }}</span><template v-if="eventWhen"> · {{ eventWhen }}</template>
          </span>
        </div>
      </template>

      <template v-else-if="props.row.state === 'confirmed' || props.row.state === 'committed'">
        <div class="flex items-center gap-2 mb-1 flex-wrap">
          <StudyChip v-if="studyName" :name="studyName" />
          <span v-if="subjectLabel" class="inline-flex items-center gap-1.5 text-[12px] font-medium text-slate-700">
            <span class="opacity-60">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <circle cx="12" cy="8" r="4" />
                <path d="M4 21a8 8 0 0 1 16 0" />
              </svg>
            </span>{{ subjectLabel }}
          </span>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-muw-teal-700 flex-wrap">
          <span class="w-4 h-4 rounded-full bg-muw-teal text-white inline-flex items-center justify-center">
            <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
          </span>
          {{ t('octPortal.assignment.assigned') }} ·
          <span class="font-semibold">{{ eventLabel || (isOct ? t('octPortal.assignment.parked') : t('uploadPortal.assignment.inInbox')) }}</span>
          <span v-if="eventLabel && eventWhen"> · {{ eventWhen }}</span>
          <span v-if="props.row.kind === 'dicom'" class="text-slate-500" data-testid="row-deidentified">· {{ t('uploadPortal.row.deidentified') }}</span>
          <span v-if="props.row.error" class="text-rose-700">· {{ errorText }}</span>
        </div>
      </template>

      <template v-else-if="props.row.state === 'novisit'">
        <div class="flex items-center gap-2 mb-1.5 flex-wrap">
          <StudyChip v-if="studyName" :name="studyName" />
          <span class="inline-flex items-center gap-1.5 text-[12px] font-medium text-slate-700">
            <span class="opacity-60">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <circle cx="12" cy="8" r="4" />
                <path d="M4 21a8 8 0 0 1 16 0" />
              </svg>
            </span>{{ subjectLabel }}
          </span>
          <PortalStatusPill tone="ok">{{ t('octPortal.assignment.patientFound') }}</PortalStatusPill>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-500">
          <span class="text-slate-400">
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <rect width="18" height="18" x="3" y="4" rx="2" />
              <path d="M16 2v4M8 2v4M3 10h18" />
            </svg>
          </span>
          {{ dateLabel ? t('octPortal.assignment.noEventForDate', { date: dateLabel }) : t('uploadPortal.assignment.noEvent') }}
        </div>
      </template>

      <template v-else-if="props.row.state === 'nopatient'">
        <div class="flex items-center gap-2 mb-1.5">
          <PortalStatusPill tone="bad">{{ t('octPortal.assignment.patientNotFound') }}</PortalStatusPill>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-500">
          <template v-if="props.row.patientId">
            {{ t('octPortal.assignment.patientIdMissingPrefix') }} <span class="font-mono text-slate-600">{{ props.row.patientId }}</span> {{ t('octPortal.assignment.patientIdMissingSuffix') }}
          </template>
          <template v-else>{{ t('uploadPortal.assignment.noLabel') }}</template>
        </div>
      </template>

      <template v-else-if="props.row.state === 'ambiguous'">
        <div class="flex items-center gap-2 mb-1.5 flex-wrap">
          <PortalStatusPill tone="suggest">{{ t('octPortal.assignment.multipleStudies') }}</PortalStatusPill>
          <span class="inline-flex items-center gap-1.5 text-[12px] font-medium text-slate-700">{{ props.row.patientId }}</span>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-500">
          {{ t('octPortal.assignment.patientIdAmbiguous') }}
        </div>
      </template>

      <template v-else-if="props.row.state === 'duplicate'">
        <div class="flex items-center gap-2 mb-1.5">
          <PortalStatusPill tone="ok">{{ t('octPortal.assignment.alreadyUploaded') }}</PortalStatusPill>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-500">
          {{ t('uploadPortal.assignment.alreadyUploadedDetail') }}
          <span class="font-mono text-slate-700">#{{ props.row.existingJobId ?? props.row.existingIngestItemId }}</span>
        </div>
      </template>

      <template v-else-if="props.row.state === 'error'">
        <div class="inline-flex items-center gap-2 text-[12px] text-rose-700" data-testid="row-error">
          <span>
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
              <path d="M10.3 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.7 3.86a2 2 0 0 0-3.42 0Z" />
              <path d="M12 9v4M12 17h.01" />
            </svg>
          </span>{{ errorText }}
        </div>
      </template>

      <template v-else-if="props.row.state === 'committing'">
        <div class="flex items-center justify-between gap-3">
          <div class="inline-flex items-center gap-2 text-[12px] text-slate-600 font-medium">
            <span class="w-1.5 h-1.5 rounded-full bg-muw-teal animate-pulse"></span>
            {{ t('octPortal.assignment.uploading') }}
          </div>
          <span
            class="font-mono text-[12px] font-semibold text-muw-teal-700 shrink-0 tabular-nums"
            :data-testid="`upload-pct-${props.row.rowId}`"
          >{{ uploadPercentLabel }}</span>
        </div>
      </template>

      <template v-else>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-400">
          <EyeBadge :laterality="null" />
          {{ t('octPortal.parseRow.studyVisitDetermining') }}
        </div>
      </template>
    </div>

    <div class="shrink-0">
      <template v-if="props.row.state === 'suggested'">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-[12px] font-medium inline-flex items-center gap-1 text-slate-500 hover:text-slate-700 min-h-8 px-1"
            :data-testid="`action-change-${props.row.rowId}`"
            @click="emit('pick-visit', props.row.rowId)"
          >{{ t('octPortal.assignment.change') }}</button>
          <button
            type="button"
            class="px-3.5 py-2 text-[13px] font-semibold bg-muw-blue text-white rounded-lg hover:bg-muw-blue-700 inline-flex items-center gap-2 shadow-[0_1px_2px_rgba(17,29,78,0.18)] whitespace-nowrap"
            :data-testid="`action-confirm-${props.row.rowId}`"
            @click="emit('confirm', props.row.rowId)"
          >
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
            {{ t('octPortal.actions.confirm') }}
          </button>
        </div>
      </template>

      <template v-else-if="props.row.state === 'confirmed' || props.row.state === 'committed'">
        <button
          type="button"
          class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700 min-h-8 px-1"
          :data-testid="`action-undo-${props.row.rowId}`"
          @click="emit('undo', props.row.rowId)"
        >
          <span class="opacity-70">
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
              <path d="M3 7v6h6" />
              <path d="M3 13a9 9 0 1 0 3-7.7L3 8" />
            </svg>
          </span>
          {{ t('octPortal.actions.undo') }}
        </button>
      </template>

      <template v-else-if="props.row.state === 'novisit'">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700 min-h-8 px-1"
            :data-testid="`action-pick-visit-${props.row.rowId}`"
            @click="emit('pick-visit', props.row.rowId)"
          >{{ t('octPortal.actions.pickVisit') }}</button>
          <button
            type="button"
            class="px-3 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 whitespace-nowrap"
            :data-testid="`action-park-${props.row.rowId}`"
            @click="emit('park', props.row.rowId)"
          >{{ withoutVisitLabel }}</button>
        </div>
      </template>

      <template v-else-if="props.row.state === 'nopatient'">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700 min-h-8 px-1"
            :data-testid="`action-search-${props.row.rowId}`"
            @click="emit('search-patient', props.row.rowId)"
          >
            <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
              <circle cx="11" cy="11" r="8" />
              <path d="m21 21-4.3-4.3" />
            </svg>
            {{ t('octPortal.actions.searchPatient') }}
          </button>
          <button
            type="button"
            class="px-3 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 whitespace-nowrap"
            :data-testid="`action-park-${props.row.rowId}`"
            @click="emit('park', props.row.rowId)"
          >{{ isOct ? t('octPortal.actions.park') : t('uploadPortal.actions.uploadWithoutVisit') }}</button>
        </div>
      </template>

      <template v-else-if="props.row.state === 'ambiguous'">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700 min-h-8 px-1"
            :data-testid="`action-pick-study-${props.row.rowId}`"
            @click="emit('pick-study', props.row.rowId)"
          >{{ t('octPortal.actions.pickStudy') }}</button>
          <button
            type="button"
            class="px-3 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 whitespace-nowrap"
            :data-testid="`action-park-${props.row.rowId}`"
            @click="emit('park', props.row.rowId)"
          >{{ withoutVisitLabel }}</button>
        </div>
      </template>

      <template v-else-if="props.row.state === 'error' || props.row.state === 'duplicate'">
        <button
          type="button"
          class="p-1.5 rounded-lg text-slate-400 hover:text-rose-600 hover:bg-rose-50 min-h-8 min-w-8"
          :data-testid="`action-dismiss-${props.row.rowId}`"
          :aria-label="t('octPortal.assignment.dismissAria')"
          @click="emit('dismiss', props.row.rowId)"
        >
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <line x1="18" x2="6" y1="6" y2="18" />
            <line x1="6" x2="18" y1="6" y2="18" />
          </svg>
        </button>
      </template>
    </div>
  </div>
</template>
