<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — Assignment + Action cells.
 *
 * Mirrors the mockup's `Assignment` + `Action` primitives
 * (oct-portal.jsx ~ line 150 + 196). One sub-component handles the
 * five row sub-states the review queue shows; the parent ReviewRow
 * just drops this into the row's flexible middle/right segment.
 *
 * Sub-states the mockup renders:
 *  - {@code suggested}   — auto-pick visit + Bestätigen button
 *  - {@code confirmed}   — green check + Rückgängig link
 *  - {@code novisit}     — patient found, no event for date → Visite
 *                          wählen / Später zuordnen
 *  - {@code nopatient}   — PatientId not in any study → Patient suchen
 *                          / Parken
 *  - {@code ambiguous}   — multi-study match → currently surfaces as
 *                          a suggest pill with the "ändern" affordance
 *                          so the operator can pick the right cohort
 *  - {@code error}       — non-.e2e / parse failure → red ✕ dismiss
 *  - {@code committing}  — in-flight spinner overlay
 *  - {@code committed}   — temporary terminal state until undo
 *
 * Each user-facing action surfaces as an emit; the parent
 * (OctUploadPortalView) routes them to the store. We deliberately
 * keep ALL strings inline (German hard-coded) per the plan's "no
 * de.json for v1" choice.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import EyeBadge from './EyeBadge.vue'
import PortalStatusPill from './PortalStatusPill.vue'
import StudyChip from './StudyChip.vue'
import type { ReviewRow } from '@/stores/octPortal'

const { t } = useI18n()

interface Props {
  row: ReviewRow
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
const subjectLabel = computed(() => candidate.value?.subjectLabel ?? props.row.scan?.patientId ?? '')
const eventLabel = computed(() => props.row.selectedEvent?.definitionLabel ?? '')

/** Format the matching event's date — "heute" when the scan was
 *  acquired today; otherwise the ISO date. Mirrors the mockup's
 *  "heute geplant" / "heute" wording. */
const eventWhen = computed(() => {
  const ev = props.row.selectedEvent
  if (!ev) return ''
  const dateStart = ev.dateStart
  const today = new Date()
  const todayIso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  if (dateStart === todayIso) return t('octPortal.assignment.todayScheduled')
  return dateStart
})

const scanDateLabel = computed(() => {
  const d = props.row.scan?.scanDate
  if (!d) return ''
  const dd = String(d.getDate()).padStart(2, '0')
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  return `${dd}-${months[d.getMonth()]}-${d.getFullYear()}`
})
</script>

<template>
  <div class="flex items-center gap-4 flex-1 min-w-0">
    <!-- ============================ Assignment column ============================ -->
    <div class="flex-1 min-w-0">
      <!-- suggested: green pill + amber visit chip -->
      <template v-if="props.row.state === 'suggested'">
        <div class="flex items-center gap-2 mb-1.5 flex-wrap">
          <StudyChip :name="studyName" />
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
            <span class="font-semibold">{{ eventLabel }}</span> · {{ eventWhen }}
          </span>
          <button
            type="button"
            class="ml-1 text-[11px] text-slate-400 hover:text-slate-600 inline-flex items-center gap-0.5"
            @click="emit('pick-visit', props.row.rowId)"
          >{{ t('octPortal.assignment.change') }}
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><polyline points="6 9 12 15 18 9" /></svg>
          </button>
        </div>
      </template>

      <!-- confirmed: green dot + visit -->
      <template v-else-if="props.row.state === 'confirmed' || props.row.state === 'committed'">
        <div class="flex items-center gap-2 mb-1 flex-wrap">
          <StudyChip :name="studyName" />
          <span class="inline-flex items-center gap-1.5 text-[12px] font-medium text-slate-700">
            <span class="opacity-60">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <circle cx="12" cy="8" r="4" />
                <path d="M4 21a8 8 0 0 1 16 0" />
              </svg>
            </span>{{ subjectLabel }}
          </span>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-muw-teal-700">
          <span class="w-4 h-4 rounded-full bg-muw-teal text-white inline-flex items-center justify-center">
            <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
          </span>
          {{ t('octPortal.assignment.assigned') }} · <span class="font-semibold">{{ eventLabel || t('octPortal.assignment.parked') }}</span>
          <span v-if="eventLabel"> · {{ eventWhen || t('octPortal.assignment.today') }}</span>
        </div>
      </template>

      <!-- novisit: patient found, no event for date -->
      <template v-else-if="props.row.state === 'novisit'">
        <div class="flex items-center gap-2 mb-1.5 flex-wrap">
          <StudyChip :name="studyName" />
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
          {{ t('octPortal.assignment.noEventForDate', { date: scanDateLabel }) }}
        </div>
      </template>

      <!-- nopatient: PatientId not found -->
      <template v-else-if="props.row.state === 'nopatient'">
        <div class="flex items-center gap-2 mb-1.5">
          <PortalStatusPill tone="bad">{{ t('octPortal.assignment.patientNotFound') }}</PortalStatusPill>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-500">
          {{ t('octPortal.assignment.patientIdMissingPrefix') }} <span class="font-mono text-slate-600">{{ props.row.scan?.patientId }}</span> {{ t('octPortal.assignment.patientIdMissingSuffix') }}
        </div>
      </template>

      <!-- ambiguous: multi-study match (rendered as a soft warning) -->
      <template v-else-if="props.row.state === 'ambiguous'">
        <div class="flex items-center gap-2 mb-1.5 flex-wrap">
          <PortalStatusPill tone="suggest">{{ t('octPortal.assignment.multipleStudies') }}</PortalStatusPill>
          <span class="inline-flex items-center gap-1.5 text-[12px] font-medium text-slate-700">
            <span class="opacity-60">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <circle cx="12" cy="8" r="4" />
                <path d="M4 21a8 8 0 0 1 16 0" />
              </svg>
            </span>{{ props.row.scan?.patientId }}
          </span>
        </div>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-500">
          {{ t('octPortal.assignment.patientIdAmbiguous') }}
        </div>
      </template>

      <!-- error: red alert -->
      <template v-else-if="props.row.state === 'error'">
        <div class="inline-flex items-center gap-2 text-[12px] text-rose-700">
          <span>
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
              <path d="M10.3 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.7 3.86a2 2 0 0 0-3.42 0Z" />
              <path d="M12 9v4M12 17h.01" />
            </svg>
          </span>{{ props.row.error || t('octPortal.assignment.processError') }}
        </div>
      </template>

      <!-- committing: in-flight spinner overlay -->
      <template v-else-if="props.row.state === 'committing'">
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-500">
          <span class="text-muw-blue inline-block">
            <svg class="muw-portal-spin" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">
              <path d="M21 12a9 9 0 1 1-6.2-8.5" opacity="0.9" />
            </svg>
          </span>
          {{ t('octPortal.assignment.uploading') }}
        </div>
      </template>

      <!-- parsing fallthrough — should be filtered by ReviewQueue before
           reaching Assignment, but render a neutral marker just in case -->
      <template v-else>
        <div class="inline-flex items-center gap-2 text-[12px] text-slate-400">
          <EyeBadge :laterality="null" />
        </div>
      </template>
    </div>

    <!-- ============================ Action column ============================ -->
    <div class="shrink-0">
      <template v-if="props.row.state === 'suggested'">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-[12px] font-medium inline-flex items-center gap-1 text-slate-500 hover:text-slate-700"
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
          class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700"
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
            class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700"
            @click="emit('pick-visit', props.row.rowId)"
          >{{ t('octPortal.actions.pickVisit') }}</button>
          <button
            type="button"
            class="px-3 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 whitespace-nowrap"
            :data-testid="`action-park-${props.row.rowId}`"
            @click="emit('park', props.row.rowId)"
          >{{ t('octPortal.actions.parkLater') }}</button>
        </div>
      </template>

      <template v-else-if="props.row.state === 'nopatient'">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700"
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
          >{{ t('octPortal.actions.park') }}</button>
        </div>
      </template>

      <template v-else-if="props.row.state === 'ambiguous'">
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="text-[12px] font-medium inline-flex items-center gap-1 text-muw-blue hover:text-muw-blue-700"
            :data-testid="`action-pick-study-${props.row.rowId}`"
            @click="emit('pick-study', props.row.rowId)"
          >{{ t('octPortal.actions.pickStudy') }}</button>
          <button
            type="button"
            class="px-3 py-2 text-[13px] font-medium border border-slate-200 rounded-lg bg-white hover:bg-slate-50 text-slate-700 inline-flex items-center gap-2 whitespace-nowrap"
            @click="emit('park', props.row.rowId)"
          >{{ t('octPortal.actions.parkLater') }}</button>
        </div>
      </template>

      <template v-else-if="props.row.state === 'error'">
        <button
          type="button"
          class="p-1.5 rounded-lg text-slate-400 hover:text-rose-600 hover:bg-rose-50"
          :data-testid="`action-dismiss-${props.row.rowId}`"
          @click="emit('dismiss', props.row.rowId)"
          :aria-label="t('octPortal.assignment.dismissAria')"
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
