<script setup lang="ts">
/**
 * Wave 2C follow-up (2026-06-19) — StudyPickerModal.
 *
 * Surfaces the disambiguation picker for an {@code ambiguous} review
 * row, where the parsed {@code patientId} matched a study_subject in
 * more than one study and the operator must pick the correct cohort
 * before the row can advance.
 *
 * <p>Unlike {@link VisitPickerModal}, which fetches its options from
 * the backend, {@link StudyPickerModal} renders the candidates the
 * row already carries — the {@code /resolve} call has populated
 * {@code row.candidates} with every matching study_subject. The
 * picker is purely a render-and-emit affordance.
 *
 * <p>Per-candidate hint: if the candidate carries a
 * {@code matchingEvent}, the picker tags it as "Visite gefunden" so
 * the operator knows the pick will commit immediately. Otherwise the
 * pick transitions the row into {@code novisit} and the standard
 * visit-picker takes over.
 *
 * <p>Strings come from {@code octPortal.modals.studyPicker.*} in the
 * Wave 1C i18n registry.
 */
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import type { ResolveCandidate } from '@/api/octPortal'

interface Props {
  open: boolean
  /** Cohort options surfaced by {@code /resolve} for an ambiguous row. */
  candidates: ResolveCandidate[]
  /** Parsed PatientId from the .e2e header — displayed in the modal
   *  header so the operator can confirm the disambiguation is for the
   *  row they expected. */
  patientId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'study-picked', candidate: ResolveCandidate): void
  (e: 'close'): void
}>()

const { t } = useI18n()

function pick(candidate: ResolveCandidate): void {
  emit('study-picked', candidate)
}

function onClose(): void {
  emit('close')
}

const labelledById = 'oct-portal-study-picker-heading'
</script>

<template>
  <Modal
    :open="props.open"
    :labelled-by="labelledById"
    panel-class="max-w-xl"
    @close="onClose"
  >
    <template #header>
      <h2 :id="labelledById" class="text-[15px] font-semibold text-slate-900">
        {{ t('octPortal.modals.studyPicker.title') }}
      </h2>
      <p class="text-[12px] text-slate-500 mt-0.5">
        {{ t('octPortal.modals.studyPicker.subtitle') }}
        <span class="font-mono text-slate-700">{{ props.patientId }}</span>
      </p>
    </template>

    <div class="flex flex-col gap-3">
      <ul
        data-testid="study-picker-results"
        class="flex flex-col gap-1.5 max-h-96 overflow-y-auto"
      >
        <li v-for="c in props.candidates" :key="c.studySubjectId">
          <button
            type="button"
            :data-testid="`study-picker-result-${c.studySubjectId}`"
            class="w-full text-left rounded-md border border-slate-200 bg-white px-3 py-2 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-muw-blue"
            @click="pick(c)"
          >
            <div class="flex items-center gap-2 justify-between">
              <div class="text-[13px] font-semibold text-slate-900 flex-1 min-w-0 truncate">
                {{ c.studyName }}
              </div>
              <span
                v-if="c.matchingEvent"
                class="text-[10.5px] uppercase tracking-wide font-medium border rounded px-1.5 py-0.5 shrink-0 bg-muw-teal-50 text-muw-teal-700 border-muw-teal-200"
              >{{ t('octPortal.modals.studyPicker.visitFound') }}</span>
              <span
                v-else
                class="text-[10.5px] uppercase tracking-wide font-medium border rounded px-1.5 py-0.5 shrink-0 bg-amber-50 text-amber-700 border-amber-200"
              >{{ t('octPortal.modals.studyPicker.noVisit') }}</span>
            </div>
            <div class="text-[11.5px] text-slate-500 mt-0.5 flex items-center gap-2 flex-wrap">
              <span>{{ t('octPortal.modals.studyPicker.subjectLabelPrefix') }} <span class="font-mono">{{ c.subjectLabel }}</span></span>
              <span v-if="c.siteName" class="text-slate-400">·</span>
              <span v-if="c.siteName">{{ t('octPortal.modals.studyPicker.siteLabelPrefix') }} {{ c.siteName }}</span>
              <span v-if="c.matchingEvent" class="text-slate-400">·</span>
              <span v-if="c.matchingEvent" class="text-muw-teal-700">
                {{ c.matchingEvent.definitionLabel }} · {{ c.matchingEvent.dateStart }}
              </span>
            </div>
          </button>
        </li>
      </ul>
    </div>

    <template #footer>
      <button
        type="button"
        data-testid="study-picker-cancel"
        class="px-3 py-1.5 text-[12.5px] font-medium border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
        @click="onClose"
      >
        {{ t('octPortal.modals.studyPicker.cancel') }}
      </button>
    </template>
  </Modal>
</template>
